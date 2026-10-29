package io.a10vm.guest;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 15 guest zygote endpoint.
 *
 * <p>Android 15's {@code ZygoteProcess} opens
 * {@code new LocalSocketAddress("zygote", Namespace.RESERVED)} — the filesystem
 * socket {@code /dev/socket/zygote} that init creates on a real device — and
 * sends every app-process request over it. Nothing in this runtime creates that
 * socket, so {@code system_server} failed every {@code startProcess} with:
 *
 * <pre>
 *   ActivityManager: Failure starting process com.android.systemui
 *     Caused by: ZygoteStartFailedEx: Error connecting to zygote
 *     Caused by: java.io.IOException: No such file or directory
 *       at android.os.ZygoteProcess$ZygoteState.connect(ZygoteProcess.java:197)
 * </pre>
 * (real device 2026-10-29, instance 1793270858542).
 *
 * <p>Android 9/10 never ran the image's zygote binary either: {@code A9Boot} and
 * {@code DesktopBoot} start {@link DesktopProcesses}, a Java endpoint that speaks
 * the same wire protocol and forks guest app processes with the project's own
 * launcher. This class is that endpoint for API 35.
 *
 * <p>Wire protocol, read from the image's own {@code framework.jar}
 * ({@code android.os.ZygoteProcess}, dexdump):
 *
 * <pre>
 *   client -> "&lt;argc&gt;\n" then one line per argument
 *   server -> int (pid for a launch, length/status for the queries) + optional payload
 * </pre>
 *
 * The handshake on connect is {@code --query-abi-list} (length-prefixed ASCII
 * list); the launcher also sends {@code --set-api-denylist-exemptions},
 * {@code --hidden-api-log-sampling-rate=}, {@code --usap-pool-enabled=},
 * {@code --get-pid} and the two WebView preload queries.
 *
 * <p>Deliberately NOT a fork of a real zygote: no USAP pool, no preloaded
 * classes, no child-zygote support. Every accepted launch request is executed as
 * a fresh {@code dalvikvm64} process by {@link A15AppLaunch}. A launch request is
 * answered with the child pid and {@code usingWrapper=false}, which is what the
 * framework expects from a zygote that does not wrap processes.
 */
final class A15Zygote {
    private static final String SOCKET = "/dev/socket/zygote";

    private final LocalSocket listener = new LocalSocket();
    private final LocalServerSocket server;
    private final List<Process> children = new ArrayList<>();
    private int sequence;

