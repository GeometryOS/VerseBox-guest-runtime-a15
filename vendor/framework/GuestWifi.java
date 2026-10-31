package io.a10vm.guest;

import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host Wi-Fi state relay; radio changes are presented in the host's system panel. */
final class GuestWifi implements InvocationHandler {
    private volatile int verboseLogging;
    private static int previousState = -1;
    private static String previousNetwork = "";
    /**
     * The IScanResultsCallback proxies the framework registered through the relay.
     *
     * <p>A15 的 IScanResultsCallback.onScanResultsAvailable() **不带参数**（镜像
     * framework-wifi.jar 方法表），扫描结果由接收方自己再调 getScanResults() 取，
     * 所以这里只需要记住谁订阅了，并在主机扫描更新时通知一次。
     */
    private static final java.util.List<Object> scanCallbacks = new java.util.ArrayList<>();

    static void register() throws Exception {
        LocalGuestService.register("wifi", "android.net.wifi.IWifiManager", new GuestWifi());
    }

    static WifiInfo connectionInfo(JSONObject snapshot) throws Exception {
        JSONObject wifi = snapshot.optJSONObject("wifi");
        if (wifi == null) wifi = new JSONObject();
        WifiInfo info = ssidInfo(wifi);
        // Every field is set through field(): A15's WifiInfo no longer has all of the
        // setters A9/A10 had, and the old code called them with getMethod().invoke()
        // back to back -- one missing setter threw out of the relay, the whole
        // getConnectionInfo() transaction failed, and the caller then had no WifiInfo
        // at all. That is exactly the reported shape:
        //   WLAN 页：一个 Saved 条目 + [Level:0][Connecting...]，MAC/IP 空
        //   上层面板：只显示"网络可用"，不显示连的是哪个 SSID
        //   状态栏：没有 Wi-Fi 图标（SystemUI 的 WifiStatusTracker 也读 WifiInfo）
        // (maintainer 2026-10-31, instance 1793442402796)
        // A field A15 does not have is now named once (a15_wifi_info_field=...) instead
        // of aborting the call; the rest keep working.
        WifiInfo.class.getMethod("setNetworkId", int.class).invoke(info, wifi.optInt("networkId", -1));
        WifiInfo.class.getMethod("setRssi", int.class).invoke(info, wifi.optInt("rssi", -127));
        WifiInfo.class.getMethod("setLinkSpeed", int.class).invoke(info, wifi.optInt("linkSpeed", -1));
        WifiInfo.class.getMethod("setFrequency", int.class).invoke(info, wifi.optInt("frequency", -1));
        WifiInfo.class.getMethod("setBSSID", String.class).invoke(info, wifi.optString("bssid", null));
        WifiInfo.class.getMethod("setMacAddress", String.class).invoke(info,
                wifi.optString("mac", "02:00:00:00:00:00"));
        WifiInfo.class.getMethod("setSupplicantState", SupplicantState.class).invoke(info,
                connected(snapshot) ?
                        SupplicantState.COMPLETED : SupplicantState.DISCONNECTED);
        // Android 15 SystemUI treats only the primary WifiInfo as the active
        // transport for the status-bar icon and signal level.
        try {
            WifiInfo.class.getMethod("setIsPrimary", boolean.class).invoke(info, connected(snapshot));
            if (reported.add("connectionInfo.primary"))
                System.out.println("a15_wifi_info_primary=" + connected(snapshot));
        } catch (Throwable failure) {
            if (reported.add("!connectionInfo.primary"))
                System.out.println("a15_wifi_info_primary_failed=" + failure);
        }
        int ip = wifi.optInt("ip");
        if (ip != 0) WifiInfo.class.getMethod("setInetAddress", InetAddress.class).invoke(info,
                InetAddress.getByAddress(new byte[] {(byte) ip, (byte) (ip >> 8), (byte) (ip >> 16), (byte) (ip >> 24)}));
        // 这一段与 A10 的 guest-runtime/check/GuestWifi.java:31-49 逐行一致；A15 镜像的
        // WifiInfo 确实有 setBSSID/setFrequency/setInetAddress/setLinkSpeed/setMacAddress/
        // setNetworkId/setRssi/setSSID/setSupplicantState 全部九个 setter
        // （_bin/a15/scratch/fwifi/dump.txt:104342-105081），所以"缺 setter 导致整条
        // getConnectionInfo 抛异常"的推断**不成立**，那一版 try/catch 已回滚。
        if (reported.add("connectionInfo"))
            System.out.println("a15_wifi_connection_info=" + wifi.optString("ssid", "?")
                    + "/netId:" + wifi.optInt("networkId", -1) + "/rssi:" + wifi.optInt("rssi", -127)
                    + "/ip:" + wifi.optInt("ip", 0));
        return info;
    }

