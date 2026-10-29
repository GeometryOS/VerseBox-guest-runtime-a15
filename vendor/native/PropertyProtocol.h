#pragma once
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

enum { PROPERTY_SET = 0x00020001, PROPERTY_NAME_LIMIT = 128, PROPERTY_VALUE_LIMIT = 92,
       // Android 15 stores read-only ("ro.") values longer than PROP_VALUE_MAX out of
       // line: prop_info::kLongFlag + long_property.offset, and
       // SystemProperties::Add() only rejects `valuelen >= PROP_VALUE_MAX` when the
       // name is NOT read-only (bionic/libc/system_properties/system_properties.cpp:346;
       // is_read_only() at :180 is exactly "name starts with ro.").
       // The bridge transport must not apply the 92-byte limit to ro.* values, or
       // SystemProperties.set() fails with EINVAL before the real store ever sees it
       // (real device 2026-10-28: SystemServer.run() ->
       //  Build.ensureFingerprintProperty() -> set("ro.build.fingerprint", 96-byte
       //  fingerprint) -> RuntimeException "Invalid argument").
       // This is a transport framing cap only; the property area still enforces its
       // own capacity inside Add().
       PROPERTY_VALUE_LIMIT_LONG = 4096 };

static inline int property_value_fits(const char* name, size_t size) {
    if (name != NULL && strncmp(name, "ro.", 3) == 0) return size < PROPERTY_VALUE_LIMIT_LONG;
    return size < PROPERTY_VALUE_LIMIT;
}

static inline int property_read(int fd, void* output, size_t size) {
    if (!size) return 1;
    ssize_t count;
    do { count = recv(fd, output, size, MSG_WAITALL); } while (count < 0 && errno == EINTR);
    return count == (ssize_t)size;
}

static inline int property_write(int fd, const void* input, size_t size) {
    const char* bytes = (const char*)input;
    while (size) {
        ssize_t count = send(fd, bytes, size, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        bytes += count;
        size -= count;
    }
    return 1;
}
