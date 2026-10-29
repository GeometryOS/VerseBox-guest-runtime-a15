package io.a10vm.guest;

import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.lang.reflect.Method;

public final class DesktopCheck {
    public static void main(String[] args) throws Exception {
        // Android 15 runs this check on dalvikvm64, not app_process64: A15's app_process
        // resolves the main class only through the boot class loader, so a class that lives
        // in CLASSPATH (framework-check.dex) is never found and the log stays empty
        // (same root cause as the server; see docs/android15-port/12 §3.1). dalvikvm64 does
        // not run AndroidRuntime::startReg either, so the JNI table
        // (SystemProperties/ServiceManager/Build natives) must be installed explicitly by
        // the same project bridge the server uses. The host passes
        // -Da10vm.jni.register=1 only for this shape, so A9/A10 keep their app_process argv.
        if ("1".equals(System.getProperty("a10vm.jni.register")))
            System.loadLibrary("a15vm-jni-register");
        // Print a marker before anything that can block: the check loops up to 180 s and a
        // session that dies earlier (because the server died) would otherwise leave
        // framework-client.log 0 bytes, which is indistinguishable from "main class not
        // found". Judgement for the F track is this line, not the final ready marker.
        System.out.println("desktop_check_started=1");
        System.out.flush();
        // A15 reuses the A10 desktop check verbatim: the service set, the user-unlock
        // probe and the Launcher3 home resolution are the same surfaces on API 35.
        // The only A10-specific line was this hard-coded API 29 assertion (A10's
        // DesktopCheck is reached through `app_process64` + CLASSPATH, so it never
        // compares against a different SDK). A15 reaches it with the same argv shape
        // and the guest really is API 35, so accept the SDK the boot entry already
        // verified (`desktop_guest_sdk=35`, printed by A15Boot) instead of claiming 29.
        int sdk = Build.VERSION.SDK_INT;
        if (sdk != 29 && sdk != 35) throw new AssertionError("Wrong guest SDK: " + sdk);
        System.out.println("desktop_check_sdk=" + sdk);
        System.out.flush();
        Method get = Class.forName("android.os.ServiceManager").getMethod("checkService", String.class);
        Method property = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
        Method isUnlocked = Class.forName("android.os.IUserManager").getMethod("isUserUnlocked", int.class);
        Object packages = null;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        // Android 15 changed the AIDL signature from (Intent, String, int, int) to
        // (Intent, String, long, int): verified against the image's framework.jar,
        //   Landroid/content/pm/IPackageManager;->resolveIntent(Landroid/content/Intent;Ljava/lang/String;JI)Landroid/content/pm/ResolveInfo;
        // The old `int.class` lookup therefore threw NoSuchMethodException at runtime
        // (real device 2026-10-28, instance 1793161543465, DesktopCheck.java:44).
        // The reflective invoke also needs Long boxed values below.
        Method resolve = Class.forName("android.content.pm.IPackageManager").getMethod(
                "resolveIntent", Intent.class, String.class, long.class, int.class);
        String[] names = {"activity", "activity_task", "package", "user", "window", "display", "power"};
        boolean[] alive = new boolean[names.length];
        boolean ready;
        String boot;
        boolean unlocked;
        ResolveInfo resolved;
        long deadline = SystemClock.elapsedRealtime() + 180000;
        do {
            ready = true;
            boot = (String) property.invoke(null, "sys.boot_completed");
            unlocked = false;
            resolved = null;
            if ("1".equals(boot)) {
                for (int i = 0; i < names.length; ++i) {
                    IBinder service = (IBinder) get.invoke(null, names[i]);
                    alive[i] = service != null && service.isBinderAlive();
                    ready &= alive[i];
                }
                Object user = binderInterface(get, "user", "android.os.IUserManager");
                packages = binderInterface(get, "package", "android.content.pm.IPackageManager");
                unlocked = user != null && (Boolean) isUnlocked.invoke(user, 0);
                resolved = packages == null ? null : (ResolveInfo) resolve.invoke(packages, home, null, 0L, 0);
            } else ready = false;
            // The host only needs the guest to be up: boot completed, the framework
            // services alive and the user unlocked. WHICH package resolves as HOME is
            // the guest's business (this is a virtual machine, not a virtual desktop):
            // provisioning stub, Launcher3 or a launcher the user installed all count.
            // Gating this on com.android.launcher3 is what kept a booted guest hidden
            // behind the host's "starting" screen and left touch disabled (round 81:
            // home = com.android.provision/DefaultActivity, marker never printed, host
            // waited the full 205 s and never called startInput()).
            if (ready && "1".equals(boot) && unlocked) break;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        for (int i = 0; i < names.length; ++i) {
            IBinder service = (IBinder) get.invoke(null, names[i]);
            alive[i] = service != null && service.isBinderAlive();
            System.out.println("desktop_service_" + names[i] + "=" + (alive[i] ? "1" : "0"));
        }
        System.out.println("desktop_boot_completed=" + boot);
        System.out.println("desktop_user_unlocked=" + unlocked);
        ResolveInfo locked = packages == null ? null : (ResolveInfo) resolve.invoke(packages, home, null, 0xc0000L, 0);
        System.out.println("desktop_home_resolved=" + (resolved == null ? "none" : resolved.activityInfo.packageName));
        System.out.println("desktop_home_direct_boot=" + (locked == null ? "none" : locked.activityInfo.packageName));
        ProcessEvidence.report("desktop");
        if (args.length > 0 && "--dump-surfaceflinger".equals(args[0])) {
            IBinder compositor = (IBinder) get.invoke(null, "SurfaceFlinger");
            if (compositor != null) compositor.dump(java.io.FileDescriptor.out, new String[0]);
        }
        if (!ready || !"1".equals(boot)) throw new AssertionError("Guest desktop services still starting");
        if (!unlocked) throw new AssertionError("Guest user is not unlocked");
        // Show whatever the guest resolved as HOME, best effort: a home that fails to
        // draw must be reported, not turned into "the VM is not booted". The readiness
        // marker below is what ends the host's "starting" state and starts touch.
        if (resolved != null) {
            try {
                DesktopHome.show((IBinder) get.invoke(null, "activity_task"), resolved);
            } catch (Throwable error) {
                System.out.println("desktop_home_show_failed=" + error);
            }
        } else {
            System.out.println("desktop_home_show_failed=no-home-activity");
        }
        if (java.util.Arrays.asList(args).contains("--dump-window"))
            ((IBinder) get.invoke(null, "window")).dump(java.io.FileDescriptor.out, new String[] {"windows"});
        System.out.println("desktop_services_ready=1");
    }

    private static Object binderInterface(Method get, String service, String type) throws Exception {
        return Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, get.invoke(null, service));
    }
}