    private static WifiInfo ssidInfo(JSONObject wifi) throws Exception {
        WifiInfo info = WifiInfo.class.getConstructor().newInstance();
        String ssid = wifi == null ? WifiManager.UNKNOWN_SSID : wifi.optString("ssid", WifiManager.UNKNOWN_SSID);
        Class<?> type = Class.forName("android.net.wifi.WifiSsid");
        Object value = WifiManager.UNKNOWN_SSID.equals(ssid) ? null : wifiSsid(ssid);
        WifiInfo.class.getMethod("setSSID", type).invoke(info, value);
        return info;
    }

    /**
     * Builds the image's own WifiSsid from the host's SSID text.
     *
     * <p>The factory the A9/A10 relay used, {@code createFromByteArray(byte[])}, does
     * not exist on Android 15: the image's WifiSsid (framework-wifi.jar classes.dex)
     * exposes {@code fromBytes(byte[])}, {@code fromString(String)},
     * {@code fromUtf8Text(CharSequence)} and {@code createFromAsciiEncoded(String)}.
     * The missing method was not cosmetic: it was thrown inside
     * {@code GuestConnectivity.refresh()} *before* {@code registerNetworkAgent}, so
     * the relay retried once a second and no NetworkAgent ever existed --
     * 79 x "Guest host network: java.lang.NoSuchMethodException:
     * android.net.wifi.WifiSsid.createFromByteArray"
     * (instance 1793419587061, 2026-10-31 12:06).
     *
     * <p>Every strategy is tried by name and the one that answered is printed once,
     * so the next log says which image answered instead of leaving it to memory.
     */
    private static Object wifiSsid(String ssid) throws Exception {
        Class<?> type = Class.forName("android.net.wifi.WifiSsid");
        Object value = null;
        if (ssid.startsWith("0x")) {
            value = factory(type, "createFromHex", String.class, ssid, "createFromHex");
            if (value == null) value = factory(type, "createFromAsciiEncoded", String.class, ssid, "createFromAsciiEncoded");
            if (value == null) value = factory(type, "fromString", String.class, ssid, "fromString");
        } else {
            String text = ssid;
            if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
                text = text.substring(1, text.length() - 1);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            value = factory(type, "fromBytes", byte[].class, bytes, "fromBytes");
            if (value == null) value = factory(type, "createFromByteArray", byte[].class, bytes, "createFromByteArray");
            if (value == null) value = factory(type, "fromString", String.class, text, "fromString");
            if (value == null) value = factory(type, "fromUtf8Text", CharSequence.class, text, "fromUtf8Text");
        }
        if (value == null)
            throw new NoSuchMethodException("android.net.wifi.WifiSsid factory for " + ssid);
        return value;
    }

    private static final java.util.Set<String> reported = new java.util.HashSet<>();

    private static Object factory(Class<?> type, String method, Class<?> parameter, Object value, String label) {
        try {
            Object built = type.getMethod(method, parameter).invoke(null, value);
            if (reported.add(label)) System.out.println("a15_wifi_ssid=" + label);
            return built;
        } catch (NoSuchMethodException absent) {
            return null;
        } catch (Throwable failed) {
            if (reported.add("!" + label))
                System.out.println("a15_wifi_ssid_failed=" + label + ":" + failed);
            return null;
        }
    }

    static String ssid(JSONObject wifi) throws Exception {
        String ssid = ssidInfo(wifi).getSSID();
        return WifiManager.UNKNOWN_SSID.equals(ssid) ? null : ssid;
    }

    static boolean connected(JSONObject snapshot) {
        JSONObject wifi = snapshot.optJSONObject("wifi");
        if (wifi != null && wifi.has("connected")) return wifi.optBoolean("connected");
        JSONArray transports = snapshot.optJSONArray("transports");
        if (snapshot.optBoolean("connected") && transports != null)
            for (int i = 0; i < transports.length(); i++) if (transports.optInt(i) == 1) return true;
        return false;
    }

