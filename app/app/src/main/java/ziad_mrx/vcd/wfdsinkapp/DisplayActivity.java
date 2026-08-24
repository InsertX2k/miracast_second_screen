package ziad_mrx.vcd.wfdsinkapp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class DisplayActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private final String TAG = "DisplayActivity";
    private final String CONFIRM_EXIT_DIALOG_TAG = "CONFIRM_EXIT_DIALOG_DISPLAYACTIVITY";

    private MpegTsPayloadDecoder mDecoderEngine;

    // construct a new exit confirmation dialog fragment
    private ConfirmExitDialogFragment exitDialogFragment;

    private NetworkPacketReceiver mNetPacketReceiver;
    WifiManager wm;
    WifiManager.WifiLock wifiLock;

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
        // Feed live hardware Surface back into TS extraction pipeline
        mDecoderEngine = new MpegTsPayloadDecoder(holder.getSurface());
        mDecoderEngine.start();
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        doCleanup();
    }
}