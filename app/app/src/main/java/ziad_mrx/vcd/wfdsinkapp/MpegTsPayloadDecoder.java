package ziad_mrx.vcd.wfdsinkapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import android.os.Trace;

public class MpegTsPayloadDecoder extends Thread {
    private static final String TAG = "MpegTsDecoder";
    private static final int UDP_PORT = SharedObjectRegistry.UDP_PORT;

    private final Surface mSurface;
    private MediaCodec mCodec;
    private boolean isRunning = true;

    // H.264 Streaming Window Buffer
    private final byte[] mStreamBuffer = new byte[2 * 1024 * 1024]; // 2MB sliding buffer

    private final byte[] mAudioStreamBuffer = new byte[4 * 48000]; // 1 second buffer
    private int mBufferLength = 0;

    private int mAudioBufferLength = 0;

    // Parsed PIDs
    private int mPmtPid = -1;
    private int mVideoPid = -1;
    private int mAudioPid = -1;

    // ArrayBlockingQueue for storing pending NALus
    public ArrayBlockingQueue<byte[]> mPendingNALus = new ArrayBlockingQueue<>(500); // maximum capacity of 500 NALus.

    // ArrayBlockingQueue for storing available input buffers indices.
    private ArrayBlockingQueue<Integer> mAvailableInputBuffersIndexs = new ArrayBlockingQueue<Integer>(32); // more than enough I guess.
    private final Object mFeederLock = new Object();

    private final AudioPayloadHandler mAudioPayloadHandler;

    // epoch base to handle wrap-arounds in the parsed PTS/PCR values
    private long stc_epoch = 0;
    private long last_stc_value = -1;

    private byte[] __raw_pcr_bytes = new byte[6];

    private long __parsed_pcr_base = -1;

    private double __audio_pes_packet_length_ns = 0;

    private long __last_audio_pts_value = -1;

    private long target_video_presentation_ns = -1;

    private byte[] __video_pts_t_buffer = new byte[9]; // 8 (64-bits) for long + 1 byte for marker 'X'.


    public MpegTsPayloadDecoder(Surface surface, AudioPayloadHandler audiopayloadhandler) {
        this.mSurface = surface;
        this.mAudioPayloadHandler = audiopayloadhandler;
    }

    @Override
    public void run() {
        // increase thread priority
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        HandlerThread ht = new HandlerThread(SharedObjectRegistry.HANDLER_THREAD_NAME, Process.THREAD_PRIORITY_URGENT_DISPLAY);
        try {
            mCodec = MediaCodec.createByCodecName(getOptimalAVCDecoderName());
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            // we need to create a handlerthread to use in codec callback

            ht.start();
            if (ht.isAlive()) Log.i(TAG, "HandlerThread is running...");
            else Log.e(TAG, "Handler thread is not running!!!");
            Handler callbackHandler = new Handler(ht.getLooper());
            if (ht.getLooper() == null) Log.e(TAG, "getLooper() returns null!!!!");


            // register callback here.
            mCodec.setCallback(new MediaCodec.Callback() {
                byte[] elem = null;

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    Log.e(TAG, "Codec Error!: " + e.getMessage());
                }

                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    mAvailableInputBuffersIndexs.offer(index);
                    tryToFeedDecoder(codec);
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    // release output buffer immediately if it contains no video data
                    if (info.size <= 0) {
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }
                    if (info.presentationTimeUs <= 0) {
                        // release immediately
                        codec.releaseOutputBuffer(index, true);
                        return;
                    }
                    if ((System.nanoTime() / (long)1000) > (info.presentationTimeUs + SharedObjectRegistry.VIDEO_FRAME_DISPLAY_ACCEPTABLE_DELAY_US)) {
                        // we're late, just drop already
                        codec.releaseOutputBuffer(index, false);
                        Log.w(TAG, "Dropped frame at output buffer index: " + index + ", Arrived too late!");
                    } else {
                        codec.releaseOutputBuffer(index, info.presentationTimeUs);
                    }
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    int new_width = format.getInteger(MediaFormat.KEY_WIDTH);
                    int new_height = format.getInteger(MediaFormat.KEY_HEIGHT);
                    Log.i(TAG, "Codec format changed: " + new_width + "x" + new_height);
                }
            },callbackHandler);
            // if a client wishes to use this component asynchronuously, they must do it before the call
            // to configure codec.

