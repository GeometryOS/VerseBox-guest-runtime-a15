package io.a10vm.guest;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class GuestMemory {
    private final GuestScheduling scheduling = new GuestScheduling();
    private int hostUid = -1;
    private int notes = 0;
    private int psiLines = 0;
    private int dropped = 0;

    static void start() throws Exception {
        LocalSocket listener = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
        listener.bind(new LocalSocketAddress("/dev/socket/lmkd", LocalSocketAddress.Namespace.FILESYSTEM));
        Os.chmod("/dev/socket/lmkd", 0600);
        LocalServerSocket server = new LocalServerSocket(listener.getFileDescriptor());
        GuestMemory memory = new GuestMemory();
        new Thread(() -> {
            try (LocalSocket held = listener; LocalServerSocket endpoint = server) {
                while (true) {
                    LocalSocket client = endpoint.accept();
                    new Thread(() -> memory.commands(client), "guest-memory-client").start();
                }
            } catch (Exception error) { System.err.println("Guest memory listener: " + error); }
        }, "guest-memory-listener").start();
        System.out.println("desktop_memory_ready=1");
    }

    // The host reclaims memory; publish guest app importance to its existing killer.
    private void commands(LocalSocket socket) {
        try (LocalSocket client = socket) {
            InputStream input = client.getInputStream();
            // A15 的 lmkd 包最大是 LMK_TARGET：4*(2*mOomAdj.length+1) 字节；取 256 以整包读入。
            byte[] bytes = new byte[256];
            ByteBuffer packet = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            int size;
            while ((size = input.read(bytes)) > 0) {
                if (size < 4 || size % 4 != 0) { drop("packet-size", size); continue; }
                packet.clear();
                packet.limit(size);
                int command = packet.getInt();
                synchronized (scheduling) {
                    switch (command) {
                        case 0:
                            // LMK_TARGET：ProcessList.onLmkdConnect / updateOomLevels
                            // [0, (minfree_pages, adj) * n]
                            if (size < 12 || (size - 4) % 8 != 0) { drop("targets", command); break; }
                            break;
                        case 1:
                            // LMK_PROCPRIO：ProcessList.setOomAdj [1, pid, uid, adj]
                            if (size != 16) { drop("priority-size", command); break; }
                            priority(packet.getInt(), packet.getInt(), packet.getInt());
                            break;
                        case 2:
                            if (size != 8) { drop("removal-size", command); break; }
                            scheduling.remove(packet.getInt());
                            break;
                        case 3:
                            if (size != 4) { drop("reset-size", command); break; }
                            scheduling.reset();
                            break;
                        case 4:
                            // LMK_GETKILLCNT：ProcessList.getLmkdKillCount [4, pid, uid] → [4, count]
                            if (size != 12) { drop("kill-count-size", command); break; }
                            client.getOutputStream().write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                                    .putInt(command).putInt(0).array());
                            break;
                        case 5:
                            // ProcessList.onLmkdConnect 每次连接建立都发两个 8 字节包：[5, 0] 与 [5, 1]
                            // （dexdump：[35169e]-[3516f8]，写完不读回复）。它们不携带 pid/uid/oom adj，
                            // 也不是进程启动请求：按"无回复通知"丢弃，绝不抛异常、绝不关连接。
                            if (size != 8) { drop("kill-note-size", command); break; }
                            note(packet.getInt());
                            break;
                        case 9:
                            // LMK_START_PSI_MONITORING：ProcessList.startPsiMonitoringAfterBoot 发 4 字节 [9]，无回复。
                            if (size != 4) { drop("psi-size", command); break; }
                            psi();
                            break;
                        case 11:
                            // LMK_BATCH_PROCPRIO：ProcessList.batchSetOomAdj
                            // [11, (pid, uid, adj, 0) * 1..3]，4 + 16n 字节，无回复。
                            if (size < 20 || (size - 4) % 16 != 0) { drop("batch-priority-size", command); break; }
                            while (packet.remaining() >= 16) {
                                int batchPid = packet.getInt();
                                int batchUid = packet.getInt();
                                int batchAdj = packet.getInt();
                                packet.getInt();  // dexdump：putInt(0)，保留字段
                                priority(batchPid, batchUid, batchAdj);
                            }
                            break;
                        default:
                            // 未知命令只丢这一包：SEQPACKET 保留报文边界，丢包不会串流；
                            // 关连接反而会让 AMS 进入重连风暴（第 82 轮的 18,590 次）。
                            drop("unknown", command);
                            break;
                    }
                }
            }
        } catch (Exception error) { System.err.println("Guest memory protocol: " + error); }
    }

    private void priority(int pid, int uid, int adjustment) {
        if (pid <= 0 || uid < 0 || adjustment < -1000 || adjustment > 1000) {
            drop("identity", pid);
            return;
        }
        if (uid < 10000) return;
        kernelPriority(pid, adjustment);
        scheduling.update(pid, adjustment);
    }

    private void drop(String reason, int value) {
        if (dropped++ < 8) System.out.println("a15_memory_drop=" + reason + ":" + value);
    }

    private void note(int value) {
        if (notes++ < 4) System.out.println("a15_memory_note=" + value);
    }

    private void psi() {
        if (psiLines++ < 2) System.out.println("a15_memory_psi=requested");
    }

    private void kernelPriority(int pid, int adjustment) {
        try (FileOutputStream output = new FileOutputStream("/proc/" + pid + "/oom_score_adj")) {
            if (hostUid < 0) hostUid = Os.stat("/proc/self").st_uid;
            if (Os.fstat(output.getFD()).st_uid != hostUid) return;
            // An ordinary UID can lower protection, but cannot grant negative OOM scores.
            output.write(Integer.toString(Math.max(0, adjustment)).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception error) { System.err.println("Guest memory priority pid=" + pid + ": " + error); }
    }
}
