package io.a10vm.guest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Android 15 container adaptation: the framework's BPF maps.
 *
 * <p>Android 13+ keeps per-UID network state (uid rules, data saver, cookie tags,
 * ingress discard, per-UID traffic counters) in BPF maps under
 * {@code /sys/fs/bpf/netd_shared}. {@code BpfNetMaps} opens six of them in
 * {@code initBpfMaps()} and <em>writes</em> two configuration entries right away;
 * a failure aborts the caller. On Android 15 that caller is
 * {@code NetworkStatsServiceInitializer}, i.e. SystemServer's
 * {@code StartNetworkStatsService}, so the whole {@code startOtherServices}
 * sequence dies:
 *
 * <pre>
 * java.lang.RuntimeException: Failed to create service
 *     com.android.server.NetworkStatsServiceInitializer: service constructor threw
 * Caused by: java.lang.IllegalStateException: Cannot open netd configuration map
 *   at ...BpfNetMaps.getConfigurationMap(BpfNetMaps.java:195)
 * Caused by: android.system.ErrnoException: nativeBpfFdGet failed: EACCES
 *   at ...BpfMap.nativeBpfFdGet(Native Method)      (instance 1793220806716)
 * </pre>
 *
 * <p>This is the same wall the image's netd hits in {@code libnetd_updatable_init}:
 * the guest runs in the host app's SELinux domain, so {@code bpf(2)} on a
 * root-owned bpffs node is EACCES. It is a kernel permission, not a missing
 * file, and no property or extra node changes it.
 *
 * <p>What this bridge does is therefore the honest container substitution: the
 * six singleton maps of {@code BpfNetMaps} are replaced with empty, write-
 * discarding implementations <em>before</em> SystemServer starts. Map state
 * changes are accepted and forgotten, exactly like {@code GuestNetworks} treats
 * netd's kernel rule tables, and reads report "no entry".
 *
 * <p>One map is not plain-empty: {@code sConfigurationMap.getValue()} is read
 * without a null check by {@code BpfNetMaps.swapActiveStatsMap()} (r36 line 752):
 *
 * <pre>
 * final long config = sConfigurationMap.getValue(
 *         CURRENT_STATS_MAP_CONFIGURATION_KEY).val;
 * </pre>
 *
 * so the "no entry" answer aborts {@code NetworkStatsService.systemReady()} at
 * boot phase 550 (instance 1793227741685, 2026-10-29):
 *   RuntimeException: Failed to boot service
 *     com.android.server.NetworkStatsServiceInitializer: onBootPhase threw ...
 *   Caused by: NullPointerException: Attempt to read from field
 *     'long ...Struct$U32.val' on a null object reference in method
 *     'void ...BpfNetMaps.swapActiveStatsMap()'
 * Its value type ({@code Struct.U32}) has a trivial zero, so this one proxy
 * answers {@code getValue} with {@code U32(0)} (= STATS_SELECT_MAP_A). Writes are
 * still discarded and every other map keeps the empty semantics.
 *
 * <p>Known limitation (recorded in STATUS.md): with no kernel BPF there is no
 * per-UID traffic accounting, no xtbpf UID firewall, no data-saver enforcement
 * at the socket layer and no ingress-discard counters. Boot and the desktop do
 * not depend on them.
 */
final class A15BpfMaps {
    /** Service jars are jarjar'd: the runtime name carries this prefix. */
    private static final String[] CLASS_NAMES = {
        "android.net.connectivity.com.android.server.BpfNetMaps",
        "com.android.server.BpfNetMaps",
    };

    /**
     * The six singletons declared by BpfNetMaps (r36 lines 125-132). They are
     * private static, and {@code initBpfMaps()} only opens a map when its field
     * is still null, so setting them here skips every {@code nativeBpfFdGet}.
     */
    private static final String[] FIELD_NAMES = {
        "sConfigurationMap",
        "sUidOwnerMap",
        "sUidPermissionMap",
        "sCookieTagMap",
        "sDataSaverEnabledMap",
        "sIngressDiscardMap",
    };

