package ziad_mrx.vcd.wfdsinkapp;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class UibcHandler extends Thread {
    private static final String TAG = "UibcHandler";
    private Socket sock = new Socket();
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private AtomicBoolean isRunning = new AtomicBoolean(true);

    private SynchronousQueue<byte[]> mUibcPacketsQueue = new SynchronousQueue<byte[]>(true);

    private Thread mUibcSenderThread = new Thread(this::UibcSender);

    // The descriptor as a hex string, one line per group from my table.
    private static final String DESCRIPTOR_HEX =
            "050D0904A101" + "0922A100"
                    + "0942" + "1500" + "2501" + "7501" + "9501" + "8102"
                    + "9507" + "8103"
                    + "0501" + "1500" + "26FF0F" + "7510" + "9501"
                    + "550E" + "6513" + "3500" + "46B504" + "0930" + "8102"
                    + "46B504" + "0931" + "8102"
                    + "050D" + "550C" + "660110" + "47FFFF0000"
                    + "27FFFF0000" + "7510" + "9501" + "0956" + "8102"
                    + "C0C0";

    public static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;   // the (byte) cast is what handles values above 0x7F
    }

    // One UIBC packet: header (category 1) + HIDC body (USB path, Single Touch type).
    public static byte[] frameHidc(int usage, byte[] data) {       // usage: 0x01 descriptor, 0x00 input report
        int bodyLen = 5 + data.length;
        int pad = bodyLen % 2;                               // body must be an even length
        int total = 4 + bodyLen + pad;                       // Length includes the 4-byte header
        ByteArrayOutputStream b = new ByteArrayOutputStream(total);
        b.write(0x00); b.write(0x01);                        // version/T/reserved, category = 1
        b.write(total >> 8); b.write(total & 0xFF);
        b.write(0x01); b.write(0x02); b.write(usage);        // USB, Single Touch, usage
        b.write(data.length >> 8); b.write(data.length & 0xFF);
        b.write(data, 0, data.length);
        if (pad == 1) b.write(0x00);
        return b.toByteArray();
    }


    private void UibcSender() {
        final String STAG = "UibcSender Thread";
        byte[] elem;
        try {
            while (isRunning.get()) {
                // fetch from the Uibc queue, then send it.
                elem = mUibcPacketsQueue.poll(10, TimeUnit.SECONDS);
                if (elem != null) {
                    try {
                        sock.getOutputStream().write(elem);
                        sock.getOutputStream().flush();
                        Log.d(TAG, STAG + "TX: " + toHex(elem, elem.length));
                    } catch (IOException ioe) {
                        Log.e(TAG, STAG + ": Failed to send bytes to queue: IOException: " + ioe.getMessage());
                    }
                }
            }
        } catch (InterruptedException ie) {
            return;
        }
    }


    public static byte[] touchReport(boolean tipDown, int x, int y, int scanTime100us) {
        x = Math.max(0, Math.min(4095, x));      // clamp to the declared range
        y = Math.max(0, Math.min(4095, y));
        int t = scanTime100us & 0xFFFF;          // 16-bit, wraps
        return new byte[] {
                (byte) (tipDown ? 1 : 0),            // bit 0 = tip switch, other 7 bits are padding (0)
                (byte) (x & 0xFF), (byte) (x >> 8),  // X, little-endian
                (byte) (y & 0xFF), (byte) (y >> 8),  // Y, little-endian
                (byte) (t & 0xFF), (byte) (t >> 8)   // scan time, little-endian
        };
    }

    public static String toHex(byte[] buf, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", buf[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    @Override
    public void run() {
        Log.d(TAG, "Thread started!");
        // we need to listen to our uibc port first.
        try {
            while (!isConnected.get()) {
                try {
                    synchronized (SharedObjectRegistry.UIBC_PORT_LOCK) {
                        sock.connect(new InetSocketAddress(SharedObjectRegistry.connected_source_ip_addr, SharedObjectRegistry.UIBC_SINK_PORT), 1500);
                    }
                    Log.i(TAG, "Connected for UIBC to source at " + SharedObjectRegistry.connected_source_ip_addr);
                    isConnected.set(true);
                    break;
                } catch (SocketTimeoutException ignored) {

                } catch (IOException ioe) {
                    Log.e(TAG, "IOException while dialing out to Source with IP " + SharedObjectRegistry.connected_source_ip_addr + " at port " + SharedObjectRegistry.UIBC_SINK_PORT + ": " + ioe.getMessage());
                }
            }
            // atp we're connected (probably)
            try {
                // we will just launch the queue drainer thread
                mUibcSenderThread.start();
                // add descriptor bytes to the queue
                addToQueue(frameHidc((int)0x01, hexToBytes(DESCRIPTOR_HEX)));
                int bytesRead = 0;
                byte[] b = new byte[1500];
                while ((bytesRead = sock.getInputStream().read(b)) != 0) {
                    Log.d(TAG, "UIBC_PORT_SRC RX: " + toHex(b, bytesRead));
                }
            } catch (IOException ioe) {
                Log.e(TAG, "UIBC Socket IOException: " + ioe.getMessage());
                return;
            }
            Log.i(TAG, "UIBC Socket connection session ended!");
        } catch (Throwable t) {
            Log.e(TAG, "UIBC Handler exception: " + t.getMessage());
            return;
        }

    }

    @Override
    public void interrupt() {
        isRunning.set(false);
        mUibcSenderThread.interrupt();
        // interrupt the thread actually.
        super.interrupt();
    }

    public boolean addToQueue(byte[] element) {
        try {
            return mUibcPacketsQueue.offer(element, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }
}
