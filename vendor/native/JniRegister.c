/*
 * liba15vm-jni-register.so — register libandroid_runtime's JNI table when the
 * guest desktop server is started by dalvikvm64 instead of app_process.
 *
 * app_process calls AndroidRuntime::startReg() before the main class runs; that
 * is what installs every gRegJNI entry (android.os.SystemProperties, Build,
 * ...). dalvikvm64 does not, so SystemProperties.native_get fails with
 * "No implementation found" and Build.<clinit> aborts.
 *
 * libandroid_runtime.so exports AndroidRuntime::startReg(JNIEnv*) (A15:
 * _ZN7android14AndroidRuntime8startRegEP7_JNIEnv, 208 bytes; it returns int and
 * takes only the env — verified by disassembly of
 * rootfs/system/lib64/libandroid_runtime.so). It is already mapped because
 * FrameworkSession puts libandroid_runtime.so in LD_PRELOAD.
 *
 * This bridge is loaded with System.loadLibrary(). That only works when the
 * guest ld.config exports the "default" namespace (see GuestRuntime.prepare):
 * otherwise the classloader namespace has no libc.so in its shared group, the
 * linker maps a second copy of the project libc and dlopen dies with
 *   TLS symbol "(null)" ... using IE access model
 * before JNI_OnLoad ever runs.
 *
 * Resolution ladder (never loads a second copy of libandroid_runtime.so):
 *   1. dlsym(RTLD_DEFAULT)                  — same global group as the preload
 *   2. dlopen(A15VM_ANDROID_RUNTIME, NOLOAD) — explicit path handed over by the host
 *   3. dlopen("libandroid_runtime.so", NOLOAD)
 * Every step writes one line to fd 2 (framework-server.log) so the real device
 * log says which path worked and what startReg returned. Do not replace this
 * with hardcoded offsets into the image: they are bound to one image build.
 */
#include <jni.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>

#define START_REG "_ZN7android14AndroidRuntime8startRegEP7_JNIEnv"
// Round 68: AndroidRuntime::mJavaVM is a static data member, set only by
// AndroidRuntime::startVm(), i.e. only when the process is started by
// app_process. Our A15 desktop server runs on dalvikvm64, so it stays NULL and
// every libandroid_runtime thread helper dies on it:
//   android::AndroidRuntime::javaThreadShell(void*)  (0x11abc8 in the image's
//   libandroid_runtime.so) does  mJavaVM->AttachCurrentThread(...)  and the
//   first load faults at address 0 (SEGV_MAPERR, pc=0x11abc8, x0=0).
// Real device 2026-10-29 00:59 (instance 1793206685399): that was the 139 that
// killed SystemServer right after "Starting com.android.BatteryService".
// Publishing the VM handle here is what app_process does for us; nothing else
// in AndroidRuntime is needed for the attach path.
#define JAVA_VM_SLOT "_ZN7android14AndroidRuntime7mJavaVME"

typedef int (*StartReg)(JNIEnv*);

// Round 71: set by JNI_OnLoad and used by the android_dlopen_ext hook below to
// re-install the vibrator stubs after libandroid_servers.so finishes loading.
static JavaVM* g_vm = NULL;
static int g_vibrator_stubs_late = 0;
typedef void* (*AndroidDlopenExt)(const char*, int, const android_dlextinfo*);
static AndroidDlopenExt g_next_dlopen_ext = NULL;

static void note(const char* format, ...);

/*
 * Round 69: Android 15's VibratorManagerService owns a native HAL layer that
 * Android 10 does not have. Its constructor calls
 *   nativeGetVibratorIds(J) -> int[]
 * and the image ships no vibrator HIDL service (vendor/bin/hw has no
 * android.hardware.vibrator@1.0-service; the only vendor piece is the legacy
 * HAL module vendor/lib64/hw/vibrator.default.so, which is not what
 * libvibratorutils / libandroid_runtime query). HIDL's getService() then loops
 * in HidlServiceManagement forever:
 *
 *   HidlServiceManagement: Waited one second for
 *     android.hardware.vibrator@1.0::IVibrator/default
 *
 * Real device 2026-10-29 (instance 1793207204413): the main thread sat inside
 * nativeGetVibratorIds and the Watchdog killed system_server after 65 s:
 *   Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on main
 *   thread (main) for 65s
 *     at ...VibratorManagerService.nativeGetVibratorIds(Native Method)
 *     at ...VibratorManagerService.<init>(VibratorManagerService.java:297)
 *
 * A device without a vibrator is a normal configuration for AOSP: the
 * constructor accepts an empty id list and simply registers no vibrator. What
 * is NOT normal is blocking forever, which is why this bridge replaces the ten
 * native entries with the "no hardware" answers. Nothing that would work with a
 * real HAL is disabled here: every one of these symbols is unreachable on a
 * device that exposes no vibrator id.
 *
 * The signatures below are not guessed: they were read out of the image's
 * services.jar (classes2.dex, Lcom/android/server/vibrator/VibratorManagerService;,
 * ACC_NATIVE methods) with _bin/a15/scratch/dxsig.py:
 *   nativeGetVibratorIds (J)[I      nativeInit (L...$VibratorManagerNativeCallbacks;)J
 *   nativeGetCapabilities (J)J      nativePrepareSynced (J[I)Z
 *   nativeGetFinalizer ()J          nativeStartSession (JJ[I)Z
 *   nativeCancelSynced (J)V         nativeTriggerSynced (JJ)Z
 *   nativeClearSessions (J)V        nativeEndSession (JJZ)V
 */
static int vibrator_ids_calls = 0;

