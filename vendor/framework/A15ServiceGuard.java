package io.a10vm.guest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * First-registration-wins guard for the service names this runtime publishes itself.
 *
 * <p>Android 15's own {@code WifiService} (started by StartOtherServices) and
 * {@code WifiScanningService} publish {@code wifi} / {@code wifiscanner} *after*
 * A15Boot has published the host-backed relay, and ServiceManager's default policy is
 * "last writer wins". Clients that resolve the name in between and cache the proxy --
 * SystemUI's {@code WifiStateWorker}/{@code WifiRepositoryImpl} do exactly that at
 * boot -- then keep talking to a service whose radio is disabled:
 *
 * <pre>
 *   409:   a15_wifi_endpoint=registered            (relay)
 *   20145: WifiService: Registering wifi           (image, overwrites)
 *   24814: a15_wifi_endpoint=rebound               (relay again -- too late for SystemUI)
 *   SystemUI application-*.log: WifiStateWorker: WifiManager.getWifiState():1   (= DISABLED)
 *   Settings: onWifiStateChanged ... wifi state: 3                              (= ENABLED)
 * </pre>
 *
 * <p>Re-registering after boot cannot fix a client that already holds the old proxy, so
 * the duplicate registration has to be refused at the source. This class replaces
 * {@code android.os.ServiceManager.sServiceManager} with a dynamic proxy that drops
 * {@code addService} calls for those two names and names the loser once:
 *
 * <pre>   a15_service_add_rejected=wifi:&lt;pid&gt; </pre>
 *
 * <p>Our own registrations (A15Boot, before and after boot) are forwarded while
 * {@link #beginOwner()} / {@link #endOwner()} brackets them. Everything else on
 * {@code IServiceManager} -- getService, listServices, checkService, asBinder -- is
 * forwarded unchanged, so this cannot change name resolution.
 */
final class A15ServiceGuard {
    private static final Set<String> GUARDED = new HashSet<>(Arrays.asList("wifi", "wifiscanner"));
    private static final Set<String> REPORTED = new HashSet<>();
    private static int ownerDepth;
    private static boolean installed;

    private A15ServiceGuard() { }

    /** Marks the current thread as the relay's own registration path. */
    static void beginOwner() { ++ownerDepth; }

    static void endOwner() { if (ownerDepth > 0) --ownerDepth; }

    static void install() {
        if (installed) return;
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getManager = manager.getDeclaredMethod("getIServiceManager");
            getManager.setAccessible(true);
            final Object real = getManager.invoke(null);
            if (real == null) throw new IllegalStateException("no IServiceManager");
            Field field = manager.getDeclaredField("sServiceManager");
            field.setAccessible(true);
            Class<?> iface = Class.forName("android.os.IServiceManager");
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if (ownerDepth == 0 && name.startsWith("addService")
                                    && args != null && args.length > 0 && args[0] instanceof String
                                    && GUARDED.contains(args[0])) {
                                String service = (String) args[0];
                                if (REPORTED.add(service))
                                    System.out.println("a15_service_add_rejected=" + service + ":"
                                            + android.os.Process.myPid());
                                return defaultValue(method.getReturnType());
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        }
                    });
            field.set(null, proxy);
            installed = true;
            System.out.println("a15_service_guard=installed");
        } catch (Throwable failure) {
            System.out.println("a15_service_guard=failed:" + failure);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        return null;
    }
}
