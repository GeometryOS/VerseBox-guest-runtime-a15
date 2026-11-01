package io.a10vm.guest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Android 15 (API 35) guest app-process entry wrapper.
 *
 * <p>Every guest application is started by the project zygote endpoint
 * ({@code A15Zygote} / {@code A15AppLaunch}) as
 *
 * <pre>
 *   ld-android.so dalvikvm64 -Ximage:/system/framework/boot.art -Xgc:CMC \
 *     -Xbootclasspath:... -Djava.library.path=&lt;vm&gt;/lib \
 *     -cp &lt;vm&gt;/framework-check.dex io.a10vm.guest.A15GuestApplication \
 *     &lt;targetSdk&gt; &lt;mainClass&gt; &lt;main args...&gt;
 * </pre>
 *
 * <p>That is the Android 15 equivalent of A9's {@code A9GuestApplication}. The
 * wrapper exists because {@code dalvikvm64} (unlike {@code app_process}) never
 * runs {@code AndroidRuntime::startReg}, so the guest JNI table has to be
 * installed by the project bridge, and because the framework's own
 * {@code RuntimeInit} entry can only be reached from {@code app_process}, which
 * this runtime does not use for app children.
 */
public final class A15GuestApplication {
    /** Started from liba15vm-jni-register.so: ProcessState::self()->startThreadPool(). */
    private static native int a15StartBinderThreadPool();

    public static void main(String[] args) throws Throwable {
        // The same bridge the server loads: its JNI_OnLoad calls
        // android::AndroidRuntime::startReg(JNIEnv*), which fills the JNI table
        // (SystemProperties, Build, ServiceManager, ...) that every framework class
        // below depends on.
        String jni;
        try {
            System.loadLibrary("a15vm-jni-register");
            jni = "ready";
        } catch (Throwable failure) {
            jni = "unavailable:" + failure;
        }
        // Android app processes get their binder thread pool from app_process
        // (AndroidRuntime::start -> ProcessState::startThreadPool). This runtime starts
        // app children on dalvikvm64 instead, so nothing ever called startThreadPool:
        // /proc/<pid>/task showed only 7 threads and NO binder:* thread (real device
        // 2026-10-29 round 81, instance 1793282039904). The consequence was the whole
        // "failed to complete startup" loop: an app can only *send* transactions without
        // a pool, never receive them, so ActivityManager's oneway
        // IApplicationThread.bindApplication was never dispatched, handleBindApplication
        // never ran (no APK classloader line in the child log, no
        // finishAttachApplication), and AMS killed every healthy child at its
        // bind-application timeout.
        String pool;
        try {
            pool = "1".equals("" + a15StartBinderThreadPool()) ? "started" : "unavailable";
        } catch (Throwable failure) {
            pool = "unavailable:" + failure;
        }
        System.out.println("a15_application_binder_pool=" + pool);
        System.out.flush();
        int sdk = Integer.parseInt(args[0]);
        String target = args[1];
        System.out.println("a15_application_entry=1 sdk=" + sdk + " main=" + target + " jni=" + jni);
        System.out.flush();
        // What the framework does for an app process before running its main:
        // RuntimeInit.commonInit() installs the uncaught-exception handlers, the
        // timezone supplier, the logging manager reset and http.agent. It is a
        // protected static on API 35, so reflection + setAccessible is required.
        String init;
        try {
            Class<?> runtimeInit = Class.forName("com.android.internal.os.RuntimeInit");
            Method commonInit = runtimeInit.getDeclaredMethod("commonInit");
            commonInit.setAccessible(true);
            commonInit.invoke(null);
            init = "ok";
        } catch (Throwable failure) {
            init = "unavailable:" + failure;
        }
        System.out.println("a15_application_runtime_init=" + init);
        // Round 115 (W track): the guest has no logd ("logcat: Failed to wait for
        // logd.ready to become true. logd not running?"), and both
        // RuntimeInit.commonInit()'s handler and ART's own uncaught handler report
        // the stack through Slog -> logd. The result is a process that dies with
        // "AndroidRuntime: Shutting down VM" on stderr and NO exception text
        // anywhere (instance 1793503945104: org.chromium.webview_shell exits 133
        // ~2 s after Displayed, dropbox has no data_app_crash, stderr has no
        // FATAL EXCEPTION). Print the throwable to stdout -- which the launcher
        // redirects into the same application-*.log -- before the process dies.
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.out.println("a15_application_uncaught thread=" + thread.getName() + " " + throwable);
            throwable.printStackTrace(System.out);
            System.out.flush();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
        // The MIME database MediaProvider resolves extensions through. Android 15
        // builds it from classpath resources (/res/*.mime.types in framework.jar);
        // when that lookup comes back empty every MediaStore insert with an
        // explicit MIME type is rejected with "Unsupported MIME type" (the
        // screenshot failure). One line says which table the guest ended up with.
        System.out.println("a15_application_mime=" + A15MimeTypes.install());
        System.out.flush();
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getMethod("getRuntime").invoke(null);
            vm.getMethod("setTargetSdkVersion", int.class).invoke(runtime, sdk);
        } catch (Throwable failure) {
            System.out.println("a15_application_target_sdk=" + failure);
        }
        if (args[1].equals("android.webkit.WebViewLibraryLoader$RelroFileCreator")) {
            android.os.Looper.prepareMainLooper();
            Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        }
        System.out.flush();
        // Round 79-81 sampled the main thread here every 250 ms to prove the child was
        // alive; round 82 removes it (Z-track cleanup). It was pure diagnostics and it
        // perturbed the very startup it measured.
        System.out.println("a15_application_main_start=" + target);
        System.out.flush();
        try {
            Method main = Class.forName(target).getDeclaredMethod("main", String[].class);
            main.setAccessible(true);
            main.invoke(null, (Object) Arrays.copyOfRange(args, 2, args.length));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause != null) {
                cause.printStackTrace(System.out);
                System.out.flush();
                throw cause;
            }
            throw failure;
        }
    }

}