static jintArray stub_vibrator_ids(JNIEnv* env, jclass clazz, jlong handle) {
    (void)clazz;
    (void)handle;
    // Printed once: proves the stub (not libandroid_servers' real implementation)
    // is what the framework actually entered. Without this line the log could not
    // tell "stub not installed" from "stub installed but never called".
    if (vibrator_ids_calls++ == 0) note("a15_vibrator_stub_hit=getVibratorIds\n");
    return (*env)->NewIntArray(env, 0);
}

static jlong stub_vibrator_init(JNIEnv* env, jclass clazz, jobject callbacks) {
    (void)env;
    (void)clazz;
    (void)callbacks;
    return 0;
}

static jlong stub_vibrator_capabilities(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    (void)handle;
    return 0;
}

static jlong stub_vibrator_finalizer(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return 0;
}

static void stub_vibrator_cancel(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    (void)handle;
}

static void stub_vibrator_clear(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    (void)handle;
}

static void stub_vibrator_end(JNIEnv* env, jclass clazz, jlong handle, jlong time,
                              jboolean hasCallback) {
    (void)env;
    (void)clazz;
    (void)handle;
    (void)time;
    (void)hasCallback;
}

static jboolean stub_vibrator_prepare(JNIEnv* env, jclass clazz, jlong handle,
                                      jintArray ids) {
    (void)env;
    (void)clazz;
    (void)handle;
    (void)ids;
    return JNI_FALSE;
}

static jboolean stub_vibrator_start(JNIEnv* env, jclass clazz, jlong handle, jlong time,
                                    jintArray ids) {
    (void)env;
    (void)clazz;
    (void)handle;
    (void)time;
    (void)ids;
    return JNI_FALSE;
}

static jboolean stub_vibrator_trigger(JNIEnv* env, jclass clazz, jlong handle,
                                      jlong time) {
    (void)env;
    (void)clazz;
    (void)handle;
    (void)time;
    return JNI_FALSE;
}

static const JNINativeMethod VIBRATOR_STUBS[] = {
    {"nativeGetVibratorIds", "(J)[I", (void*)stub_vibrator_ids},
    {"nativeInit", "(Lcom/android/server/vibrator/VibratorManagerService$VibratorManagerNativeCallbacks;)J",
     (void*)stub_vibrator_init},
    {"nativeGetCapabilities", "(J)J", (void*)stub_vibrator_capabilities},
    {"nativeGetFinalizer", "()J", (void*)stub_vibrator_finalizer},
    {"nativeCancelSynced", "(J)V", (void*)stub_vibrator_cancel},
    {"nativeClearSessions", "(J)V", (void*)stub_vibrator_clear},
    {"nativeEndSession", "(JJZ)V", (void*)stub_vibrator_end},
    {"nativePrepareSynced", "(J[I)Z", (void*)stub_vibrator_prepare},
    {"nativeStartSession", "(JJ[I)Z", (void*)stub_vibrator_start},
    {"nativeTriggerSynced", "(JJ)Z", (void*)stub_vibrator_trigger},
};

static void note(const char* format, ...) {
    char line[256];
    va_list arguments;
    va_start(arguments, format);
    int size = vsnprintf(line, sizeof(line), format, arguments);
    va_end(arguments);
    if (size < 0) return;
    if (size > (int)sizeof(line) - 1) size = (int)sizeof(line) - 1;
    ssize_t ignored = write(2, line, (size_t)size);
    (void)ignored;
}

