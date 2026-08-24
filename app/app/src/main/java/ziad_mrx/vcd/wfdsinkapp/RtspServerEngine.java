package ziad_mrx.vcd.wfdsinkapp;

import android.annotation.SuppressLint;
import android.net.wifi.p2p.WifiP2pDevice;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;

public class RtspServerEngine extends Thread {
    private static final String TAG = "RtspServerEngine";
    private static final int WFD_CTRL_PORT = 7236;
    private static final int UDP_MEDIA_PORT = 19000;

    private final InetAddress mBindAddress;
    private final RtspCallback mCallback;
    private boolean isRunning = true;
    private final AtomicBoolean sessionClaimed = new AtomicBoolean(false);
    private MiracastSinkManager mSinkManager;

    private static final String SINK_FRIENDLY_NAME = "Mr X Software Virtual WiFi Display App";
    private static final String SINK_MANUFACTURER = "Ziad Mr X Software";
    private static final String SINK_MODEL_NAME = "VWiFi_Display_App";
    private static final String SINK_INTEL_VER = "1.0";
    private static final String SINK_INFO_URL = "https://insertx2k.github.io/mrx";

    public static final HashMap<String, String> KNOWN_WFD_PARAMETERS = new HashMap<>();
    static {
        //KNOWN_WFD_PARAMETERS.put("wfd_video_formats","00 00 02 02 00000040 00000000 00000000 00 0000 0000 00 none none");
//        KNOWN_WFD_PARAMETERS.put("wfd_video_formats","30 00 01 08 0001ffff 3fffffff 00000000 00 0000 0000 00 none none");
        KNOWN_WFD_PARAMETERS.put("wfd_video_formats","30 00 01 08 0000ffff 3fffffff 00000000 00 0000 0000 00 none none");
        KNOWN_WFD_PARAMETERS.put("wfd_audio_codecs","LPCM 00000002 00 00");
        KNOWN_WFD_PARAMETERS.put("wfd_client_rtp_ports","RTP/AVP/UDP;unicast " + UDP_MEDIA_PORT + " 0 mode=play");
        KNOWN_WFD_PARAMETERS.put("intel_friendly_name", SINK_FRIENDLY_NAME);
        KNOWN_WFD_PARAMETERS.put("intel_sink_manufacturer_name", SINK_MANUFACTURER);
        KNOWN_WFD_PARAMETERS.put("intel_sink_model_name", SINK_MODEL_NAME);
        KNOWN_WFD_PARAMETERS.put("intel_sink_version",SINK_INTEL_VER);
        KNOWN_WFD_PARAMETERS.put("intel_sink_device_URL",SINK_INFO_URL);
        KNOWN_WFD_PARAMETERS.put("wfd_connector_type","07"); // Internal/Integrated.
        KNOWN_WFD_PARAMETERS.put("wfd_idr_request_capability","none");
        KNOWN_WFD_PARAMETERS.put("wfd_uibc_capability","none");
//        KNOWN_WFD_PARAMETERS.put("wfd_display_edid","none");
//        KNOWN_WFD_PARAMETERS.put("wfd_display_edid","0002 00ffffffffffff004dd9008201010101000f0103800000780a0dc9a05747982712484c00000001010101010101010101010101010101023a801871382d40582c450010090000001e8c0ad08a20e02d10103e9600040300000018000000fc00534f4e592054560a2020202020000000fd003b3d0f460f000a202020202020014502031c76480590030406070102230907078301000066030c00100080011d007251d01e206e28550010090000001e8c0aa01451f01600267c4300040300000098011d8018711c1620582c250010090000009e8c0ad08a20e02d10103e960010090000001800000000000000000000000000000000000000000000000000000061");
        KNOWN_WFD_PARAMETERS.put("wfd_display_edid","0002 00ffffffffffff004dd9008201010101000f0103800000780a0dc9a05747982712484c00000001010101010101010101010101010101023a801871382d40582c450010090000001e8c0ad08a20e02d10103e9600040300000018000000fc004d722e582053696e6b20417070000000fd003b3d0f460f000a20202020202001c702031c76480590030406070102230907078301000066030c00100080011d007251d01e206e28550010090000001e8c0aa01451f01600267c4300040300000098011d8018711c1620582c250010090000009e8c0ad08a20e02d10103e960010090000001800000000000000000000000000000000000000000000000000000061");

        KNOWN_WFD_PARAMETERS.put("microsoft_latency_management_capability","none");
        KNOWN_WFD_PARAMETERS.put("microsoft_format_change_capability","none");
        KNOWN_WFD_PARAMETERS.put("microsoft_diagnostics_capability","none");
        KNOWN_WFD_PARAMETERS.put("microsoft_cursor","none");
    };

