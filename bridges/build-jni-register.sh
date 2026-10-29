#!/bin/sh
# Build the API 35 JNI registration bridge: liba15vm-jni-register.so.
# Pure C; links the source-built API 35 libc/libdl.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RT="$MODULE/runtime"
UP="$RT/upstream"
OUT="$MODULE/bridges"
NDK=${ANDROID_NDK_HOME:-"C:/Users/22437/AppData/Local/Android/Sdk/ndk/29.0.14206865"}
if command -v cygpath >/dev/null 2>&1; then NDK=$(cygpath -u "$NDK"); fi
case "$(uname -s 2>/dev/null || echo Linux)" in
    MINGW*|MSYS*|CYGWIN*) NDK_HOST=windows-x86_64 ;;
    *) NDK_HOST=linux-x86_64 ;;
esac
export PATH="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin:$PATH"
unset LD_LIBRARY_PATH LD_PRELOAD || true
SYSROOT="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/sysroot"
CRT="$RT/build/obj/libm"
mkdir -p "$OUT/lib" "$OUT/obj"

test -f "$RT/build/lib/libc.so" || { echo "run build-libc.sh first" >&2; exit 1; }
test -f "$RT/build/lib/libdl.so" || { echo "run build-libc.sh first (libdl)" >&2; exit 1; }
test -f "$CRT/crtbegin_so.o" || { echo "run build-libm.sh first (crt objects)" >&2; exit 1; }

clang --target=aarch64-linux-android35 --sysroot="$SYSROOT" \
    -fPIC -O2 -Wall -Wextra -Werror \
    -I"$UP/bionic/libc" -I"$UP/bionic/libc/include" \
    -c "$MODULE/vendor/native/JniRegister.c" -o "$OUT/obj/jni-register.o"

ld.lld -shared -soname liba15vm-jni-register.so -z now -z relro -z max-page-size=4096 \
    --no-undefined \
    "$CRT/crtbegin_so.o" "$OUT/obj/jni-register.o" \
    "$RT/build/lib/libc.so" "$RT/build/lib/libdl.so" "$CRT/crtend_so.o" \
    -o "$OUT/lib/liba15vm-jni-register.so"

llvm-readelf -hld "$OUT/lib/liba15vm-jni-register.so" > "$OUT/lib/liba15vm-jni-register.elf.txt"
llvm-nm -D --defined-only "$OUT/lib/liba15vm-jni-register.so" | grep -E 'JNI_OnLoad' || true
echo "jni-register: $OUT/lib/liba15vm-jni-register.so"
