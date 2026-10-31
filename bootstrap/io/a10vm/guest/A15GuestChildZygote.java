package io.a10vm.guest;

import android.net.LocalSocketAddress;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;

/**
 * Android 15 guest child-zygote endpoint.
 *
 * <p>On a real device {@code ZygoteProcess.startChildZygote} makes the main zygote
 * fork a second zygote (webview_zygote / app_zygote) that then forks isolated
 * renderers. The argv it sends is built in the image's own framework
 * (classes3.dex {@code android.os.ZygoteProcess.startChildZygote}
 * 0x2ade1c-0x2adf2c):
 *
 * <pre>
 *   --zygote-socket=&lt;prefix&gt;/&lt;uuid&gt;   (abstract namespace)
 *   --abi-list=&lt;abi&gt;
 *   --uid-range-start=&lt;first&gt;
 *   --uid-range-end=&lt;last&gt;
 *   --start-child-zygote &lt;processClass&gt; [class args]
 * </pre>
 *
 * <p>This runtime cannot fork a zygote: every guest process is a fresh
 * {@code dalvikvm64} exec (see {@link A15AppLaunch}). So the child zygote is a
 * process that binds that abstract socket and answers the ordinary zygote wire
 * protocol, launching each request as its own process -- the same shape
 * Android 9/10 use ({@code GuestChildZygote} + {@code DesktopProcesses}).
 *
 * <p>Without it every WebView renderer request failed with
 * {@code a15_child_zygote=webview_zygote}, i.e. no renderer ever existed. The
 * argv is printed once so the exact shape is on the device log, not in a memory
 * of AOSP bytecode.
 */
public final class A15GuestChildZygote {
    public static void main(String[] args) throws Exception {
        String socket = null;
        String abi = "arm64-v8a";
        int firstUid = 99000, lastUid = 99999;
        StringBuilder seen = new StringBuilder();
        for (String argument : args) {
            if (argument.startsWith("--zygote-socket=")) socket = argument.substring(16);
            else if (argument.startsWith("--abi-list=")) abi = argument.substring(11);
            else if (argument.startsWith("--uid-range-start="))
                firstUid = Integer.parseInt(argument.substring(18));
            else if (argument.startsWith("--uid-range-end="))
                lastUid = Integer.parseInt(argument.substring(16));
            if (seen.length() < 900) {
                if (seen.length() > 0) seen.append('|');
                seen.append(argument);
            }
        }
        System.out.println("a15_child_zygote_args=" + seen);
        System.out.flush();
        if (socket == null || socket.isEmpty())
            throw new IllegalArgumentException("a15 child zygote without --zygote-socket=");
        // Isolated renderers live in 90000-99999 on both A10 and A15. Refuse anything
        // else instead of silently spawning processes with the wrong identity.
        if (firstUid < 90000 || lastUid < firstUid)
            throw new IllegalArgumentException(
                    "a15 child zygote uid range " + firstUid + ".." + lastUid);
        Os.prctl(OsConstants.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        Looper.prepareMainLooper();
        DesktopProcesses.start(new LocalSocketAddress(socket, LocalSocketAddress.Namespace.ABSTRACT),
                firstUid, lastUid);
        System.out.println("a15_child_zygote_ready=" + socket + " abi=" + abi
                + " uidRange=" + firstUid + ".." + lastUid);
        System.out.flush();
        Looper.loop();
    }
}