    private A15Zygote() throws Exception {
        listener.bind(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
        Os.chmod(SOCKET, 0600);
        server = new LocalServerSocket(listener.getFileDescriptor());
    }

    static void start() throws Exception {
        A15Zygote zygote = new A15Zygote();
        Runtime.getRuntime().addShutdownHook(new Thread(zygote::stop, "a15-zygote-stop"));
        new Thread(zygote::accept, "a15-zygote-listener").start();
        System.out.println("a15_zygote_socket=" + SOCKET);
    }

    private void accept() {
        try {
            while (true) {
                LocalSocket client = server.accept();
                new Thread(() -> connection(client), "a15-zygote-connection").start();
            }
        } catch (Exception error) {
            System.err.println("a15_zygote_listener_error=" + error);
        }
    }

    private void connection(LocalSocket socket) {
        try (LocalSocket client = socket) {
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            String line;
            while ((line = input.readLine()) != null) {
                int count = Integer.parseInt(line.trim());
                if (count < 1 || count > 1024)
                    throw new IllegalArgumentException("a15_zygote argc=" + count);
                List<String> arguments = new ArrayList<>();
                for (int i = 0; i < count; ++i) {
                    String argument = input.readLine();
                    if (argument == null)
                        throw new IllegalArgumentException("incomplete a15_zygote arguments");
                    arguments.add(argument);
                }
                if (!answer(arguments, output)) return;
                output.flush();
            }
        } catch (Exception error) {
            System.err.println("a15_zygote_protocol_error=" + error);
        }
    }

    /**
     * Answers one request. Returns false when the connection shouldn't be reused.
     *
     * <p>Every reply shape below is taken from the image's ZygoteProcess bytecode:
     * queries reply {@code int} (+ length-prefixed payload), a preload replies
     * {@code int} status, a launch replies {@code int pid + boolean usingWrapper}.
     */
    private boolean answer(List<String> arguments, DataOutputStream output) throws Exception {
        String first = arguments.get(0);
        if (first.equals("--query-abi-list")) {
            // ZygoteProcess.getAbiList(): readInt() then readFully(); the payload is
            // a US-ASCII list split on ','.
            writeText(output, "arm64-v8a");
            System.out.println("a15_zygote_query=abi-list");
            return true;
        }
        if (first.equals("--get-pid")) {
            writeText(output, Integer.toString(android.os.Process.myPid()));
            System.out.println("a15_zygote_query=get-pid");
            return true;
        }
        if (first.equals("--boot-completed")) {
            // ZygoteProcess.bootCompleted(String) writes "1\n--boot-completed\n",
            // flushes and then readInt() (image ZygoteProcess dex 0x2af260-0x2af2a2:
            // const-string "1\n--boot-completed\n"; invoke write; invoke flush;
            // invoke DataInputStream.readInt). The reply is one status int, and the
            // connection must stay open: closing it here is what made every later
            // startProcess fail on the same ZygoteState.
            // Real device 2026-10-29 (instance 1793279849066):
            //   a15_zygote_launch_failed=guest-application:... argc=1 argv=--boot-completed
            //   ActivityManager: Failure starting process com.android.systemui
            output.writeInt(0);
            System.out.println("a15_zygote_query=boot-completed");
            return true;
        }
        if (first.equals("--preload-default")) {
            // WebView preload. There is no preloaded WebView zygote in this runtime;
            // report the same status A9's endpoint reports so boot does not block.
            output.writeInt(1);
            System.out.println("a15_zygote_query=preload-default");
            return true;
        }
        if (first.equals("--preload-package") || first.equals("--preload-app")) {
            output.writeInt(0);
            System.out.println("a15_zygote_query=" + first.substring(2));
            return true;
        }
        if (first.equals("--set-api-denylist-exemptions")
                || first.equals("--set-api-blacklist-exemptions")
                || first.startsWith("--hidden-api-")) {
            // The runtime accepts the request and reports success; the denylist is
            // not used by the container.
            output.writeInt(0);
            System.out.println("a15_zygote_query=" + first);
            return true;
        }
        if (first.startsWith("--usap-pool-enabled=")) {
            // No USAP pool: two status ints, one per zygote.
            output.writeInt(0);
            output.writeInt(0);
            System.out.println("a15_zygote_query=" + first);
            return true;
        }
        int pid = -1;
        String name = "guest-application";
        try {
            name = name(arguments);
            synchronized (this) {
                children.removeIf(child -> !child.isAlive());
                Process child = A15AppLaunch.start(arguments, ++sequence);
                children.add(child);
                pid = A15AppLaunch.pid(child);
            }
        } catch (Throwable error) {
            // Round 81: a rejected launch left no way to tell *which* request shape
            // was rejected. AMS restarts a killed persistent process through the
            // same socket but with a different argv (instance 1793278969550:
            // "invalid a15 application launch name=guest-application", i.e. no
            // --nice-name= and no bare entry point), so the failure line has to
            // carry the raw argv. Same line, no behaviour change.
            System.err.println("a15_zygote_launch_failed=" + name + ":" + error
                    + " argc=" + arguments.size() + " argv=" + describe(arguments));
            error.printStackTrace(System.err);
        }
        output.writeInt(pid);
        output.writeBoolean(false);
        return pid >= 0;
    }

    /** One-line rendering of a launch request for the failure marker. */
    private static String describe(List<String> arguments) {
        StringBuilder text = new StringBuilder();
        for (String argument : arguments) {
            if (text.length() > 0) text.append('|');
            text.append(argument);
        }
        if (text.length() > 900) {
            text.setLength(900);
            text.append("...");
        }
        return text.toString();
    }

    private static String name(List<String> arguments) {
        for (String argument : arguments) {
            if (argument.startsWith("--nice-name=")) return argument.substring(12);
        }
        return "guest-application";
    }

    private static void writeText(DataOutputStream output, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private synchronized void stop() {
        try {
            server.close();
            listener.close();
        } catch (Exception ignored) {
        }
        for (Process child : children)
            if (child.isAlive()) child.destroyForcibly();
    }
}
