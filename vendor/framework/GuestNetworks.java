package io.a10vm.guest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class GuestNetworks implements InvocationHandler {
    private Object listener;
    private android.os.IBinder oemNetd;
    private final Map<String, Set<Integer>> firewallUids = new HashMap<>();
    private final Map<Integer, Boolean> enabledFirewallChains = new HashMap<>();
    private int defaultNetwork;
    private final Map<Integer, Set<String>> interfaces = new HashMap<>();
    private final Map<Integer, Integer> userPermissions = new HashMap<>();
    private final Map<Integer, Integer> trafficPermissions = new HashMap<>();

    static void register() throws Exception {
        registerNetworkService();
        GuestDnsResolver.register();
        GuestWifi.register();
        GuestDiscovery.start();
    }

    // API 28 needs the same binder service as API 29: its NetdService is
    // android.net.util.NetdService over android.net.INetd (AIDL), not HIDL.
    // The resolver/wifi/discovery helpers above are API 29 additions, so the
    // Android 9 bootstrap registers only the netd endpoint.
    static void registerNetworkService() throws Exception {
        LocalGuestService.register("netd", "android.net.INetd", new GuestNetworks());
    }

    private static String reportedHash;
    private static boolean hashReported;

    /**
     * The interface hash of the image's own INetd AIDL declaration. Read it from
     * the framework class instead of hard-coding a constant: the value belongs to
     * the exact AOSP tag the image was built from (docs/android15-port/02), and a
     * stale literal would be a silent lie. Strategies, in order:
     *   1. static android.net.INetd$Stub.getInterfaceHash();
     *   2. static String field HASH on android.net.INetd$Stub;
     *   3. static String field HASH on android.net.INetd;
     *   4. "" (what android.net.INetd$Default reports).
     * Printed once as a15_netd_hash=<v> via=<how>, so the真机 log shows which
     * branch answered instead of leaving it to memory.
     */
    static synchronized String interfaceHash() {
        if (hashReported) return reportedHash;
        hashReported = true;
        StringBuilder how = new StringBuilder();
        reportedHash = staticString("android.net.INetd$Stub", "getInterfaceHash", how);
        if (reportedHash == null) reportedHash = staticString("android.net.INetd$Stub", "HASH", how);
        if (reportedHash == null) reportedHash = staticString("android.net.INetd", "HASH", how);
        if (reportedHash == null) {
            reportedHash = "";
            if (how.length() == 0) how.append("no-constant");
        }
        System.out.println("a15_netd_hash=" + reportedHash + " via=" + how);
        System.out.flush();
        return reportedHash;
    }

    private static String staticString(String type, String name, StringBuilder how) {
        try {
            Class<?> owner = Class.forName(type);
            try {
                java.lang.reflect.Method method = owner.getMethod(name);
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) &&
                        method.getReturnType() == String.class)
                    return report(how, type + "." + name + "()", (String) method.invoke(null));
            } catch (NoSuchMethodException absent) {
                // fall through to the field probe
            }
            java.lang.reflect.Field field = owner.getField(name);
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) &&
                    field.getType() == String.class)
                return report(how, type + "." + name, (String) field.get(null));
        } catch (Throwable missing) {
            how.append(type).append('.').append(name).append(":").append(missing.getClass().getSimpleName()).append(' ');
        }
        return null;
    }

    private static String report(StringBuilder how, String source, String value) {
        how.append(source).append('=').append(value);
        return value;
    }

    static RuntimeException error(int code, String message) throws Exception {
        return (RuntimeException) Class.forName("android.os.ServiceSpecificException")
                .getConstructor(int.class, String.class).newInstance(code, message);
    }

    @Override public synchronized Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        switch (method.getName()) {
            case "isAlive": return true;
            case "getOemNetd":
                if (oemNetd == null) oemNetd = GuestOemNetd.create();
                return oemNetd;
            case "getInterfaceVersion": return Class.forName("android.net.INetd").getField("VERSION").getInt(null);
            // Round 89: INetd is a *versioned* AIDL interface in API 35, so the
            // generated client stub also asks for the interface hash. This shim
            // answered everything else but this one, and the failure is fatal to
            // the whole network stack:
            //   java.lang.RuntimeException: Unable to bind to service
            //     com.android.server.NetworkStackService ...
            //   Caused by: android.os.ServiceSpecificException:
            //     Guest host-socket network: getInterfaceHash (code 95)
            //     at com.android.networkstack.android.net.INetd$Stub$Proxy
            //        .getInterfaceHash(INetd.java:3843)
            //     at com.android.server.NetworkStackService$NetworkStackConnector
            //        .<init>(NetworkStackService.java:312)
            //   GUEST_LOG 4 Process: Sending signal. PID: ... SIG: 9
            // NetworkStackService's constructor calls getInterfaceVersion() and
            // getInterfaceHash() inside a try that only catches RemoteException;
            // ServiceSpecificException is NOT a RemoteException, so it escapes
            // handleBindService, FATALs the persistent process, and AMS restarts
            // it forever (1997 spawns / 1963 exits in one boot, instance
            // 1793301644648). The hash is only bookkeeping there (it feeds
            // NetworkStackConnector.updateNetdAidlVersion(version, hash), no
            // comparison), so the honest container answer is the value the
            // framework's own no-op implementation reports.
            case "getInterfaceHash": return interfaceHash();
            case "registerUnsolicitedEventListener": listener = args[0]; return null;
            case "interfaceGetList":
                String name = GuestConnectivity.snapshot().optString("interface");
                return name.isEmpty() ? new String[] {"lo"} : new String[] {"lo", name};
            case "interfaceGetCfg":
                JSONObject network = GuestConnectivity.snapshot();
                boolean loopback = "lo".equals(args[0]);
                if (!loopback && !network.optString("interface").equals(args[0]))
                    throw error(19, "Unknown guest interface");
                String address = "127.0.0.1/8";
                if (!loopback) {
                    address = "0.0.0.0/0";
                    JSONArray addresses = network.optJSONArray("addresses");
                    if (addresses != null) for (int i = 0; i < addresses.length(); ++i)
                        if (!addresses.getString(i).contains(":")) { address = addresses.getString(i); break; }
                }
                String[] parts = address.split("/");
                Class<?> type = Class.forName("android.net.InterfaceConfigurationParcel");
                Object config = type.getConstructor().newInstance();
                type.getField("ifName").set(config, args[0]);
                type.getField("hwAddr").set(config, loopback ? "00:00:00:00:00:00" : "");
                type.getField("ipv4Addr").set(config, parts[0]);
                type.getField("prefixLength").setInt(config, Integer.parseInt(parts[1]));
                type.getField("flags").set(config, loopback ? new String[] {"up", "running", "loopback"} :
                        new String[] {"up", "running"});
                return config;
            case "ipfwdEnabled": case "tetherIsEnabled": return false;
            // Netd state changes are accepted and discarded: the container has no kernel rule
            // tables, and ConnectivityService treats a rejection as a boot-blocking failure.
            case "bandwidthSetGlobalAlert":
            case "bandwidthSetInterfaceQuota":
            case "bandwidthRemoveInterfaceQuota":
            case "bandwidthSetInterfaceAlert":
            case "bandwidthRemoveInterfaceAlert":
            case "bandwidthAddNaughtyApp":
            case "bandwidthRemoveNaughtyApp":
            case "bandwidthAddNiceApp":
            case "bandwidthRemoveNiceApp":
            case "networkAddUidRanges":
            case "networkRemoveUidRanges":
            case "networkRejectNonSecureVpn":
            case "networkAddRoute":
            case "networkRemoveRoute":
            case "networkSetPermissionForNetwork":
            case "setTcpRWmemorySize":
            case "setIPv6AddrGenMode":
            case "idletimerAddInterface":
            case "idletimerRemoveInterface":
            case "wakeupAddInterface":
            case "wakeupDelInterface":
                return null;
            // Host sockets remain subject to the host's data policy, not guest iptables.
            case "bandwidthEnableDataSaver": return false;
            case "networkCreatePhysical": interfaces.put((Integer) args[0], new HashSet<>()); return null;
            case "networkDestroy": interfaces.remove((Integer) args[0]); return null;
            case "networkAddInterface":
                if (!interfaces.containsKey(args[0])) throw error(64, "Guest network missing");
                interfaces.get(args[0]).add((String) args[1]); return null;
            case "networkRemoveInterface":
                if (interfaces.containsKey(args[0])) interfaces.get(args[0]).remove(args[1]); return null;
            case "networkSetDefault": defaultNetwork = (Integer) args[0]; return null;
            case "networkClearDefault": defaultNetwork = 0; return null;
            case "networkGetDefault": return defaultNetwork;
            case "interfaceSetMtu":
                if (!GuestConnectivity.snapshot().optString("interface").equals(args[0]) ||
                        (Integer) args[1] != GuestConnectivity.snapshot().optInt("mtu"))
                    throw error(95, "Guest MTU differs from host interface");
                return null;
            // PermissionMonitor also updates traffic permissions when a package is installed.
            // Host sockets use the ordinary app UID, without guest kernel permission tables.
            case "networkSetPermissionForUser":
                for (int uid : (int[]) args[1]) userPermissions.put(uid, (Integer) args[0]);
                return null;
            case "networkClearPermissionForUser":
                for (int uid : (int[]) args[0]) userPermissions.remove(uid);
                return null;
            case "trafficSetNetPermForUids":
                for (int uid : (int[]) args[1]) {
                    if ((Integer) args[0] == -1) trafficPermissions.remove(uid);
                    else trafficPermissions.put(uid, (Integer) args[0]);
                }
                return null;
            case "tetherInterfaceList": return new String[0];
            // A15's NetworkStatsService pulls tethering counters through
            // INetd.tetherGetStats() on every uid-stats query; the old shim let it
            // fall into the catch-all, so the framework logged a stack trace on
            // every stats pull:
            //   NetworkStats: problem reading network stats
            //   android.os.ServiceSpecificException: Guest host-socket network:
            //     tetherGetStats (code 95)
            // This container never tethers, so the honest answer is "no tethering
            // stats" as an empty array of the image's own parcel type (the type is
            // read from the interface, not hard-coded: connectivity is jarjar'd).
            case "tetherGetStats":
                return java.lang.reflect.Array.newInstance(method.getReturnType().getComponentType(), 0);
            case "firewallSetFirewallType":
                if (((Integer) args[0]) != 1) throw error(95, "Guest network firewall mode");
                return null;
            case "firewallReplaceUidChain":
                Set<Integer> uids = new HashSet<>();
                for (int uid : (int[]) args[2]) uids.add(uid);
                firewallUids.put((String) args[0], uids);
                return true;
            case "firewallEnableChildChain":
                enabledFirewallChains.put((Integer) args[0], (Boolean) args[1]);
                return null;
            default: throw error(95, "Guest host-socket network: " + method.getName());
        }
    }
}