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
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

public class MpegTsPayloadDecoder extends Thread {
    private static final String TAG = "MpegTsDecoder";
    private static final int UDP_PORT = SharedObjectRegistry.UDP_PORT;

    private final Surface mSurface;
    private MediaCodec mCodec;
    private boolean isRunning = true;

    // H.264 Streaming Window Buffer
    private final byte[] mStreamBuffer = new byte[2 * 1024 * 1024]; // 2MB sliding buffer
    private int mBufferLength = 0;

    // Parsed PIDs
    private int mPmtPid = -1;
    private int mVideoPid = -1;

    // ArrayBlockingQueue for storing pending NALus
    public ArrayBlockingQueue<byte[]> mPendingNALus = new ArrayBlockingQueue<>(500); // maximum capacity of 500 NALus.

    // ArrayBlockingQueue for storing available input buffers indices.
    private ArrayBlockingQueue<Integer> mAvailableInputBuffersIndexs = new ArrayBlockingQueue<Integer>(32); // more than enough I guess.
    private final Object mFeederLock = new Object();


    public MpegTsPayloadDecoder(Surface surface) {
        this.mSurface = surface;
    }

    @Override
    public void run() {
        // increase thread priority
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        HandlerThread ht = new HandlerThread(SharedObjectRegistry.HANDLER_THREAD_NAME, Process.THREAD_PRIORITY_URGENT_AUDIO);
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
                    codec.releaseOutputBuffer(index, true);
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

        int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
        boolean pusi = (data[offset + 1] & 0x40) != 0;
        int adaptation = (data[offset + 3] & 0x30) >> 4;

        int payloadOffset = offset + 4;
        if (adaptation == 2 || adaptation == 3) {
            int afLen = data[offset + 4] & 0xFF;
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
                }  // TODO: New else if for parsing Audio packets after retrieving its PID from PMT
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
            int streamType = data[streamOffset] & 0xFF;
            int elementaryPid = ((data[streamOffset + 1] & 0x1F) << 8) | (data[streamOffset + 2] & 0xFF);
            int esInfoLength = ((data[streamOffset + 3] & 0x0F) << 8) | (data[streamOffset + 4] & 0xFF);

            if (streamType == 0x1B) { // 0x1B corresponds to AVC / H.264
                mVideoPid = elementaryPid;
                break;
            } // TODO: Check if streamType == ID of LPCM Audio packets
            streamOffset += 5 + esInfoLength;
        }
    }

    private void parseVideoPes(byte[] data, int offset, int end, boolean pusi) {
        int dataToReadOffset = offset;
        if (pusi) {
            // Check for PES start code (0x000001)
            if (data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 1) {
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
        synchronized (mFeederLock) {
            try {
                while (!mAvailableInputBuffersIndexs.isEmpty()) {
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
                        buf.put(nalu);
                        mPendingNALus.poll(); // remove head since I've used NALu.
                        codec.queueInputBuffer(ind, 0, nalu.length, 0, 0);
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