static void* no_load(const char* path) {
    if (path == NULL || path[0] == '\0') return NULL;
    return dlopen(path, RTLD_LAZY | RTLD_NOLOAD);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_vm = vm;
    // Resolved here, while the linker is fully up: the hook is only reachable
    // after this library has been loaded, so g_next_dlopen_ext is never NULL by
    // the time android_dlopen_ext() runs.
    g_next_dlopen_ext = (AndroidDlopenExt)dlsym(RTLD_NEXT, "android_dlopen_ext");
    note("a15_dlopen_hook=%d\n", g_next_dlopen_ext != NULL ? 1 : 0);
    JNIEnv* env = NULL;
    if (vm == NULL || (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        note("a15vm-jni-register: no JNIEnv\n");
        return JNI_ERR;
    }

    StartReg startReg = (StartReg)dlsym(RTLD_DEFAULT, START_REG);
    const char* via = "rtld_default";
    if (startReg == NULL) {
        void* runtime = no_load(getenv("A15VM_ANDROID_RUNTIME"));
        if (runtime != NULL) {
            startReg = (StartReg)dlsym(runtime, START_REG);
            via = "configured_path";
        }
    }
    if (startReg == NULL) {
        void* runtime = no_load("libandroid_runtime.so");
        if (runtime != NULL) {
            startReg = (StartReg)dlsym(runtime, START_REG);
            via = "soname";
        }
    }
    if (startReg == NULL) {
        note("a15vm-jni-register: startReg not visible "
             "(rtld_default/configured_path/soname all failed)\n");
        return JNI_ERR;
    }
    // Publish this VM as AndroidRuntime::mJavaVM, exactly as app_process does in
    // AndroidRuntime::startVm(). Same resolution ladder: never load a second copy
    // of libandroid_runtime.so (it is in LD_PRELOAD; a second copy would have its
    // own mJavaVM and the framework's threads would keep dying).
    JavaVM** slot = (JavaVM**)dlsym(RTLD_DEFAULT, JAVA_VM_SLOT);
    const char* slotVia = "rtld_default";
    if (slot == NULL) {
        void* runtime = no_load(getenv("A15VM_ANDROID_RUNTIME"));
        if (runtime != NULL) {
            slot = (JavaVM**)dlsym(runtime, JAVA_VM_SLOT);
            slotVia = "configured_path";
        }
    }
    if (slot == NULL) {
        void* runtime = no_load("libandroid_runtime.so");
        if (runtime != NULL) {
            slot = (JavaVM**)dlsym(runtime, JAVA_VM_SLOT);
            slotVia = "soname";
        }
    }
    int published = 0;
    if (slot != NULL && *slot == NULL) {
        *slot = vm;
        published = 1;
    }
    note("a15_jni_vm_slot=%d published=%d via=%s\n", slot != NULL ? 1 : 0, published, slotVia);
    int result = startReg(env);
    note("a15_jni_start_reg=%d via=%s\n", result, via);
    return JNI_VERSION_1_6;
}

/*
 * Called from A15Boot.main() before SystemServer.main():
 *   installVibratorStub(Class.forName("com.android.server.vibrator.VibratorManagerService"))
 *
 * RegisterNatives is allowed to replace an already-registered implementation.
 * The class has not been initialized yet, so the first nativeGetVibratorIds
 * call already lands in the stub and the constructor never reaches HIDL.
 * The printed status is the gate marker: 0 means the ten signatures matched
 * the image's services.jar; anything else prints the exact reason.
 */
JNIEXPORT void JNICALL Java_io_a10vm_guest_A15Boot_installVibratorStub(
        JNIEnv* env, jclass self, jclass service) {
    (void)self;
    if (env == NULL || service == NULL) {
        note("a15_vibrator_stub=no_class\n");
        return;
    }
    jint status = (*env)->RegisterNatives(env, service, VIBRATOR_STUBS,
            (jint)(sizeof(VIBRATOR_STUBS) / sizeof(VIBRATOR_STUBS[0])));
    note("a15_vibrator_stub=%d methods=%d\n", (int)status,
         (int)(sizeof(VIBRATOR_STUBS) / sizeof(VIBRATOR_STUBS[0])));
}

/*
 * Round 71: install the vibrator stubs at the moment libandroid_servers.so has
 * finished loading, i.e. right after its JNI_OnLoad registered the real
 * implementations. Called from the android_dlopen_ext hook below, on the thread
 * SystemServer itself uses, so FindClass resolves the class in the services.jar
 * class loader. That is what round 70 got wrong: it preloaded the library from
 * A15Boot's loader, which made InputFlinger's AIDL classes exist twice and broke
 * StartInputManagerService (9 "Class descriptor collision" lines).
 */
static void install_vibrator_stubs_late(void) {
    if (g_vibrator_stubs_late || g_vm == NULL) return;
    JNIEnv* env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return;
    jclass service = (*env)->FindClass(env,
            "com/android/server/vibrator/VibratorManagerService");
    if (service == NULL) {
        (*env)->ExceptionClear(env);
        note("a15_vibrator_stub_late=no_class\n");
        return;
    }
    jint status = (*env)->RegisterNatives(env, service, VIBRATOR_STUBS,
            (jint)(sizeof(VIBRATOR_STUBS) / sizeof(VIBRATOR_STUBS[0])));
    note("a15_vibrator_stub_late=%d methods=%d\n", (int)status,
         (int)(sizeof(VIBRATOR_STUBS) / sizeof(VIBRATOR_STUBS[0])));
    g_vibrator_stubs_late = 1;
}

void* android_dlopen_ext(const char* filename, int flags,
                         const android_dlextinfo* extinfo) {
    if (g_next_dlopen_ext == NULL)
        g_next_dlopen_ext = (AndroidDlopenExt)dlsym(RTLD_NEXT, "android_dlopen_ext");
    if (g_next_dlopen_ext == NULL) return NULL;
    void* handle = g_next_dlopen_ext(filename, flags, extinfo);
    if (handle != NULL && filename != NULL &&
        strstr(filename, "libandroid_servers.so") != NULL) {
        install_vibrator_stubs_late();
    }
    return handle;
}

/*
 * Round 74b: Android 15's BpfMap natives — the container has no kernel BPF.
 *
 * BpfMap lives in framework-connectivity.jar (boot class path) and declares six
 * natives. The descriptors below were read from the image's own classes.dex with
 * dexdump (service-connectivity.dex + framework-connectivity.dex), not guessed:
 *
 *   nativeBpfFdGet         (Ljava/lang/String;III)I   -> int fd
 *   nativeFindMapEntry     (I[B[B)Z                   -> found, value out
 *   nativeWriteToMapEntry  (I[B[BI)V
 *   nativeGetNextMapKey    (I[B[B)Z                   -> has next, key out
 *   nativeDeleteMapEntry   (I[B)Z
 *   nativeSynchronizeKernelRCU ()V
 *
 * Why they are replaced: opening the maps under /sys/fs/bpf/netd_shared is
 * EACCES in the host app's SELinux domain, so every map open throws and the
 * caller aborts the boot
 * (instance 1793220806716: NetworkStatsServiceInitializer ->
 * "Cannot open netd configuration map" -> startOtherServices dies). This is a
 * kernel permission, not a missing node.
 *
 * The substitutes keep the Java object contract intact: nativeBpfFdGet hands
 * back a real fd (an eventfd, never read or written), reads report "no entry",
 * writes are accepted and discarded, iteration stops immediately. Consequence,
 * recorded in STATUS.md: no per-UID traffic counters, no xtbpf UID firewall, no
 * data-saver enforcement at the socket layer, no ingress-discard counters.
 * Boot and the desktop do not use them.
 *
 * RegisterNatives on an already registered native is allowed (same mechanism the
 * vibrator stubs above rely on), so this replaces the real JNI entries.
 */
static int bpf_fd_reported = 0;

static jint bpf_stub_fd_get(JNIEnv* env, jobject self, jstring path, jint mode,
                            jint key_size, jint value_size) {
    (void)self;
    (void)key_size;
    (void)value_size;
    int fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (bpf_fd_reported < 12) {
        const char* name = path != NULL ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
        note("a15_bpf_fd=%s fd=%d mode=%d\n", name != NULL ? name : "(null)", fd, (int)mode);
        if (name != NULL) (*env)->ReleaseStringUTFChars(env, path, name);
        ++bpf_fd_reported;
    }
    return (jint)fd;
}

static jboolean bpf_stub_find(JNIEnv* env, jobject self, jint fd,
                              jbyteArray key, jbyteArray value) {
    (void)env; (void)self; (void)fd; (void)key; (void)value;
    return JNI_FALSE;  // empty map: BpfMap.getValue() returns null
}

static void bpf_stub_write(JNIEnv* env, jobject self, jint fd, jbyteArray key,
                           jbyteArray value, jint flags) {
    (void)env; (void)self; (void)fd; (void)key; (void)value; (void)flags;
    // Accepted and discarded: initBpfMaps() writes two configuration entries.
}

static jboolean bpf_stub_next(JNIEnv* env, jobject self, jint fd,
                              jbyteArray key, jbyteArray next) {
    (void)env; (void)self; (void)fd; (void)key; (void)next;
    return JNI_FALSE;  // no next key: iteration ends
}

static jboolean bpf_stub_delete(JNIEnv* env, jobject self, jint fd, jbyteArray key) {
    (void)env; (void)self; (void)fd; (void)key;
    return JNI_FALSE;  // nothing to delete in an empty map
}

static void bpf_stub_rcu(JNIEnv* env, jclass self) {
    (void)env; (void)self;
}

static const JNINativeMethod BPF_MAP_STUBS[] = {
    {"nativeBpfFdGet", "(Ljava/lang/String;III)I", (void*)bpf_stub_fd_get},
    {"nativeFindMapEntry", "(I[B[B)Z", (void*)bpf_stub_find},
    {"nativeWriteToMapEntry", "(I[B[BI)V", (void*)bpf_stub_write},
    {"nativeGetNextMapKey", "(I[B[B)Z", (void*)bpf_stub_next},
    {"nativeDeleteMapEntry", "(I[B)Z", (void*)bpf_stub_delete},
    {"nativeSynchronizeKernelRCU", "()V", (void*)bpf_stub_rcu},
};

JNIEXPORT jint JNICALL Java_io_a10vm_guest_A15Boot_installBpfMapBridge(
        JNIEnv* env, jclass self, jclass type) {
    (void)self;
    if (env == NULL || type == NULL) {
        note("a15_bpf_native=no_class\n");
        return -1;
    }
    jint status = (*env)->RegisterNatives(env, type, BPF_MAP_STUBS,
            (jint)(sizeof(BPF_MAP_STUBS) / sizeof(BPF_MAP_STUBS[0])));
    note("a15_bpf_native=%d methods=%d\n", (int)status,
         (int)(sizeof(BPF_MAP_STUBS) / sizeof(BPF_MAP_STUBS[0])));
    return status;
}

/*
 * Round 78: NetworkStatsFactory's native reads are the last libnetworkstats
 * entry point on the boot path.
 *
 * Real-device evidence (instance 1793235515690, 2026-10-29 08:58): B has passed
 * every Start<X> of startOtherServices and is inside its final
 * OnBootPhase_550 loop. NetworkStatsServiceInitializer.onBootPhase(550) calls
 * NetworkStatsService.systemReady() -> bootstrapStatsLocked() ->
 * NetworkStatsFactory.readNetworkStatsDetail() ->
 * NetworkStatsFactory$Dependencies.getNetworkStatsDetail() ->
 * nativeReadNetworkStatsDetail(). The image's libnetworkstats.so opens
 * /sys/fs/bpf/netd_shared with bpf(BPF_OBJ_GET); this process has no kernel
 * BPF, so the open fails and the library aborts from its map-info helper:
 *
 *   SystemServerTiming: OnBootPhase_550_...NetworkStatsServiceInitializer
 *   a15_bpf cmd=7 ret=-1 fd=<pathname low bits>
 *   A9VM abort caller=liba15vm-image-f0851e5c15136be6ba73.so+0x2376c
 *     (= apex/com.android.tethering/lib64/libnetworkstats.so)
 *   Desktop server exited: code=134
 *
 * The JNI boundary, not libc, is where this container can answer correctly:
 * the two natives below are the only functions in NetworkStatsFactory that reach
 * libnetworkstats. Descriptors are read from the image's own
 * service-connectivity.jar with dexdump (classes.dex):
 *
 *   nativeReadNetworkStatsDetail (Landroid/net/NetworkStats;)I   (public static native)
 *   nativeReadNetworkStatsDev    (Landroid/net/NetworkStats;)I   (public static native)
 *
 * Bytecode of NetworkStatsFactory$Dependencies.getNetworkStatsDetail (dex
 * 0x0c8a90): it constructs an empty NetworkStats, calls
 * nativeReadNetworkStatsDetail(stats), and only throws
 * IOException("Failed to parse network stats") when the return value is non-zero.
 * getNetworkStatsDev has the same shape. Returning 0 therefore yields the honest
 * container state: an empty NetworkStats, exactly what the already-shutdown
 * A15BpfMaps / BpfMap bridge reports for the same missing kernel maps.
 *
 * Consequence (also in STATUS.md): per-UID / per-iface traffic counters read as
 * empty. Boot, SystemUI and the desktop do not require them. This is not a
 * placeholder for a future BPF implementation; the host app domain cannot open
 * the kernel maps (SELinux EACCES/EPERM), which is why netd itself is an endpoint.
 *
 * Ordering: NetworkStatsFactory.<clinit> calls
 * System.loadLibrary("service-connectivity"), and the library is already loaded
 * by the BpfMap bridge just above, so its JNI_OnLoad has registered the real
 * implementations before this RegisterNatives replaces them. The class is not
 * initialized until NetworkStatsService starts, so no call can race the stub.
 */
static int netstats_stub_reported = 0;

static jint netstats_stub_read_detail(JNIEnv* env, jclass clazz, jobject stats) {
    (void)env; (void)clazz; (void)stats;
    if (netstats_stub_reported++ == 0) note("a15_netstats_stub=readDetail\n");
    return 0;  // empty NetworkStats; caller only checks zero/non-zero
}

static jint netstats_stub_read_dev(JNIEnv* env, jclass clazz, jobject stats) {
    (void)env; (void)clazz; (void)stats;
    if (netstats_stub_reported++ == 0) note("a15_netstats_stub=readDev\n");
    return 0;
}

static const JNINativeMethod NETSTATS_STUBS[] = {
    {"nativeReadNetworkStatsDetail", "(Landroid/net/NetworkStats;)I",
     (void*)netstats_stub_read_detail},
    {"nativeReadNetworkStatsDev", "(Landroid/net/NetworkStats;)I",
     (void*)netstats_stub_read_dev},
};

JNIEXPORT jint JNICALL Java_io_a10vm_guest_A15Boot_installNetworkStatsBridge(
        JNIEnv* env, jclass self, jclass type) {
    (void)self;
    if (env == NULL || type == NULL) {
        note("a15_netstats_native=no_class\n");
        return -1;
    }
    jint status = (*env)->RegisterNatives(env, type, NETSTATS_STUBS,
            (jint)(sizeof(NETSTATS_STUBS) / sizeof(NETSTATS_STUBS[0])));
    note("a15_netstats_native=%d methods=%d\n", (int)status,
         (int)(sizeof(NETSTATS_STUBS) / sizeof(NETSTATS_STUBS[0])));
    return status;
}

/*
 * Round 80: NetworkStatsService's own natives — libnetworkstats' second entry.
 *
 * Round 78 replaced NetworkStatsFactory's two native reads
 * (nativeReadNetworkStatsDetail / nativeReadNetworkStatsDev) and the one-shot
 * abort at libnetworkstats+0x2376c disappeared (instance 1793268872142). The
 * same library aborts again from a different offset:
 *
 *   a15_bpf cmd=7 ret=-1 fd=<pathname low bits>
 *   A9VM abort caller=liba15vm-image-f0851e5c15136be6ba73.so+0x1cf70
 *     (= apex/com.android.tethering/lib64/libnetworkstats.so)
 *   A9VM bdev crash sig=6 ... (instance 1793276245028, 2026-10-29 20:23)
 *
 * A fresh dexdump of the image's own service-connectivity.jar (classes.dex,
 * _bin/a15/scratch/svcconn2/) shows a *second* class in the same library's JNI
 * surface: android.net.connectivity.com.android.server.net.NetworkStatsService
 * declares five natives, and none of them was bridged:
 *
 *   nativeGetIfaceStat       (Ljava/lang/String;)Landroid/net/NetworkStats$Entry;
 *   nativeGetTotalStat       ()Landroid/net/NetworkStats$Entry;
 *   nativeGetUidStat         (I)Landroid/net/NetworkStats$Entry;
 *   nativeInitNetworkTracing ()V
 *   nativeRegisterIface      (Ljava/lang/String;)V
 *
 * Both NetworkStatsFactory and NetworkStatsService load the same image library
 * (System.loadLibrary("service-connectivity"), <clinit> of each class), whose
 * BPF map opens are EACCES in this container — these five are the remaining ways
 * into libnetworkstats from the framework. They are the eBPF per-UID tracing
 * surface, i.e. exactly the state A15BpfMaps / BpfMap already report as empty.
 *
 * Answer contract, read from the same dexdump:
 *   - getIfaceStatsInternal / getTotalStatsInternal tolerate a null Entry
 *     (if-nez right after the call, then "return-object null"), and
 *   - getUidStatsInternal returns the Entry straight to its caller.
 * We hand back a real zeroed NetworkStats$Entry instead of null so that no caller
 * can dereference null. NetworkStats$Entry.<init>()V exists in
 * framework-connectivity-t.jar (dexdump: PUBLIC CONSTRUCTOR; it fills iface/uid
 * with IFACE_ALL/-1). uid/iface are then overwritten with the caller's values so
 * the entry keys match what the framework asked for.
 * nativeInitNetworkTracing / nativeRegisterIface are the tracing-hook setup: no
 * kernel maps exist here, so they stay no-ops and only record that they ran.
 *
 * Consequence (also in STATUS.md): per-UID / per-iface counters read as zero.
 * Boot, SystemUI and the desktop do not consume them.
 */
static int netstats_service_reported = 0;

static jobject netstats_service_entry(JNIEnv* env, jint uid, jstring iface) {
    jclass cls = (*env)->FindClass(env, "android/net/NetworkStats$Entry");
    if (cls == NULL) {
        (*env)->ExceptionClear(env);
        if (netstats_service_reported++ == 0) note("a15_netstats_stub=no_entry_class\n");
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
    if (ctor == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, cls);
        if (netstats_service_reported++ == 0) note("a15_netstats_stub=no_entry_ctor\n");
        return NULL;
    }
    jobject entry = (*env)->NewObject(env, cls, ctor);
    if (entry != NULL && uid >= 0) {
        jfieldID field = (*env)->GetFieldID(env, cls, "uid", "I");
        if (field != NULL) (*env)->SetIntField(env, entry, field, uid);
    }
    if (entry != NULL && iface != NULL) {
        jfieldID field = (*env)->GetFieldID(env, cls, "iface", "Ljava/lang/String;");
        if (field != NULL) (*env)->SetObjectField(env, entry, field, iface);
    }
    (*env)->DeleteLocalRef(env, cls);
    return entry;
}

static int netstats_iface_reported = 0;
static int netstats_total_reported = 0;
static int netstats_uid_reported = 0;
static int netstats_register_reported = 0;
static int netstats_tracing_reported = 0;

static jobject netstats_stub_get_iface_stat(JNIEnv* env, jclass clazz, jstring iface) {
    (void)clazz;
    if (netstats_iface_reported++ == 0) note("a15_netstats_stub=ifaceStat\n");
    return netstats_service_entry(env, -1, iface);
}

static jobject netstats_stub_get_total_stat(JNIEnv* env, jclass clazz) {
    (void)clazz;
    if (netstats_total_reported++ == 0) note("a15_netstats_stub=totalStat\n");
    return netstats_service_entry(env, -1, NULL);
}

static jobject netstats_stub_get_uid_stat(JNIEnv* env, jclass clazz, jint uid) {
    (void)clazz;
    if (netstats_uid_reported++ == 0) note("a15_netstats_stub=uidStat\n");
    return netstats_service_entry(env, uid, NULL);
}

static void netstats_stub_register_iface(JNIEnv* env, jclass clazz, jstring iface) {
    (void)env; (void)clazz; (void)iface;
    if (netstats_register_reported++ == 0) note("a15_netstats_stub=registerIface\n");
}

static void netstats_stub_init_tracing(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    if (netstats_tracing_reported++ == 0) note("a15_netstats_stub=initNetworkTracing\n");
}

static const JNINativeMethod NETSTATS_SERVICE_STUBS[] = {
    {"nativeGetIfaceStat", "(Ljava/lang/String;)Landroid/net/NetworkStats$Entry;",
     (void*)netstats_stub_get_iface_stat},
    {"nativeGetTotalStat", "()Landroid/net/NetworkStats$Entry;",
     (void*)netstats_stub_get_total_stat},
    {"nativeGetUidStat", "(I)Landroid/net/NetworkStats$Entry;",
     (void*)netstats_stub_get_uid_stat},
    {"nativeInitNetworkTracing", "()V", (void*)netstats_stub_init_tracing},
    {"nativeRegisterIface", "(Ljava/lang/String;)V", (void*)netstats_stub_register_iface},
};

JNIEXPORT jint JNICALL Java_io_a10vm_guest_A15Boot_installNetworkStatsServiceBridge(
        JNIEnv* env, jclass self, jclass type) {
    (void)self;
    if (env == NULL || type == NULL) {
        note("a15_netstats_service_native=no_class\n");
        return -1;
    }
    jint status = (*env)->RegisterNatives(env, type, NETSTATS_SERVICE_STUBS,
            (jint)(sizeof(NETSTATS_SERVICE_STUBS) / sizeof(NETSTATS_SERVICE_STUBS[0])));
    note("a15_netstats_service_native=%d methods=%d\n", (int)status,
         (int)(sizeof(NETSTATS_SERVICE_STUBS) / sizeof(NETSTATS_SERVICE_STUBS[0])));
    return status;
}

/*
 * Round 82: app processes need a binder thread pool.
 *
 * app_process calls AndroidRuntime::start -> ProcessState::startThreadPool(); that
 * is what gives every Android app process its "binder:<pid>_N" threads. This
 * runtime starts app children on dalvikvm64, so nothing called it: /proc/<pid>/task
 * of a guest app child had 7 threads and no binder:* thread (real device
 * 2026-10-29, instance 1793282039904). An app without a pool can *send*
 * transactions but never receive one, so ActivityManager's oneway
 * IApplicationThread.bindApplication was never dispatched, handleBindApplication
 * never ran (the child log has no "Configuring clns-... for other apk <apk>" line
 * and no finishAttachApplication), and AMS declared every healthy child
 * "failed to complete startup" and killed it every ~10 s.
 *
 * sp<ProcessState> is a non-trivial return value, so AOSP returns it through the
 * hidden result pointer (AAPCS64 x8). A plain 16-byte struct would be returned in
 * x0/x1 instead, so the result type here is padded past 16 bytes to force the
 * memory return the callee actually uses.
 */
typedef struct { void* pointer; void* reference; unsigned char pad[16]; } A15ProcessStateSp;
typedef A15ProcessStateSp (*A15ProcessStateSelf)(void);
typedef void (*A15ProcessStateStartThreadPool)(void*);

static void* a15_binder_symbol(const char* name) {
    void* symbol = dlsym(RTLD_DEFAULT, name);
    if (symbol != NULL) return symbol;
    void* binder = dlopen("libbinder.so", RTLD_NOW | RTLD_NOLOAD);
    if (binder == NULL) binder = dlopen("libbinder.so", RTLD_NOW);
    if (binder == NULL) return NULL;
    return dlsym(binder, name);
}

JNIEXPORT jint JNICALL Java_io_a10vm_guest_A15GuestApplication_a15StartBinderThreadPool(
        JNIEnv* env, jclass self) {
    (void)env; (void)self;
    A15ProcessStateSelf processState = (A15ProcessStateSelf)a15_binder_symbol(
            "_ZN7android12ProcessState4selfEv");
    A15ProcessStateStartThreadPool startThreadPool = (A15ProcessStateStartThreadPool)
            a15_binder_symbol("_ZN7android12ProcessState15startThreadPoolEv");
    if (processState == NULL || startThreadPool == NULL) {
        note("a15_binder_pool=no_symbol self=%d start=%d\n",
             processState != NULL ? 1 : 0, startThreadPool != NULL ? 1 : 0);
        return 0;
    }
    A15ProcessStateSp state;
    memset(&state, 0, sizeof(state));
    state = processState();
    if (state.pointer == NULL) {
        note("a15_binder_pool=no_process_state\n");
        return 0;
    }
    startThreadPool(state.pointer);
    note("a15_binder_pool=started\n");
    return 1;
}

/*
 * Round 84: the guest uid must be visible to the framework's process table.
 *
 * Android 15 added a liveness check on the "provider already runs" path of
 * ContentProviderHelper.getContentProviderImpl (image services.jar, classes.dex
 * method #20, dex 0x3344ac):
 *
 *   if (runUpdate(provider.proc) != 0) {
 *       if (verifiedAdj == setAdj || !isProcessAliveLocked(provider.proc)) {
 *           Slog.wtf("Existing provider " + provider.name + " is crashing; detaching " + r);
 *           ...
 *       }
 *   }
 *
 * and isProcessAliveLocked (method #31, dex 0x335bd0) ends with:
 *
 *   return (Process.getUidForPid(pid) == proc.uid);
 *
 * ProcessRecord.uid is the GUEST uid (the settings provider is installed into
 * the "system" ProcessRecord, uid 1000 — image installSystemProviders looks up
 * ProcessNames.get("system", 1000)). The kernel, however, reports the host app
 * uid for every process in this container (/proc/<pid> stat uid = 10207). So
 * the settings provider looked dead on the first getContentProvider, every app
 * was detached from it, and the boot ended with:
 *
 *   ContentProviderHelper: Existing provider
 *     com.android.providers.settings/.SettingsProvider is crashing;
 *   detaching ProcessRecord{... com.android.systemui/u0a61}
 *   ActivityManager: Timeout waiting for provider com.android.providers.settings/1000
 *   ActivityThread: Failed to find provider info for settings
 * (real device 2026-10-30, instance 1793290715327, line 19409 of
 * vm/framework-server.log; previous instance 1793286290828 line 29090.)
 *
 * Injection point: Process.getUidForPid is NOT native in A15 anymore — it is
 * Java (image framework.jar classes3.dex 0x28d2cc) that calls
 *
 *   readProcLines("/proc/" + pid + "/status", new String[] {"Uid:"}, values)
 *
 * and readProcLines is PUBLIC STATIC FINAL NATIVE
 * (Ljava/lang/String;[Ljava/lang/String;[J)V (classes3.dex method #51). A
 * RegisterNatives on getUidForPid therefore fails with
 *   NoSuchMethodError: no native method "...getUidForPid(I)I"
 * which is what the first attempt of this round hit. readProcLines is the real
 * native entry point; the three callers in the image's framework all request a
 * single line-prefix field (getParentPid → "PPid:", getThreadGroupLeader →
 * "Tgid:", getUidForPid → "Uid:"), so a faithful generic parser is enough for
 * everything except the "Uid:" query, which is answered from the guest process
 * table instead of the kernel.
 *
 * The table is filled at spawn time: A15Zygote already parses --setuid= for
 * every app child, and A15AppLaunch records (pid, uid) here before the child can
 * be asked for a provider. Self is answered from libc's existing
 * A10VM_GUEST_UID getuid() override (GuestIdentity.cpp), so the system process
 * (uid 1000) needs no table entry. No environment parsing, no /proc status
 * rewrite, no libc change.
 */
#define PROCESS_PID_SLOTS 4096
static int process_pid_number[PROCESS_PID_SLOTS];
static int process_pid_uid[PROCESS_PID_SLOTS];
static int process_pid_next;
static int process_pid_reported;
static pthread_mutex_t process_pid_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT void JNICALL Java_io_a10vm_guest_A15Boot_noteGuestProcess(
        JNIEnv* env, jclass self, jint pid, jint uid) {
    (void)env; (void)self;
    if (pid <= 0) return;
    pthread_mutex_lock(&process_pid_lock);
    int slot = -1;
    for (int i = 0; i < PROCESS_PID_SLOTS; ++i) {
        if (process_pid_number[i] == pid) { slot = i; break; }
    }
    if (slot < 0) {
        slot = process_pid_next;
        process_pid_next = (process_pid_next + 1) % PROCESS_PID_SLOTS;
    }
    process_pid_number[slot] = pid;
    process_pid_uid[slot] = uid;
    pthread_mutex_unlock(&process_pid_lock);
}

JNIEXPORT void JNICALL Java_io_a10vm_guest_A15Boot_forgetGuestProcess(
        JNIEnv* env, jclass self, jint pid) {
    (void)env; (void)self;
    if (pid <= 0) return;
    pthread_mutex_lock(&process_pid_lock);
    for (int i = 0; i < PROCESS_PID_SLOTS; ++i) {
        if (process_pid_number[i] == pid) {
            process_pid_number[i] = 0;
            process_pid_uid[i] = 0;
        }
    }
    pthread_mutex_unlock(&process_pid_lock);
}

static jint process_guest_uid(jint pid) {
    if (pid <= 0) return -1;
    if (pid == (jint)getpid()) return (jint)getuid();
    jint uid = -1;
    pthread_mutex_lock(&process_pid_lock);
    for (int i = 0; i < PROCESS_PID_SLOTS; ++i) {
        if (process_pid_number[i] == pid) { uid = process_pid_uid[i]; break; }
    }
    pthread_mutex_unlock(&process_pid_lock);
    if (uid >= 0 && process_pid_reported < 6) {
        ++process_pid_reported;
        note("a15_process_uid_lookup=%d:%d\n", (int)pid, (int)uid);
    }
    return uid;
}

/* "/proc/<pid>/status" with reqFields[0] == "Uid:" -> guest uid answer. */
static int process_status_uid_query(JNIEnv* env, const char* path,
                                    jobjectArray reqFields, unsigned long* pid) {
    if (strncmp(path, "/proc/", 6) != 0) return 0;
    const char* cursor = path + 6;
    char* end = NULL;
    unsigned long value = strtoul(cursor, &end, 10);
    if (end == cursor || value == 0 || value > 0x7fffffffUL) return 0;
    if (strcmp(end, "/status") != 0) return 0;
    jstring field = (jstring)(*env)->GetObjectArrayElement(env, reqFields, 0);
    if (field == NULL) return 0;
    const char* text = (*env)->GetStringUTFChars(env, field, NULL);
    int match = text != NULL && strcmp(text, "Uid:") == 0;
    if (text != NULL) (*env)->ReleaseStringUTFChars(env, field, text);
    (*env)->DeleteLocalRef(env, field);
    if (!match) return 0;
    *pid = value;
    return 1;
}

/*
 * The generic branch of libcore's readProcLines: for each line, for each
 * requested field, parse the integer that follows the line-leading field name.
 * Callers pre-fill the output array (with -1); fields that never match are left
 * untouched, exactly like the original.
 */
static void process_read_proc_lines(JNIEnv* env, jstring path,
                                    jobjectArray reqFields, jlongArray outFields) {
    const char* pathChars = (*env)->GetStringUTFChars(env, path, NULL);
    if (pathChars == NULL) return;
    jsize count = (*env)->GetArrayLength(env, reqFields);
    jsize outCount = (*env)->GetArrayLength(env, outFields);
    if (count > outCount) count = outCount;
    char** fields = count > 0 ? (char**)calloc((size_t)count, sizeof(char*)) : NULL;
    size_t* lengths = count > 0 ? (size_t*)calloc((size_t)count, sizeof(size_t)) : NULL;
    if (count > 0 && (fields == NULL || lengths == NULL)) {
        free(fields);
        free(lengths);
        (*env)->ReleaseStringUTFChars(env, path, pathChars);
        return;
    }
    for (jsize i = 0; i < count; ++i) {
        jstring field = (jstring)(*env)->GetObjectArrayElement(env, reqFields, i);
        if (field == NULL) continue;
        const char* text = (*env)->GetStringUTFChars(env, field, NULL);
        if (text != NULL) {
            size_t length = strlen(text);
            fields[i] = (char*)malloc(length + 1);
            if (fields[i] != NULL) {
                memcpy(fields[i], text, length + 1);
                lengths[i] = length;
            }
            (*env)->ReleaseStringUTFChars(env, field, text);
        }
        (*env)->DeleteLocalRef(env, field);
    }
    FILE* file = fopen(pathChars, "r");
    if (file != NULL) {
        char line[512];
        while (fgets(line, sizeof(line), file) != NULL) {
            for (jsize i = 0; i < count; ++i) {
                if (fields[i] == NULL || lengths[i] == 0) continue;
                if (strncmp(line, fields[i], lengths[i]) != 0) continue;
                char* end = NULL;
                long long value = strtoll(line + lengths[i], &end, 10);
                if (end == line + lengths[i]) value = -1;
                jlong output = (jlong)value;
                (*env)->SetLongArrayRegion(env, outFields, i, 1, &output);
            }
        }
        fclose(file);
    }
    for (jsize i = 0; i < count; ++i) free(fields[i]);
    free(fields);
    free(lengths);
    (*env)->ReleaseStringUTFChars(env, path, pathChars);
}

static void a15_process_read_proc_lines(JNIEnv* env, jclass self, jstring path,
                                        jobjectArray reqFields, jlongArray outFields) {
    (void)self;
    if (env == NULL || path == NULL || reqFields == NULL || outFields == NULL) return;
    if ((*env)->GetArrayLength(env, reqFields) >= 1 &&
            (*env)->GetArrayLength(env, outFields) >= 1) {
        const char* chars = (*env)->GetStringUTFChars(env, path, NULL);
        if (chars != NULL) {
            unsigned long pid = 0;
            int query = process_status_uid_query(env, chars, reqFields, &pid);
            (*env)->ReleaseStringUTFChars(env, path, chars);
            if (query) {
                jint uid = process_guest_uid((jint)pid);
                if (uid >= 0) {
                    jlong value = (jlong)uid;
                    (*env)->SetLongArrayRegion(env, outFields, 0, 1, &value);
                    return;
                }
            }
        }
    }
    process_read_proc_lines(env, path, reqFields, outFields);
}

static const JNINativeMethod PROCESS_LINES_STUBS[] = {
    {"readProcLines", "(Ljava/lang/String;[Ljava/lang/String;[J)V",
     (void*)a15_process_read_proc_lines},
};

JNIEXPORT jint JNICALL Java_io_a10vm_guest_A15Boot_installProcessUidBridge(
        JNIEnv* env, jclass self, jclass type) {
    (void)self;
    if (env == NULL || type == NULL) {
        note("a15_process_uid_native=no_class\n");
        return -1;
    }
    jint status = (*env)->RegisterNatives(env, type, PROCESS_LINES_STUBS,
            (jint)(sizeof(PROCESS_LINES_STUBS) / sizeof(PROCESS_LINES_STUBS[0])));
    note("a15_process_uid_native=%d methods=%d\n", (int)status,
         (int)(sizeof(PROCESS_LINES_STUBS) / sizeof(PROCESS_LINES_STUBS[0])));
    return status;
}

