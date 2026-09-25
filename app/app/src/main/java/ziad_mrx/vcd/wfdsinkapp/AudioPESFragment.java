package ziad_mrx.vcd.wfdsinkapp;

import java.nio.ByteBuffer;

public class AudioPESFragment {
    public long pts = -1; // in 90khz clock ticks, unwrapped around
    public short[] samples = null;

    public AudioPESFragment(long _pts) {
        this.pts = _pts;
        this.samples = null;
    }

    public AudioPESFragment(short[] _samples) {
        this.samples = _samples;
        this.pts = -1;
    }

}
