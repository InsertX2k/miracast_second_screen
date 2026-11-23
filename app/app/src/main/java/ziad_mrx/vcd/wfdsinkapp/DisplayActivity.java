package ziad_mrx.vcd.wfdsinkapp;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.window.OnBackInvokedCallback;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.List;

public class DisplayActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private final String TAG = "DisplayActivity";
    private final String CONFIRM_EXIT_DIALOG_TAG = "CONFIRM_EXIT_DIALOG_DISPLAYACTIVITY";

    private MpegTsPayloadDecoder mDecoderEngine;

    // construct a new exit confirmation dialog fragment
    private ConfirmExitDialogFragment exitDialogFragment;

    private NetworkPacketReceiver mNetPacketReceiver;
    WifiManager wm;
    WifiManager.WifiLock wifiLock;

    private AudioPayloadHandler mAPH;

    private UibcHandler mUIBCHandler;

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure immersive full screen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // hide statusbar and nav bar
        WindowInsetsControllerCompat insetControllerCompat = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetControllerCompat.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
        insetControllerCompat.hide(WindowInsetsCompat.Type.systemBars()); // call to hide window insets

        // construct exit dialog fragment
        exitDialogFragment = new ConfirmExitDialogFragment(this,getWindow(),SharedObjectRegistry.sinkManagerRef,this);


        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        setContentView(surfaceView);
        // start the thread upon content view.
        mNetPacketReceiver = new NetworkPacketReceiver();
        mNetPacketReceiver.start();

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                Log.d(TAG, "Back invoked, displaying confirm exit alert dialog...");
                exitDialogFragment.showNow(getSupportFragmentManager(), CONFIRM_EXIT_DIALOG_TAG);
            }
        });

        // let's try to acquire Wifi lock
        wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wfdsink-lock");
        wifiLock.acquire();
        Log.i(TAG, "Acquired Wifi Lock!");
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        // Generate Audio Session ID
        AudioManager am = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        boolean supportsLowLatency = getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY);
        int lowLatencyBufSizeInBytes = Integer.parseInt(((AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE)).getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)) * SharedObjectRegistry.AUDIO_SAMPLE_SIZE_IN_BYTES;
        SharedObjectRegistry.AUDIO_TRACK_SESSION_ID = am.generateAudioSessionId();
        Log.i(TAG, "Audio Track Session ID: " + (SharedObjectRegistry.AUDIO_TRACK_SESSION_ID == AudioManager.ERROR ? "Failed to retrieve" : SharedObjectRegistry.AUDIO_TRACK_SESSION_ID));
        mAPH = new AudioPayloadHandler(supportsLowLatency, lowLatencyBufSizeInBytes);
        // Feed live hardware Surface back into TS extraction pipeline
        mDecoderEngine = new MpegTsPayloadDecoder(holder.getSurface(), mAPH, this);
        mDecoderEngine.start();
        mAPH.start(); // start Audio Payload Handler thread!
        // start source STC tracker!
        NativeSourceSTCTracker.init();
        // initialize the uibc handler thread
        mUIBCHandler = new UibcHandler();
        mUIBCHandler.start();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        // Redraw ignored; resolution is hard-mapped by MediaFormat inside the decoder logic
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        doCleanup();
    }

    private void doCleanup() {
        if (mDecoderEngine != null) {
            mDecoderEngine.interrupt();
        }
        if (mNetPacketReceiver != null) {
            mNetPacketReceiver.interrupt();
        }
        // release the wifi lock
        if (wifiLock != null) {
            wifiLock.release();
        }

        if (mUIBCHandler != null) {
            mUIBCHandler.interrupt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        doCleanup();
    }


    // Function to get the screen dimensions
    private int[] getScreenDimensions() {
        int[] dims = new int[2];
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above

            // Get the window metrics
            WindowMetrics windowMetrics = getWindowManager().getCurrentWindowMetrics();

            // Get the insets and bounds of the window
            WindowInsets insets = windowMetrics.getWindowInsets();
            int insetLeft = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).left;
            int insetRight = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).right;
            int insetTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).top;
            int insetBottom = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).bottom;

            // Calculate the width and height of the screen
            int width = windowMetrics.getBounds().width() - insetLeft - insetRight;
            int height = windowMetrics.getBounds().height() - insetTop - insetBottom;

            // Return the width and height
            dims[0] = width;
            dims[1] = height;
            return dims;
        } else {
            // For Android 10 and below

            // Get the display metrics
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

            // Return the width and height
            dims[0] = displayMetrics.widthPixels;
            dims[1] = displayMetrics.heightPixels;
            return dims;
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        try {
            int ptrIdx = event.getPointerId(event.getActionIndex());
            int screenWidth, screenHeight;
            Log.i(TAG, "Action " + event.getActionMasked() + " point X: " + event.getRawX(ptrIdx) + ", Y: " + event.getRawY(ptrIdx) + " at current display.");
            // we need to normalize it
            double video_X_pos;
            double video_Y_pos;
            int normalized_X_pos;
            int normalized_Y_pos;
            synchronized (SharedObjectRegistry.VID_DIMENS_ARRAY_LOCK) {
                DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();

//                screenWidth = getScreenDimensions()[0];
//                screenHeight = getScreenDimensions()[1];
                screenWidth = metrics.widthPixels;
                screenHeight = metrics.heightPixels;
                Log.i(TAG, "Reported display resolution: " + screenWidth + "x" + screenHeight);
                double width_scale = (double) screenWidth / (double) SharedObjectRegistry.visible_video_dimens[0];
                double height_scale = (double) screenHeight / (double) SharedObjectRegistry.visible_video_dimens[1];
                video_X_pos = (int) (event.getRawX(ptrIdx) / width_scale);
                video_Y_pos = (int) (event.getRawY(ptrIdx) / height_scale);
                normalized_X_pos = Math.toIntExact(Math.round((double) video_X_pos / (double) SharedObjectRegistry.visible_video_dimens[0] * (double) 4095));
                normalized_Y_pos = Math.toIntExact(Math.round((double) video_Y_pos / (double) SharedObjectRegistry.visible_video_dimens[1] * (double) 4095));
                Log.i(TAG, "Normalized X touch Pos: " + normalized_X_pos + ", Y Pos: " + normalized_Y_pos);
                // send touch down and touch up at the same time
            }
            Log.i(TAG, "Touched point X: " + video_X_pos + ", Y: " + video_Y_pos + " at source video!");

            mUIBCHandler.addToQueue(UibcHandler.frameHidc((int)0x00, UibcHandler.touchReport(!(event.getActionMasked() == MotionEvent.ACTION_UP), normalized_X_pos, normalized_Y_pos, (int) ((SystemClock.elapsedRealtimeNanos() / 100_000) & 0xFFFF))));
//            mUIBCHandler.addToQueue(UibcHandler.frameHidc((int)0x00, UibcHandler.touchReport(false, normalized_X_pos, normalized_Y_pos, (int) ((SystemClock.elapsedRealtimeNanos() / 100_000) & 0xFFFF))));


            return super.onTouchEvent(event);
        } catch (Throwable t) {
            Log.e(TAG, "Error at onTouchEvent!: " + t.getMessage());
            return false;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged(): " + ((newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) ? "Orientation changed to landscape" : "Orientation changed to portrait"));
        super.onConfigurationChanged(newConfig);
    }
}