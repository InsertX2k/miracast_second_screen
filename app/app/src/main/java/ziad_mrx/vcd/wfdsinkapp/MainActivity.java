package ziad_mrx.vcd.wfdsinkapp;

import android.app.ComponentCaller;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.location.LocationManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.sql.Time;
import java.util.Collection;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private final String CONFIRM_EXIT_DIALOG_TAG = "CONFIRM_EXIT_DIALOG_MAINACTIVITY";
    private MiracastSinkManager mSinkManager;
    private RtspServerEngine mRtspEngine;
    private TextView mStatusText;
    private boolean mIsServerBooting = false;
    private ConfirmExitDialogFragment mConfirmExitDialogFragment;

    private LocationManager mLm;
    private Intent mOpenSettingsPagesIntent = new Intent();
    private boolean canStartSink = false;
    private boolean isSinkRunning = false;

    private final BroadcastReceiver mP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null && networkInfo.isConnected()) {
                    WifiP2pInfo p2pInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    if (p2pInfo != null && p2pInfo.groupFormed) {
                        Log.i(TAG, "P2P Group Formed!");
                        Log.i(TAG, "P2P GO address : " + p2pInfo.groupOwnerAddress.getHostAddress() + ", " + (p2pInfo.isGroupOwner? "We are Group owner!" : "We are not group owner!"));
                        synchronized(SharedObjectRegistry.SHARED_OBJ_LOCK) {
                            if (SharedObjectRegistry.sink_ip_addr.isEmpty()) {
                                SharedObjectRegistry.sink_ip_addr = p2pInfo.groupOwnerAddress.getHostAddress();
                            }
                        }
                    }
                }
            }
        }
    };



    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mStatusText = new TextView(this);
        mStatusText.setText("Waiting for Miracast Source connection...");
        mStatusText.setTextSize(24f);
        mStatusText.setGravity(android.view.Gravity.CENTER);
        setContentView(mStatusText);

        mSinkManager = new MiracastSinkManager(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        registerReceiver(mP2pReceiver, filter);
        // before going to the DisplayActivity, store our instance of MiracastSinkManager in SharedObjectRegistry.
        SharedObjectRegistry.sinkManagerRef = mSinkManager;
        try {
            mRtspEngine = new RtspServerEngine(InetAddress.getByName("0.0.0.0"), ()-> {
                Intent intent = new Intent(MainActivity.this, DisplayActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }, mSinkManager);
            mRtspEngine.start();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate: Failed to start RtspServerEngine on 0.0.0.0:", t);
        }

        mConfirmExitDialogFragment = new ConfirmExitDialogFragment(this, getWindow(), mSinkManager, this);

        // register onBackInvokedCallBack
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                Log.d(TAG, "Back button invoked, displaying confirm exit dialog...");
                mConfirmExitDialogFragment.showNow(getSupportFragmentManager(), CONFIRM_EXIT_DIALOG_TAG);
            }
        });

        // Check if WiFi and location are enabled at startup.
        mLm = (LocationManager) getSystemService(LocationManager.class);
        // and optionally start such activities if they're not enabled
        try {
            promptToEnableRadiosIfNecessary();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate(): failed to check the status of necessary radios: " + t.getMessage());
        }

        // add hooks for when an app might be forceibly terminated.
//        Runtime.getRuntime().addShutdownHook(); // TODO - Request a shutdown hook thread!!!

        // starting sink only if canStartSink is true
        if (canStartSink) {
            startSink();
        }
    }

    private synchronized void startRtspServerOnP2pInterface(WifiP2pInfo info) {
        if (mIsServerBooting) return;
        mIsServerBooting = true;

        new Thread(() -> {
            InetAddress p2pAddress = null;
            try {
                // DHCP can take a second. Loop up to 5 times to ensure we get an IP.
                for (int i = 0; i < 5; i++) {
                    if (info.isGroupOwner) {
                        p2pAddress = info.groupOwnerAddress;
                    } else {
                        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces.hasMoreElements()) {
                            NetworkInterface iface = interfaces.nextElement();
                            if (iface.getName().startsWith("p2p")) {
                                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                                while (addresses.hasMoreElements()) {
                                    InetAddress addr = addresses.nextElement();
                                    // Make sure it's an IPv4 address, not an IPv6 local link
                                    if (addr instanceof java.net.Inet4Address) {
                                        p2pAddress = addr;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (p2pAddress != null && !p2pAddress.isAnyLocalAddress()) {
                        break; // We successfully found the IP!
                    }

                    Log.w(TAG, "IP not ready yet. Retrying in 1 second...");
                    Thread.sleep(1000);
                }

                if (p2pAddress != null) {
                    final String ipString = p2pAddress.getHostAddress();
                    runOnUiThread(() -> mStatusText.setText("P2P Connected!\nIP: " + ipString + "\nWaiting for RTSP..."));

                    mRtspEngine = new RtspServerEngine(p2pAddress, () -> {
                        Intent intent = new Intent(MainActivity.this, DisplayActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    }, mSinkManager);
                    mRtspEngine.start();
                } else {
                    Log.e(TAG, "Failed to extract P2P IP Address after 5 seconds!");
                    mIsServerBooting = false; // Reset lock
                }
            } catch (Exception e) {
                Log.e(TAG, "Error finding P2P Interface", e);
                mIsServerBooting = false; // Reset lock
            }
        }).start();
    }

    private void doCleanUp() {
        Log.i(TAG, "doCleanUp...");
        try {
            unregisterReceiver(mP2pReceiver);
            mSinkManager.stopBroadcasting();
            mSinkManager.stopP2pDhcpServer();
            // log
            Log.i(TAG, "Post-Activity-Destruction cleanup complete!");
            Toast.makeText(this, R.string.toast_cleanup_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to do post-activity-destruction cleanup!: " + e.getMessage() + "!, Please REBOOT YOUR DEVICE!");
            // we may also want to do this in a toast.
            Toast t = Toast.makeText(this, R.string.toast_failed_to_cleanup, Toast.LENGTH_LONG);
            t.show(); // show the toast to the user!
        }
    }

    private void startSink() {
        if (!isSinkRunning) {
            Log.i(TAG, "Sink is not running, starting sink...");
            mSinkManager.startAdvertising();
            mRtspEngine.startDialOutAttempts(); // keep dialing out indefinitely (until someone connects)
            isSinkRunning = true;
        }
    }


    private void promptToEnableRadiosIfNecessary() throws Exception {
        int wifiStatus = Settings.Global.getInt(getContentResolver(), Settings.Global.WIFI_ON);
        boolean locationStatus = mLm.isLocationEnabled();

        if (wifiStatus == 0) {
            Toast.makeText(this, R.string.wifi_disabled, Toast.LENGTH_LONG).show();
            mOpenSettingsPagesIntent.setAction(Settings.ACTION_WIFI_SETTINGS);
            startActivity(mOpenSettingsPagesIntent);
        }

        if (!locationStatus) {
            Toast.makeText(this, R.string.location_disabled, Toast.LENGTH_LONG).show();
            mOpenSettingsPagesIntent.setAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(mOpenSettingsPagesIntent);
        }

        if (
                (wifiStatus != 0) && (locationStatus)
        ) {
            canStartSink = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            promptToEnableRadiosIfNecessary();
        } catch (Throwable t) {
            Log.e(TAG, "onResume(): Failed to check enable status of necessary radios : " + t.getMessage());
        }
        if (canStartSink) {
            startSink();
        }
    }
}