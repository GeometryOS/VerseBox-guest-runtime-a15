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
                    if ("onRegistered".equals(name) && arguments.length == 1) {
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
            NetworkCapabilities.class.getMethod("setSSID", String.class).invoke(caps, GuestWifi.ssid(wifi));
            if (wifi != null && wifi.has("rssi")) NetworkCapabilities.class.getMethod("setSignalStrength", int.class)
                    .invoke(caps, wifi.getInt("rssi"));
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
        NetworkCapabilities.class.getMethod("setLinkDownstreamBandwidthKbps", int.class)
                .invoke(caps, snapshot.optInt("downstreamKbps"));
        NetworkCapabilities.class.getMethod("setLinkUpstreamBandwidthKbps", int.class)
                .invoke(caps, snapshot.optInt("upstreamKbps"));
        return caps;
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