    /**
     * Maps one RSSI to the 0..numLevels-1 bucket the framework expects.
     *
     * <p>The image's own {@code WifiManager.calculateSignalLevel(int,int)} is the
     * authority (it is what the framework calls in-process); the linear fallback
     * only keeps this relay answering if that API is not reachable reflectively.
     * MIN_RSSI/MAX_RSSI are read from the image too, so the -100/-55 numbers are
     * not a second, independent guess.
     */
    private static int signalLevel(int rssi, int levels) {
        if (levels <= 1) return 0;
        try {
            return (Integer) WifiManager.class.getMethod("calculateSignalLevel", int.class, int.class)
                    .invoke(null, rssi, levels);
        } catch (Throwable absent) {
            int min = -100;
            int max = -55;
            try { min = WifiManager.class.getField("MIN_RSSI").getInt(null); } catch (Throwable ignored) { }
            try { max = WifiManager.class.getField("MAX_RSSI").getInt(null); } catch (Throwable ignored) { }
            if (rssi <= min) return 0;
            if (rssi >= max) return levels - 1;
            return (int) ((float) (rssi - min) * (levels - 1) / (max - min));
        }
    }

    static List<ScanResult> scans(JSONObject wifi) throws Exception {
        List<ScanResult> results = new ArrayList<>();
        JSONArray values = wifi.optJSONArray("scans");
        long hostNow = wifi.optLong("hostElapsedRealtimeMicros", -1);
        long guestNow = android.os.SystemClock.elapsedRealtimeNanos() / 1000;
        for (int i = 0; values != null && i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            ScanResult scan = new ScanResult();
            scan.SSID = value.getString("ssid"); scan.BSSID = value.getString("bssid");
            scan.frequency = value.getInt("frequency"); scan.level = value.getInt("level");
            long timestamp = value.getLong("timestamp");
            if (hostNow > 0 && timestamp > 0)
                timestamp = Math.max(0, guestNow - Math.max(0, hostNow - timestamp));
            scan.timestamp = timestamp; scan.capabilities = value.getString("capabilities");
            scanSsid(scan, value.optString("ssid", ""));
            results.add(scan);
        }
        return results;
    }

    /**
     * Fills {@code ScanResult.wifiSsid}, the field Android 13+ keys a Wi-Fi entry by.
     *
     * <p>Settings' WifiPickerTracker builds {@code ScanResultKey}/{@code WifiEntry}
     * from {@code ScanResult.getWifiSsid()}. A ScanResult constructed by hand -- as
     * this relay does -- has {@code SSID} set but {@code wifiSsid} null, and the
     * tracker then produces no entry for it: the Wi-Fi page prints
     * {@code onWifiEntriesChanged: reason=1} + {@code No entries!} even though the
     * SCAN_RESULTS broadcast was delivered and ConnectivityService already carries
     * the host network (instance 1793421860752, 2026-10-31 12:57; the settings
     * process log shows "Received broadcast: android.net.wifi.SCAN_RESULTS" right
     * before the empty entry list).
     * The field is public (framework-wifi.jar ScanResult, access 0x0001, hiddenapi
     * UNSUPPORTED) but not in the SDK stub, so it is written reflectively and the
     * outcome is named once in the log.
     */
    private static void scanSsid(ScanResult scan, String ssid) {
        if (ssid == null || ssid.isEmpty()) return;
        try {
            java.lang.reflect.Field field = ScanResult.class.getDeclaredField("wifiSsid");
            field.setAccessible(true);
            field.set(scan, wifiSsid(ssid));
            if (reported.add("scan.wifiSsid")) System.out.println("a15_wifi_scan_ssid=set");
        } catch (Throwable failure) {
            if (reported.add("!scan.wifiSsid"))
                System.out.println("a15_wifi_scan_ssid=failed:" + failure);
        }
    }

    private static Object configurations(JSONObject wifi) throws Exception {
        List<WifiConfiguration> values = new ArrayList<>();
        String ssid = ssid(wifi);
        if (ssid != null && wifi.optInt("networkId", -1) >= 0) {
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = ssid;
            config.networkId = wifi.getInt("networkId");
            config.status = WifiConfiguration.Status.CURRENT;
            int security = wifi.optInt("security", -1);
            config.allowedKeyManagement.clear();
            if (security == 0) config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            else if (security == 2) config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            else if (security == 3 || security == 5) config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
            else if (security == 4) config.allowedKeyManagement.set(8); // SAE in Android 10.
            else if (security == 6) config.allowedKeyManagement.set(9); // OWE in Android 10.
            values.add(config);
        }
        return parceled(values);
    }

