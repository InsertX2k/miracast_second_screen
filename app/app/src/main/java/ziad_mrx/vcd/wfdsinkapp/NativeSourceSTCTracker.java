package ziad_mrx.vcd.wfdsinkapp;

import java.math.BigInteger;

public class NativeSourceSTCTracker {
    private static final String TAG = "NativeSourceSTCTracker";
    private static final String LIB_NAME = "wfdsinkapp";


    /**
     * Converts the given nanoseconds value to 90 KHz clock ticks count.
     * @param _timeNs The value of timestamp in nanoseconds to be converted to 90 KHz clock ticks
     * @return {@code long} - How many 90 Khz clock ticks does {@code _timeNs} represent.
     */
    public static long to90KhzClockTicks(long _timeNs) {
        return (_timeNs * 9 + 50_000) / 100_000;   // exact, rounds to nearest
    }

    /**
     * Converts the given 90 KHz clock ticks value into nanoseconds time.
     * @param _90khzticks The number of 90 Khz clock ticks to be converted to nanoseconds.
     * @return {@code long} - How many nanoseconds time does {@code _90khzticks} 90 KHz clock ticks represent.
     */
    public static long toNanoSeconds(long _90khzticks) {
        return (_90khzticks * 100_000 + 4) / 9;    // exact, rounds to nearest
    }

    /**
     * Retrieves the current estimated value of source clock STC.
     * @return long: Estimated at-the-moment nanoseconds time value of the source STC.
     */
    public static native long getSourceSTC();

    /**
     * Initializes the GstClock for accurate Source STC <-> local monotic clock timestamp mapping.
     *
     * When initialization fails, this function will terminate the current process.
     */
    public static native void init();

    /**
     * Feeds the internal clock un-wrapped absolute nanoseconds raw PCR values for calibration
     * @param _pcr raw extracted PCR value from the adaptation field's payload (final unwrapped value).
     * @return true on success, false on failure
     */
    public static native boolean feedPCR(long _pcr);

    /**
     * Estimates the source STC clock value when the local monotonic clock reaches {@code _monotonic}
     * @param _monotonic Current local monotonic clock (the same clock as {@code System.nanoTime()}) value/timestamp to be converted to source STC value/timestamp.
     * @return long: Estimated value/timestamp (in nanoseconds) of source STC when the current local monotonic clock reaches timestamp {@code _monotonic}
     */
    public static native long monotonicToSourceSTC(long _monotonic);


    /**
     * Tells you the timestamp at which this system's local monotonic clock (the same clock as {@code System.nanoTime()}) will be when the source STC clock value/timestamp becomes {@code _sstc}
     * @param _sstc Raw Source STC clock value/timestamp (in nanoseconds) to be converted into local monotonic clock value/timestamp (You must account for wraparounds here)
     * @return The local system's monotonic clock timestamp at which the source STC clock's value will be equal to {@code _sstc}
     */
    public static native long sourceSTCToMonotonic(long _sstc);





    static {
        System.loadLibrary(LIB_NAME);
    }
}
