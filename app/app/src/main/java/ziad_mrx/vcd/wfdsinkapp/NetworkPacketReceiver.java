package ziad_mrx.vcd.wfdsinkapp;

import android.os.Process;
import android.util.Log;

import java.lang.Thread;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import kotlin.text.Charsets;

public class NetworkPacketReceiver extends Thread {
    private static final String TAG = "NetworkPacketReceiver Thread: ";
    private byte[] currentPacketRTPHeader = new byte[12];

    private DatagramSocket s;
    private byte[] packetBuffer;
    private DatagramPacket p;


    // for RTP Receiver Reporting
    int sourceSsrc = 0;
    int baseSeq = -1;
    int maxSeq = 0;         // 16-bit, wraps
    int cycles = 0;         // counts wraparounds
    // records number of times the packet sequence number has reached its maximum value and had to reset.
    long received = 0L;
    long expectedPrior = 0L;
    long receivedPrior = 0L;
    double jitter = 0.0;
    long transit = -1L;
    long startNanoTime = 0L;
    int ssrc = 0;
    long timestamp = 0L;
    int seq = 0;
    // ScheduledExecutorService for periodically sending RTCP Receiver Reports
    private ScheduledExecutorService mSchedExecutorSvc = Executors.newSingleThreadScheduledExecutor();

    DatagramSocket ss = null;
    // no arg constructor
    NetworkPacketReceiver() {
        try {
            ss = new DatagramSocket();
        } catch (Throwable t) {
            Log.e(TAG, "Datagram send socket for RTCP Receiver reports failed to create, expect errors.");
        }
    }

