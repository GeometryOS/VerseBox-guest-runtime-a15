#pragma once
// Minimal replacement for the fmq/MQDescriptorBase.h that AOSP used to ship with
// libhidl. Android 15 still generates code that includes it
// (fmq/AidlMQDescriptorShim*.h), but r36 no longer provides the file. Only the
// types the FMQ headers reference are defined here; the ring itself lives in
// libfmq.so, which the image ships.
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>

namespace android {
namespace hardware {

// Flavor tags used by fmq::MessageQueue. Values match libhidl's MQFlavor.
enum MQFlavor : uint32_t {
    kSynchronizedReadWrite = 0x01,
    kUnsynchronizedWrite = 0x02,
};

// Same layout the AIDL MQDescriptor grantors are converted into.
struct GrantorDescriptor {
    uint32_t flags;
    uint32_t fdIndex;
    uint32_t offset;
    uint64_t extent;
};

namespace details {

// Grantor slots used by fmq::MessageQueueBase.
constexpr uint32_t READPTRPOS = 0;
constexpr uint32_t WRITEPTRPOS = 1;
constexpr uint32_t DATAPTRPOS = 2;
constexpr uint32_t EVFLAGWORDPOS = 3;
constexpr uint32_t kMinGrantorCount = 3;
constexpr uint32_t kMinGrantorCountForEvFlagSupport = 4;

// The queue stores two of these at the head of the mapping (read, write).
// libfmq.so performs the atomic accesses; this TU only needs the size.
using RingBufferPosition = std::atomic<uint64_t>;

inline uint64_t alignToWordBoundary(uint64_t size) {
    return (size + static_cast<uint64_t>(sizeof(uint64_t)) - 1) &
           ~(static_cast<uint64_t>(sizeof(uint64_t)) - 1);
}

inline bool isAlignedToWordBoundary(uint64_t size) {
    return (size % sizeof(uint64_t)) == 0;
}

inline void logError(const std::string& message) {
    std::fprintf(stderr, "libfmq: %s\n", message.c_str());
}

// SELinux/abort-style audit hook used by MessageQueueBase on corruption. There is
// no auditd in the container; surface it on stderr so it is not silent.
inline void errorWriteLog(int32_t, const char* message) {
    std::fprintf(stderr, "libfmq: %s\n", message);
}

}  // namespace details
}  // namespace hardware
}  // namespace android
