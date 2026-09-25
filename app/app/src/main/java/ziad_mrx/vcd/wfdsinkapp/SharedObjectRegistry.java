package ziad_mrx.vcd.wfdsinkapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;


@SuppressLint("StaticFieldLeak")
public class SharedObjectRegistry {
    public static MiracastSinkManager sinkManagerRef;
    public static ArrayBlockingQueue<byte[]> networkPacketsQueue = new ArrayBlockingQueue<>(2000); // buffer two frames

    public static final int UDP_PORT = 19000;

    public static String connected_source_ip_addr;
    public static int connected_source_rtcp_port = 7492; // currently constant port for windows PCs.

    public static final int SINK_SSRC = 115872;

    public static final String SINK_P2P_NAME = "Mr.X's Second Screen Privileged App";

    public static final String HANDLER_THREAD_NAME = "CODEC_CALLBACK_HANDLER_THREAD";

    public static final Object SHARED_OBJ_LOCK = new Object();


    public static String sink_ip_addr = "";

    // AudioTrack buffer size in bytes
    public static final int AUDIO_TRACK_BUF_SIZE = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2; // 20 KB buffer

    // Audio Attributes
    public static final AudioAttributes AUDIO_TRACK_ATTRIBS = (new AudioAttributes.Builder())
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_GAME)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            .build();
    // AudioFormat
    public static final AudioFormat AUDIO_FMT = (new AudioFormat.Builder()).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000) // 48kHz
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build();

    public static int AUDIO_TRACK_SESSION_ID = AudioManager.ERROR;

    /*
    * A boolean specifying whether or not our sink is allowed to play the audio payload coming from the
    * source.
    *
    * if false, the constructor of AudioPayloadHandler must avoid creating an AudioTrack object
    * and avoid handling any interactions at all.
    * */
    public static AtomicBoolean canPlayAudio = new AtomicBoolean(true);

    public static final long TARGET_BACKLOG_NS = 15_000_000; // how many ns of audio time that must sit in the buffer at any moment.

    public static final int AUDIO_SAMPLING_RATE = 48000;
    public static final int AUDIO_SAMPLE_SIZE_IN_BYTES = 4; // 16-bit stereo LPCM

    public static final long MAXIMUM_PCR_BASE_VALUE = 8589934591L;

    public static final long PCR_JITTER_MARGIN = 135000; // 1.5 seconds of jitter margin

    public static final double AUDIO_SAMPLE_TIME_NS = 1_000_000_000.0 / (double)AUDIO_SAMPLING_RATE;

    public static final byte VIDEO_PTS_MARKER = 0x58; // 'X', indicating that this current queue element is a PTS value for a video PES

    public static final long VIDEO_FRAME_DISPLAY_ACCEPTABLE_DELAY_US = 10_000; // 10 ms (around above a 60 fps frame)

    public static final String DEBUG_TRACING_PTS_TO_WRITE_SECTION = "pts_to_write";
    public static int tracing_cookie;

    static {

    }

}