            mCodec.configure(format, mSurface, null, 0);
            mCodec.start();
            Log.i(TAG, "Hardware H.264 MediaCodec started.");
            // get width and height of actual codec
            MediaFormat mformat = mCodec.getOutputFormat();
            int actual_Width = mformat.getInteger(MediaFormat.KEY_WIDTH);
            int actual_Height = mformat.getInteger(MediaFormat.KEY_HEIGHT);
            Log.i(TAG, "Codec started with media format of width : " + actual_Width + ", height: " + actual_Height);


            while (isRunning) {
                byte[] packet = SharedObjectRegistry.networkPacketsQueue.take();
                if (packet.length <= 12) continue; // Skip if less than RTP header

                // Strip 12-byte RTP header
                int tsOffset = 12;
                while (tsOffset + 188 <= packet.length) {
                    processTsPacket(packet, tsOffset);
                    tsOffset += 188;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in TS Decoding Thread", e);
        } finally {
            Log.i(TAG, "Stopping handler thread...");
            ht.quitSafely();
        }
    }

    private void processTsPacket(byte[] data, int offset) {
        if (data[offset] != 0x47) return; // Sync byte check

        boolean tei = (data[offset + 1] & 0x80) != 0;
        if (tei) {
            Log.w(TAG, "Received a corrupt TS packet, ignoring...");
            return;
        }

        int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
        boolean pusi = (data[offset + 1] & 0x40) != 0;
        int adaptation = (data[offset + 3] & 0x30) >> 4;

        int payloadOffset = offset + 4;
        if (adaptation == 2 || adaptation == 3) {
            int afLen = data[offset + 4] & 0xFF;
            boolean hasPcr = (data[offset + 5] & 0x10) != 0;
            if (hasPcr) {
                // we have pcr, let's parse it
                __raw_pcr_bytes[0] = data[offset + 6];
                __raw_pcr_bytes[1] = data[offset + 7];
                __raw_pcr_bytes[2] = data[offset + 8];
                __raw_pcr_bytes[3] = data[offset + 9];
                __raw_pcr_bytes[4] = data[offset + 10];
                __raw_pcr_bytes[5] = data[offset + 11];
                __parsed_pcr_base = ((long) __raw_pcr_bytes[0] << 25) | ((long) __raw_pcr_bytes[1] << 17) | ((long) __raw_pcr_bytes[2] << 9)
                        | ((long) __raw_pcr_bytes[3] << 1)  | (__raw_pcr_bytes[4] >>> 7);

                // we need to check if a wraparound happened or not first.
                if ((last_stc_value != -1) && (__parsed_pcr_base < (last_stc_value - SharedObjectRegistry.PCR_JITTER_MARGIN))) {
                    stc_epoch += SharedObjectRegistry.MAXIMUM_PCR_BASE_VALUE;
                }
                last_stc_value = stc_epoch + __parsed_pcr_base;
                // feed pcr
                NativeSourceSTCTracker.feedPCR(NativeSourceSTCTracker.toNanoSeconds(last_stc_value));
            }
            payloadOffset = offset + 5 + afLen;
        }

        if (adaptation == 1 || adaptation == 3) {
            if (payloadOffset < offset + 188) {
                if (pid == 0) {
                    parsePat(data, payloadOffset);
                } else if (pid == mPmtPid) {
                    parsePmt(data, payloadOffset);
                } else if (pid == mVideoPid) {
                    parseVideoPes(data, payloadOffset, offset + 188, pusi);
                } else if (pid == mAudioPid) {
                    parseAudioPes(data, payloadOffset, offset + 188, pusi);
                }
            }
        }
    }

    private void parsePat(byte[] data, int offset) {
        int pointerField = data[offset] & 0xFF;
        int sectionStart = offset + 1 + pointerField;
        mPmtPid = ((data[sectionStart + 10] & 0x1F) << 8) | (data[sectionStart + 11] & 0xFF);
    }

