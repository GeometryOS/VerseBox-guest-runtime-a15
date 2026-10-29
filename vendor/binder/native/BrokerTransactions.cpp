#include "Broker.h"
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <string>

namespace a10binder {
int Broker::transact(uint64_t id, Packet packet) {
    binder_transaction_data transaction;
    if (!transactionLayout(packet, &transaction)) return errno;
    Connection& connection = connections.at(id);
    Process& source = processes.at(connection.process);
    bool reply = packet.header.command == BC_REPLY;
    bool oneway = transaction.flags & TF_ONE_WAY;
    // D2 diagnostic (temporary, read-only): installd's
    //   addService("installd", svc, allowIsolated=false, dumpPriority=8)
    // is answered with -1 == PERMISSION_DENIED (docs/android15-port/12 §4.2). The first
    // attempt filtered on transaction.code == 3 and printed nothing, so the code the broker
    // sees is not what the guest sent (or the transaction is not the one we assumed).
    // Log *every* incoming transaction instead: a session has <100 of them, and the code +
    // target handle + sender_euid together identify both the caller identity and which
    // sender_euid servicemanager will read. sender_euid is filled from the per-connection
    // guestUid (Broker.cpp:87), so this also proves whether A10VM_GUEST_UID reached us.
    if (!reply)
        fprintf(stderr,
                "binder transaction code=%u target=%llu sender_pid=%d sender_euid=%d data=%zu flags=0x%x\n",
                unsigned(transaction.code), static_cast<unsigned long long>(transaction.target.handle),
                int(source.pid), int(source.guestUid), size_t(transaction.data_size),
                unsigned(transaction.flags));
    // Round 43 diagnostic (read-only): the service *name* is the one fact the
    // code/size line cannot give. Every AIDL transaction starts with the
    // interface descriptor (writeInterfaceToken) followed by the first real
    // argument, so the first two String16 fields of a call addressed to the
    // context manager (handle 0) are (descriptor, service name).
    //
    // Real device 2026-10-28 (instance 1793176749366): the server keeps printing
    //   "Since 'apexservice' could not be found ..."
    // while apexd prints "AidlLazyServiceRegistrar: Registering service
    // apexservice", and no addService from a uid-0 process ever reaches the
    // servicemanager's permission shim. This line says which name each call
    // carries, so the next run distinguishes "add never sent" from
    // "add rejected" without another guess.
    if (!reply && transaction.target.handle == 0 && transaction.data_size >= 8) {
        const uint8_t* payload = packet.data.data() + sizeof(transaction);
        size_t cursor = 0;
        for (int field = 0; field < 2; ++field) {
            int32_t length = 0;
            if (cursor + sizeof(length) > transaction.data_size) break;
            memcpy(&length, payload + cursor, sizeof(length));
            cursor += sizeof(length);
            if (length <= 0 || length > 128 || cursor + size_t(length) * 2 > transaction.data_size) {
                fprintf(stderr, "binder context field%d <unparsed len=%d> size=%zu\n",
                        field, int(length), size_t(transaction.data_size));
                break;
            }
            char text[160];
            size_t index = 0;
            for (; index < size_t(length) && index < sizeof(text) - 1; ++index) {
                uint16_t unit = 0;
                memcpy(&unit, payload + cursor + index * 2, sizeof(unit));
                text[index] = (unit >= 0x20 && unit < 0x7f) ? char(unit) : '?';
            }
            text[index] = 0;
            fprintf(stderr, "binder context field%d \"%s\" code=%u\n",
                    field, text, unsigned(transaction.code));
            cursor += size_t(length) * 2;
            cursor = (cursor + 3) & ~size_t(3);
        }
    }
    uint64_t targetProcess = 0, targetThread = 0, targetNode = 0, callId = 0;
    std::string securityContext;
    if (reply) {
        if (oneway || connection.incoming.empty()) return EINVAL;
        callId = connection.incoming.back();
        auto call = calls.find(callId);
        if (call == calls.end()) return EINVAL;
        targetThread = call->second.from;
        if (!packet.fds.empty() && !(call->second.flags & TF_ACCEPT_FDS)) return EPERM;
        if (!targetThread || connections.find(targetThread) == connections.end()) {
            finishCall(callId); event(id, BR_TRANSACTION_COMPLETE); return 0;
        }
        targetProcess = connections.at(targetThread).process;
        transaction.target.ptr = transaction.cookie = 0;
    } else {
        targetNode = resolve(source.id, transaction.target.handle);
        if (!targetNode || nodes.find(targetNode) == nodes.end() || !nodes.at(targetNode).alive) {
            event(id, BR_DEAD_REPLY); return 0;
        }
        Node& node = nodes.at(targetNode);
#ifndef A9VM_PRIVATE_RUNTIME
        // AOSP 9 has no BR_TRANSACTION_SEC_CTX at all (its IPCThreadState has
        // no case for it), so the A9 bridge must never emit that command.
        // The guest decides the protocol version, not the helper.
        if (node.flags & FLAT_BINDER_FLAG_TXN_SECURITY_CTX) {
            char context[4096];
            socklen_t length = sizeof(context);
            if (getsockopt(connection.fd, SOL_SOCKET, SO_PEERSEC, context, &length) != 0) return errno;
            if (!length || length > sizeof(context)) return EINVAL;
            if (context[length - 1] == 0) --length;
            if (!length || length >= sizeof(context) || memchr(context, 0, length)) return EINVAL;
            securityContext.assign(context, length);
        }
#endif
        if (!packet.fds.empty() && !(node.flags & FLAT_BINDER_FLAG_ACCEPTS_FDS)) return EPERM;
        targetProcess = node.owner;
        transaction.target.ptr = node.ptr; transaction.cookie = node.cookie;
        if (!oneway) {
            uint64_t parent = connection.incoming.empty() ? 0 : connection.incoming.back();
            uint64_t at = parent;
            while (at && calls.find(at) != calls.end()) {
                const Call& ancestor = calls.at(at);
                auto from = connections.find(ancestor.from);
                if (from != connections.end() && from->second.process == targetProcess) { targetThread = from->first; break; }
                at = ancestor.parent;
            }
            callId = nextId++;
            calls.emplace(callId, Call{id, 0, targetProcess, parent, transaction.flags});
        }
    }
    Process& destination = processes.at(targetProcess);
    size_t bytes = packet.data.size() - sizeof(transaction) + (securityContext.empty() ? 0 : securityContext.size() + 1);
    if (bytes > kArenaLimit - destination.bufferBytes) {
        if (!reply && callId) calls.erase(callId);
        event(id, BR_FAILED_REPLY); return 0;
    }
    transaction.sender_pid = oneway ? 0 : source.pid;
    transaction.sender_euid = source.guestUid;
    memcpy(packet.data.data(), &transaction, sizeof(transaction));
    uint64_t bufferId = nextId++;
    Buffer buffer;
    buffer.owner = targetProcess;
    buffer.asyncNode = oneway ? targetNode : 0;
    buffer.bytes = bytes;
    buffer.id = bufferId;
    buffer.senderProcess = source.id;
    buffer.call = callId;
    buffer.command = packet.header.command;
    buffer.transactionCode = transaction.code;
    buffer.flags = transaction.flags;
    buffer.dataBytes = transaction.data_size;
    buffer.offsetsBytes = transaction.offsets_size;
    int status = translate(id, targetProcess, &packet, &buffer);
    if (status) {
        for (const Reference& reference : buffer.references) {
            adjust(reference.node, reference.strong, -1);
            if (reference.handle) { --destination.handles.at(reference.handle).temporary; trimHandle(targetProcess, reference.handle); }
        }
        if (!reply && callId) calls.erase(callId);
        return status;
    }
    destination.bufferBytes += bytes;
    if (!reply && callId) connection.outgoing.push_back(callId);
    buffer.id = bufferId;
    buffers.emplace(bufferId, std::move(buffer));
    packet.header.kind = Event; packet.header.command = reply ? BR_REPLY : BR_TRANSACTION;
    if (!securityContext.empty()) {
        packet.header.command = BR_TRANSACTION_SEC_CTX;
        packet.append(securityContext.c_str(), securityContext.size() + 1);
    }
    packet.header.token = bufferId; packet.header.arg = reply || oneway ? 0 : callId;
    if (oneway) {
        Node& node = nodes.at(targetNode);
        if (node.asyncBusy) node.asyncQueue.push_back(std::move(packet));
        else { processEvent(targetProcess, std::move(packet)); node.asyncBusy = true; }
        ++counters.oneways;
    } else if (targetThread) threadEvent(targetThread, std::move(packet));
    else processEvent(targetProcess, std::move(packet));
    if (reply) { finishCall(callId); ++counters.replies; }
    else ++counters.transactions;
    event(id, BR_TRANSACTION_COMPLETE);
    return 0;
}
}
