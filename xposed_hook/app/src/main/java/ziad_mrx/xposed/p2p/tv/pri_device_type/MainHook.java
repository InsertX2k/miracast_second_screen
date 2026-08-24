package ziad_mrx.xposed.p2p.tv.pri_device_type;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.res.Resources;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class MainHook implements IXposedHookLoadPackage {
    public static final String TAG = "MainHook @ p2p.tv.pri_device_type: ";
    private Context mContext;
    Integer targetResId = null;
    Integer nameResId = null;

    private boolean hooksApplied = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (
                (!lpparam.packageName.equalsIgnoreCase("android")) &&
                (!lpparam.packageName.equalsIgnoreCase("com.android.wifi.resources"))
        ) {
            return; // we aren't in target package.
        }
        XposedBridge.log(TAG + "Injected into a valid package!");

        try {
            XposedHelpers.findAndHookMethod("com.android.server.SystemServiceManager", lpparam.classLoader, "startServiceFromJar", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // param.args[0] is a String object holding current service name
                    // param.result is the service instance, from which we will get the
                    // actual classloader object we need to use.
                    String svc_name = (String) param.args[0];
                    if (svc_name.toLowerCase().contains("wifi.p2p")) {
                        XposedBridge.log(TAG + "Found service:" + svc_name + "!");
                        if (!hooksApplied) {
                            // if hooks aren't applied only
                            // we will retrieve the class loader object from the return value
                            // of this function
                            Object svc_instance = param.getResult();
                            if (svc_instance != null) {
                                // we will get its class loader and use it with applyhooks
                                applyHooks(svc_instance.getClass().getClassLoader());
                                XposedBridge.log(TAG + "Successfully applied hooks!");
                            } else {
                                XposedBridge.log(TAG + "Service instance is NULL!");
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            XposedBridge.log(TAG + "Error: failed to hook into startServiceFromJar(): " + e.getMessage());
        }


    }


    @SuppressLint("PrivateApi")
    private void applyHooks(ClassLoader cloader) {
        try {
            Class<?> p2pNativeClazz = XposedHelpers.findClassIfExists("com.android.server.wifi.p2p.WifiP2pNative", cloader);
            if (p2pNativeClazz == null) {
                XposedBridge.log(TAG + "Failed to find WifiP2pNative class!!!");
                return;
            }
            for (Method m : p2pNativeClazz.getDeclaredMethods()) {
                // setP2pDeviceType(String type) hook
                if (m.getName().toLowerCase().contains("setp2pdevicetype")) {
                    XposedBridge.hookMethod(m,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (isMiracastAppRunning()) {
                                        XposedBridge.log(TAG + "Miracast app is running!, changing P2P Device type to report Projector...");
                                        param.args[0] = "7-0050F204-1"; // TV
                                    }
                                }
                            }
                    );
                }
                // setDeviceName(String name) hook
                if (m.getName().toLowerCase().contains("setdevicename")) {
                    XposedBridge.hookMethod(m,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (isMiracastAppRunning()) {
                                        XposedBridge.log(TAG + "Miracast app is running!, changing reported device name...");
                                        param.args[0] = "SECOND_SCREEN_APP";
                                    }
                                }
                            }
                    );
                }
            }
            hooksApplied = true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook into WifiP2pServiceImpl's initializeP2pSettings(): " + t.getMessage());
        }
    }

    private boolean isMiracastAppRunning() {
        ActivityManager am = (ActivityManager) AndroidAppHelper.currentApplication().getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            XposedBridge.log(TAG + "isMiracastAppRunning(): ActivityService returned null!");
            return false;
        }

        List<ActivityManager.RunningAppProcessInfo> appProcessesInfos = am.getRunningAppProcesses();
        if (appProcessesInfos == null) {
            XposedBridge.log(TAG + "isMiracastAppRunning(): appProcessesInfos is null!!!");
            return false;
        }

        for (ActivityManager.RunningAppProcessInfo appProcessInfo : appProcessesInfos) {
            if (appProcessInfo.processName.equals("ziad_mrx.vcd.wfdsinkapp")) {
                XposedBridge.log(TAG + "Miracast sink app found to be running!");
                return true; // whether or not it is foreground.
            }
        }
        XposedBridge.log(TAG + "Miracast sink app not running!");
        return false; // at this point the app can't be running.
    }
}
