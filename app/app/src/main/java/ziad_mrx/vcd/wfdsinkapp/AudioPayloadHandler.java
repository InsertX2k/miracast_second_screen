package ziad_mrx.vcd.wfdsinkapp;

import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;

public class AudioPayloadHandler extends Thread {
    private static final String TAG = "AudioPayloadHandler";
    private AudioTrack mAudioTrack;

    private ArrayBlockingQueue<byte[]> mLpcmPayloadQueue = new ArrayBlockingQueue<>(96000); // 48000 audio frames max


    public AudioPayloadHandler() {
        if (SharedObjectRegistry.AUDIO_TRACK_SESSION_ID != AudioManager.ERROR) {
            // AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes, int mode, int sessionId
            this.mAudioTrack = (new AudioTrack.Builder()).setAudioAttributes(SharedObjectRegistry.AUDIO_TRACK_ATTRIBS)
                    .setAudioFormat(SharedObjectRegistry.AUDIO_FMT)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(SharedObjectRegistry.AUDIO_TRACK_BUF_SIZE)
                    .setSessionId(SharedObjectRegistry.AUDIO_TRACK_SESSION_ID)
                    .build();

            if (this.mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "*******  AudioTrack object failed to initialize!!!!");
            }
        } else {
            Log.e(TAG, "AUDIO_TRACK_SESSION_ID was ERROR at construction time!");
            SharedObjectRegistry.canPlayAudio.set(false); // can't play audio
        }
    }


    @Override
    public void run() {
        try {
            if (SharedObjectRegistry.canPlayAudio.get()) {
                // play AudioTrack
                this.mAudioTrack.play();
                byte[] _buf = null;

                while (true) {
                    _buf = mLpcmPayloadQueue.take();
                    int res = this.mAudioTrack.write(_buf, 0, _buf.length);
                    if (res < 0) Log.e(TAG, "AudioTrack failed to write...: " + res);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error has occured: " + t.getMessage());
            return;
        }
    }

    public void addToQueue(byte[] input) {
        if (input != null) {
            if (input.length > 0) {
                boolean res = mLpcmPayloadQueue.offer(input);
                if (!res) {
                    Log.e(TAG, "Attempted to add another LPCM audio frame to an already filled up queue!!!!");
                }
            }
        }
    }


}
