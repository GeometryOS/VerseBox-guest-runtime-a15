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

final class DesktopProcesses {
    private final LocalSocket listener = new LocalSocket();
    private final LocalServerSocket server;
    private final List<Process> children = new ArrayList<>();
    private final int firstUid, lastUid;
    private int sequence;

    private DesktopProcesses(LocalSocketAddress address, int firstUid, int lastUid) throws Exception {
        this.firstUid = firstUid;
        this.lastUid = lastUid;
        listener.bind(address);
        if (address.getNamespace() == LocalSocketAddress.Namespace.FILESYSTEM) Os.chmod(address.getName(), 0600);
        server = new LocalServerSocket(listener.getFileDescriptor());
    }

    static void start() throws Exception {
        start(new LocalSocketAddress("/dev/socket/zygote", LocalSocketAddress.Namespace.FILESYSTEM), 0, Integer.MAX_VALUE);
        System.out.println("desktop_application_endpoint=1");
    }

    static void start(LocalSocketAddress address, int firstUid, int lastUid) throws Exception {
        DesktopProcesses launcher = new DesktopProcesses(address, firstUid, lastUid);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> launcher.stop(), "guest-app-stop"));
        new Thread(() -> launcher.accept(), "guest-app-listener").start();
    }

    private void accept() {
        try {
            while (true) {
                LocalSocket client = server.accept();
                new Thread(() -> connection(client), "guest-app-connection").start();
            }
        } catch (Exception error) { System.err.println("Guest application listener: " + error); }
    }

    private void connection(LocalSocket socket) {
        try (LocalSocket client = socket) {
            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            String line;
            while ((line = input.readLine()) != null) {
                int count = Integer.parseInt(line);
                if (count < 1 || count > 1024) throw new IllegalArgumentException("Guest launch argument count");
                List<String> arguments = new ArrayList<>();
                for (int i = 0; i < count; ++i) {
                    String argument = input.readLine();
                    if (argument == null) throw new IllegalArgumentException("Incomplete guest launch arguments");
                    arguments.add(argument);
                }
                if (arguments.get(0).equals("--query-abi-list")) {
                    byte[] abi = "arm64-v8a".getBytes(StandardCharsets.US_ASCII);
                    output.writeInt(abi.length);
                    output.write(abi);
                } else if (arguments.get(0).equals("--get-pid")) {
                    byte[] pid = Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.US_ASCII);
                    output.writeInt(pid.length);
                    output.write(pid);
                } else if (arguments.get(0).equals("--preload-default")) {
                    output.writeInt(1);
                } else if (arguments.get(0).equals("--preload-package") || arguments.get(0).equals("--preload-app")) {
                    int status = -1;
                    try { GuestWebView.preload(arguments); status = 0; }
                    catch (Exception error) { System.err.println("Guest WebView preload: " + error); }
                    output.writeInt(status);
                } else if (arguments.get(0).startsWith("--hidden-api-") || arguments.get(0).equals("--set-api-blacklist-exemptions")) {
                    output.writeInt(0);
                } else {
                    int pid = -1;
                    try {
                        synchronized (this) {
                            checkIdentity(arguments);
                            children.removeIf(child -> !child.isAlive());
                            // Round 166: renderers that arrive through the child
                            // zygote must start exactly like the ones that arrive
                            // through the main zygote, or they die silently right
                            // after AssetManager configuration (no Java trace, no
                            // A9VM crash line, no tombstone). Real device
                            // 2026-11-08, instance 1794133452551 (2201123C): every
                            // renderer went through AppLaunch (A9-era shape)
                            //   desktop_application_launch=23123:...sandboxed_process0
                            // and died in 4 ms, while the same APK on PKX110 takes
                            // the A15AppLaunch path
                            //   a15_application_launch=32239:...sandboxed_process0 uid=99000
                            // and paints the page. Same payload, same SDK; the only
                            // difference is which launcher owned the request.
                            Process child = A15AppLaunch.start(arguments, ++sequence);
                            children.add(child);
                            pid = AppLaunch.pid(child);
                        }
                    } catch (Exception error) { System.err.println("Guest application launch: " + error); }
                    output.writeInt(pid);
                    output.writeBoolean(false);
                }
                output.flush();
            }
        } catch (Exception error) { System.err.println("Guest application protocol: " + error); }
    }

    private void checkIdentity(List<String> arguments) {
        for (String argument : arguments) {
            if (!argument.startsWith("--setuid=") && !argument.startsWith("--setgid=")) continue;
            int id = Integer.parseInt(argument.substring(9));
            if (id < firstUid || id > lastUid)
                throw new IllegalArgumentException("Identity outside guest child zygote range");
        }
    }

    private synchronized void stop() {
        try { server.close(); listener.close(); } catch (Exception ignored) { }
        for (Process child : children) if (child.isAlive()) child.destroyForcibly();
    }
}