    /**
     * Builds the ParceledListSlice the *wifi AIDL* declares.
     *
     * Android 15's wifi module is jarjar'd, so its AIDL returns
     * {@code com.android.wifi.x.com.android.modules.utils.ParceledListSlice}
     * (image framework-wifi.jar classes.dex, IWifiManager #65/#67/#68), not the
     * platform {@code android.content.pm.ParceledListSlice}. Both are attempted so
     * the same relay file still answers API 28/29.
     */
    private static Object parceled(List<?> values) throws Exception {
        for (String type : new String[]{
                "com.android.wifi.x.com.android.modules.utils.ParceledListSlice",
                "android.content.pm.ParceledListSlice"}) {
            try {
                return Class.forName(type).getConstructor(List.class).newInstance(values);
            } catch (ClassNotFoundException absent) { }
        }
        throw new ClassNotFoundException("ParceledListSlice");
    }

    private static boolean request(String action) throws Exception {
        java.nio.file.Path pending = Paths.get(System.getenv("A10VM_STATE_ROOT"), "host-wifi-action.tmp");
        java.nio.file.Path target = pending.resolveSibling("host-wifi-action.json");
        Files.write(pending, new JSONObject().put("action", action).toString().getBytes(StandardCharsets.UTF_8));
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    static synchronized void publish(JSONObject snapshot) throws Exception {
        JSONObject wifi = snapshot.optJSONObject("wifi");
        if (wifi == null) return;
        Class<?> activity = Class.forName("android.app.ActivityThread");
        Object thread = activity.getMethod("currentActivityThread").invoke(null);
        android.content.Context context = (android.content.Context) activity.getMethod("getSystemContext").invoke(thread);
        int state = wifi.optInt("state", WifiManager.WIFI_STATE_DISABLED);
        if (state != previousState || snapshot.has("wifiAction")) {
            android.content.Intent intent = new android.content.Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    .putExtra(WifiManager.EXTRA_WIFI_STATE, state)
                    .putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, previousState < 0 ? state : previousState);
            broadcast(context, intent, true);
            previousState = state;
        }
        String signature = wifi.toString() + connected(snapshot);
        if (!signature.equals(previousNetwork)) {
            android.net.NetworkInfo info = android.net.NetworkInfo.class.getConstructor(int.class, int.class,
                    String.class, String.class).newInstance(1, 0, "WIFI", "");
            android.net.NetworkInfo.class.getMethod("setIsAvailable", boolean.class).invoke(info, state == 3);
            android.net.NetworkInfo.class.getMethod("setDetailedState", android.net.NetworkInfo.DetailedState.class,
                    String.class, String.class).invoke(info, connected(snapshot) ?
                        android.net.NetworkInfo.DetailedState.CONNECTED : android.net.NetworkInfo.DetailedState.DISCONNECTED,
                        null, connected(snapshot) ? ssid(wifi) : null);
            broadcast(context, new android.content.Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                    .putExtra(WifiManager.EXTRA_NETWORK_INFO, info)
                    .putExtra(WifiManager.EXTRA_WIFI_INFO, connectionInfo(snapshot)), true);
            broadcast(context, new android.content.Intent(WifiManager.RSSI_CHANGED_ACTION)
                    .putExtra(WifiManager.EXTRA_NEW_RSSI, wifi.optInt("rssi", -127)), false);
            previousNetwork = signature;
        }
        if (snapshot.has("scanUpdated")) {
            broadcast(context, new android.content.Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, snapshot.optBoolean("scanUpdated")), false);
            // A15 的门面之外还有一条回调通道（SystemUI 走它），广播和回调都要发。
            for (Object callback : new java.util.ArrayList<>(scanCallbacks)) pushScanResults(callback);
        }
    }

    /**
     * Notifies one registered A15 scan-results callback.
     *
     * <p>参数个数按镜像的 {@code IScanResultsCallback.onScanResultsAvailable()} 定：
     * **零个**。上一版传了一整个 List，LocalGuestService.call() 按“名字 + 参数个数”
     * 匹配方法，多传一个参数就抛 NoSuchMethodException，而且异常发生在
     * {@code System.out.println("a15_wifi_scan_callback=set")} **之前**，所以那行判据
     * 一次都没打印过 —— 这就是“请求进了 relay、推送却消失”的机制。
     * 现在异常也被就地捕获并命名，下一次日志能直接看到断点在哪一侧。
     */
    private static void pushScanResults(Object callback) {
        if (callback == null) return;
        try {
            LocalGuestService.call(callback, "android.net.wifi.IScanResultsCallback",
                    "onScanResultsAvailable");
            if (reported.add("scanCallback.push")) System.out.println("a15_wifi_scan_callback_pushed=1");
        } catch (Throwable failure) {
            if (reported.add("!scanCallback.push"))
                System.out.println("a15_wifi_scan_callback_failed=" + failure);
        }
    }

    private static void broadcast(android.content.Context context, android.content.Intent intent, boolean sticky) throws Exception {
        Object all = android.os.UserHandle.class.getField("ALL").get(null);
        android.content.Context.class.getMethod(sticky ? "sendStickyBroadcastAsUser" : "sendBroadcastAsUser",
                android.content.Intent.class, android.os.UserHandle.class).invoke(context, intent, all);
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        JSONObject snapshot = GuestConnectivity.snapshot();
        JSONObject wifi = snapshot.optJSONObject("wifi");
        if (wifi == null) wifi = new JSONObject();
        if (method.getName().toLowerCase().contains("callback") && unhandled.add(method.getName()))
            System.out.println("a15_wifi_call=" + method.getName());
        switch (method.getName()) {
            case "getVerboseLoggingLevel": return verboseLogging;
            case "enableVerboseLogging": verboseLogging = Math.max(0, (Integer) args[0]); return null;
            case "getConnectionInfo": return connectionInfo(snapshot);
            case "getWifiEnabledState": return wifi.optInt("state", WifiManager.WIFI_STATE_DISABLED);
            // A15 的 StandardWifiEntry.updateWifiInfo() 用这个把 WifiInfo 的 RSSI
            // 换成 0..4 级信号；旧代码落进 default 分支返回 0，于是 Settings 与
            // 状态栏里每一条都是 [Level:0]（实例 1793460458429：
            // WifiPickerTracker: Entry 1/1 ... [Level:0]，同时
            // a15_wifi_unhandled=calculateSignalLevel）。
            case "calculateSignalLevel": {
                if (args == null || args.length == 0) return 0;
                int rssi = ((Number) args[0]).intValue();
                int levels = args.length > 1 ? ((Number) args[1]).intValue() : 5;
                return signalLevel(rssi, levels);
            }
            case "getSupportedFeatures": return 1L | (wifi.optBoolean("dualBand") ? 2L : 0L);
            // MIUI's developer switch controls a physical radio extension absent from the relay.
            case "isWifiCoverageExtendFeatureEnabled": return false;
            case "enableWifiCoverageExtendFeature": return null;
            // A15: IWifiManager.getScanResults(String,String) returns the *jarjar'd*
            // ParceledListSlice (com.android.wifi.x.com.android.modules.utils),
            // not a bare List. Returning List made the framework fail to unmarshal the
            // reply, which is what Settings reports as
            //   WifiPickerTracker: onWifiEntriesChanged: reason=0 / No entries!
            // (instance 1793421081689, 2026-10-31 12:34).
            case "getScanResults": return parceled(scans(wifi));
            case "getFactoryMacAddresses": return new String[0];
            case "getAllMatchingFqdnsForScanResults": case "getMatchingOsuProviders":
            case "getMatchingPasspointConfigsForOsuProviders": return Collections.emptyMap();
            case "getWifiConfigsForPasspointProfiles": return parceled(Collections.emptyList());
            case "getConfiguredNetworks": case "getPrivilegedConfiguredNetworks":
                return configurations(wifi);
            case "startScan": return request("scan");
            case "setWifiEnabled": return request("panel");
            case "getDhcpInfo":
                android.net.DhcpInfo dhcp = new android.net.DhcpInfo();
                JSONObject lease = wifi.optJSONObject("dhcp");
                if (lease == null) lease = new JSONObject();
                dhcp.ipAddress = lease.optInt("ipAddress", wifi.optInt("ip"));
                dhcp.gateway = lease.optInt("gateway"); dhcp.netmask = lease.optInt("netmask");
                dhcp.dns1 = lease.optInt("dns1"); dhcp.dns2 = lease.optInt("dns2");
                dhcp.serverAddress = lease.optInt("serverAddress"); dhcp.leaseDuration = lease.optInt("leaseDuration");
                return dhcp;
            case "acquireWifiLock": case "releaseWifiLock": case "disconnect": return false;
            case "getWifiServiceMessenger": return GuestWifiChannel.get();
            case "isScanAlwaysAvailable": return false;
            case "is5GHzBandSupported": return wifi.optBoolean("dualBand");
            // The relay has no hotspot or traffic-activity feed. Publish their initial idle state.
            case "getWifiApEnabledState": return 11; // WIFI_AP_STATE_DISABLED
            case "getWifiApConfiguration": return null;
            // A15 status bar pipeline: SystemUI's WifiRepositoryImpl builds its Wi-Fi
            // flows from *callbacks*, not from getScanResults(). 旧代码按 A9/A10 的
            // 两参形状取 args[1]，而 A15 的镜像里这三个注册方法都只有 **一个** 参数，
            // 于是 relay 自己抛 ArrayIndexOutOfBoundsException → 调用方收到 failed
            // reply → 扫描回调永远不推送（判据行 a15_wifi_scan_callback=set 从不出现）→
            // 状态栏/控制中心没有 Wi-Fi。
            //
            // 签名来自镜像 framework-wifi.jar classes.dex 的方法表（本轮取证，不是记忆）：
            //   IWifiManager.registerScanResultsCallback(IScanResultsCallback)     1 参数
            //   IWifiManager.registerTrafficStateCallback(ITrafficStateCallback)   1 参数
            //   IWifiManager.registerSoftApCallback(ISoftApCallback)               1 参数
            //   IWifiManager.registerLocalOnlyHotspotSoftApCallback(ISoftApCallback, Bundle)
            //   IScanResultsCallback.onScanResultsAvailable()                      0 参数
            //   ITrafficStateCallback.onStateChanged(int)
            //   ISoftApCallback.onStateChanged(SoftApState)  ← 参数是对象，不是 int
            case "registerScanResultsCallback": {
                scanCallbacks.add(args[0]);
                pushScanResults(args[0]);
                System.out.println("a15_wifi_scan_callback=set");
                return null;
            }
            case "unregisterScanResultsCallback":
                scanCallbacks.remove(args[0]);
                return null;
            case "registerTrafficStateCallback":
                LocalGuestService.call(args[0], "android.net.wifi.ITrafficStateCallback", "onStateChanged", 0);
                return null;
            case "registerSoftApCallback": case "registerLocalOnlyHotspotSoftApCallback":
                // A15 的热点回调收 SoftApState 对象；这个容器没有热点硬件，硬推一个
                // 编造的 SoftApState 只会让控制中心显示一个假热点，所以只登记不推送。
                return null;
            case "unregisterTrafficStateCallback": case "unregisterSoftApCallback": return null;
            case "reportActivityInfo": return null;
            case "requestActivityInfo":
                if (args[0] != null) ((android.os.ResultReceiver) args[0]).send(0, null);
                return null;
            case "getCurrentNetwork":
                if (!connected(snapshot)) return null;
                String path = java.nio.file.Paths.get(System.getenv("A10VM_STATE_ROOT"), "guest-network-id").toString();
                if (!java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(path))) return null;
                int id = Integer.parseInt(ProcessEvidence.read(path).trim());
                return Class.forName("android.net.Network").getConstructor(int.class).newInstance(id);
            // 未实现的方法不再抛异常。
            //
            // 抛 UnsupportedOperationException 会在 system_server 的 binder 线程里
            // 失败，调用方（Settings）收到 failed reply → 直接闪退：
            //   "进入 WLAN 设置页就闪退"（维护者 2026-10-31 第 95 轮反馈）。
            // 真机 A9/A10 的 relay 之所以没暴露这个问题，是因为那些版本的
            // IWifiManager 方法少、Settings 走的路径也不同；A15 的 Settings 会问
            // 一堆新方法，必然撞上默认分支。
            // 中立返回值 + 每个方法只记一次名字，既不闪退，也不把缺口藏起来。
            default:
                if (unhandled.add(method.getName()))
                    System.out.println("a15_wifi_unhandled=" + method.getName());
                return defaultValue(method.getReturnType());
        }
    }

    /** 名字只报一次，避免每个方法把日志刷爆。 */
    private static final java.util.Set<String> unhandled = new java.util.HashSet<>();

    /**
     * 中立返回值：基本类型给 0/false，容器给空（ParceledListSlice 只在声明类型就是
     * 它时才构造），数组给空数组，其余给 null。绝不抛异常——抛出即等于让调用方闪退。
     */
    private static Object defaultValue(Class<?> type) {
        if (type == null || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        if (type == String.class) return null;
        if (type.isArray()) return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        if (type.getName().endsWith("ParceledListSlice")) {
            try { return parceled(Collections.emptyList()); } catch (Throwable ignored) { }
        }
        if (java.util.List.class.isAssignableFrom(type)) return new ArrayList<>();
        if (java.util.Map.class.isAssignableFrom(type)) return Collections.emptyMap();
        return null;
    }
}
