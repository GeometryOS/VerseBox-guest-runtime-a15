// Minimal declaration of android::base::Log for the vendored qemu subset.
//
// third_party/graphics/a9/upstream only contains the files listed in
// container/sources.txt (the ones the guest graphics build needs). The desktop
// GPU host pulls android/base/Optional.h, which includes android/base/Log.h and
// uses CHECK(cond) << "..."; that header is not part of the subset.
//
// This shim provides exactly that interface: CHECK streams a message to stderr
// and aborts. It is only reached on an already-fatal path (Optional::value() on
// an empty object), so it does not change the host's behaviour.
#pragma once
#include <cstdlib>
#include <iostream>
namespace android {
namespace base {
class LogMessage {
public:
    LogMessage(const char* file, int line) { std::cerr << file << ":" << line << ": "; }
    ~LogMessage() { std::cerr << std::endl; std::abort(); }
    std::ostream& stream() { return std::cerr; }
};
}  // namespace base
}  // namespace android
#define CHECK(cond) while (!(cond)) ::android::base::LogMessage(__FILE__, __LINE__).stream()
