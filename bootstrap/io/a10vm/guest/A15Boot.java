package io.a10vm.guest;

import java.lang.reflect.InvocationTargetException;

/**
 * Android 15 (API 35) system_server bootstrap.
 *
 * Stage boundary: this class currently only performs the checks that the
 * API 35 guest can support without a verified framework layer, then enters
 * SystemServer. The API 28 bootstrap in guest-runtime-a9 pre-registers netd,
 * audio, media and gatekeeper endpoints because those services exist as
 * separate binaries that cannot run here; on Android 15 those components moved
 * (AIDL netd, health HAL, keystore2), so each equivalent registration was
 * rebuilt against the API 35 interface and is added here only after its own
 * real-device evidence: GuestNetworks (netd), GuestDnsResolver,
 * A15BpfMaps + the BpfMap natives, and A15GateKeeper.
 *
 * Do not add registrations here that have not been proven on a real device.
 */
public final class A15Boot {
    public static void main(String[] args) throws Throwable {
        // dalvikvm64 does not run AndroidRuntime::startReg, so the guest JNI
        // table (android.os.SystemProperties, Build, ...) is empty. This bridge
        // calls the exported android::AndroidRuntime::startReg(JNIEnv*) from its
        // JNI_OnLoad. Requires -Djava.library.path=<instance>/lib and an ld.config
        // that exports the "default" namespace (otherwise dlopen of the bridge
        // fails with a TLS relocation error and JNI_OnLoad never runs).
        // Register the table with System.loadLibrary() and then prove it with a
        // public API that reads it: Build.<clinit> is the first consumer of
        // SystemProperties.native_get, so a successful read means startReg really
        // ran. Both outcomes are printed — that line is the gate marker for the
        // next real-device run (docs/android15-port/09 排错手册).
        String loadFailure = "none";
        try {
            System.loadLibrary("a15vm-jni-register");
        } catch (Throwable failure) {
            loadFailure = failure.toString();
        }
        int sdk;
        try {
            sdk = android.os.Build.VERSION.SDK_INT;
        } catch (Throwable failure) {
            System.out.println("a15_jni_table=empty\n  load=" + loadFailure
                    + "\n  probe=" + failure);
            throw failure;
        }
        System.out.println("a15_jni_table=ready\n  load=" + loadFailure + "\n  sdk=" + sdk);
        if (sdk != 35) throw new AssertionError("Wrong guest SDK");
        System.out.println("desktop_guest_sdk=" + sdk);
        ProcessEvidence.report("desktop");
        configureRuntime(35);
        // Permanent evidence: this value is the classloader namespace search path
        // for every System.loadLibrary() call. On A15 the APEX directories must
        // be present here, otherwise libart/libbase lookups fail.
        System.out.println("a15_java_library_path=" + System.getProperty("java.library.path", ""));
        // --- Guest process identity ---------------------------------------------
        // Android 15's ContentProviderHelper.isProcessAliveLocked() ends with
        //   Process.getUidForPid(pid) == ProcessRecord.uid
        // and getUidForPid is Java on A15 (it parses /proc/<pid>/status through
        // the readProcLines native). The kernel reports the host app uid for
        // every guest process, while ProcessRecord.uid is the guest uid, so the
        // settings provider (installed into the "system" record, uid 1000) was
        // declared crashing on its first getContentProvider:
        //   ContentProviderHelper: Existing provider
        //     com.android.providers.settings/.SettingsProvider is crashing
        //   ActivityManager: Timeout waiting for provider .../1000
        // The bridge replaces readProcLines and answers the "Uid:" query from
        // the pid table A15AppLaunch fills from each zygote request (--setuid);
        // self is answered by libc's A10VM_GUEST_UID override.
        try {
            Class<?> process = Class.forName("android.os.Process");
            int installed = installProcessUidBridge(process);
            Object self = process.getMethod("getUidForPid", int.class)
                    .invoke(null, android.os.Process.myPid());
            System.out.println("a15_process_uid_native=" + installed
                    + " self=" + self + " myUid=" + android.os.Process.myUid());
        } catch (Throwable failure) {
            System.out.println("a15_process_uid_native=failed:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track diagnostic (temporary; remove once the NCDFE is closed) ----
        // SystemServer.run() dies between InitBeforeStartServices and StartServices,
        // and ART's message carries neither a class name nor a stack
        // ("Class not found using the boot class loader; no stack trace available"
        // is thrown by native JNI FindClass). Instead of guessing from the dex,
        // reproduce the three calls that live in that window here, each in its own
        // try/catch, so the next device log states which one throws.
        // The two `*_boot` probes answer "can the boot class loader see it at all",
        // which is exactly what a native FindClass uses.
        probe("lambda_class", () -> Class.forName(
                "com.android.server.SystemServer$$ExternalSyntheticLambda0", false,
                A15Boot.class.getClassLoader()));
        probe("app_shared_memory_class", () -> Class.forName(
                "com.android.internal.os.ApplicationSharedMemory", true,
                A15Boot.class.getClassLoader()));
        probe("app_shared_memory_boot", () -> Class.forName(
                "com.android.internal.os.ApplicationSharedMemory", true, null));
        probe("shared_memory_boot", () -> Class.forName("android.os.SharedMemory", true, null));
        probe("app_shared_memory_create", () -> {
            Class<?> type = Class.forName("com.android.internal.os.ApplicationSharedMemory",
                    true, A15Boot.class.getClassLoader());
            type.getMethod("create").invoke(null);
        });
        probe("app_shared_memory_native_create", () -> {
            Class<?> type = Class.forName("com.android.internal.os.ApplicationSharedMemory",
                    true, A15Boot.class.getClassLoader());
            java.lang.reflect.Method nativeCreate = type.getDeclaredMethod("nativeCreate");
            nativeCreate.setAccessible(true);
            nativeCreate.invoke(null);
        });
        // The remaining statements of SystemServer.run() between traceEnd and
        // StartServices, in the same order they run there. `create()` alone was
        // already proven to work from this context (a15_probe_app_shared_memory_create),
        // so what is left is instantiation, setDefaultApplicationWtfHandler and
        // setInstance. Each is isolated again.
        probe("lambda_instance", () -> Class.forName(
                "com.android.server.SystemServer$$ExternalSyntheticLambda0", true,
                A15Boot.class.getClassLoader()).getDeclaredConstructor().newInstance());
        probe("wtf_handler", () -> {
            Class<?> handlerType = Class.forName(
                    "com.android.internal.os.RuntimeInit$ApplicationWtfHandler", true, null);
            Object handler = java.lang.reflect.Proxy.newProxyInstance(
                    A15Boot.class.getClassLoader(), new Class<?>[] {handlerType},
                    (proxy, method, callArgs) -> null);
            Class.forName("com.android.internal.os.RuntimeInit", true, null)
                    .getMethod("setDefaultApplicationWtfHandler", handlerType)
                    .invoke(null, handler);
        });
        // Statement order in SystemServer.run() (services.jar bytecode 0x0176-0x0185):
        //   0x0176 traceEnd()                       <- "took to complete" printed, so it finished
        //   0x0179 new-instance SystemServer$$ExternalSyntheticLambda0
        //   0x017e invoke-static RuntimeInit.setDefaultApplicationWtfHandler(lambda)
        //   0x0181 invoke-static ApplicationSharedMemory.create()
        //   0x0185 invoke-static ApplicationSharedMemory.setInstance(created)
        // 25th round evidence: a15_probe_post_get_instance says the singleton was
        // NEVER installed -> 0x0185 was never reached. So the failure is at 0x0179,
        // 0x017e or 0x0181. The old `wtf_handler` probe proved only that a *Proxy*
        // of the right interface is accepted; the real statement passes the real
        // D8-generated lambda class. Reproduce that exact pair here.
        probe("wtf_handler_real", () -> {
            Class<?> handlerType = Class.forName(
                    "com.android.internal.os.RuntimeInit$ApplicationWtfHandler", true, null);
            Object lambda = Class.forName(
                    "com.android.server.SystemServer$$ExternalSyntheticLambda0", true,
                    A15Boot.class.getClassLoader()).getDeclaredConstructor().newInstance();
            System.out.println("a15_wtf_real_is_instance=" + handlerType.isInstance(lambda));
            Class.forName("com.android.internal.os.RuntimeInit", true, null)
                    .getMethod("setDefaultApplicationWtfHandler", handlerType)
                    .invoke(null, lambda);
        });
        // 2026-10-27 25th round: the setInstance probe was REMOVED on purpose.
        // ApplicationSharedMemory.setInstance() is a one-shot per process
        // (framework.jar bytecode: `if (sInstance != null) throw
        // IllegalStateException("ApplicationSharedMemory already initialized")`),
        // and SystemServer.run() calls exactly that statement at 0x0185. A probe
        // that pre-sets the singleton therefore changes the statement under test.
        // Probes must observe, not occupy, runtime state.
        probe("systemserver_loader", () -> {
            Class<?> server = Class.forName("com.android.server.SystemServer");
            System.out.println("a15_systemserver_class_loader=" + server.getClassLoader());
        });
        // Direct call sites (no reflection): the same new-instance / invoke-static
        // pairs SystemServer.run() executes at 0x0179-0x0181. Compiled against
        // bootstrap/stubs so javac emits the real call shape; the singleton
        // installer step stays disabled until the post-mortem phase.
        A15Statements.run(false);
        // Reproduce the one boot-class call in the window that no probe has executed yet:
        // `0x0176 invoke-virtual {v2}, Landroid/util/TimingsTraceLog;.traceEnd`.
        // TimingsTraceAndSlog lives in services.jar and TimingsTraceLog in framework.jar
        // (both boot-visible for that class), so this is the only statement of the window
        // that runs *boot-class* method bodies right before the crash. If a boot-class
        // method resolves a type that BOOTCLASSPATH does not contain, ART answers with the
        // pre-allocated NoClassDefFoundError (class_linker.cc, `class_loader == nullptr`),
        // i.e. exactly the observed text — and nothing else in the window can produce it.
        probe("trace_end", () -> {
            Class<?> timings = Class.forName("com.android.server.utils.TimingsTraceAndSlog",
                    true, A15Boot.class.getClassLoader());
            Object trace = timings.getDeclaredConstructor().newInstance();
            timings.getMethod("traceBegin", String.class).invoke(trace, "A15ProbeTrace");
            timings.getMethod("traceEnd").invoke(trace);
            System.out.println("a15_probe_trace_end=completed");
        });
        // --- B-track calibration (temporary; remove once the NCDFE is closed) ----
        // The failure text is ART's *pre-allocated* NoClassDefFoundError, created once in
        // Runtime::CreatePreAllocatedExceptions (runtime.cc, android15-release, lines
        // 1467-1474) with the literal body
        //   "Class not found using the boot class loader; no stack trace available".
        // A15 sets that object in exactly three places in runtime/class_linker.cc:
        //   1. ClassLinker::FindClass, `class_loader == nullptr`, boot class path miss;
        //   2. ClassLinker::FindClass, non-null loader miss **on a runtime thread**;
        //   3. ClassLinker::InitializeClass, AOT compiler only.
        // The message therefore never carries a class name and never has a stack trace:
        // "no stack trace available" is part of the string, not a lost log.
        // These two probes calibrate which of (1)/(2) this exact process can produce,
        // using two lookups whose *expected* answers are known:
        //   loader_miss      -> a class that exists nowhere, through the app loader.
        //                       A healthy runtime answers with a NAMED
        //                       ClassNotFoundException (class_linker.cc converts the
        //                       NCDFE); if it instead reports the pre-allocated text, the
        //                       app-loader miss path is not the normal one and every
        //                       missing class inside run() would look identical.
        //   boot_loader_miss -> a class that only lives in the class path (services.jar)
        //                       looked up through the boot loader. This must reproduce the
        //                       pre-allocated text verbatim: it is the control that proves
        //                       the text is the boot-loader marker.
        // Both only read; neither touches a field or a singleton.
        probe("loader_miss", () -> {
            try {
                Class.forName("com.android.server.A15NoSuchClassProbe", false,
                        A15Boot.class.getClassLoader());
                System.out.println("a15_loader_miss=found(unexpected)");
            } catch (ClassNotFoundException expected) {
                System.out.println("a15_loader_miss_cause=" + expected.getCause());
            }
        });
        probe("boot_loader_miss", () -> {
            try {
                Class.forName("com.android.server.SystemServer$$ExternalSyntheticLambda0", false, null);
                System.out.println("a15_boot_miss=found(unexpected)");
            } catch (ClassNotFoundException expected) {
                System.out.println("a15_boot_miss_cause=" + expected.getCause());
            }
        });
        // Sentinel bisect for 0x017e. RuntimeInit.setDefaultApplicationWtfHandler
        // is a plain static field store (framework.jar bytecode: sput-object +
        // return-void), so planting a sentinel cannot fail and cannot poison
        // anything: run() either overwrites it (0x017e reached) or does not.
        // Comparing field identity after the crash separates "died at 0x0179
        // new-instance" from "died at 0x0181 create()" with one line.
        try {
            Class<?> handlerType = Class.forName(
                    "com.android.internal.os.RuntimeInit$ApplicationWtfHandler", true, null);
            Object sentinel = java.lang.reflect.Proxy.newProxyInstance(
                    A15Boot.class.getClassLoader(), new Class<?>[] {handlerType},
                    (proxy, method, callArgs) -> null);
            Class.forName("com.android.internal.os.RuntimeInit", true, null)
                    .getMethod("setDefaultApplicationWtfHandler", handlerType)
                    .invoke(null, sentinel);
            System.out.println("a15_wtf_sentinel="
                    + Integer.toHexString(System.identityHashCode(sentinel)));
        } catch (Throwable failure) {
            System.out.println("a15_wtf_sentinel=threw:" + failure);
        }
        System.out.flush();
        // --- B-track: the apexservice placeholder --------------------------------
        // Must be installed BEFORE SystemServer.main(): the first bootstrap timing
        // block after StartServices is PlatformCompat, whose CompatConfig.loadConfigFiles()
        // calls ApexManager.getActiveApexInfos() -> ServiceManager.waitForService("apexservice").
        // With nothing registered the server logs "Waited one second for apexservice"
        // forever and never reaches PlatformCompat's "took to complete".
        //
        // This is deliberately NOT the image's real apexd and NOT runtimeProfile.services:
        // round 43 ran apexd there and proved it cannot register (servicemanager never saw
        // the name) and cannot mount/verify inside this container, while it writes /apex
        // and /data/apex next to a live SystemServer. See A15ApexService and
        // docs/android15-port/12 §5.7.
        try {
            A15ApexService.install();
            System.out.println("a15_apexservice_registered=1");
        } catch (Throwable failure) {
            // Do not stop the boot: the next log line says whether the server would have
            // worked without it. The failure text is the only thing needed to fix it.
            System.out.println("a15_apexservice_registered=0 error=" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track: the INetd endpoint ("netd") --------------------------------
        // startOtherServices() stops at StartNetworkManagementService because
        // NetworkManagementService.create() -> connectNativeNetdService() ->
        // NetdService.get() waits for the AIDL service "netd"; the Watchdog then
        // kills system_server after 65 s (instance 1793219521579: exit code 137,
        // stack: NetdService.get <- connectNativeNetdService <- create).
        //
        // The image's real netd CANNOT provide that name in this container.
        // Its first step, libnetd_updatable_init(), waits for bpf.progs_loaded
        // (a real device sets it from bpfloader) and, when that property is
        // forced, dies inside BpfMap:
        //   NetdUpdatable: libnetd_updatable_init: Failed: (13) [Permission denied]
        //     Failed to get program from /sys/fs/bpf/netd_shared/...
        //   A9VM bdev crash sig=6            (instance 1793218987117)
        // bpf(BPF_OBJ_GET) is EPERM for this process, so no BPF-based service can
        // come up here. netd was reverted after that measurement.
        //
        // The proven contract is the API 28 one: publish "netd" from the bootstrap
        // dex (A9Boot -> GuestNetworks.registerNetworkService) as a real Binder
        // that answers INetd transactions, with host networking described by
        // GuestConnectivity. This is a real AIDL service, not a placeholder name:
        // create()'s only netd call is registerUnsolicitedEventListener, which the
        // endpoint accepts; later calls get a ServiceSpecificException instead of
        // blocking the boot.
        try {
            GuestNetworks.registerNetworkService();
            System.out.println("a15_netd_binder=registered");
        } catch (Throwable failure) {
            // Do not stop the boot: the failure text is the next action.
            System.out.println("a15_netd_binder=unavailable:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track: the DNS resolver endpoint ("dnsresolver") ------------------
        // StartConnectivityService constructs ConnectivityService, whose
        // constructor calls getDnsResolver(context):
        //   context.getSystemService(DnsResolverServiceManager.class)
        //     -> SystemServiceRegistry fetcher -> ServiceManager.getServiceOrThrow("dnsresolver")
        // CachedServiceFetcher swallows ServiceNotFoundException and returns null,
        // so the constructor dies on the returned null:
        //   NullPointerException ... DnsResolverServiceManager.getService() on a null
        //   at ConnectivityService.getDnsResolver(:967) <- <init>(:1792)
        //   <- ConnectivityServiceInitializer.<init>(:54)
        // (instance 1793222120947, 2026-10-29 05:15)
        // The project already has the endpoint (GuestDnsResolver, the same one the
        // Android 10 runtime registers); Android 15 only renamed the event
        // listener method, and ConnectivityService wraps that call in try/catch.
        try {
            GuestDnsResolver.register();
            System.out.println("a15_dnsresolver_binder=registered");
        } catch (Throwable failure) {
            System.out.println("a15_dnsresolver_binder=unavailable:" + failure);
        }
        System.out.flush();
        // --- N-track: the two calls DesktopBoot makes and A15Boot never did ------
        // Android 9/10 register the network relays in DesktopBoot, in this order,
        // before SystemServer.main():
        //     GuestNetworks.register();   -> netd + dnsresolver + wifi + discovery
        //     GuestConnectivity.start();  -> NetworkAgent for the host's network
        // A15Boot rebuilt the netd and dnsresolver halves and stopped there, so this
        // runtime never published "wifi" and never registered a NetworkAgent:
        // framework-server.log has no "guest_host_network_registered=" line, and the
        // image's own WifiService (no wificond, no vendor HAL, no supplicant) ends in
        // "WifiController: STA disabled, return to DisabledState" while owning the
        // name. Same hole class as the zygote endpoint in round 79: the A15 entry
        // point replaced DesktopBoot and silently dropped what it used to call.
        //
        // The fix is to call the same two helpers, in the same order, before
        // SystemServer.main(): GuestWifi serves android.net.wifi.IWifiManager from
        // <stateRoot>/host-network.json (written by the host's HostNetworkBridge), and
        // GuestConnectivity registers that snapshot as a NetworkAgent so
        // ConnectivityService, apps and the status bar see the host's real network.
        try {
            GuestWifi.register();
            System.out.println("a15_wifi_endpoint=registered");
        } catch (Throwable failure) {
            // A duplicate name would mean the image's WifiService registered first.
            // Keep booting and name the loser; the next log line says who owns it.
            System.out.println("a15_wifi_endpoint=unavailable:" + failure);
        }
        System.out.flush();
        try {
            GuestConnectivity.start();
            System.out.println("a15_host_network=started");
        } catch (Throwable failure) {
            System.out.println("a15_host_network=unavailable:" + failure);
        }
        System.out.flush();
        // --- B-track: the gatekeeper endpoint ------------------------------------
        // StartBiometricService (SystemServer.java:2680) constructs BiometricService,
        // whose Injector.getGateKeeperService() is GateKeeper.getService():
        //   ServiceManager.getService("android.service.gatekeeper.IGateKeeperService")
        // and throws IllegalStateException("Gatekeeper service not available") when
        // null. Real-device evidence (instance 1793226895323, 2026-10-29 06:37):
        //   Failed to create service com.android.server.biometrics.BiometricService
        //   Caused by: IllegalStateException: Gatekeeper service not available
        //     at android.security.GateKeeper.getService(GateKeeper.java:41)
        //     at BiometricService$Injector.getGateKeeperService(BiometricService.java:1182)
        //     at BiometricService.<init>(BiometricService.java:1303)
        // The image ships system/bin/gatekeeperd plus a HIDL 1.0 software HAL, but
        // nothing starts them (no init) and gatekeeperd would need /data/misc/gatekeeper.
        // This is the same gap class as artd / sensorservice / audioserver; the proven
        // shape is the API 28 bootstrap endpoint (A9Boot -> A9GateKeeper), rebuilt for
        // API 35 in A15GateKeeper with that tag's transaction numbers (getSecureUserId
        // is 4 on Android 15, not 5).
        try {
            A15GateKeeper.register();
            System.out.println("a15_gatekeeper_endpoint=registered");
        } catch (Throwable failure) {
            // Do not stop the boot: the exception in startOtherServices names the
            // service that still could not be created.
            System.out.println("a15_gatekeeper_endpoint=unavailable:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track: the vold / storaged endpoints -------------------------------
        // PackageManagerService.systemReady() (SystemServer.java:3036) runs
        // StagingManager.restoreSessions(), which calls
        // StorageManagerService.supportsCheckpoint() directly on mVold:
        //   java.lang.NullPointerException: Attempt to invoke interface method
        //     'boolean android.os.IVold.supportsCheckpoint()' on a null object reference
        //   at StorageManagerService.supportsCheckpoint(StorageManagerService.java:3225)
        //   at StagingManager.restoreSessions(StagingManager.java:656)
        //   at PackageInstallerService.restoreAndApplyStagedSessionIfNeeded(:415)
        //   at PackageManagerService.systemReady(:4351)
        //   at SystemServer.startOtherServices(SystemServer.java:3036)
        // (instance 1793227418161, 2026-10-29 06:45)
        // StorageManagerService.connectVold() retries "vold" every second, but this
        // runtime registered no such name, so mVold never left null.
        // PrivateVolumes is the project's API 28+ endpoint (A9Boot and DesktopBoot
        // already register it): it implements the storage operations the framework
        // performs during boot, and answers needsCheckpoint / supportsCheckpoint /
        // supportsBlockCheckpoint with false. Register it before SystemServer.main()
        // so connectVold() succeeds on its first attempt and mVold is non-null when
        // PackageManagerService.systemReady() runs.
        try {
            PrivateVolumes.register();
            System.out.println("a15_vold_endpoint=registered");
        } catch (Throwable failure) {
            System.out.println("a15_vold_endpoint=unavailable:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track: the lmkd endpoint -----------------------------------------
        // ActivityManager hands *every* oom adjustment to lmkd over
        // /dev/socket/lmkd (SOCK_SEQPACKET) and times that write:
        //   ProcessList.setOomAdj(pid, tid, adj) -> ProcessList.writeLmkd(...)
        //   if (elapsed > 250ms) Slog.w("ActivityManager",
        //                               "SLOW OOM ADJ: <ms>ms for pid <pid>")
        // (verified in the image's own services.jar with dexdump: ProcessList
        //  .setOomAdj, dex 0x357024-0x3570de.)
        // No process in this container ever bound that name, so the connect retry
        // loop never ends:
        //   ActivityManager: Connection failed: java.io.IOException: No such file or directory
        //   ActivityManager: Failed to connect to lowmemorykiller, retry later
        //   ActivityManager: Failed to connect to lmkd, retry after 1000 ms
        //   ActivityManager: SLOW OOM ADJ: 3000ms for pid 24835 = -800
        //   ActivityManager: Slow operation: 3009ms so far, now at
        //                    attachApplicationLocked: after updateOomAdjLocked
        //   ActivityManager: Extending process start timeout ... for
        //                    ProcessRecord{... com.android.systemui}
        //   ActivityManager: ANR in com.android.systemui
        //   Reason: Process ... failed to complete startup
        //   ActivityManager: Killing ... (adj -800): user request after error
        // (instance 1793278346426, 2026-10-29 20:53-20:56)
        // i.e. the 3s stall is writeLmkd itself, it sits inside
        // attachApplicationLocked, and it is what blows the proc-start timeout for
        // SystemUI. A9Boot starts the project's lmkd endpoint for exactly this
        // reason (A9Boot.java:17); A15Boot had simply never started it. GuestMemory
        // binds /dev/socket/lmkd and answers the lmkd wire protocol (set oom adj,
        // remove, reset, kill count) with the host's existing killer doing the
        // actual reclaim.
        try {
            GuestMemory.start();
            System.out.println("a15_memory_endpoint=registered");
        } catch (Throwable failure) {
            System.out.println("a15_memory_endpoint=unavailable:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track: the framework's BPF maps ----------------------------------
        // StartNetworkStatsService (SystemServer.java:2128) creates
        // NetworkStatsService, whose NetworkStatsFactory opens BpfNetMaps; opening
        // /sys/fs/bpf/netd_shared/netd_configuration_map is EACCES in this process
        // (host app domain), and the exception aborts startOtherServices:
        //   RuntimeException: Failed to create service
        //     com.android.server.NetworkStatsServiceInitializer
        //   Caused by: IllegalStateException: Cannot open netd configuration map
        //   Caused by: ErrnoException: nativeBpfFdGet failed: EACCES
        // (instance 1793220806716, 2026-10-29 04:54)
        // Same wall as netd's libnetd_updatable_init: bpf(2) on the host bpffs is
        // denied by SELinux, not missing. A15BpfMaps pre-fills BpfNetMaps' six
        // singleton maps with empty, write-discarding implementations so that
        // initBpfMaps() finishes; see the class comment for the feature impact.
        try {
            Class<?> server = Class.forName("com.android.server.SystemServer");
            System.out.println("a15_bpf_maps=" + A15BpfMaps.install(server.getClassLoader()));
        } catch (Throwable failure) {
            // Do not stop the boot: the same exception will show up in
            // startOtherServices and name the exact map.
            System.out.println("a15_bpf_maps=failed:" + failure);
        }
        System.out.flush();
        // --- B-track: BpfMap's native boundary (every other BPF map) -------------
        // A15BpfMaps above only covers BpfNetMaps' six singletons. Services that
        // own a BpfMap directly go through BpfMap.<init> ->
        // cachedBpfFdGet -> nativeBpfFdGet, and that open is EACCES too:
        //   NetworkStatsService$Dependencies.getIfaceStatsMap(:943)
        //   -> BpfMap.<init> -> nativeBpfFdGet failed: EACCES
        //   -> IllegalStateException: Failed to open interface stats map
        // (instance 1793221376526, 2026-10-29 05:04)
        // liba15vm-jni-register.so replaces BpfMap's six natives with the
        // container behaviour: a real fd from an eventfd, reads report "no
        // entry", writes are discarded. Descriptors come from the image's
        // classes.dex (dexdump), see JniRegister.c.
        try {
            Class<?> bpfMap = Class.forName(
                    "android.net.connectivity.com.android.net.module.util.BpfMap");
            System.out.println("a15_bpf_native=" + installBpfMapBridge(bpfMap));
        } catch (Throwable failure) {
            // Do not stop the boot: the exception in startOtherServices will show
            // which map was still using the kernel.
            System.out.println("a15_bpf_native=failed:" + failure);
        }
        System.out.flush();
        // --- B-track: NetworkStatsFactory's native reads ------------------------
        // The final OnBootPhase_550 loop reaches NetworkStatsServiceInitializer,
        // whose systemReady() -> bootstrapStatsLocked() ->
        // NetworkStatsFactory.readNetworkStatsDetail() -> $Dependencies
        // .getNetworkStatsDetail() -> nativeReadNetworkStatsDetail() enters
        // libnetworkstats.so. That library opens /sys/fs/bpf/netd_shared/* with
        // bpf(BPF_OBJ_GET); the host app domain has no kernel BPF, so it aborts
        // from its map-info helper:
        //   a15_bpf cmd=7 ret=-1
        //   A9VM abort caller=liba15vm-image-...so+0x2376c (= libnetworkstats.so)
        //   Desktop server exited: code=134
        // (instance 1793235515690, 2026-10-29 08:58)
        //
        // The two natives are the only NetworkStatsFactory entry points into
        // libnetworkstats; their descriptors were read from the image's own
        // service-connectivity.jar with dexdump, and getNetworkStatsDetail's
        // bytecode throws only on a non-zero return. Returning 0 gives the same
        // empty-stats state the A15BpfMaps / BpfMap bridge already reports for
        // the absent kernel maps. No libc change and no bpf(2) emulation.
        try {
            Class<?> networkStatsFactory = Class.forName(
                    "android.net.connectivity.com.android.server.net.NetworkStatsFactory");
            System.out.println("a15_netstats_native="
                    + installNetworkStatsBridge(networkStatsFactory));
        } catch (Throwable failure) {
            // Do not stop the boot: the native abort will name the remaining path.
            System.out.println("a15_netstats_native=failed:" + failure);
            failure.printStackTrace(System.out);
        }
        // --- B-track: NetworkStatsService's own natives -------------------------
        // NetworkStatsFactory was only half of libnetworkstats' JNI surface. The
        // same image library also backs
        // android.net.connectivity.com.android.server.net.NetworkStatsService,
        // which declares five more natives (fresh dexdump of the image's own
        // service-connectivity.jar):
        //   nativeGetIfaceStat (String) -> NetworkStats$Entry
        //   nativeGetTotalStat () -> NetworkStats$Entry
        //   nativeGetUidStat (int) -> NetworkStats$Entry
        //   nativeInitNetworkTracing ()V
        //   nativeRegisterIface (String)V
        // They are the eBPF per-UID tracing hooks; with no kernel BPF their map
        // opens are EACCES, and the library aborts the whole server:
        //   a15_bpf cmd=7 ret=-1
        //   A9VM abort caller=libnetworkstats.so+0x1cf70
        //   A9VM bdev crash sig=6 (instance 1793276245028, 2026-10-29 20:23)
        // The bridge answers with a zeroed NetworkStats$Entry (get*StatsInternal
        // tolerates null, getUidStatsInternal does not, so a real object is used)
        // and keeps the two tracing hooks as no-ops.
        try {
            Class<?> networkStatsService = Class.forName(
                    "android.net.connectivity.com.android.server.net.NetworkStatsService");
            System.out.println("a15_netstats_service_native="
                    + installNetworkStatsServiceBridge(networkStatsService));
        } catch (Throwable failure) {
            // Do not stop the boot: the native abort will name the remaining path.
            System.out.println("a15_netstats_service_native=failed:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track: Android 15's vibrator native HAL layer ---------------------
        // VibratorManagerService's constructor calls
        // NativeWrapper.getVibratorIds() -> nativeGetVibratorIds(J), and the image
        // ships no vibrator HIDL service (vendor/bin/hw has only the legacy HAL
        // module vibrator.default.so). HIDL's getService() then waits forever:
        //   HidlServiceManagement: Waited one second for
        //     android.hardware.vibrator@1.0::IVibrator/default
        // Real device 2026-10-29 (instance 1793207204413): the main thread sat
        // there and the Watchdog killed system_server after 65 s
        // ("Blocked in handler on main thread (main) for 65s"). A device with no
        // vibrator is a normal Android configuration - the constructor accepts an
        // empty id list and registers no vibrator - so the bridge replaces the ten
        // native entries with the "no hardware" answers. See JniRegister.c for the
        // services.jar-derived signatures and the exact stub bodies.
        //
        // ORDER MATTERS: the methods live in libandroid_servers.so, and its
        // JNI_OnLoad registers the real implementations. SystemServer.run() calls
        // System.loadLibrary("android_servers") after this point, overwriting these
        // stubs, so this stub does not survive to the VibratorManagerService
        // constructor: the HAL query blocks in HIDL getService() and the Watchdog
        // kills system_server after 65 s.
        //
        // Round 70 and round 71 tried two preload shapes to win that ordering
        // (System.loadLibrary from this class, then Runtime.loadLibrary0 with
        // SystemServer.class as the caller). Both change which dex path
        // nativeloader reports as the caller of libandroid_servers.so, and neither
        // was proven to leave the rest of startOtherServices intact. They are
        // REVERTED by the maintainer's instruction; the vibrator hang stays as a
        // documented limitation until a full startOtherServices comparison exists.
        try {
            installVibratorStub(Class.forName(
                    "com.android.server.vibrator.VibratorManagerService"));
            System.out.println("a15_vibrator_stub=requested");
        } catch (Throwable failure) {
            System.out.println("a15_vibrator_stub_failed=" + failure);
        }
        System.out.flush();
        // --- Z-track: the guest zygote endpoint ----------------------------------
        // Android 15 system_server starts EVERY app process through
        // ZygoteProcess.openZygoteSocketIfNeeded(), i.e. connect to
        // /dev/socket/zygote (LocalSocketAddress "zygote", Namespace.RESERVED).
        // A9/A10 never ran the image's zygote binary either: A9Boot and DesktopBoot
        // call DesktopProcesses.start(), a project Java endpoint that speaks the
        // zygote wire protocol and forks app children itself. A15Boot never did,
        // so the socket did not exist and every launch failed with
        //   ActivityManager: Failure starting process com.android.systemui
        //     Caused by: ZygoteStartFailedEx: Error connecting to zygote
        //     Caused by: java.io.IOException: No such file or directory
        // (real device 2026-10-29, instance 1793270858542). This is the Z track:
        // SystemUI / Launcher3 / phone / networkstack all need it.
        try {
            A15Zygote.start();
            System.out.println("a15_zygote_endpoint=registered");
        } catch (Throwable failure) {
            // Do not stop the boot: the startProcess failure in the log then names
            // the socket error that is left.
            System.out.println("a15_zygote_endpoint=unavailable:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
        // --- B-track: the missing boot-animation completion signal ---------------
        // The image's vendor/build.prop sets debug.sf.nobootanimation=1 and there is
        // no init here to start bootanim.rc's service, so SurfaceFlinger never starts
        // the guest BootAnimation process; this runtime starts the same binary from
        // the host instead (FrameworkSession.startBootAnimation). On a real device
        // BootAnimation calls IActivityManager.bootAnimationComplete() when it exits,
        // and WindowManagerService calls it after the animation; with no animation
        // nobody calls it. ActivityManagerService.finishBooting() then waits on
        // mBootAnimationComplete and never sets sys.boot_completed.
        //
        // Real-device evidence (instance 1793270428124, 2026-10-29 18:41):
        //   SystemServerTiming: StartServices took to complete: 20914ms
        //   no a15_systemserver_threw, no Watchdog, server still alive,
        //   sys.boot_completed absent, client still waiting for
        //   desktop_services_ready=1.
        //
        // This is the same call the framework itself uses, not a property write:
        // poll until the "activity" binder exists, then call
        // IActivityManager.bootAnimationComplete() once per second until
        // sys.boot_completed flips to 1 (the call is idempotent; AMS sets the flag
        // and posts finishBooting).
        requestBootAnimationComplete();
        try {
            Class.forName("com.android.server.SystemServer").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause == null) throw failure;
            System.out.println("a15_systemserver_threw=" + cause);
            System.out.println("a15_systemserver_threw_frames="
                    + (cause == null ? "none" : cause.getStackTrace().length)
                    + " nested=" + (cause == null ? "none" : cause.getCause()));
            cause.printStackTrace(System.out);
            System.out.flush();
            // Post-mortem: which statement of SystemServer.run() actually ran?
            // getInstance() throws IllegalStateException("not initialized") when
            // the singleton was never installed, so this single line separates
            // "died at/before create()" from "died after setInstance()".
            probe("post_get_instance", () -> {
                Class<?> type = Class.forName(
                        "com.android.internal.os.ApplicationSharedMemory", true, null);
                System.out.println("a15_post_shared_memory_instance="
                        + type.getMethod("getInstance").invoke(null));
            });
            probe("post_create_again", () -> {
                Class<?> type = Class.forName(
                        "com.android.internal.os.ApplicationSharedMemory", true, null);
                System.out.println("a15_post_shared_memory_create="
                        + type.getMethod("create").invoke(null));
            });
            // Post-mortem sentinel check: identity equal to a15_wtf_sentinel means
            // SystemServer.run() never reached 0x017e (so the failure is the
            // 0x0179 new-instance); a different identity means 0x017e ran and the
            // failure is 0x0181 create() (0x0185 is already proven unreached).
            probe("post_wtf_handler", () -> {
                Class<?> handlerType = Class.forName(
                        "com.android.internal.os.RuntimeInit$ApplicationWtfHandler", true, null);
                Class<?> runtime = Class.forName(
                        "com.android.internal.os.RuntimeInit", true, null);
                for (java.lang.reflect.Field field : runtime.getDeclaredFields()) {
                    if (field.getType() != handlerType) continue;
                    field.setAccessible(true);
                    Object value = field.get(null);
                    System.out.println("a15_post_wtf_field=" + field.getName()
                            + " value=" + value
                            + " identity=" + (value == null ? "null"
                                    : Integer.toHexString(System.identityHashCode(value))));
                }
            });
            // --- B-track post-mortem: why is the APEX package scan privileged-blind? ---
            // Round 56 died in InstallPackageHelper.adjustScanFlags with
            //   NullPointerException: PackageSetting.getSigningDetails() on null
            // because `mPm.mSettings.getPackageLPr("android")` does not exist yet: the
            // APEX package scan (InitAppsHelper line 175) runs *before* scanSystemDirs
            // (line 178). That branch is only reached when the APEX package is seen as
            // privileged, which is decided by getSystemPackageScanFlags() matching the
            // APK path against PackageManagerService.SYSTEM_PARTITIONS + the APEX scan
            // partitions built by InitAppsHelper.getApexScanPartitions() from
            // ApexManager.getActiveApexInfos() + resolveApexToScanPartition().
            // Both are the framework's own data, so print them instead of guessing.
            probe("system_partitions", () -> {
                Class<?> pms = Class.forName(
                        "com.android.server.pm.PackageManagerService", true, A15Boot.class.getClassLoader());
                java.lang.reflect.Field field = pms.getDeclaredField("SYSTEM_PARTITIONS");
                field.setAccessible(true);
                java.util.List<?> partitions = (java.util.List<?>) field.get(null);
                System.out.println("a15_system_partitions="
                        + (partitions == null ? "null" : partitions.size()));
                if (partitions == null) return;
                for (Object partition : partitions) {
                    Object folder = partition.getClass().getMethod("getFolder").invoke(partition);
                    System.out.println("a15_system_partition=" + folder);
                }
            });
            probe("apex_scan_partitions", () -> {
                Class<?> apexManager = Class.forName(
                        "com.android.server.pm.ApexManager", true, A15Boot.class.getClassLoader());
                Object instance = apexManager.getMethod("getInstance").invoke(null);
                java.util.List<?> active = (java.util.List<?>) apexManager
                        .getMethod("getActiveApexInfos").invoke(instance);
                System.out.println("a15_active_apex_infos="
                        + (active == null ? "null" : active.size()));
                if (active == null || active.isEmpty()) return;
                Class<?> helper = Class.forName(
                        "com.android.server.pm.InitAppsHelper", true, A15Boot.class.getClassLoader());
                Class<?> activeType = Class.forName(
                        "com.android.server.pm.ApexManager$ActiveApexInfo", true, A15Boot.class.getClassLoader());
                java.lang.reflect.Method resolve =
                        helper.getMethod("resolveApexToScanPartition", activeType);
                int resolved = 0;
                for (int index = 0; index < active.size(); ++index) {
                    Object one = active.get(index);
                    if (resolve.invoke(null, one) != null) ++resolved;
                    if (index != 0) continue;
                    for (String name : new String[] {
                            "apexModuleName", "apexDirectory", "preInstalledApexPath"}) {
                        java.lang.reflect.Field field = one.getClass().getDeclaredField(name);
                        field.setAccessible(true);
                        System.out.println("a15_active_apex_" + name + "=" + field.get(one));
                    }
                }
                System.out.println("a15_apex_scan_partitions=" + resolved + "/" + active.size());
            });
            // Last step of the window (0x0185), only now: installing the singleton
            // before run() would turn the statement under test into a failure.
            A15Statements.run(true);
            throw cause;
        }
    }

    private interface Probe {
        void run() throws Throwable;
    }

    /**
     * Implemented by liba15vm-jni-register.so (JniRegister.c). Replaces the ten
     * ACC_NATIVE entries of the given class, which for A15 is
     * {@code com.android.server.vibrator.VibratorManagerService}: its HAL layer
     * blocks forever in HIDL getService() when the image has no vibrator service.
     * The signatures were read from the image's services.jar, not guessed.
     */
    private static native void installVibratorStub(Class<?> serviceClass);

    /**
     * Implemented by liba15vm-jni-register.so (JniRegister.c). Replaces the six
     * native entries of {@code android.net.connectivity.com.android.net.module.
     * util.BpfMap} with the container's "no kernel BPF" behaviour. The return
     * value is the RegisterNatives status: 0 means all six descriptors matched
     * the image's framework-connectivity.jar.
     */
    private static native int installBpfMapBridge(Class<?> type);

    /**
     * Implemented by liba15vm-jni-register.so (JniRegister.c). Replaces
     * {@code NetworkStatsFactory}'s two native reads
     * ({@code nativeReadNetworkStatsDetail} / {@code nativeReadNetworkStatsDev},
     * both {@code (Landroid/net/NetworkStats;)I}) with the container's
     * "no kernel BPF -> empty stats" answer. The return value is the
     * RegisterNatives status: 0 means both descriptors matched the image's
     * service-connectivity.jar.
     */
    private static native int installNetworkStatsBridge(Class<?> type);

    /**
     * Implemented by liba15vm-jni-register.so (JniRegister.c). Replaces
     * {@code NetworkStatsService}'s five natives ({@code nativeGetIfaceStat},
     * {@code nativeGetTotalStat}, {@code nativeGetUidStat},
     * {@code nativeInitNetworkTracing}, {@code nativeRegisterIface}) — the second
     * JNI surface of the image's libnetworkstats.so. They are the eBPF per-UID
     * tracing hooks: with no kernel BPF they must answer "empty stats" instead of
     * aborting the process. The return value is the RegisterNatives status: 0
     * means all five descriptors matched the image's service-connectivity.jar.
     */
    private static native int installNetworkStatsServiceBridge(Class<?> type);

    /**
     * Implemented by liba15vm-jni-register.so (JniRegister.c). Replaces the
     * {@code android.os.Process.readProcLines} native so that the framework's
     * guest process table sees the guest uid instead of the host app uid.
     * A15's {@code getUidForPid} is Java and reaches readProcLines with
     * ("/proc/&lt;pid&gt;/status", ["Uid:"]); the bridge answers that query from
     * the table filled by {@link A15AppLaunch}. Return value is the
     * RegisterNatives status: 0 means the descriptor matched framework.jar.
     */
    private static native int installProcessUidBridge(Class<?> type);

    /** Records the guest uid of one child started through {@link A15Zygote}. */
    static native void noteGuestProcess(int pid, int uid);

    /** Drops a child's guest uid after it exits. */
    static native void forgetGuestProcess(int pid);

    /**
     * Delivers the boot-animation-complete signal the framework normally gets
     * from the guest BootAnimation process. This runtime disables that process
     * without init / bootanim.rc (the host launches the same image binary), so
     * ActivityManagerService would otherwise wait on mBootAnimationComplete
     * forever and never set sys.boot_completed.
     *
     * <p>Uses the framework's own IActivityManager.bootAnimationComplete(), not a
     * sys.boot_completed write. Polling is idempotent: AMS sets the flag and posts
     * finishBooting, so repeated calls before systemReady are harmless.
     */
    private static void requestBootAnimationComplete() {
        Thread thread = new Thread(() -> {
            boolean reported = false;
            long deadline = android.os.SystemClock.elapsedRealtime() + 120000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    String boot = (String) Class.forName("android.os.SystemProperties")
                            .getMethod("get", String.class)
                            .invoke(null, "sys.boot_completed");
                    if ("1".equals(boot)) {
                        // SurfaceFlinger::stopBootAnim() sets exactly this property on a
                        // real device and the guest bootanimation polls it every frame.
                        // The host also stops the process when the desktop check passes,
                        // so this is the canonical exit, not the only one.
                        try {
                            Class.forName("android.os.SystemProperties")
                                    .getMethod("set", String.class, String.class)
                                    .invoke(null, "service.bootanim.exit", "1");
                            System.out.println("a15_boot_animation_exit=requested");
                        } catch (Throwable ignored) {
                            // The host-side stop still removes the surface.
                        }
                        break;
                    }
                    Object service = Class.forName("android.app.ActivityManager")
                            .getMethod("getService").invoke(null);
                    if (service != null) {
                        Class.forName("android.app.IActivityManager")
                                .getMethod("bootAnimationComplete").invoke(service);
                        if (!reported) {
                            System.out.println("a15_boot_animation_complete=requested");
                            System.out.flush();
                            reported = true;
                        }
                    }
                } catch (Throwable failure) {
                    if (!reported) {
                        System.out.println("a15_boot_animation_complete=waiting:" + failure);
                        System.out.flush();
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }, "a15-boot-animation-complete");
        thread.setDaemon(true);
        thread.start();
    }

    private static void probe(String name, Probe probe) {
        try {
            probe.run();
            System.out.println("a15_probe_" + name + "=ok");
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException
                    ? failure.getCause() : failure;
            System.out.println("a15_probe_" + name + "=threw:" + cause);
            cause.printStackTrace(System.out);
        }
        System.out.flush();
    }

    private static void configureRuntime(int sdk) throws Exception {
        Class<?> vm = Class.forName("dalvik.system.VMRuntime");
        Object runtime = vm.getMethod("getRuntime").invoke(null);
        vm.getMethod("setTargetSdkVersion", int.class).invoke(runtime, sdk);
        Class.forName("android.graphics.Typeface");
        try {
            Class.forName("android.security.keystore.AndroidKeyStoreProvider")
                    .getMethod("install").invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        }
    }
}
