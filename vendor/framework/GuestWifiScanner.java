package io.a10vm.guest;

import android.net.wifi.ScanResult;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * Host-backed endpoint for Android 15's {@code wifiscanner} ({@code IWifiScanner}).
 *
 * <p>Why it exists: A15's {@code WifiPickerTracker} does not build its list from
 * {@code WifiManager.getScanResults()} — it registers a listener with the
 * WifiScanner service and expects {@code onFullResults}/{@code onResults}
 * callbacks. In this container the image's WifiScanningService has no radio and
 * no HAL, so it answers nothing:
 *
 * <pre>
 *   WifiPickerTracker: Issuing scan request from WifiScanner
 *   WifiScanner: listener does not exist
 *   WifiPickerTracker: Failed to scan! Reason: -1,
 *   WifiScanner: Failed to retrieve wifiscanner      (WifiScanRequestProxy, from WifiService)
 * </pre>
 * The result is what the maintainer sees: the WLAN page shows only the saved
 * network from our IWifiManager relay, with {@code [Level:0][Connecting...]} and
 * no other APs, and no status-bar Wi-Fi icon.
 * (real device 2026-10-31 18:1x, instance 1793441400580,
 *  application-16758-10.log: every line is
 *  {@code Entry 1/1: [StandardWifiEntry][CMCC-FKAz-5G][Connected][Level:0][Connecting...]})
 *
 * <p>This endpoint publishes that same host snapshot as a scan: on
 * {@code startScan} it pushes the host's AP list to the caller's
 * {@code IWifiScannerListener} and then reports success. Every failure is logged
 * as {@code a15_wifiscanner_*} and swallowed — a throw here would cross binder
 * and crash the caller (Settings), which is exactly the bug the wifi relay's
 * default branch was fixed for.
 */
final class GuestWifiScanner implements InvocationHandler {
    private static final java.util.Set<String> unhandled = new java.util.HashSet<>();

    static void register() throws Exception {
        LocalGuestService.register("wifiscanner", "android.net.wifi.IWifiScanner",
                new GuestWifiScanner());
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) {
        try {
            switch (method.getName()) {
                // The two calls WifiPickerTracker/WifiScanRequestProxy make.
                case "startScan": case "startBackgroundScan": case "startPnoScan": {
                    if (args != null && args.length > 0) deliver(args[0]);
                    return null;
                }
                case "stopScan": case "stopBackgroundScan": case "stopPnoScan":
                case "unregisterScanListener": case "registerScanListener":
                case "enableVerboseLogging": case "getCachedScanData":
                    return null;
                case "getScanResults": return Boolean.TRUE;
                case "setScanningEnabled": return Boolean.TRUE;
                case "isScanning": return Boolean.FALSE;
                case "getSingleScanResults": return hostScans();
                case "getAvailableChannels": return null;
                default:
                    if (unhandled.add(method.getName()))
                        System.out.println("a15_wifiscanner_unhandled=" + method.getName());
                    return defaultValue(method.getReturnType());
            }
        } catch (Throwable failure) {
            System.out.println("a15_wifiscanner_failed=" + method.getName() + ":" + failure);
            return defaultValue(method.getReturnType());
        }
    }

    /** Pushes the host AP list to one {@code IWifiScannerListener}. */
    private static void deliver(Object listener) {
        if (listener == null) return;
        try {
            List<ScanResult> scans = hostScans();
            System.out.println("a15_wifiscanner_deliver=" + scans.size());
            // A15's WifiPickerTracker implements WifiScanner.ScanListener.onResults(
            // ScanData[]): onFullResults only feeds onFullResult (used for RTT/roaming
            // hints), so the list stayed empty. The bucket form is what fills entries.
            Object data = bucket(scans);
            if (data != null) {
                LocalGuestService.call(listener, "android.net.wifi.IWifiScannerListener",
                        "onResults", (Object) resultsArray(data));
                System.out.println("a15_wifiscanner_onResults=1");
            } else {
                System.out.println("a15_wifiscanner_onResults=0");
            }
            LocalGuestService.call(listener, "android.net.wifi.IWifiScannerListener",
                    "onSingleScanCompleted");
            LocalGuestService.call(listener, "android.net.wifi.IWifiScannerListener",
                    "onSuccess");
        } catch (Throwable failure) {
            System.out.println("a15_wifiscanner_deliver_failed=" + failure);
        }
    }

    private static Object resultsArray(Object data) {
        Object results = Array.newInstance(data.getClass(), 1);
        Array.set(results, 0, data);
        return results;
    }

    /** Builds the public Android 15 ScanData(int id, int flags, ScanResult[]). */
    private static Object bucket(List<ScanResult> scans) {
        try {
            Class<?> type = Class.forName("android.net.wifi.WifiScanner$ScanData");
            Constructor<?> constructor = type.getConstructor(
                    int.class, int.class, ScanResult[].class);
            Object data = constructor.newInstance(0, 0, scans.toArray(new ScanResult[0]));
            System.out.println("a15_wifiscanner_bucket=array");
            return data;
        } catch (Throwable failure) {
            System.out.println("a15_wifiscanner_bucket_failed=" + failure);
            return null;
        }
    }

    private static List<ScanResult> hostScans() {
        try {
            JSONObject snapshot = GuestConnectivity.snapshot();
            JSONObject wifi = snapshot.optJSONObject("wifi");
            return GuestWifi.scans(wifi == null ? new JSONObject() : wifi);
        } catch (Throwable failure) {
            System.out.println("a15_wifiscanner_scans_failed=" + failure);
            return Collections.emptyList();
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == null || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == String.class) return null;
        if (java.util.List.class.isAssignableFrom(type)) return new java.util.ArrayList<>();
        return null;
    }
}
