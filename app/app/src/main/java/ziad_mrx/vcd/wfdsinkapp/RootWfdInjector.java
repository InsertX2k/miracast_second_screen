package ziad_mrx.vcd.wfdsinkapp;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

public class RootWfdInjector {
    private static final int DEVICE_TYPE_PRIMARY_SINK = 1;
    private static final int WFD_PORT = 7236;

    public static void main(String[] args) {
        try {
            System.out.println("Root Dalvik Process Started.");

            // 1. Bypass Hidden APIs for this root VM instance
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntimeMethod = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setHiddenApiExemptionsMethod = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});
            setHiddenApiExemptionsMethod.invoke(vmRuntime, new Object[]{new String[]{"L"}});

            System.out.println("Hidden APIs unlocked in root process.");

            // 2. Setup a Main Looper. WifiP2pManager requires a Looper to process AsyncChannel messages.
            Looper.prepareMainLooper();

            // 3. Obtain a legitimate System Context securely via ActivityThread (UID 0 privileges)
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method systemMainMethod = activityThreadClass.getMethod("systemMain");
            Object activityThread = systemMainMethod.invoke(null);
            Method getSystemContextMethod = activityThreadClass.getMethod("getSystemContext");
            Context context = (Context) getSystemContextMethod.invoke(activityThread);

            System.out.println("Obtained System Context. Initializing P2P Framework...");

            // 4. Initialize standard WifiP2pManager natively as Root
            WifiP2pManager manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (manager == null) {
                throw new RuntimeException("Could not obtain WIFI_P2P_SERVICE");
            }
            WifiP2pManager.Channel channel = manager.initialize(context, Looper.myLooper(), null);

            // 5. Build the exact WFD Info payload via reflection
            Class<?> wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo");
            Object wfdInfo = wfdInfoClass.getDeclaredConstructor().newInstance();
            wfdInfoClass.getMethod("setEnabled", boolean.class).invoke(wfdInfo, true);
            wfdInfoClass.getMethod("setDeviceType", int.class).invoke(wfdInfo, DEVICE_TYPE_PRIMARY_SINK);
            wfdInfoClass.getMethod("setSessionAvailable", boolean.class).invoke(wfdInfo, true);
            wfdInfoClass.getMethod("setControlPort", int.class).invoke(wfdInfo, WFD_PORT);
            wfdInfoClass.getMethod("setMaxThroughput", int.class).invoke(wfdInfo, 150);

            // 6. Invoke WifiP2pManager.setWfdInfo() natively.
            // Because this executes as root, mService.checkConfigureWifiDisplayPermission() will pass naturally!
            Method setWfdInfoMethod = WifiP2pManager.class.getMethod(
                    "setWfdInfo",
                    WifiP2pManager.Channel.class,
                    wfdInfoClass,
                    WifiP2pManager.ActionListener.class
            );

            System.out.println("Dispatching setWfdInfo to System Server...");

            setWfdInfoMethod.invoke(manager, channel, wfdInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    System.out.println("SUCCESS: WFD Info successfully pushed to System Server!");
                    try {
                        Method setChannelsMethod = WifiP2pManager.class.getMethod(
                                "setWifiP2pChannels",
                                WifiP2pManager.Channel.class,
                                int.class, int.class,
                                WifiP2pManager.ActionListener.class);

                        setChannelsMethod.invoke(manager, channel, 6, 6, new WifiP2pManager.ActionListener() {
                            @Override
                            public void onSuccess() {
                                try {
                                    Method setDeviceNameMethod = WifiP2pManager.class.getMethod(
                                            "setDeviceName",
                                            WifiP2pManager.Channel.class,
                                            String.class,
                                            WifiP2pManager.ActionListener.class
                                    );
                                    setDeviceNameMethod.setAccessible(true);
                                    setDeviceNameMethod.invoke(
                                            manager, channel, SharedObjectRegistry.SINK_P2P_NAME, new WifiP2pManager.ActionListener() {
                                                final String TAG = "RootWfdInjector-SetDeviceName";

                                                @Override
                                                public void onFailure(int reason) {
                                                    System.err.println("Failed to set device name!");
                                                    System.exit(1);
                                                }

                                                @Override
                                                public void onSuccess() {
                                                    System.out.println("Successfully set device name!");
                                                    System.exit(0);
                                                }
                                            }
                                    );
                                } catch (Throwable t) {
                                    System.err.println("Failed to set Wifi P2P Device name: " + t.getMessage());
                                }
                                System.out.println("SUCCESS: P2P locked to channel 6 (2.4GHz).");
                            }
                            @Override
                            public void onFailure(int reason) {
                                System.err.println("Channel lock failed, reason=" + reason);
                            }
                        });
                    } catch (Exception e) {
                        String stString = Log.getStackTraceString(e);
                        Log.e("RootWfdInjector:", "Failed to lock channel to 2.4 GHz: " + stString);
                        System.exit(1);
                    }
                }

                @Override
                public void onFailure(int reason) {
                    System.err.println("ERROR: WFD Info failed. Framework rejected with reason code: " + reason);
                    System.exit(1); // Exit with error
                }
            });

            // invoke setDeviceName to change P2P name of the device

            // 7. Start the message loop to process the asynchronous IPC callbacks
            Looper.loop();

        } catch (Exception e) {
            System.err.println("Root Injection Exception: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}