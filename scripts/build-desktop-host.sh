#!/bin/sh
# Build the host GPU endpoint (desktop-gpu-host) from the sources vendored in
# this repository -- no A10 container pipeline required.
#
# Inputs (all in-repo except the fetched upstream tree):
#   third_party/graphics/container/desktop/*.cpp      host/decoder sources
#   third_party/graphics/container/native/*.cpp       socket + codec platform
#   third_party/graphics/a9/generated/*               gl2_dec / renderControl dec (emugen output)
#   third_party/graphics/a9/upstream/{goldfish,qemu}  pinned sources (scripts/fetch-graphics-sources.sh)
#   third_party/graphics/compat/android/base/Log.h    shim for android/base/Log.h (not in the subset)
#   third_party/graphics/codec-id.txt                 must equal the guest build's codec id
#
# Output: bridges/bin/desktop-gpu-host (ELF, aarch64, API 28 sysroot); prepare-a15.py consumes it.
# Requires: Android NDK 29 (ANDROID_NDK_HOME / ANDROID_NDK_ROOT), Python 3.
# patchelf is optional (only used to drop RPATH when present).
set -eu
MODULE=${MODULE:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
cd "$MODULE"
if [ -n "${ANDROID_NDK_HOME:-}" ]; then NDK=$ANDROID_NDK_HOME
elif [ -n "${ANDROID_NDK_ROOT:-}" ]; then NDK=$ANDROID_NDK_ROOT
elif [ -d "$HOME/AppData/Local/Android/Sdk/ndk/29.0.14206865" ]; then NDK="$HOME/AppData/Local/Android/Sdk/ndk/29.0.14206865"
else NDK="$HOME/Android/Sdk/ndk/29.0.14206865"
fi
case "$(uname -s 2>/dev/null || echo Linux)" in
    MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
    *) HOST=linux-x86_64 ;;
esac
SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST/sysroot"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
test -f "$SYSROOT/usr/lib/aarch64-linux-android/libc++_static.a" || {
    echo "build-desktop-host: NDK 29 not found at $NDK" >&2; exit 1; }
UNWIND=$(ls -d "$NDK"/toolchains/llvm/prebuilt/$HOST/lib/clang/*/lib/linux/aarch64 2>/dev/null | head -1)
test -n "$UNWIND" || { echo "build-desktop-host: libunwind not found under $NDK" >&2; exit 1; }

SRC="$MODULE/third_party/graphics/container"
GEN="$MODULE/third_party/graphics/a9/generated"
DEF="$MODULE/third_party/graphics/a9/definitions"
G="$MODULE/third_party/graphics/a9/upstream/goldfish"
Q="$MODULE/third_party/graphics/a9/upstream/qemu/android/android-emugl"
EMU="$MODULE/third_party/graphics/a9/upstream/qemu/android/android-emu"
SHIM="$MODULE/third_party/graphics/compat"
OUT="$MODULE/bridges/obj/desktop-host"
mkdir -p "$OUT"
test -d "$G" || { echo "build-desktop-host: run scripts/fetch-graphics-sources.sh first" >&2; exit 1; }
CODEC=$(cat "$MODULE/bridges/graphics/codec-id.txt" 2>/dev/null || cat "$MODULE/third_party/graphics/codec-id.txt")
CODEC=$(printf '%s' "$CODEC" | tr -d '[:space:]')

PY=${PYTHON:-}
if [ -z "$PY" ]; then if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi; fi
"$PY" "$SRC/desktop/generate-adapters.py" "$GEN" "$OUT/gl-adapters.inc"

"$BIN/clang++" --target=aarch64-linux-android28 --sysroot="$SYSROOT" \
  -nostdinc++ -isystem "$SYSROOT/usr/include/c++/v1" -nostdlib++ \
  -std=c++17 -O2 -fPIC -gline-tables-only -DANDROID -DA10VM_NO_SNAPSHOTS \
  "-DA10VM_DESKTOP_CODEC_ID=\"$CODEC\"" -include "$SRC/compat/log/log.h" \
  -I"$SRC/compat" -I"$SRC/native" -I"$SRC/desktop" -I"$SRC/window" \
  -I"$GEN" -I"$DEF" -I"$OUT" -I"$SHIM" \
  -I"$Q/shared/OpenglCodecCommon" -I"$G/shared/OpenglCodecCommon" -I"$EMU" -I"$Q/guest" \
  -L"$UNWIND" \
  "$SRC/desktop/DesktopHost.cpp" "$SRC/desktop/DesktopGpuServer.cpp" \
  "$SRC/desktop/GpuBackend.cpp" "$SRC/desktop/GuestGpuSync.cpp" \
  "$SRC/desktop/SharedTextureSync.cpp" "$SRC/desktop/FramebufferCopy.cpp" \
  "$SRC/desktop/ColorPixels.cpp" "$SRC/desktop/DesktopPresenter.cpp" "$SRC/desktop/DesktopDecoder.cpp" \
  "$SRC/native/SocketStream.cpp" "$SRC/native/CodecPlatform.cpp" \
  "$Q/shared/OpenglCodecCommon/ChecksumCalculator.cpp" \
  "$EMU/android/base/files/Stream.cpp" \
  "$GEN/gl2_dec.cpp" "$GEN/gl2_server_context.cpp" \
  "$GEN/renderControl_dec.cpp" "$GEN/renderControl_server_context.cpp" \
  "$SYSROOT/usr/lib/aarch64-linux-android/libc++_static.a" \
  "$SYSROOT/usr/lib/aarch64-linux-android/libc++abi.a" \
  -lEGL -lGLESv3 -landroid -llog -ldl -lm \
  -o "$MODULE/bridges/bin/desktop-gpu-host.new"
if command -v patchelf >/dev/null 2>&1; then patchelf --remove-rpath "$MODULE/bridges/bin/desktop-gpu-host.new"; fi
mv "$MODULE/bridges/bin/desktop-gpu-host.new" "$MODULE/bridges/bin/desktop-gpu-host"
printf 'desktop-gpu-host: rebuilt from source (%s bytes, codec %s)\n' \
    "$(wc -c < "$MODULE/bridges/bin/desktop-gpu-host" | tr -d ' ')" "$CODEC"
