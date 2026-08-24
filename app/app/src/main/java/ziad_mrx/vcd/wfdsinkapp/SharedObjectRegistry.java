package ziad_mrx.vcd.wfdsinkapp;

import android.annotation.SuppressLint;
import android.os.HandlerThread;

import java.io.InvalidObjectException;
import java.util.concurrent.ArrayBlockingQueue;


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

}