    /**
     * Installs the empty maps and returns how many fields were filled.
     * {@code loader} must be the SystemServer class loader (services.jar plus
     * the Connectivity/Tethering APEX jars), not the bootstrap dex loader.
     */
    static int install(ClassLoader loader) throws Exception {
        Class<?> type = null;
        for (String name : CLASS_NAMES) {
            try {
                type = Class.forName(name, true, loader);
                break;
            } catch (ClassNotFoundException missing) {
                // Try the next spelling; the jarjar prefix depends on the module.
            }
        }
        if (type == null) throw new ClassNotFoundException("BpfNetMaps");
        int installed = 0;
        for (String name : FIELD_NAMES) {
            Field field;
            try {
                field = type.getDeclaredField(name);
            } catch (NoSuchFieldException missing) {
                // A different API level: report through the count, do not fail.
                continue;
            }
            field.setAccessible(true);
            if (field.get(null) != null) continue;
            Class<?> contract = field.getType();
            Object empty;
            if ("sConfigurationMap".equals(name) || "sDataSaverEnabledMap".equals(name)) {
                // Two maps are read through a value object without a null check:
                //   sConfigurationMap: BpfNetMaps.swapActiveStatsMap() reads
                //     getValue(...).val (Struct$U32) during boot phase 550;
                //   sDataSaverEnabledMap: BpfNetMapsUtils.getDataSaverEnabled()
                //     reads getValue(...).val (Struct$U8) from the
                //     ConnectivityServiceThread; a null killed that thread and
                //     left ConnectivityService.systemReady blocked on its
                //     ConditionVariable (instance 1793268872142, 2026-10-29).
                // Zero means "no configuration / data saver off"; writes are
                // still discarded because the kernel maps do not exist here.
                // sDataSaverEnabledMap is declared as a RAW IBpfMap in the image's
                // service-connectivity.jar (dexdump of BpfNetMaps field #4:
                //   type 'Landroid/net/connectivity/com/android/net/module/util/IBpfMap;'
                // with no Signature attribute), so zeroValue(field) cannot infer its
                // value type and falls back to U32. The reader casts that value to
                // Struct$U8 (service-connectivity.jar dexdump 0x072fde:
                //   check-cast Struct$U8; iget-short .val:S),
                // and the cast is what SystemServer hit inside
                // JobSchedulerService.onBootPhase(600):
                //   ClassCastException: Struct$U32 cannot be cast to Struct$U8
                // The value type is therefore stated for this one field, not inferred.
                final Object zeroValue = valueFor(name, field, loader);
                empty = Proxy.newProxyInstance(contract.getClassLoader(),
                        new Class<?>[] {contract},
                        (proxy, method, arguments) -> {
                            if ("getValue".equals(method.getName())) return zeroValue;
                            return answer(proxy, method, arguments);
                        });
            } else {
                empty = Proxy.newProxyInstance(contract.getClassLoader(),
                        new Class<?>[] {contract}, HANDLER);
            }
            field.set(null, empty);
            ++installed;
        }
        return installed;
    }

    private static final InvocationHandler HANDLER = A15BpfMaps::answer;

    /**
     * Empty-map semantics:
     * <ul>
     *   <li>reads ({@code getValue}, {@code getFirstKey}, {@code getNextKey})
     *       return null — the framework already treats null as "no entry" and its
     *       iteration loops stop on a null key;</li>
     *   <li>writes ({@code updateEntry}, {@code insertEntry}, {@code deleteEntry},
     *       {@code clear}) are accepted and discarded, which is what
     *       {@code initBpfMaps()} needs to finish;</li>
     *   <li>{@code isEmpty()} is true and {@code forEach} succeeds without
     *       visiting anything.</li>
     * </ul>
     */
    /**
     * Builds the all-zero value object for a {@code BpfMap<K, V>} field. The value
     * class comes from the field's generic signature (the jarjar'd {@code U32}),
     * and both known Struct shapes are covered: a {@code (long)} constructor
     * ({@code U32}) or a no-argument constructor plus a {@code val} field.
     */
    private static Object zeroValue(Field field, ClassLoader loader) throws Exception {
        Class<?> valueType = null;
        java.lang.reflect.Type generic = field.getGenericType();
        if (generic instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.Type[] arguments =
                    ((java.lang.reflect.ParameterizedType) generic).getActualTypeArguments();
            if (arguments.length == 2 && arguments[1] instanceof Class<?>) {
                valueType = (Class<?>) arguments[1];
            }
        }
        if (valueType == null) valueType = structType(loader, "U32");
        return zeroValue(valueType);
    }

