package io.a10vm.guest;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class GuestCommand {
    public static void main(String[] args) throws Exception {
        run(args, System.getenv("A10VM_COMMAND_INPUT"));
    }

    static void run(String[] args, String input) throws Exception {
        int result = command(args, input, FileDescriptor.out, FileDescriptor.err);
        System.out.println("guest_command_result=" + result);
        System.out.flush();
        // The host waits for this process to exit (FrameworkSession.runPackageCommand
        // -> Process.waitFor). app_process (A9/A10) exits on its own after main, but
        // Android 15 runs this class through dalvikvm64 + A15GuestApplication, which
        // starts a binder thread pool (a15StartBinderThreadPool). Those threads are
        // not daemons, so the JVM stayed alive after main returned and the host saw
        // 90 s of silence and reported "应用安装超时" even though the command itself
        // had already succeeded:
        //   Success: created install session [1603186381]
        //   guest_command_result=0
        // (real device 2026-10-31 15:59-16:01, instance 1793432154923,
        //  vm/framework-install-create.log, importing 哔哩哔哩 into Android 15).
        System.exit(result == 0 ? 0 : 1);
    }

    static String capture(String[] args, String input) throws Exception {
        File output = File.createTempFile("package-command-", ".log", new File("/data/local/tmp"));
        try {
            int result;
            try (FileOutputStream stream = new FileOutputStream(output)) {
                result = command(args, input, stream.getFD(), stream.getFD());
            }
            String text = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            if (result != 0) throw new IllegalStateException(text.trim());
            return text;
        } finally { output.delete(); }
    }

    private static int command(String[] args, String input, FileDescriptor out, FileDescriptor err) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected service and command");
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("checkService", String.class).invoke(null, args[0]);
        if (service == null) throw new IllegalStateException("Guest service missing: " + args[0]);
        CountDownLatch finished = new CountDownLatch(1);
        int[] result = {-1};
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                result[0] = code;
                finished.countDown();
            }
        };
        try (FileInputStream stream = new FileInputStream(input == null ? "/dev/null" : input)) {
            IBinder.class.getMethod("shellCommand", FileDescriptor.class, FileDescriptor.class,
                    FileDescriptor.class, String[].class, Class.forName("android.os.ShellCallback"),
                    ResultReceiver.class).invoke(service, stream.getFD(), out,
                    err, Arrays.copyOfRange(args, 1, args.length), null, receiver);
            // 30 分钟，而不是 120 秒：install-commit 要把整个 APK 落盘并做 verify dexopt，
            // 哔哩哔哩 226,519,153 B 在这个容器里远不止 120 s。原来的 120 s 让客体自己先把
            // 命令判死，宿主把超时放宽到 900 s 也没用（实例 1793434047234，16:08-16:11）。
            if (!finished.await(30, TimeUnit.MINUTES)) throw new IllegalStateException("Guest command timed out");
        }
        return result[0];
    }
}
