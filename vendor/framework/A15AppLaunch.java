package io.a10vm.guest;

import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Launches one Android 15 guest application process for {@link A15Zygote}.
 *
 * <p>Android 9/10 fork app children with {@code app_process --application
 * <project wrapper> <sdk> <main> <args>} (see {@code AppLaunch}). That shape is
 * measured NOT to work on this Android 15 runtime: its {@code app_process} only
 * resolves a main class through the boot image, so a class that lives in the
 * project dex is never found —
 * {@code NoClassDefFoundError: Class not found using the boot class loader}
 * (see {@code _audit/coding-log/2026-10-27-CL-A15-app_process主类语义与镜像image不一致.md}).
 *
 * <p>The server already runs on {@code dalvikvm64} with an explicit
 * {@code -Ximage}/{@code -Xgc}/{@code -Xbootclasspath} argv; app children use the
 * same argv and the project wrapper ({@link A15GuestApplication}) as entry, which
 * installs the JNI table and then invokes the requested main class.
 */
final class A15AppLaunch {
    static Process start(List<String> arguments, int sequence) throws Exception {
        String uid = null, gid = null, sdk = "35", name = "guest-application", data = null;
        int entry = -1;
        boolean childZygote = false;
        for (int i = 0; i < arguments.size(); ++i) {
            String argument = arguments.get(i);
            if (argument.startsWith("--setuid=")) uid = argument.substring(9);
            else if (argument.startsWith("--setgid=")) gid = argument.substring(9);
            else if (argument.startsWith("--target-sdk-version=")) sdk = argument.substring(21);
            else if (argument.startsWith("--nice-name=")) name = argument.substring(12);
            else if (argument.startsWith("--app-data-dir=")) data = argument.substring(15);
            else if (argument.equals("--start-child-zygote")) childZygote = true;
            else if (argument.equals("--invoke-with")) ++i;   // wrapper path, unsupported
            else if (argument.equals("--")) { }               // argv separator
            else if (!argument.startsWith("--")) { entry = i; break; }
        }
        // W-track (round 90): a child-zygote request is a *process*, not a fork.
        // ZygoteProcess.startChildZygote (image framework classes3.dex
        // 0x2ade1c-0x2adf2c) builds
        //   --zygote-socket=<prefix>/<uuid>  --abi-list=<abi>
        //   --uid-range-start=<first>        --uid-range-end=<last>
        // plus --start-child-zygote and the process class, and expects that process
        // to answer the ordinary zygote wire protocol on that ABSTRACT socket so it
        // can fork isolated renderers. This runtime has no fork, so the request is
        // served by A15GuestChildZygote: it binds the socket and launches each
        // renderer as a fresh dalvikvm64 process, which is the A9/A10 shape
        // (GuestChildZygote). Previously this branch threw
        // "a15_child_zygote=webview_zygote", i.e. WebView never had a renderer.
        if (entry < 0)
            throw new IllegalArgumentException("invalid a15 application launch name=" + name);
        if (!childZygote && (uid == null || gid == null
                || Integer.parseInt(uid) < 0 || Integer.parseInt(gid) < 0))
            throw new IllegalArgumentException("invalid a15 application launch name=" + name);

        String linker = System.getenv("A10VM_EXEC_LINKER");
        String dalvikvm = System.getenv("A10VM_DALVIKVM64");
        String libraryPath = System.getenv("A10VM_LIBRARY_PATH");
        String bootClassPath = System.getenv("BOOTCLASSPATH");
        if (linker == null || dalvikvm == null || libraryPath == null)
            throw new IllegalStateException("a15 launcher environment is incomplete");
        File state = new File(libraryPath).getParentFile();
        String classPath = new File(state, "framework-check.dex").getPath();
        String wrapper = System.getenv().getOrDefault("A10VM_GUEST_APPLICATION_CLASS",
                "io.a10vm.guest.A15GuestApplication");

        List<String> command = new ArrayList<>();
        command.add(linker);
        command.add(dalvikvm);
        // Same ART argv as the server (FrameworkSession.dalvikArguments): the image
        // boot image has to be named explicitly, and the boot.oat header of this
        // image records concurrent-copying=false, which the arm64 default CC
        // collector rejects.
        command.add("-Ximage:/system/framework/boot.art");
        command.add("-Xgc:CMC");
        if (bootClassPath != null && !bootClassPath.isEmpty())
            command.add("-Xbootclasspath:" + bootClassPath);
        command.add("-Djava.library.path=" + libraryPath);
        command.add("-cp");
        command.add(classPath);
        command.add(wrapper);
        command.add(sdk);
        if (childZygote) {
            // Pass the original argv through: the endpoint reads the socket name, the
            // abi list and the uid range from it, and logs them once, so the next
            // round can verify the shape from the device log instead of the bytecode.
            command.add("io.a10vm.guest.A15GuestChildZygote");
            command.addAll(arguments);
            System.out.println("a15_child_zygote_launch=" + name + " args=" + arguments.size());
        } else {
            command.add(arguments.get(entry));
            command.addAll(arguments.subList(entry + 1, arguments.size()));
        }

        long started = SystemClock.elapsedRealtimeNanos();
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(new File(System.getenv("A10VM_ROOTFS")))
                .redirectErrorStream(true)
                .redirectOutput(logFile(state, sequence));
        Map<String, String> environment = builder.environment();
        environment.put("A10VM_GUEST_UID", uid == null ? "0" : uid);
        environment.put("A10VM_GUEST_GID", gid == null ? "0" : gid);
        environment.put("CLASSPATH", classPath);
        environment.put("A10VM_APPLICATION_PARENT_PID", Integer.toString(android.os.Process.myPid()));
        environment.put("A10VM_APPLICATION_START_NS", Long.toString(started));
        // r110 的临时取证开关 `LD_DEBUG=dynamic` 已在 r124 删除：它让 A15 linker
        // 为每个依赖打印 dynamic entry / soinfo / zip 探测行，MT 启动因此产生 10+ MB
        // 日志、2.7M minor faults、主线程 8.6s 卡在 linker，随后 ANR/137。
        // WebView 取证已结束（W 轨关闭），这里必须保持安静。
        environment.remove("A10VM_DEX_CACHE");
        if (!"0".equals(System.getenv("A10VM_DEX_CACHE_ENABLED"))) {
            try {
                String cache = AppDexCache.directory(data, name, Integer.parseInt(uid));
                if (cache != null) environment.put("A10VM_DEX_CACHE", cache);
            } catch (Throwable error) {
                System.out.println("a15_application_dex_cache_unavailable=" + name + ":"
                        + error.getClass().getSimpleName());
            }
        }
        Process child = builder.start();
        int pid = pid(child);
        int guestUid = uid == null ? 0 : Integer.parseInt(uid);
        // Publish the child's guest uid before any provider can be requested:
        // ContentProviderHelper.isProcessAliveLocked() compares it with
        // Process.getUidForPid(), which the readProcLines bridge answers from
        // this table instead of the kernel's host app uid.
        A15Boot.noteGuestProcess(pid, guestUid);
        System.out.println("a15_application_launch=" + pid + ":" + name
                + " uid=" + guestUid
                + " spawn_ms=" + (SystemClock.elapsedRealtimeNanos() - started) / 1000000);
        watch(name, pid, child);
        return child;
    }

    /**
     * Observation only: records how the child ends. Round 79-81 also sent SIGQUIT to
     * a child that was still alive after five seconds to force an ART thread dump;
     * round 82 removes that (Z-track cleanup) because the dump perturbed the startup
     * it measured, and with the binder thread pool fixed the children no longer need
     * to be sampled to prove they are alive.
     */
    private static void watch(String name, int pid, Process child) {
        Thread thread = new Thread(() -> {
            try {
                int code = child.waitFor();
                A15Boot.forgetGuestProcess(pid);
                System.out.println("a15_application_exit=" + pid + ":" + name + " code=" + code);
            } catch (Throwable error) {
                System.out.println("a15_application_watch=" + pid + ":" + name + ":" + error);
            }
        }, "a15-application-watch-" + pid);
        thread.setDaemon(true);
        thread.start();
    }

    private static File logFile(File state, int sequence) {
        return new File(state, "application-" + android.os.Process.myPid() + "-" + sequence + ".log");
    }

    static int pid(Process child) throws Exception {
        Field field = child.getClass().getDeclaredField("pid");
        field.setAccessible(true);
        return ((Number) field.get(child)).intValue();
    }
}
