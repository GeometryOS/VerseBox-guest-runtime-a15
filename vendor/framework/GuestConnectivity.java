package io.a10vm.guest;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.ProxyInfo;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class GuestConnectivity {
    private static final List<GuestConnectivity> agents = new ArrayList<>();
    private static JSONObject current = new JSONObject();
    private static Handler handler;
    private static FileObserver observer;
    private static volatile GuestConnectivity active;
    private static String applied = "";
    private static final java.util.Set<String> reported = new java.util.HashSet<>();
    private final Messenger endpoint;
    private final List<Message> pending = new ArrayList<>();
    private Messenger peer;
    /** A15: the INetworkAgentRegistry the framework hands to the agent in onRegistered(). */
    private Object registry;
    /** The INetworkAgent object handed to registerNetworkAgent (needed to unregister). */
    private Object agent;
    /** A15: the Network object returned by registerNetworkAgent. */
    private Object network;
    /** The IConnectivityManager proxy, kept for reportNetworkConnectivity. */
    private Object connectivity;
    private final int netId;
    private final long hostNetId;
    private final Handler validationHandler;
    private NetworkInfo info;
    private static int protocol(String name) throws Exception {
        return Class.forName(name.startsWith("EVENT_")
                ? "android.net.NetworkAgent" : "com.android.internal.util.AsyncChannel").getField(name).getInt(null);
    }

    static void start() {
        if (!"1".equals(System.getenv("A10VM_HOST_NETWORK"))) return;
        HandlerThread thread = new HandlerThread("guest-host-network");
        thread.start();
        handler = new Handler(thread.getLooper());
        observer = new FileObserver(System.getenv("A10VM_STATE_ROOT"), FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String name) {
                if ("host-network.json".equals(name)) handler.post(GuestConnectivity::refresh);
            }
        };
        observer.startWatching();
        handler.post(GuestConnectivity::refresh);
    }

    static synchronized JSONObject snapshot() { return current; }

    private static void refresh() {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, "connectivity");
            if (binder == null) { handler.postDelayed(GuestConnectivity::refresh, 250); return; }
            Path path = Paths.get(System.getenv("A10VM_STATE_ROOT"), "host-network.json");
            if (!Files.isRegularFile(path)) return;
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (text.equals(applied)) return;
            JSONObject snapshot = new JSONObject(text);
            synchronized (GuestConnectivity.class) { current = snapshot; }
            if (active != null && (!snapshot.optBoolean("connected") ||
                    snapshot.optLong("hostNetId") != active.hostNetId)) {
                active.info = networkInfo(new JSONObject().put("connected", false));
                active.send("EVENT_NETWORK_INFO_CHANGED", active.info);
                active = null;
            }
            if (snapshot.optBoolean("connected")) {
                if (active == null) {
                    active = new GuestConnectivity(binder, snapshot);
                    agents.add(active);
                    Files.write(Paths.get(System.getenv("A10VM_STATE_ROOT"), "guest-network-id"),
                            (active.netId + "\n").getBytes(StandardCharsets.UTF_8));
                    System.out.println("guest_host_network_registered=" + active.netId);
                } else {
                    active.update(snapshot);
                }
            }
            GuestWifi.publish(snapshot);
            applied = text;
        } catch (Exception error) {
            System.err.println("Guest host network: " + error);
            handler.postDelayed(GuestConnectivity::refresh, 1000);
        }
    }

    private GuestConnectivity(IBinder binder, JSONObject snapshot) throws Exception {
        info = networkInfo(snapshot);
        hostNetId = snapshot.getLong("hostNetId");
        endpoint = new Messenger(new Handler(handler.getLooper()) {
            @Override public void handleMessage(Message message) {
                try {
                    if (message.what == protocol("CMD_CHANNEL_FULL_CONNECTION")) {
                        peer = message.replyTo;
                        Message reply = Message.obtain(null, protocol("CMD_CHANNEL_FULLY_CONNECTED"), 0, 0);
                        reply.replyTo = endpoint;
                        peer.send(reply);
                        for (Message item : pending) peer.send(item);
                        pending.clear();
                        publishValidation();
                    } else if (message.what == protocol("CMD_CHANNEL_DISCONNECT")) {
                        if (peer != null) peer.send(Message.obtain(null, protocol("CMD_CHANNEL_DISCONNECTED")));
                        peer = null;
                        agents.remove(GuestConnectivity.this);
                    } else if (message.what == Class.forName("android.net.NetworkAgent")
                            .getField("CMD_START_SOCKET_KEEPALIVE").getInt(null)) {
                        Message reply = Message.obtain(null, protocol("EVENT_SOCKET_KEEPALIVE"), message.arg1, -30);
                        if (peer != null) peer.send(reply);
                    }
                } catch (Exception error) { System.err.println("Guest network channel: " + error); }
            }
        });
        connectivity = Class.forName("android.net.IConnectivityManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        java.lang.reflect.Field tracker = connectivity.getClass().getDeclaredField("mTrackerHandler");
        tracker.setAccessible(true);
        validationHandler = (Handler) tracker.get(connectivity);
        Object registered = registerAgent(connectivity, info, links(snapshot), capabilities(snapshot));
        network = registered;
        // registerNetworkAgent returns android.net.Network on API 35 and an int on
        // API 28/29. Network's netId is @hide, so read the field reflectively.
        netId = registered instanceof Integer ? (Integer) registered : netIdOf(registered);
        // Android 15 的状态栏与 Settings 只在 `WifiPickerTracker` 收到**默认网络**的
        // NetworkCapabilities 时才会把 WifiEntry 标成 "Connected/Internet/Default"；
        // 注册完 NetworkAgent 后这里把系统当时的默认网络原样记一行，下一轮就能
        // 直接判断缺的是"默认网络没建立"还是"transportInfo 没配对"。
        handler.postDelayed(GuestConnectivity::reportDefaultNetwork, 3000);
    }

    /**
     * registerNetworkAgent carries a different parameter list per API level.
     *
     * <p>The image's own AIDL declaration (framework-connectivity.jar classes.dex,
     * {@code android.net.IConnectivityManager.registerNetworkAgent}) is
     *
     * <pre>
     *   A15: (INetworkAgent, NetworkInfo, LinkProperties, NetworkCapabilities,
     *         NetworkScore, LocalNetworkConfig, NetworkAgentConfig, int)
     *   A9/A10: (INetworkAgent, NetworkInfo, LinkProperties, NetworkCapabilities,
     *         int score, NetworkMisc misc, int providerId)
     * </pre>
     *
     * <p>Passing the API 29 shape here made the relay fail with a reflection
     * InvocationTargetException on every host-network refresh, so no NetworkAgent
     * was ever registered. Both shapes are attempted, the A15 one first; the log
     * line says which one answered.
     */
    private Object registerAgent(Object service, NetworkInfo info, LinkProperties links,
            NetworkCapabilities caps) throws Exception {
        // Android 15's agent is an AIDL object, not the API 28/29 Messenger:
        //   IConnectivityManager.registerNetworkAgent(INetworkAgent, NetworkInfo,
        //       LinkProperties, NetworkCapabilities, NetworkScore,
        //       LocalNetworkConfig, NetworkAgentConfig, int)
        // (image framework-connectivity.jar classes.dex, @0x... see the comment above).
        // The framework answers by calling onRegistered(INetworkAgentRegistry); every
        // later state update goes through that registry instead of AsyncChannel
        // messages, which is why the old Messenger shape could never work here.
        Object agent = LocalGuestService.localEndpoint("android.net.INetworkAgent",
                (proxy, method, arguments) -> {
                    String name = method.getName();
                    if (reported.add("agent.call." + name))
                        System.out.println("a15_network_agent_call=" + name + "/"
                                + (arguments == null ? -1 : arguments.length));
                    if ("onRegistered".equals(name) && arguments != null && arguments.length == 1) {
                        registry = arguments[0];
                        System.out.println("a15_network_agent_registered=1");
                        push(info, links, caps);
                    } else if ("onNetworkCreated".equals(name)) {
                        System.out.println("a15_network_agent_created=1");
                    } else if ("onValidationStatusChanged".equals(name)) {
                        System.out.println("a15_network_agent_validation=" + arguments[0]);
                    }
                    return null;
                });
        Object result = LocalGuestService.call(service, "android.net.IConnectivityManager",
                "registerNetworkAgent", agent, info, links, caps, networkScore(60),
                localNetworkConfig(), networkAgentConfig(), -1);
        this.agent = agent;
        System.out.println("a15_network_agent_shape=api35");
        return result;
    }

    /**
     * Pushes the host snapshot to ConnectivityService. A15 takes it through the
     * {@code INetworkAgentRegistry} handed to {@code onRegistered()}; the API 28/29
     * path used AsyncChannel events on the Messenger, so both shapes are kept and
     * the registry one wins when it exists.
     */
    private void push(NetworkInfo info, LinkProperties links, NetworkCapabilities caps) {
        Object registry = this.registry;
        if (registry == null) {
            try {
                send("EVENT_NETWORK_INFO_CHANGED", info);
                send("EVENT_NETWORK_PROPERTIES_CHANGED", links);
                send("EVENT_NETWORK_CAPABILITIES_CHANGED", caps);
            } catch (Exception error) { System.err.println("Guest network publish: " + error); }
            return;
        }
        try {
            LocalGuestService.call(registry, "android.net.INetworkAgentRegistry", "sendNetworkInfo", info);
            LocalGuestService.call(registry, "android.net.INetworkAgentRegistry", "sendLinkProperties", links);
            LocalGuestService.call(registry, "android.net.INetworkAgentRegistry", "sendNetworkCapabilities", caps);
            LocalGuestService.call(registry, "android.net.INetworkAgentRegistry", "sendScore", networkScore(60));
        } catch (Exception error) {
            System.err.println("Guest network registry: " + error);
        }
    }

    /**
     * Which network ConnectivityService calls the default, sampled repeatedly.
     *
     * <p>One sample 3 s after registration only proves what was true before boot
     * completed (it printed null on every run so far). The framework evaluates the
     * default network when requests arrive later, so keep sampling for a minute and
     * stop early once a network appears; the log line therefore says `attempt=N`.
     */
    private static final java.util.concurrent.atomic.AtomicInteger defaultSamples =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void reportDefaultNetwork() {
        int attempt = defaultSamples.incrementAndGet();
        try {
            Object thread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context context = (android.content.Context)
                    thread.getClass().getMethod("getSystemContext").invoke(thread);
            Object manager = context.getSystemService(Class.forName("android.net.ConnectivityManager"));
            Object network = manager.getClass().getMethod("getActiveNetwork").invoke(manager);
            Object caps = null;
            if (network != null)
                caps = manager.getClass().getMethod("getNetworkCapabilities", Class.forName("android.net.Network"))
                        .invoke(manager, network);
            System.out.println("a15_default_network=" + (network == null ? "null" : netIdOf(network))
                    + (caps == null ? " caps=null" : " caps=" + caps)
                    + " attempt=" + attempt);
            if (network != null) return;
            maybeReregister();
        } catch (Throwable failure) {
            System.out.println("a15_default_network_failed=" + failure + " attempt=" + attempt);
        }
        if (attempt < 24 && handler != null) handler.postDelayed(GuestConnectivity::reportDefaultNetwork, 2500);
    }

    private static volatile boolean reregistered;

    private static boolean bootCompleted() {
        try {
            return "1".equals((String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, "sys.boot_completed"));
        } catch (Throwable ignored) { return false; }
    }

    /**
     * Re-registers the agent once, after boot completed.
     *
     * <p>Real-device evidence (r105, instance 1793463319744): the agent is registered
     * at StartConnectivityService time (16:15:50) while NetworkStackClient only prints
     * "Network stack service connected" much later (server.log:26973).
     * NetworkAgentInfo.notifyRegistered() is what hands the agent its
     * INetworkAgentRegistry, and ConnectivityService.handleRegisterNetworkAgent()
     * takes an INetworkMonitor *before* that call; registered that early there is no
     * monitor yet, so onRegistered never arrives (a15_network_agent_call = 0 lines)
     * and the default network stays null forever (a15_default_network=null x24).
     * Re-registering after boot puts the agent behind a ready NetworkStack.
     */
    private static void maybeReregister() {
        GuestConnectivity relay = active;
        if (relay == null || relay.registry != null || reregistered || !bootCompleted()) return;
        reregistered = true;
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, "connectivity");
            if (binder == null) return;
            try {
                LocalGuestService.call(relay.connectivity, "android.net.IConnectivityManager",
                        "unregisterNetworkAgent", relay.agent);
            } catch (Throwable ignored) { }
            defaultSamples.set(0);
            GuestConnectivity replacement = new GuestConnectivity(binder, snapshot());
            agents.remove(relay);
            agents.add(replacement);
            active = replacement;
            System.out.println("a15_network_agent_reregistered=" + replacement.netId);
        } catch (Throwable failure) {
            System.out.println("a15_network_agent_reregister_failed=" + failure);
        }
    }

    private static int netIdOf(Object network) {
        if (network == null) return -1;
        try {
            return network.getClass().getField("netId").getInt(network);
        } catch (Throwable failed) {
            System.out.println("a15_network_id=unavailable:" + failed);
            return -1;
        }
    }

    /** One host-snapshot update, from whichever side can deliver it. */
    private void update(JSONObject snapshot) throws Exception {
        info = networkInfo(snapshot);
        push(info, links(snapshot), capabilities(snapshot));
        report();
    }

    /**
     * A15 replaced the A10 trick "post EVENT_NETWORK_TESTED on ConnectivityService's
     * private tracker handler" with the public
     * {@code IConnectivityManager.reportNetworkConnectivity(Network, boolean)}
     * (image framework-connectivity.jar, IConnectivityManager #58). The host
     * snapshot already says whether the host itself is validated, so the relay
     * reports exactly that instead of inventing a verdict.
     */
    private void report() throws Exception {
        if (connectivity == null || network == null) { publishValidation(); return; }
        LocalGuestService.call(connectivity, "android.net.IConnectivityManager",
                "reportNetworkConnectivity", network, snapshot().optBoolean("validated"));
    }

    /** A15 carries a NetworkScore object; API 28/29 carry the raw int. */
    private static Object networkScore(int value) {
        try {
            Class<?> builder = Class.forName("android.net.NetworkScore$Builder");
            Object instance = builder.getConstructor().newInstance();
            builder.getMethod("setLegacyInt", int.class).invoke(instance, value);
            return builder.getMethod("build").invoke(instance);
        } catch (Throwable absent) {
            return value;
        }
    }

    /**
     * A15's third new argument. A container network has no local-only/multicast
     * routing, which is exactly the empty configuration; null is the documented
     * "not a local network" value and is what this runtime can honestly claim.
     */
    private static Object localNetworkConfig() {
        try {
            Class<?> type = Class.forName("android.net.connectivity.android.net.LocalNetworkConfig");
            return type.getConstructor(Class.forName("android.net.NetworkRequest"),
                    Class.forName("android.net.connectivity.android.net.MulticastRoutingConfig"),
                    Class.forName("android.net.connectivity.android.net.MulticastRoutingConfig"))
                    .newInstance(null, null, null);
        } catch (Throwable failed) {
            // The image's constructor is private (dexdump shows a direct method), so
            // only reflection with setAccessible could build it; null is the
            // documented "not a local network" value and is what this relay is.
            if (reported.add("localNetworkConfig"))
                System.out.println("a15_local_network_config=null");
            return null;
        }
    }

    /** A15's fourth new argument: per-agent configuration; the defaults are correct here. */
    private static Object networkAgentConfig() {
        try {
            Class<?> builder = Class.forName("android.net.NetworkAgentConfig$Builder");
            Object instance = builder.getConstructor().newInstance();
            return builder.getMethod("build").invoke(instance);
        } catch (Throwable failed) {
            System.out.println("a15_network_agent_config=null:" + failed);
            return null;
        }
    }

    public static int validationResult(int proposed, int netId) {
        GuestConnectivity relay = active;
        JSONObject host = snapshot();
        return relay != null && relay.netId == netId && host.optBoolean("connected")
                && relay.hostNetId == host.optLong("hostNetId") ? (host.optBoolean("validated") ? 1 : 0) : proposed;
    }

    private void publishValidation() throws Exception {
        int event = Class.forName("com.android.server.ConnectivityService").getField("EVENT_NETWORK_TESTED").getInt(null);
        validationHandler.obtainMessage(event, validationResult(0, netId), netId, "").sendToTarget();
    }

    private void send(String event, Object value) throws Exception {
        Message message = Message.obtain(null, protocol(event), value);
        if (peer == null) pending.add(message); else peer.send(message);
    }

    private static NetworkInfo networkInfo(JSONObject snapshot) throws Exception {
        JSONArray transports = snapshot.optJSONArray("transports");
        boolean wifi = contains(transports, NetworkCapabilities.TRANSPORT_WIFI);
        boolean mobile = contains(transports, NetworkCapabilities.TRANSPORT_CELLULAR);
        NetworkInfo info = NetworkInfo.class.getConstructor(int.class, int.class, String.class, String.class)
                .newInstance(wifi ? ConnectivityManager.TYPE_WIFI : mobile ? ConnectivityManager.TYPE_MOBILE :
                        ConnectivityManager.TYPE_ETHERNET, 0, wifi ? "WIFI" : mobile ? "MOBILE" : "ETHERNET", "");
        NetworkInfo.class.getMethod("setIsAvailable", boolean.class).invoke(info, snapshot.optBoolean("connected"));
        NetworkInfo.class.getMethod("setDetailedState", NetworkInfo.DetailedState.class, String.class, String.class)
                .invoke(info, snapshot.optBoolean("connected") ? NetworkInfo.DetailedState.CONNECTED :
                        NetworkInfo.DetailedState.DISCONNECTED, "host", wifi && snapshot.optBoolean("connected")
                                ? GuestWifi.ssid(snapshot.optJSONObject("wifi")) : null);
        return info;
    }

    private static boolean contains(JSONArray values, int value) {
        if (values != null) for (int i = 0; i < values.length(); ++i) if (values.optInt(i) == value) return true;
        return false;
    }

    static NetworkCapabilities capabilities(JSONObject snapshot) throws Exception {
        NetworkCapabilities caps = new NetworkCapabilities();
        JSONArray transports = snapshot.getJSONArray("transports");
        boolean relayed = false;
        // The guest owns a regular relay, not the host VPN's TUN interface or UID ranges.
        for (int i = 0; i < transports.length(); ++i) {
            int type = transports.getInt(i);
            if (type == NetworkCapabilities.TRANSPORT_VPN) continue;
            NetworkCapabilities.class.getMethod("addTransportType", int.class).invoke(caps, type);
            relayed = true;
        }
        if (!relayed) NetworkCapabilities.class.getMethod("addTransportType", int.class)
                .invoke(caps, NetworkCapabilities.TRANSPORT_ETHERNET);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            JSONObject wifi = snapshot.optJSONObject("wifi");
            try {
                NetworkCapabilities.class.getMethod("setSSID", String.class).invoke(caps, GuestWifi.ssid(wifi));
            } catch (Throwable absent) {
                if (reported.add("caps.ssid")) System.out.println("a15_caps_ssid=absent:" + absent);
            }
            if (wifi != null && wifi.has("rssi")) NetworkCapabilities.class.getMethod("setSignalStrength", int.class)
                    .invoke(caps, wifi.getInt("rssi"));
            // Android 15 的状态栏不是从 WifiManager 取连接状态，而是从**默认网络的
            // NetworkCapabilities.getTransportInfo()** 取 WifiInfo：
            //   SystemUI.apk classes2.dex 里 com.android.wifitrackerlib.WifiPickerTracker
            //   同时引用 NetworkCapabilities.getTransportInfo 与
            //   NetworkCapabilities$Builder.setTransportInfo，WifiRepositoryImpl 再用
            //   WifiEntry.isDefaultNetwork / isPrimaryNetwork 过滤出"当前默认网络"，
            //   过滤不到就退化成 WifiNetworkModel$Inactive（= 状态栏不画 Wi-Fi 图标）。
            // 真机的 WifiNetworkAgent 在 registerNetworkAgent 之前就是这么塞的；
            // 我们只塞了旧字段 SSID/signalStrength，A15 的 wifitrackerlib 认不出来。
            // 实例 1793449504963：wifiStateFlow=3、扫描回调已推送（a15_wifi_scan_callback=set）、
            // 仍然没有图标，缺口就在这里。
        }
        for (String key : new String[] {"internet", "validated", "notMetered", "notRoaming", "notSuspended"}) {
            int value = key.equals("internet") ? NetworkCapabilities.NET_CAPABILITY_INTERNET :
                    key.equals("validated") ? NetworkCapabilities.NET_CAPABILITY_VALIDATED :
                    key.equals("notMetered") ? NetworkCapabilities.NET_CAPABILITY_NOT_METERED :
                    key.equals("notRoaming") ? NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING :
                    NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
            NetworkCapabilities.class.getMethod(snapshot.optBoolean(key) ? "addCapability" : "removeCapability",
                    int.class).invoke(caps, value);
        }
        // Android 15 的系统默认网络请求带 NOT_VCN_MANAGED
        // (ConnectivityService 的 requestNetwork 日志: INTERNET&NOT_RESTRICTED&TRUSTED
        //  &NOT_VPN&NOT_VCN_MANAGED&NOT_BANDWIDTH_CONSTRAINED)。agent 的 caps 缺这一位
        // 时 satisfies() 直接失败，默认网络永远是 null（实例 1793461890684:
        // a15_default_network=null，而 registerNetworkAgent 的 nc{} 里没有
        // NOT_VCN_MANAGED）——这也正是状态栏 WiFi 图标、Internet 标记和 IP 页面
        // 一起为空的上游。位值从镜像自己的常量取，不写死 28。
        try {
            int notVcnManaged = NetworkCapabilities.class.getField("NET_CAPABILITY_NOT_VCN_MANAGED")
                    .getInt(null);
            NetworkCapabilities.class.getMethod("addCapability", int.class).invoke(caps, notVcnManaged);
            if (reported.add("caps.notVcnManaged"))
                System.out.println("a15_caps_not_vcn_managed=" + notVcnManaged);
        } catch (Throwable absent) {
            if (reported.add("!caps.notVcnManaged"))
                System.out.println("a15_caps_not_vcn_managed_failed=" + absent);
        }
        NetworkCapabilities.class.getMethod("setLinkDownstreamBandwidthKbps", int.class)
                .invoke(caps, snapshot.optInt("downstreamKbps"));
        NetworkCapabilities.class.getMethod("setLinkUpstreamBandwidthKbps", int.class)
                .invoke(caps, snapshot.optInt("upstreamKbps"));
        // 最后才挂 WifiInfo：Builder 重建出来的对象可能不可再改，所以放在所有
        // addCapability/setLink* 之后，避免"挂上之后就不能再写"的二义性。
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            try {
                caps = attachTransportInfo(caps, GuestWifi.connectionInfo(snapshot));
            } catch (Throwable failure) {
                if (reported.add("!caps.transportInfo"))
                    System.out.println("a15_caps_transport_info_failed=" + failure);
            }
        }
        return caps;
    }

    /**
     * Attaches the relay's WifiInfo to the capabilities, which is the only thing the
     * Android 15 status bar uses to decide "is this wifi the current default network".
     *
     * <p>镜像取证：SystemUI.apk classes2.dex 的
     * {@code com.android.wifitrackerlib.WifiPickerTracker} 同时引用
     * {@code NetworkCapabilities.getTransportInfo()} 与
     * {@code NetworkCapabilities$Builder.setTransportInfo(TransportInfo)}，
     * {@code WifiRepositoryImpl} 再用 {@code WifiEntry.isDefaultNetwork} /
     * {@code isPrimaryNetwork} 过滤；过滤不到就退回
     * {@code WifiNetworkModel$Inactive}，{@code WifiViewModel$wifiIcon} 于是给
     * {@code WifiIcon$Hidden} —— 状态栏没有 Wi-Fi 图标（实例 1793449504963：
     * wifiStateFlow=3、扫描回调已推送、图标仍无）。
     *
     * <p>为什么要有三条路：A15 的 {@code NetworkCapabilities} 同时出现在
     * {@code framework.jar}（只有 getTransportInfo）和 {@code framework-connectivity.jar}
     * （有 setter）里，运行期只认先加载的那一份，所以直接调 setter 可能不存在。
     * 这里依次尝试 直接 setter → Builder 重建 → 私有字段 mTransportInfo，
     * 命中的那条写进 {@code a15_caps_transport_info=}，三条都失败才写 failed。
     */
    private static NetworkCapabilities attachTransportInfo(NetworkCapabilities caps,
            android.net.wifi.WifiInfo info) throws Exception {
        Class<?> transportInfo = Class.forName("android.net.TransportInfo");
        String label = null;
        NetworkCapabilities result = caps;
        try {
            NetworkCapabilities.class.getMethod("setTransportInfo", transportInfo).invoke(caps, info);
            label = "setter";
        } catch (Throwable direct) {
            try {
                Object builder = Class.forName("android.net.NetworkCapabilities$Builder")
                        .getConstructor(NetworkCapabilities.class).newInstance(caps);
                builder.getClass().getMethod("setTransportInfo", transportInfo).invoke(builder, info);
                Object built = builder.getClass().getMethod("build").invoke(builder);
                if (built instanceof NetworkCapabilities) { result = (NetworkCapabilities) built; label = "builder"; }
            } catch (Throwable rebuild) {
                // 最后一条：镜像里的字段名（dexdump 之外没有再核过，所以失败要留原文）。
                java.lang.reflect.Field field = NetworkCapabilities.class.getDeclaredField("mTransportInfo");
                field.setAccessible(true);
                field.set(caps, info);
                label = "field";
            }
        }
        if (reported.add("caps.transportInfo"))
            System.out.println("a15_caps_transport_info=" + label + ":" + info.getSSID()
                    + "/" + info.getNetworkId() + "/" + info.getRssi());
        return result;
    }

    private static LinkProperties links(JSONObject snapshot) throws Exception {
        LinkProperties links = new LinkProperties();
        links.setInterfaceName(snapshot.optString("interface"));
        if (snapshot.optInt("mtu") != 0) links.setMtu(snapshot.getInt("mtu"));
        links.setDomains(snapshot.optString("domains", null));
        JSONArray addresses = snapshot.getJSONArray("addresses"), dns = snapshot.getJSONArray("dns");
        for (int i = 0; i < addresses.length(); ++i)
            LinkProperties.class.getMethod("addLinkAddress", LinkAddress.class).invoke(links,
                    LinkAddress.class.getConstructor(String.class).newInstance(addresses.getString(i)));
        for (int i = 0; i < dns.length(); ++i)
            LinkProperties.class.getMethod("addDnsServer", InetAddress.class)
                    .invoke(links, InetAddress.getByName(dns.getString(i)));
        JSONArray routes = snapshot.getJSONArray("routes");
        Class<?> prefix = Class.forName("android.net.IpPrefix"), route = Class.forName("android.net.RouteInfo");
        for (int i = 0; i < routes.length(); ++i) {
            JSONObject value = routes.getJSONObject(i);
            Object entry = route.getConstructor(prefix, InetAddress.class, String.class, int.class).newInstance(
                    prefix.getConstructor(String.class).newInstance(value.getString("destination")),
                    value.optString("gateway").isEmpty() ? null : InetAddress.getByName(value.getString("gateway")),
                    value.optString("interface"), value.getInt("type"));
            LinkProperties.class.getMethod("addRoute", route).invoke(links, entry);
        }
        JSONObject proxy = snapshot.optJSONObject("proxy");
        if (proxy != null && !proxy.optString("host").isEmpty())
            links.setHttpProxy(ProxyInfo.buildDirectProxy(proxy.getString("host"), proxy.getInt("port")));
        return links;
    }
}
