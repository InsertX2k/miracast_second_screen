package ziad_mrx.vcd.wfdsinkapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;
import android.net.wifi.p2p.WifiP2pDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import java.util.ArrayList;
import java.util.Collection;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

public class MiracastSinkManager {
    private static final String TAG = "MiracastSinkManager";

    private final WifiP2pManager mWifiP2pManager;
    private final WifiP2pManager.Channel mChannel;
    private final Context mContext;
    private final String scriptPath = "/system/bin/wfd_dhcp_setup_script.sh";

    private String dhcpServerJobPID = "";

    public MiracastSinkManager(Context context) {
        mContext = context;
        bypassHiddenApiRestrictions();

        mWifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mWifiP2pManager.initialize(context, context.getMainLooper(), null);
    }

    private void bypassHiddenApiRestrictions() {
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntimeMethod = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setHiddenApiExemptionsMethod = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});
            setHiddenApiExemptionsMethod.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            Log.i(TAG, "Successfully bypassed Android 15 Hidden API restrictions.");
        } catch (Exception e) {
            Log.w(TAG, "Failed to bypass hidden API restrictions.", e);
        }
    }

    public void startAdvertising() {
        new Thread(() -> {
            Log.i(TAG, "Attempting Root Binder Injection for WFD Info...");

            // Get the exact path to this app's APK inside the Magisk module (/system/priv-app/...)
            String apkPath = mContext.getPackageCodePath();
            String className = RootWfdInjector.class.getName();

            // Construct the command to execute our Java class natively as root
            String cmd = "CLASSPATH=" + apkPath + " app_process /system/bin " + className;

            try {
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "[RootInjector] " + line);
                }
                while ((line = errorReader.readLine()) != null) {
                    Log.e(TAG, "[RootInjector ERROR] " + line);
                }

                process.waitFor();
                Log.i(TAG, "Root injection process finished. Forcing P2P discovery...");

                // Once the system server has the root payload, trigger standard P2P discovery
                discoverPeers();
                // we must start ensuring a p2p dhcp server
                Log.i(TAG, "Starting p2p DHCP server...");
                ensureP2pDhcpServer();

            } catch (Exception e) {
                Log.e(TAG, "Failed to execute root injection", e);
            }
        }).start();
    }

    @SuppressLint("MissingPermission")
    private void discoverPeers() {
        mWifiP2pManager.createGroup(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failed to create P2P Group!, can't start broadcasting WFD IE beacon!");
                return;
            }

            @Override
            public void onSuccess() {
                Log.i(TAG, "Successfully created P2P Group!");
//                mWifiP2pManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
//                    @Override
//                    public void onSuccess() {
//                        Log.i(TAG, "P2P Discovery initiated. Broadcasting WFD IE beacon...");
//                    }
//
//                    @Override
//                    public void onFailure(int reason) {
//                        Log.e(TAG, "Failed to start P2P Discovery. Reason: " + reason);
//                    }
//                });
            }
        });
    }

    // should teardown created p2p group + stop broadcasting self.
    public void stopBroadcasting() {
        Log.i(TAG, "Stop broadcasting...");
        mWifiP2pManager.removeGroup(mChannel, new WifiP2pManager.ActionListener() {

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failed to tear down group!!!, PLEASE REBOOT YOUR DEVICE AS SOON AS POSSIBLE!");
            }

            @Override
            public void onSuccess() {
                Log.i(TAG, "Successfully torn down group!");
            }
        });
    };



    public void ensureP2pDhcpServer() {
        new Thread(() -> {
            try {
                // setsid sh -c 'sh /data/local/tmp/wfd_dhcp_setup_script.sh < /dev/null > /data/local/tmp/wfd_dhcp_setup.log 2>&1 & echo $!'
                String cmd = "setsid sh -c 'sh " + scriptPath + " < /dev/null > /data/local/tmp/wfd_dhcp_setup.log 2>&1 & echo $!'";
                Process launcher = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                launcher.waitFor(); // wait for the process to finish to get a valid pid
                BufferedReader r = new BufferedReader(new InputStreamReader(launcher.getInputStream()));
                String rl = r.readLine();
                if (rl.isEmpty()) rl = r.readLine(); // try again
                Log.i(TAG, "Caught PID for P2P DHCP Server Script!: " + rl);
                dhcpServerJobPID = rl;
                Log.i(TAG, "Detached DHCP setup script launched.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch DHCP setup script", e);
            }
        }).start();
    }

    public String getDhcpServerJobPID() {
        return dhcpServerJobPID;
    }

    public void stopP2pDhcpServer() {
        Log.i(TAG, "Stopping P2P DHCP Server...");
        final String LOG_P2P_DHCP_SERVER_STOP_FAILED = "Failed to stop P2P DHCP Server, PLEASE REBOOT YOUR DEVICE: ";
        try {
            // try to do su -c kill -9 dhcpServerJobPID and check error level if it was 0 or not.
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "kill -9 " + dhcpServerJobPID + " >/dev/null 2>/dev/null;echo $?"});
            p.waitFor(); // wait until process fully finishes.
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
            int errorLevel = Integer.parseInt(out.readLine());
            if (errorLevel != 0) {
                Log.e(TAG, LOG_P2P_DHCP_SERVER_STOP_FAILED);
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, LOG_P2P_DHCP_SERVER_STOP_FAILED + t.getMessage());
            return;
        }
        Log.i(TAG, "Successfully stopped DHCP Server!");
    }

}