    /**
     * The zero value answered by {@code getValue} for a map that is read without a
     * null check. Only {@code sDataSaverEnabledMap} needs an explicit type (see
     * the dexdump note in {@link #install}); every other map keeps the generic
     * inference. A failure to resolve U8 must not stop the six maps from being
     * installed, so it falls back to the inferred value and says so in the log.
     */
    private static Object valueFor(String name, Field field, ClassLoader loader) throws Exception {
        if (!"sDataSaverEnabledMap".equals(name)) return zeroValue(field, loader);
        try {
            Object value = structValue(loader, "U8");
            System.out.println("a15_bpf_map_value=sDataSaverEnabledMap:"
                    + value.getClass().getName());
            return value;
        } catch (Exception unavailable) {
            System.out.println("a15_bpf_map_value=sDataSaverEnabledMap=failed:" + unavailable);
            return zeroValue(field, loader);
        }
    }

    /**
     * The jarjar'd {@code Struct$<width>} class. The connectivity module is
     * prefixed ({@code android.net.connectivity.}) in this image; the unprefixed
     * spelling is kept as a fallback for a differently packaged module.
     */
    private static Class<?> structType(ClassLoader loader, String simple)
            throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        for (String prefix : new String[] {
                "android.net.connectivity.com.android.net.module.util.Struct$",
                "com.android.net.module.util.Struct$"}) {
            try {
                return Class.forName(prefix + simple, true, loader);
            } catch (ClassNotFoundException missing) {
                failure = missing;
            }
        }
        throw failure;
    }

    /** A zero value of the given {@code Struct} subclass. */
    private static Object structValue(ClassLoader loader, String simple) throws Exception {
        return zeroValue(structType(loader, simple));
    }

    /** All-zero instance of one {@code Struct} subclass. */
    private static Object zeroValue(Class<?> valueType) throws Exception {
        // Struct subclasses carry a numeric "val" with a matching constructor:
        // U32(long), U8(short), and future widths use int/byte. Try every shape
        // before falling back to the no-argument form.
        for (Class<?> parameter : new Class<?>[] {long.class, int.class, short.class, byte.class}) {
            try {
                // Reflection requires the exact wrapper type of the primitive
                // parameter: U8(short) must receive Short, not Integer.
                Object zero;
                if (parameter == long.class) zero = 0L;
                else if (parameter == int.class) zero = 0;
                else if (parameter == short.class) zero = (short) 0;
                else zero = (byte) 0;
                return valueType.getConstructor(parameter).newInstance(zero);
            } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException ignored) {
                // Next shape.
            } catch (InstantiationException | IllegalAccessException failure) {
                throw new IllegalStateException("zero Struct " + valueType, failure);
            }
        }
        Object value = valueType.getDeclaredConstructor().newInstance();
        try {
            java.lang.reflect.Field valField = valueType.getField("val");
            Class<?> fieldType = valField.getType();
            if (fieldType == long.class) valField.setLong(value, 0L);
            else if (fieldType == int.class) valField.setInt(value, 0);
            else if (fieldType == short.class) valField.setShort(value, (short) 0);
            else if (fieldType == byte.class) valField.setByte(value, (byte) 0);
            else if (fieldType == boolean.class) valField.setBoolean(value, false);
        } catch (NoSuchFieldException ignored) {
            // A different Struct shape: the caller only reads .val for U32/U8.
        }
        return value;
    }

    private static Object answer(Object proxy, Method method, Object[] arguments) {
        // The return type must be answered before the method-name switch. The
        // previous order returned null for deleteEntry, which is a boolean
        // method; the caller unboxed null and killed the ConnectivityService
        // thread inside PermissionMonitor.startMonitoring (instance
        // 1793268872142: "Expected to unbox a 'boolean' ... but was returned
        // null" at BpfNetMaps.setNetPermForUids -> deleteEntry).
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            // Empty map: isEmpty() is true; every mutation reports "not done"
            // (deleteEntry false, insertEntry false, ...), forEach visits none.
            return "isEmpty".equals(method.getName());
        }
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        switch (method.getName()) {
            case "getValue":
            case "getFirstKey":
            case "getNextKey":
                return null;
            default:
                break;
        }
        return null;
    }

    private A15BpfMaps() {
    }
}
