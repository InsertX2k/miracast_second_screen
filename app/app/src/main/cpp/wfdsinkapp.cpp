#include <jni.h>
#include <gst/gstclock.h>
#include <gst/gst.h>
#include <android/log.h>

// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("wfdsinkapp");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("wfdsinkapp")
//      }
//    }

// initialize gstclock
static GstClock* pcr_clock = nullptr;
GError* last_gerror = nullptr;
bool initialized = false; // keep track of initial calibration

static const char ANDROID_LOGGER_TAG[] = "NativeSourceSTCTracker";


extern "C"
JNIEXPORT jlong JNICALL
Java_ziad_1mrx_vcd_wfdsinkapp_NativeSourceSTCTracker_getSourceSTC(JNIEnv *env, jclass clazz) {
    return gst_clock_get_time(pcr_clock);
}


extern "C"
JNIEXPORT jboolean JNICALL
Java_ziad_1mrx_vcd_wfdsinkapp_NativeSourceSTCTracker_feedPCR(JNIEnv *env, jclass clazz,
                                                             jlong _pcr) {
    // we will call gst_clock_add_observation in all cases.
    GstClockTime _internal = gst_clock_get_internal_time(pcr_clock);
    auto _external = (GstClockTime)_pcr;
    gdouble *rsquared = nullptr;
    gst_clock_add_observation(pcr_clock, _internal, _external, rsquared);
    if (!initialized) {
        // feed it the initial PCR in unwrapped ns
        gst_clock_set_calibration(pcr_clock, _internal, _external, 1, 1);
        // set to initialized
        initialized = true;
        return true;
    }
    // if it's not the initial PCR
    return true;
}


extern "C"
JNIEXPORT jlong JNICALL
Java_ziad_1mrx_vcd_wfdsinkapp_NativeSourceSTCTracker_monotonicToSourceSTC(JNIEnv *env, jclass clazz,
                                                                          jlong _monotonic) {
    GstClockTime cinternal, cexternal, cnum, cdenom;
    gst_clock_get_calibration(pcr_clock, &cinternal, &cexternal, &cnum, &cdenom);
    return (jlong) gst_clock_adjust_with_calibration(
        NULL,
        (GstClockTime)_monotonic,
        cinternal,
        cexternal,
        cnum,
        cdenom
    );
}


extern "C"
JNIEXPORT jlong JNICALL
Java_ziad_1mrx_vcd_wfdsinkapp_NativeSourceSTCTracker_sourceSTCToMonotonic(JNIEnv *env, jclass clazz,
                                                                          jlong _sstc) {
    GstClockTime cinternal, cexternal, cnum, cdenom;
    gst_clock_get_calibration(pcr_clock, &cinternal, &cexternal, &cnum, &cdenom);
    if (_sstc < cexternal) {
        __android_log_print(ANDROID_LOG_WARN, ANDROID_LOGGER_TAG, "WARNING: Received a source STC value that's less than external calibration anchor, defaulting to now!!!!");
        return (jlong) gst_clock_get_internal_time(pcr_clock);
    }
    return (jlong) gst_clock_unadjust_with_calibration(
            NULL,
            (GstClockTime)_sstc,
            cinternal,
            cexternal,
            cnum,
            cdenom
    );
}
extern "C"
JNIEXPORT void JNICALL
Java_ziad_1mrx_vcd_wfdsinkapp_NativeSourceSTCTracker_init(JNIEnv *env, jclass clazz) {
    if (!gst_init_check(nullptr, nullptr, &last_gerror)) {
        // there happened to be an init failure
        __android_log_print(ANDROID_LOG_ERROR, ANDROID_LOGGER_TAG,
            "gst_init failed!: %s, code: %d", (last_gerror ? last_gerror->message : "Unknown message!"), (last_gerror ? last_gerror->code : -1)
        );
        if (last_gerror) g_error_free(last_gerror);
        exit(255); // exit code 255 is for a failure in gstclock init!
        return;
    }
    pcr_clock = GST_CLOCK( g_object_new(
            GST_TYPE_SYSTEM_CLOCK,
            "clock-type", GST_CLOCK_TYPE_MONOTONIC,
            NULL
    ));
    if (!pcr_clock) {
        __android_log_print(ANDROID_LOG_ERROR, ANDROID_LOGGER_TAG, "Failed to obtain a GstSystemClock!");
        exit(200); // exit code 200 is for a failure in obtaining a GstSystemClock
        return;
    }
    gst_object_ref_sink(pcr_clock);
}