    private void parsePmt(byte[] data, int offset) {
        int pointerField = data[offset] & 0xFF;
        int sectionStart = offset + 1 + pointerField;
        int programInfoLength = ((data[sectionStart + 10] & 0x0F) << 8) | (data[sectionStart + 11] & 0xFF);
        int streamOffset = sectionStart + 12 + programInfoLength;

        while (streamOffset < offset + 184) {
            if ((mVideoPid != -1) && (mAudioPid != -1)) break;


            int streamType = data[streamOffset] & 0xFF;
            int elementaryPid = ((data[streamOffset + 1] & 0x1F) << 8) | (data[streamOffset + 2] & 0xFF);
            int esInfoLength = ((data[streamOffset + 3] & 0x0F) << 8) | (data[streamOffset + 4] & 0xFF);

            if (streamType == 0x1B) { // 0x1B corresponds to AVC / H.264
                mVideoPid = elementaryPid;
            }
            if (streamType == 0x83) { // raw LPCM
                Log.i(TAG, "Found PID for RAW LPCM!: " + elementaryPid);
                mAudioPid = elementaryPid;
            }


            streamOffset += 5 + esInfoLength;
        }
    }


    private void parseVideoPes(byte[] data, int offset, int end, boolean pusi) {
        int dataToReadOffset = offset;
        if (pusi) {
            if (mBufferLength > 0) { feedToDecoder(Arrays.copyOf(mStreamBuffer, mBufferLength)); mBufferLength = 0; }
            // Check for PES start code (0x000001)
            if (data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 1) {
                boolean hasPts = ((data[offset + 6] & 0xFF) & 3) >= 2;
                if (hasPts) {
                    int ptsStartOffset = offset + 9;
                    // Read the 5 sequential bytes representing the PTS structure
                    long b0 = data[ptsStartOffset]     & 0xFF;
                    long b1 = data[ptsStartOffset + 1] & 0xFF;
                    long b2 = data[ptsStartOffset + 2] & 0xFF;
                    long b3 = data[ptsStartOffset + 3] & 0xFF;
                    long b4 = data[ptsStartOffset + 4] & 0xFF;

                    // Isolate and stitch the 33 bits:
                    // Chunk 1: Bits 32-30 (from b0) -> mask with 0x0E, shift right 1, then shift up to bit 30
                    long pts = stc_epoch + ((((b0 & 0x0E) >> 1) << 30) |
                            // Chunk 2: Bits 29-15 (from b1 and b2) -> combine b1 and b2, mask out the LSB marker bit of b2, shift up to bit 15
                            (((((b1 << 8) | b2) & 0xFFFE) >> 1) << 15) |
                            // Chunk 3: Bits 14-0 (from b3 and b4) -> combine b3 and b4, mask out the LSB marker bit of b4
                            ((((b3 << 8) | b4) & 0xFFFE) >> 1));

                    // store that as a byte[] to write to the queue
                    __video_pts_t_buffer[0] = SharedObjectRegistry.VIDEO_PTS_MARKER; // 'X'
                    __video_pts_t_buffer[1] = (byte)(pts >>> 56);
                    __video_pts_t_buffer[2] = (byte)(pts >>> 48);
                    __video_pts_t_buffer[3] = (byte)(pts >>> 40);
                    __video_pts_t_buffer[4] = (byte)(pts >>> 32);
                    __video_pts_t_buffer[5] = (byte)(pts >>> 24);
                    __video_pts_t_buffer[6] = (byte)(pts >>> 16);
                    __video_pts_t_buffer[7] = (byte)(pts >>> 8);
                    __video_pts_t_buffer[8] = (byte)pts;
                    // offer that to the queue
                    try {mPendingNALus.put(__video_pts_t_buffer.clone());} catch (
                            InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                } else {
                    Arrays.fill(__video_pts_t_buffer, (byte) 0x00);
                    __video_pts_t_buffer[0] = SharedObjectRegistry.VIDEO_PTS_MARKER; // 'X'
                    try {mPendingNALus.put(__video_pts_t_buffer.clone());} catch (
                            InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                int pesHeaderLen = data[offset + 8] & 0xFF;
                dataToReadOffset = offset + 9 + pesHeaderLen;
            }
        }

        int length = end - dataToReadOffset;
        if (length > 0 && mBufferLength + length <= mStreamBuffer.length) {
            System.arraycopy(data, dataToReadOffset, mStreamBuffer, mBufferLength, length);
            mBufferLength += length;
            extractNalUnits();
        }
    }

    private void parseAudioPes(byte[] data, int offset, int end, boolean pusi) {
        int dataToReadOffset = offset;
        if (pusi) {
            // Check for PES start code (0x000001)
            if (data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 1) {
                int __pes_data_length_after_base_header = ((data[offset + 4] & 0xFF) << 8) | (data[offset + 5] & 0xFF);
                int pesHeaderLen = data[offset + 8] & 0xFF;
                int __audio_payload_length_bytes = (__pes_data_length_after_base_header - 3 - pesHeaderLen);
                // i want to see if i have pts here or not first
                boolean hasPts = ((data[offset + 6] & 0xFF) & 3) >= 2;
                if (hasPts) {
                    int ptsStartOffset = offset + 9;
                    // Read the 5 sequential bytes representing the PTS structure
                    long b0 = data[ptsStartOffset]     & 0xFF;
                    long b1 = data[ptsStartOffset + 1] & 0xFF;
                    long b2 = data[ptsStartOffset + 2] & 0xFF;
                    long b3 = data[ptsStartOffset + 3] & 0xFF;
                    long b4 = data[ptsStartOffset + 4] & 0xFF;

                    // Isolate and stitch the 33 bits:
                    // Chunk 1: Bits 32-30 (from b0) -> mask with 0x0E, shift right 1, then shift up to bit 30
                    long pts = stc_epoch + ((((b0 & 0x0E) >> 1) << 30) |
                    // Chunk 2: Bits 29-15 (from b1 and b2) -> combine b1 and b2, mask out the LSB marker bit of b2, shift up to bit 15
                            (((((b1 << 8) | b2) & 0xFFFE) >> 1) << 15) |
                    // Chunk 3: Bits 14-0 (from b3 and b4) -> combine b3 and b4, mask out the LSB marker bit of b4
                            ((((b3 << 8) | b4) & 0xFFFE) >> 1));

                    __last_audio_pts_value = pts;
                    SharedObjectRegistry.tracing_cookie = (int)(pts & 0xFFFF); // first 2 bytes of the pts
//                    Trace.beginAsyncSection(SharedObjectRegistry.DEBUG_TRACING_PTS_TO_WRITE_SECTION, SharedObjectRegistry.tracing_cookie);
                    mAudioPayloadHandler.addToQueue(new AudioPESFragment(pts));
//                    Log.i(TAG, "Beginning of Audio PES: Got PTS: " + pts);

                } else {
                    if (__last_audio_pts_value != -1) {
                        long pts = __last_audio_pts_value + NativeSourceSTCTracker.to90KhzClockTicks(Math.round(__audio_pes_packet_length_ns));
                        SharedObjectRegistry.tracing_cookie = (int)(pts & 0xFFFF); // first 2 bytes of the pts
//                        Trace.beginAsyncSection(SharedObjectRegistry.DEBUG_TRACING_PTS_TO_WRITE_SECTION, SharedObjectRegistry.tracing_cookie);
                        mAudioPayloadHandler.addToQueue(new AudioPESFragment(pts));
                        __last_audio_pts_value = pts;
//                        Log.i(TAG, "Beginning of Audio PES: Calculated PTS: " + pts);
                    }
                }
                // calculate how many samples exist within this PES packet's payload
                int __audio_payload_samples = __audio_payload_length_bytes / SharedObjectRegistry.AUDIO_SAMPLE_SIZE_IN_BYTES;
                __audio_pes_packet_length_ns = __audio_payload_samples * SharedObjectRegistry.AUDIO_SAMPLE_TIME_NS;
                dataToReadOffset = offset + 9 + pesHeaderLen + 4; // LPCM header only appears when PUSI = 1
            }
        }

        int length = end - dataToReadOffset;
        if (length > 0 && mAudioBufferLength + length <= mAudioStreamBuffer.length) {
            System.arraycopy(data, dataToReadOffset, mAudioStreamBuffer, mAudioBufferLength, length);
            mAudioBufferLength += length;
            extractLpcmFrames();
        }
    }


    private void extractLpcmFrames() {
        if (mAudioBufferLength < 4) return; // if we don't even have a single frame why continue?
        int _origAudioBufLen = mAudioBufferLength;
        byte[] _writebuf = new byte[((int)(mAudioBufferLength/4))*4]; // write buffer
        ByteBuffer __writebbuf;
        for (int i = 0; i < _origAudioBufLen;) {
            if (( i + 4 ) <= _origAudioBufLen) {
                System.arraycopy(mAudioStreamBuffer, i, _writebuf, i, 4);
//                inplaceConvertToLE(_writebuf, _writebuf.length);
                mAudioBufferLength -= 4;
                i += 4;
            } else {
                byte[] _tmpbuf = new byte[mAudioBufferLength];
                System.arraycopy(mAudioStreamBuffer, i, _tmpbuf, 0, mAudioBufferLength);
//                Arrays.fill(mAudioStreamBuffer, (byte) 0);
                System.arraycopy(_tmpbuf,0,mAudioStreamBuffer,0,mAudioBufferLength);
                break;
            }
        }
        __writebbuf = ByteBuffer.wrap(_writebuf);
        __writebbuf.order(ByteOrder.BIG_ENDIAN);
        ShortBuffer samples = __writebbuf.asShortBuffer();

        short[] pcm = new short[samples.remaining()];
        samples.get(pcm);

        mAudioPayloadHandler.addToQueue(new AudioPESFragment(pcm.clone()));
    }


    private void extractNalUnits() {
        if (mBufferLength < 3) return;

        int searchIdx = 0;
        while (searchIdx < mBufferLength - 2) {
            // Locate Annex B NALU Start Code 00 00 01 (Catches both 3-byte and 4-byte codes)
            if (mStreamBuffer[searchIdx] == 0x00 &&
                    mStreamBuffer[searchIdx + 1] == 0x00 &&
                    mStreamBuffer[searchIdx + 2] == 0x01) {

                // Determine if it's actually a 4-byte start code (00 00 00 01)
                int startCodeIdx = searchIdx;
                if (searchIdx > 0 && mStreamBuffer[searchIdx - 1] == 0x00) {
                    startCodeIdx = searchIdx - 1;
                }

                if (startCodeIdx > 0) {
                    // We found a start code in the middle of the buffer.
                    // Everything before this belongs to the previous NAL unit.
                    byte[] nal = new byte[startCodeIdx];
                    System.arraycopy(mStreamBuffer, 0, nal, 0, startCodeIdx);
                    feedToDecoder(nal);

                    // Shift the remaining data (which starts with the new start code) to the front
                    int remaining = mBufferLength - startCodeIdx;
                    System.arraycopy(mStreamBuffer, startCodeIdx, mStreamBuffer, 0, remaining);
                    mBufferLength = remaining;

                    // Reset search index to the start of the new buffer
                    searchIdx = 0;
                } else {
                    // The buffer starts exactly with a start code.
                    // We must scan forward to find the NEXT start code to know where this NALU ends.
                    int nextIdx = searchIdx + 3;
                    boolean foundNext = false;

                    while (nextIdx < mBufferLength - 2) {
                        if (mStreamBuffer[nextIdx] == 0x00 &&
                                mStreamBuffer[nextIdx + 1] == 0x00 &&
                                mStreamBuffer[nextIdx + 2] == 0x01) {

                            int nextStartCodeIdx = nextIdx;
                            if (mStreamBuffer[nextIdx - 1] == 0x00) {
                                nextStartCodeIdx = nextIdx - 1;
                            }

                            // Extract the complete NAL unit
                            byte[] nal = new byte[nextStartCodeIdx];
                            System.arraycopy(mStreamBuffer, 0, nal, 0, nextStartCodeIdx);
                            feedToDecoder(nal);

                            // Shift the remaining data to the front
                            int remaining = mBufferLength - nextStartCodeIdx;
                            System.arraycopy(mStreamBuffer, nextStartCodeIdx, mStreamBuffer, 0, remaining);
                            mBufferLength = remaining;

                            foundNext = true;
                            break; // Break inner loop, outer loop will process the new start code at index 0
                        }
                        nextIdx++;
                    }

                    if (!foundNext) {
                        // We haven't received the end of this NAL unit yet.
                        // Break the outer loop and wait for more UDP packets.
                        break;
                    }
                }
            } else {
                searchIdx++;
            }
        }
    }

//    private void feedToDecoder(byte[] naluData) {
//        if (mCodec == null) return;
//        try {
//            int inIndex = mCodec.dequeueInputBuffer(10000);
//            if (inIndex >= 0) {
//                ByteBuffer buffer = mCodec.getInputBuffer(inIndex);
//                if (buffer != null) {
//                    buffer.clear();
//                    buffer.put(naluData);
//                    mCodec.queueInputBuffer(inIndex, 0, naluData.length, 0, 0);
//                }
//            }
//
//            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
//            int outIndex = mCodec.dequeueOutputBuffer(info, 10000);
//            while (outIndex >= 0) {
//                mCodec.releaseOutputBuffer(outIndex, true); // Push straight to Surface
//                outIndex = mCodec.dequeueOutputBuffer(info, 0);
//            }
//        } catch (Exception e) {
//            Log.e(TAG, "MediaCodec Exception", e);
//        }
//    }


    private void tryToFeedDecoder(MediaCodec codec) {
        byte[] nalu;
        boolean __noPayload;
        synchronized (mFeederLock) {
            try {
                while (!mAvailableInputBuffersIndexs.isEmpty()) {
                    __noPayload = false;
                    Integer ind = mAvailableInputBuffersIndexs.poll();
                    if (ind == null) return;
                    ByteBuffer buf = codec.getInputBuffer(ind);
                    if (buf == null) continue;
                    buf.clear();
                    nalu = mPendingNALus.peek();
                    if (nalu == null) {
//                        mAvailableInputBuffersIndexs.offer(ind);
                        codec.queueInputBuffer(ind, 0, 0, 0, 0);
                        return;
                    } else {
                        // if nalu != null
                        if (nalu[0] != SharedObjectRegistry.VIDEO_PTS_MARKER) {
                            buf.put(nalu);
                        } else {
                            long __extractedPts = 0;
                            __extractedPts = (((long) nalu[1] & 0xFF) << 56) |
                                    (((long) nalu[2] & 0xFF) << 48) |
                                    (((long) nalu[3] & 0xFF) << 40) |
                                    (((long) nalu[4] & 0xFF) << 32) |
                                    (((long) nalu[5] & 0xFF) << 24) |
                                    (((long) nalu[6] & 0xFF) << 16) |
                                    (((long) nalu[7] & 0xFF) <<  8) |
                                    (((long) nalu[8] & 0xFF));
                            if (__extractedPts == 0) {
                                target_video_presentation_ns = -1;
                            }
                            else {
                                target_video_presentation_ns = NativeSourceSTCTracker.sourceSTCToMonotonic(NativeSourceSTCTracker.toNanoSeconds(__extractedPts));
                            }
                            __noPayload = true;
                        }
                        mPendingNALus.poll(); // remove head since I've used NALu.
                        if (__noPayload) {
                            codec.queueInputBuffer(ind, 0, 0, 0, 0);
                        } else {
                            if (target_video_presentation_ns != -1) {
                                codec.queueInputBuffer(ind, 0, nalu.length, (target_video_presentation_ns / (long) 1000), 0);
                            } else {
                                // display immediately
                                codec.queueInputBuffer(ind, 0, nalu.length, 0, 0);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "tryToFeedDecoder() Failed: " + t.getMessage());
            }
        }
    }



    private void feedToDecoder(byte[] naluData) { // takes a single NAL unit
        if (mCodec == null) return;
        try {
            // we can't risk dropping a single NALu since a NALu could be related to a frame
            // that the decoder's current NAL is related to.
            tryToFeedDecoder(mCodec);
            mPendingNALus.put(naluData);
        } catch (Throwable t) {
            Log.e(TAG, "feedToDecoder() Exception!: " + t.getMessage());
        }
    }



    private String getOptimalAVCDecoderName() {
        final String TAG = "getOptimalAVCDecoderName";
        StringBuilder sb = new StringBuilder();
        boolean supportsHWDecode = false;
        String codecName = "";
        String hwCodecName = "";
        sb.append("Supported AVC/H.264 Codecs:\n");
        MediaCodecList mcl = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo mci : mcl.getCodecInfos()) {
            sb.append("Codec : ").append(mci.getName()).append(
                    mci.isHardwareAccelerated() ? " Supports hardware acceleration!\n" : "No HW Acceleration\n"
            );
            for (String mt : mci.getSupportedTypes()) {
                if (mt.equals("video/avc") && !mci.isEncoder()) {
                    codecName = mci.getName();
                    if (mci.isHardwareAccelerated()) {
                        supportsHWDecode = true;
                        hwCodecName = mci.getName();
                    }
                    break;
                }
            }
        }
        Log.i(TAG, sb.toString());
        if (supportsHWDecode) {
            Log.i(TAG, "Found AVC/H.264 decoder that supports HW Accel!: " + hwCodecName);
            return hwCodecName;
        } else {
            // we don't have any hw decoders for avc/h.264
            Log.i(TAG, "No HW Decoder for AVC/H.264 found!, Defaulting to SW codec: " + codecName);
            return codecName;
        }
    }
}