    public interface RtspCallback {
        void onPlayRequested();
    }



    public RtspServerEngine(InetAddress bindAddress, RtspCallback callback, MiracastSinkManager sinkMgr) {
        this.mBindAddress = bindAddress;
        this.mCallback = callback;
        this.mSinkManager = sinkMgr;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(mBindAddress, WFD_CTRL_PORT), 50);

            Log.i(TAG, "RTSP Server listening on: " + mBindAddress.getHostAddress() + ":7236");

            while (isRunning) {
                Socket client = serverSocket.accept();
                Log.i(TAG, "Miracast Source connected! IP: " + client.getInetAddress().getHostAddress());
                handleClient(client);
            }
        } catch (Exception e) {
            Log.e(TAG, "RTSP Server Error", e);
        }
    }

    private void handleClient(Socket client) {
        sessionClaimed.set(true);
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();
            String line;

            int cseq = 0;
            int contentLength = 0;
            int myCseq = 100; // Counter for requests we send to the Source
            int setupCseq = -1; // Tracks the CSeq of our SETUP request
            boolean hasSentPlay = false;

            String method = "";
            boolean isRequest = false;
            boolean isResponse = false;

            String sessionId = "";
            String presentationUrl = "rtsp://" + client.getInetAddress().getHostAddress() + "/wfd1.0/streamid=0";

            while ((line = in.readLine()) != null) {
                Log.d(TAG, "RX: " + line);

                if (line.startsWith("OPTIONS") || line.startsWith("GET_PARAMETER") ||
                        line.startsWith("SET_PARAMETER") || line.startsWith("SETUP") ||
                        line.startsWith("PLAY") || line.startsWith("TEARDOWN")) {
                    method = line.split(" ")[0];
                    isRequest = true;
                } else if (line.startsWith("RTSP/1.0 200 OK")) {
                    isResponse = true;
                } else if (line.startsWith("CSeq:")) {
                    cseq = Integer.parseInt(line.split(":")[1].trim());
                } else if (line.startsWith("Content-Length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                } else if (line.startsWith("Session:")) {
                    // Extract Session ID from Source's 200 OK replies (e.g. "Session: 12345678;timeout=60")
                    sessionId = line.substring(line.indexOf(":") + 1).trim().split(";")[0];
                } else if (line.isEmpty()) {
                    char[] bodyChars = null;
                    String body = "";
                    if (contentLength > 0) {
                        bodyChars = new char[contentLength];
                        in.read(bodyChars, 0, contentLength);
                        body = new String(bodyChars);
                        Log.d(TAG, "RX BODY:\n" + body);

                        // If the Source gives us a specific presentation URL, save it for our SETUP request
                        if (body.contains("wfd_presentation_URL:")) {
                            for (String bLine : body.split("\n")) {
                                if (bLine.trim().startsWith("wfd_presentation_URL:")) {
                                    String[] parts = bLine.split(" ");
                                    if (parts.length > 1) {
                                        presentationUrl = parts[1].trim();
                                    }
                                }
                            }
                        }
                    }

                    if (isRequest) {
                        if (method.equals("OPTIONS")) {
                            sendResponse(out, cseq, "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN, CLOSED\r\n");

                            // Immediately send our M2 Request back
                            myCseq++;
                            String m2 = "OPTIONS * RTSP/1.0\r\n" +
                                    "CSeq: " + myCseq + "\r\n" +
                                    "Require: org.wfa.wfd1.0\r\n\r\n";
                            out.write(m2.getBytes());
                            out.flush();
                            Log.d(TAG, "TX M2:\n" + m2);

                        } else if (method.equals("GET_PARAMETER")) {
                            if (contentLength > 0) {
                                String payload = buildGetParameterMethodResponse(new String(bodyChars));

                                sendResponse(out, cseq, "Content-Type: text/parameters\r\nContent-Length: " + payload.getBytes().length + "\r\n\r\n" + payload);
                            } else {
                                // this is a keep-alive from Windows, send 200/OK
                                sendResponse(out, cseq, "");
                            }
                        } else if (method.equals("SET_PARAMETER")) {
                            sendResponse(out, cseq, "");

                            if (body.contains("wfd_trigger_method: SETUP")) {
                                myCseq++;
                                setupCseq = myCseq; // Save this CSeq so we know when Windows replies to it
                                String m6 = "SETUP " + presentationUrl + " RTSP/1.0\r\n" +
                                        "CSeq: " + myCseq + "\r\n" +
                                        "Transport: RTP/AVP/UDP;unicast;client_port=" + UDP_MEDIA_PORT + "\r\n\r\n";
                                out.write(m6.getBytes());
                                out.flush();
                                Log.d(TAG, "TX M6 (SETUP):\n" + m6);
                            } else if (body.contains("wfd_trigger_method: PLAY") && !hasSentPlay) {
                                // Fallback for Android sources that DO send the PLAY trigger
                                hasSentPlay = true;
                                myCseq++;
                                String m8 = "PLAY " + presentationUrl + " RTSP/1.0\r\n" +
                                        "CSeq: " + myCseq + "\r\n" +
                                        "Session: " + sessionId + "\r\n\r\n";
                                out.write(m8.getBytes());
                                out.flush();
                                Log.d(TAG, "TX M8 (PLAY):\n" + m8);

                                Log.i(TAG, "PLAY TRIGGERED! Starting Hardware H.264 Decoder...");
                                mCallback.onPlayRequested();
                            }

                        } else if (method.equals("SETUP")) {
                            sendResponse(out, cseq, "Session: 11223344;timeout=60\r\nTransport: RTP/AVP/UDP;unicast;client_port=" + UDP_MEDIA_PORT + "\r\n");
                        } else if (method.equals("PLAY")) {
                            sendResponse(out, cseq, "Session: 11223344\r\n");
                            if (!hasSentPlay) {
                                hasSentPlay = true;
                                mCallback.onPlayRequested();
                            }
                        } else {
                            sendResponse(out, cseq, "");
                        }
                    } else if (isResponse) {
                        Log.d(TAG, "Received 200 OK from Source (CSeq: " + cseq + ")");

                        // WINDOWS FIX: If this 200 OK is the response to our SETUP command, immediately send PLAY!
                        if (cseq == setupCseq && !hasSentPlay) {
                            hasSentPlay = true;
                            myCseq++;
                            String m7 = "PLAY " + presentationUrl + " RTSP/1.0\r\n" +
                                    "CSeq: " + myCseq + "\r\n" +
                                    "Session: " + sessionId + "\r\n\r\n";
                            out.write(m7.getBytes());
                            out.flush();
                            Log.d(TAG, "TX M7 (PLAY):\n" + m7);

                            Log.i(TAG, "PLAY SENT! Starting Hardware H.264 Decoder...");
                            mCallback.onPlayRequested();
                        }
                    }

                    // Reset variables for the next packet
                    method = "";
                    cseq = 0;
                    contentLength = 0;
                    isRequest = false;
                    isResponse = false;
                }
            }
            Log.w(TAG, "TCP Connection closed by remote peer.");
            // if connection was closed by remote peer, we will start the dial out attempts again
            sessionClaimed.set(false);
            startDialOutAttempts();
            mSinkManager.ensureP2pDhcpServer(); // start back the p2p dhcp server
            // TODO: we have to stop the RCP Receiver Reports Sender.
        } catch (Exception e) {
            Log.e(TAG, "Client handling exception", e);
        }
    }

    private void sendResponse(OutputStream out, int cseq, String extra) throws Exception {
        String response = "RTSP/1.0 200 OK\r\n" +
                "CSeq: " + cseq + "\r\n" +
                (extra.isEmpty() || extra.startsWith("Content-Type") ? extra : extra + "\r\n");
        if (extra.isEmpty()) response += "\r\n";

        Log.d(TAG, "TX:\n" + response);
        out.write(response.getBytes());
        out.flush();
    }


    // Call this once the P2P group has formed, in parallel with the existing
    // listen thread. Repeatedly scans ARP for the peer's IP and tries a short
    // outbound connect to it on the control port, in case the source is
    // waiting for US to dial in rather than dialing in itself.
    public void startDialOutAttempts() {
        new Thread(() -> {
            while (!sessionClaimed.get()) {
                List<String> p2pPeerIps = findP2pPeerIps();
                if (p2pPeerIps == null) {
                    Log.e(TAG, "Failed to retrieve peer ips!");
                    continue;
                }
                for (String ip : p2pPeerIps) {
                    if (sessionClaimed.get()) return;
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(ip, WFD_CTRL_PORT), 150);
                        Log.i(TAG, "Dial-out succeeded to " + ip + ":" + WFD_CTRL_PORT);
                        SharedObjectRegistry.connected_source_ip_addr = ip;
                        // in this case, we stop the p2p dhcp server to save resources.
                        mSinkManager.stopP2pDhcpServer();
                        handleClient(s); // same message handling either direction
                        return;
                    } catch (Exception e) {
                        Log.d(TAG, "Dial-out attempt to " + ip + " not ready: " + e.getMessage());
                    }
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
//            if (!sessionClaimed.get()) {
//                Log.w(TAG, "Dial-out attempts exhausted with no session.");
//                // we must start advertising ourselves again.
//                mSinkManager.startAdvertising();
//            }
        }).start();
    }

    private List<String> findP2pPeerIps() {
        List<String> ips = new ArrayList<>();
        Process catARPs;
        try {
            catARPs = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /proc/net/arp"});
            catARPs.waitFor(); // we will wait for the cat command to finish first!
        } catch (Throwable e) {
            Log.e(TAG, "Failed to cat ARP table using root privileges!: " + e.getMessage());
            return null;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(catARPs.getInputStream()))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; } // skip header row
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6) {
                    String ip = parts[0];
                    String device = parts[5];
                    if (device.startsWith("p2p") && !ip.equals("192.168.49.1")) {
                        ips.add(ip);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read ARP table", e);
        }
        // Fallback: brute-force the small P2P subnet if ARP hasn't populated yet.
        if (ips.isEmpty()) {
            for (int i = 2; i <= 10; i++) ips.add("192.168.49." + i);
        }
        return ips;
    }





    public String buildGetParameterMethodResponse(String getParameterBody) {
        StringBuilder sb = new StringBuilder(); // our stringbuilder
        for (String requiredParam : getParameterBody.split("\n")) {
            String param_name = requiredParam.trim();
            if (param_name.isEmpty()) continue;
            // check if it exists within known params or not, if yes
            // will add the known value, if not, will just set it to none
            if (KNOWN_WFD_PARAMETERS.containsKey(param_name)) {
                String value = KNOWN_WFD_PARAMETERS.get(param_name);
                sb.append(param_name).append(": ").append(value).append("\r\n");
            } else {continue;};
        }
        // add wfd_presentation_URL
        synchronized(SharedObjectRegistry.SHARED_OBJ_LOCK) {
            if (SharedObjectRegistry.sink_ip_addr.isEmpty()) {
                sb.append("wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n");
            } else {
                // sink IP addr is not empty!
                sb.append("wfd_presentation_URL: rtsp://").append(SharedObjectRegistry.sink_ip_addr).append("/wfd1.0/streamid=0 none\r\n");
            }
        }
        // end
        return sb.toString();
    }



}