#!/bin/sh
# Shared build environment for Android 15 (API 35) project bridge libraries.
# Every bridge links the source-built API 35 libc/libdl/libm, never A9/A10 objects.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
# Git Bash rewrites POSIX-looking arguments into Windows paths before native
# tools see them; keep /system paths intact for -dynamic-linker.
export MSYS2_ARG_CONV_EXCL='/system'
BR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$BR/../.." && pwd)
RT="$MODULE/runtime"
UP="$RT/upstream"
OUT="$MODULE/bridges"
SYSROOT_NDK=${ANDROID_NDK_HOME:-"C:/Users/22437/AppData/Local/Android/Sdk/ndk/29.0.14206865"}
if command -v cygpath >/dev/null 2>&1; then SYSROOT_NDK=$(cygpath -u "$SYSROOT_NDK"); fi
case "$(uname -s 2>/dev/null || echo Linux)" in
    MINGW*|MSYS*|CYGWIN*) NDK_HOST=windows-x86_64 ;;
    *) NDK_HOST=linux-x86_64 ;;
esac
export PATH="$SYSROOT_NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin:$PATH"
unset LD_LIBRARY_PATH LD_PRELOAD || true
SYSROOT="$SYSROOT_NDK/toolchains/llvm/prebuilt/$NDK_HOST/sysroot"
API=35
TARGET=aarch64-linux-android$API

# Source-built runtime that every bridge must link against.
LIBC="$RT/build/lib/libc.so"
LIBDL="$RT/build/lib/libdl.so"
LIBM="$RT/build/lib/libm.so"
LINKER="$RT/build/bin/source-linker64"
# A15's libc++.so is still self-contained (NEEDED only libc/libm/libdl).
LIBCXX="$MODULE/rootfs/system/lib64/libc++.so"

mkdir -p "$OUT/lib" "$OUT/bin" "$OUT/obj"

# PIE crt objects for helper executables.
crt_pie() {
    mkdir -p "$OUT/obj/crt"
    if [ ! -f "$OUT/obj/crt/crtbegin.o" ]; then
        clang --target=$TARGET --sysroot="$SYSROOT" -fPIE -O2 \
            -I"$UP/bionic/libc" -I"$UP/bionic/libc/include" -I"$UP/bionic/libc/arch-arm64" \
            -c "$UP/bionic/libc/arch-common/bionic/crtbegin.c" \
            -o "$OUT/obj/crt/crtbegin.o"
    fi
    if [ ! -f "$OUT/obj/crt/crtend.o" ]; then
        clang --target=$TARGET --sysroot="$SYSROOT" -fPIE -O2 \
            -I"$UP/bionic/libc" -I"$UP/bionic/libc/arch-arm64" \
            -c "$UP/bionic/libc/arch-common/bionic/crtend.S" \
            -o "$OUT/obj/crt/crtend.o"
    fi
}

# Shared-library crt objects.
crt_so() {
    mkdir -p "$OUT/obj/crt"
    if [ ! -f "$OUT/obj/crt/crtbegin_so.o" ]; then
        clang --target=$TARGET --sysroot="$SYSROOT" -fPIC -O2 \
            -I"$UP/bionic/libc" -I"$UP/bionic/libc/include" -I"$UP/bionic/libc/arch-arm64" \
            -c "$UP/bionic/libc/arch-common/bionic/crtbegin_so.c" \
            -o "$OUT/obj/crt/crtbegin_so.o"
    fi
    if [ ! -f "$OUT/obj/crt/crtend_so.o" ]; then
        clang --target=$TARGET --sysroot="$SYSROOT" -fPIC -O2 \
            -I"$UP/bionic/libc" -I"$UP/bionic/libc/arch-arm64" \
            -c "$UP/bionic/libc/arch-common/bionic/crtend_so.S" \
            -o "$OUT/obj/crt/crtend_so.o"
    fi
}

# API 35 system_properties uses std::atomic with size_t expressions; libc++ 8
# (external/libcxx) rejects those, so the bridges use the NDK's libc++ 21
# headers. The C headers come from the NDK sysroot (the same API 35 bionic
# headers), which keeps libc++'s <string.h> shim first in the search order.
bridge_cflags() {
    printf '%s\n' \
        --target=$TARGET --sysroot="$SYSROOT" \
        -fPIC -O2 -fno-stack-protector \
        -fno-exceptions -fno-rtti -fno-sized-deallocation \
        -ffunction-sections -fdata-sections -fno-emulated-tls \
        -D__ANDROID_VNDK__ -DA15VM_PRIVATE_RUNTIME -D_FILE_OFFSET_BITS=64 \
        -DLOG_NDEBUG=1 -DLIBLOG_LOG_TAG=1006 \
        -D__BIONIC_LP32_USE_LONG_DOUBLE -DFLT_EVAL_METHOD=0 \
        -I"$UP/bionic/libc" \
        -I"$UP/bionic/libc/include" \
        -I"$UP/bionic/libc/kernel/uapi/asm-arm64" \
        -I"$UP/bionic/libc/kernel/uapi" \
        -I"$UP/bionic/libc/kernel/android/uapi" \
        -I"$UP/bionic/libc/async_safe/include" \
        -I"$RT/build/derived/bionic/libc/system_properties/include" \
        -I"$UP/bionic/libc/system_properties/include" \
        -I"$RT/build/derived/core/property_service/libpropertyinfoparser/include" \
        -I"$UP/system/core/property_service/libpropertyinfoparser/include" \
        -I"$UP/system/libbase/include" \
        -I"$UP/system/logging/liblog/include" \
        -I"$UP/system/core/libcutils/include" \
        -I"$UP/system/core/libutils/include" \
        -I"$UP/system/core/libsystem/include" \
        -I"$UP/system/core/include" \
        -I"$UP/system/core/libprocessgroup/include" \
        -I"$MODULE/vendor/properties/native" \
        -I"$MODULE/vendor/binder/native" \
        -I"$MODULE/vendor/devices" \
        -I"$MODULE/vendor/native" \
        -I"$OUT/generated" \
        -I"$OUT/compat" \
        -Wno-deprecated-declarations
}

# platform_compile <source> <object> [extra flags...]
platform_compile() {
    input=$1
    object=$2
    shift 2
    mkdir -p "$(dirname "$object")"
    case "$input" in
        *.cpp|*.cc)
            clang++ $(bridge_cflags) \
                -nostdinc++ -isystem "$UP/external/libcxx/include" \
                -std=gnu++17 "$@" -c "$input" -o "$object" ;;
        *.c)
            clang $(bridge_cflags) -std=gnu99 "$@" -c "$input" -o "$object" ;;
        *.S)
            clang $(bridge_cflags) -D__ASSEMBLY__ "$@" -c "$input" -o "$object" ;;
        *)
            echo "unsupported source: $input" >&2; exit 1 ;;
    esac
}