    @Override
    public void run() {
        Log.i(TAG, "Thread started execution...");
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        try {
            s = new DatagramSocket(SharedObjectRegistry.UDP_PORT);
            s.setReceiveBufferSize(5242880); // 5MB buffer size
            packetBuffer = new byte[1500];
            p = new DatagramPacket(packetBuffer, packetBuffer.length);
            // schedule sendRTCPReceiverReports() to run periodically after socket connects.
            mSchedExecutorSvc.scheduleWithFixedDelay(this::sendRTCPReceiverReport, 5, 5, TimeUnit.SECONDS);
            while (true) {
                s.receive(p);
                byte[] qp = new byte[p.getLength()];
                System.arraycopy(packetBuffer,0,qp,0,qp.length);
                boolean res = SharedObjectRegistry.networkPacketsQueue.offer(qp);
                if (!res) Log.w(TAG, "WARNING: Attempted to send a packet to a filled up queue!!!");
                // put RTP header in currentPacketRTPHeader
                System.arraycopy(packetBuffer,0,currentPacketRTPHeader,0,12);
                // invoke callback
                onPacketReceived();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Exception :" + t.getMessage());
            mSchedExecutorSvc.shutdownNow();
            return;
        }
    }

    private synchronized void onPacketReceived() {
        byte[] data = currentPacketRTPHeader;
        seq = (((int)data[2] & 0xFF) << 8) | ((int)data[3] & 0xFF);
        timestamp = (((long)data[4] & 0xFF) << 24) |
                (((long)data[5] & 0xFF) << 16) |
                (((long)data[6] & 0xFF) << 8) |
                ((long)data[7] & 0xFF);
        sourceSsrc = (((int)data[8] & 0xFF) << 24) |
                (((int)data[9] & 0xFF) << 16) |
                (((int)data[10] & 0xFF) << 8) |
                ((int)data[11] & 0xFF);
        received++;

        if (baseSeq == -1) {
            baseSeq = seq;
            maxSeq = seq;
            startNanoTime = System.nanoTime();
        } else {
            // signed 16-bit circular delta: positive means `seq` is newer than maxSeq,
            // correctly handling wraparound (e.g. 65535 -> 0) via the (short) sign-extension
            int delta = (short) (seq - (maxSeq & 0xFFFF));
            if (delta > 0) {
                if (seq < (maxSeq & 0xFFFF)) {
                    cycles += 0x10000; // sequence number wrapped past 65535
                }
                maxSeq = seq;
            }
            // delta <= 0: a duplicate, reordered, or old packet — don't advance maxSeq
        }

        // jitter: needs arrival time expressed in the same clock units as the
        // RTP timestamp. MPEG2-TS/RTP (RFC 2250) uses a 90kHz clock.
        double elapsedSec = (System.nanoTime() - startNanoTime) / 1_000_000_000.0;
        long arrivalRtpUnits = (long)(elapsedSec * 90000);
        long transitNow = arrivalRtpUnits - timestamp;
        if (transit != -1L) {
            long d = Math.abs(transitNow - transit);
            jitter += (d - jitter) / 16.0;
        }
        transit = transitNow;
    }

    synchronized int[] snapshotAndReset() {
        /*
        * Returns int[]
        * 0 -> sourceSsrc
        * 1 -> fraction
        * 2 -> lostTotal
        * 3 -> extendedMax
        * 4 -> jitter
        * */
        int extendedMax = cycles + (maxSeq & 0xFFFF);
        long expected = extendedMax - baseSeq + 1L;
        long lostTotal = Math.max((expected - received),0);

        long expectedInterval = expected - expectedPrior;
        long receivedInterval = received - receivedPrior;
        long lostInterval = Math.max((expectedInterval - receivedInterval),0);
        int fraction = (expectedInterval <= 0) ? 0 : Math.max((Math.min(((int)(((double)lostInterval / expectedInterval) * 256)),255)),0);

        expectedPrior = expected;
        receivedPrior = received;
        String snapshotLoggingString = "Snapshot taken: source ssrc : " + sourceSsrc + "\nLost Fraction: " + fraction + "\nTotal Packet Loss: " + lostTotal + "\nJitter: " + jitter;
        Log.i(TAG, snapshotLoggingString);
        return new int[] {sourceSsrc, fraction, (int)lostTotal, extendedMax, (int)jitter};
    }



    public void sendRTCPReceiverReport() {
        Log.i(TAG, "Sending RTCP Receiver report operation started!");
        int[] inputs = snapshotAndReset();
        // if sourceSsrc is 0 exit
        if (inputs[0] == 0) return;
        // build RTCP binary packet
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);

        // --- RR packet: 8 words = 32 bytes with one report block ---
        buf.put((byte) 0x81);          // V=2, P=0, RC=1
        buf.put((byte)201);           // PT = RR
        buf.putShort((short)7);                 // length = words - 1
        buf.putInt(SharedObjectRegistry.SINK_SSRC);          // this sink's own SSRC

        buf.putInt(inputs[0]);              // SSRC of the source we're reporting on
        buf.putInt((inputs[1] << 24) | (inputs[2] & 0xFFFFFF));
        buf.putInt((int)inputs[3]);
        buf.putInt(inputs[4]); // jitter
        buf.putInt(0);                   // LSR — 0 if you're not tracking source's SR packets
        buf.putInt(0);                   // DLSR — 0 likewise

        // --- SDES packet: mandatory CNAME item ---
        byte[] cname = "wfd-sink@android".getBytes(Charsets.US_ASCII);
        int chunkBytes = 4 + 2 + cname.length;            // SSRC + type/len + text
        int paddedWords = (chunkBytes + 4) / 4;          // room for null terminator + padding
        buf.put((byte)0x81);          // V=2, P=0, SC=1
        buf.put((byte)202);           // PT = SDES
        buf.putShort((short) paddedWords); // length = total words - 1; paddedWords already excludes the header word
        buf.putInt(SharedObjectRegistry.SINK_SSRC);
        buf.put((byte)1);                      // SDES type = CNAME
        buf.put((byte)cname.length);
        buf.put(cname);
        for (int i = 0; i < (paddedWords * 4 - (4 + 2 + cname.length)); i++) {
            buf.put((byte)0);
        };

        byte[] out = new byte[(buf.position())];
        buf.rewind(); buf.get(out);
        try {
            ss.send(
                    new DatagramPacket(
                            out, out.length, InetAddress.getByName(SharedObjectRegistry.connected_source_ip_addr), SharedObjectRegistry.connected_source_rtcp_port
                    )
            );
            Log.i(TAG, "Sent RTCP Receiver Report to " + SharedObjectRegistry.connected_source_ip_addr + ":" + SharedObjectRegistry.connected_source_rtcp_port);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to construct a socket to use in sending RTCP Receiver reports!: " + t.getMessage());
            return;
        }
    }

}
