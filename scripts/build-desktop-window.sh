#!/bin/sh
# Build the host-side desktop window renderer (liba10vm-desktop-window.so) for the
# Android 15 domain, from THIS repository's sources.
#
# Why it lives here: the app module (native-display) only consumes a directory; which
# renderer lands in it must be decided by the per-version build chain, not by hand.
# The guest-visible consequence of shipping the wrong one is concrete: the A10 renderer
# has no GLES1 handling in DesktopDecoder::next(), so libGLESv1_CM's first call
# (glClearColor, opcode 1025) throws, the client is dropped, rcCreateContext returns 0
# and the app dies with 'createContext failed: EGL_BAD_ALLOC' (AIDA64, code=137).
#
# Inputs (all tracked in this repository):
#   third_party/graphics/container/{window,desktop,native}   renderer sources
#   third_party/graphics/a9/generated                        emugen decoders
#   third_party/graphics/a9/upstream/{goldfish,qemu}         pinned upstream
#   third_party/graphics/compat                              android/base::Log shim
#   vendor/graphics/desktop-window/codec-id.txt              desktop protocol codec
# Output (gitignored build artifact, regenerated on every build):
#   artifacts/native-display/{jniLibs/arm64-v8a,assets}  (gitignored build artifact)
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
SRC="$MODULE/third_party/graphics/container"
GEN="$MODULE/third_party/graphics/a9/generated"
DEF="$MODULE/third_party/graphics/a9/definitions"
G="$MODULE/third_party/graphics/a9/upstream/goldfish"
Q="$MODULE/third_party/graphics/a9/upstream/qemu/android/android-emugl"
EMU="$MODULE/third_party/graphics/a9/upstream/qemu/android/android-emu"
SHIM="$MODULE/third_party/graphics/compat"
# NOT under prepared/: prepare-a15.py does shutil.rmtree(prepared), which would delete this.
OUT="$MODULE/artifacts/native-display"
WORK="$MODULE/artifacts/native-display/work"
if [ -n "${ANDROID_NDK_HOME:-}" ]; then NDK=$ANDROID_NDK_HOME
elif [ -n "${ANDROID_NDK_ROOT:-}" ]; then NDK=$ANDROID_NDK_ROOT
elif [ -d "$HOME/AppData/Local/Android/Sdk/ndk/29.0.14206865" ]; then NDK="$HOME/AppData/Local/Android/Sdk/ndk/29.0.14206865"
else NDK="$HOME/Android/Sdk/ndk/29.0.14206865"; fi
case "$(uname -s 2>/dev/null || echo Linux)" in
    MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
    *) HOST=linux-x86_64 ;;
esac
SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST/sysroot"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
UNWIND=$(ls -d "$NDK"/toolchains/llvm/prebuilt/$HOST/lib/clang/*/lib/linux/aarch64 2>/dev/null | head -1)
test -x "$BIN/clang++" || { echo "build-desktop-window: NDK not found at $NDK" >&2; exit 1; }
test -n "$UNWIND" || { echo "build-desktop-window: libunwind not found" >&2; exit 1; }
# The desktop protocol codec must be the one the guest side actually sends. The guest
# graphics (bridges/build-graphics-a15.sh) derives it from the desktop-gpu-host binary we
# ship, so derive it the same way here: a hardcoded constant is how the renderer ended up
# rejecting every client with "Desktop GPU codec mismatch" (no frames, black screen).
codec_id_from() { grep -aoE '[0-9a-f]{64}' "$1" 2>/dev/null | head -n 1; }
CODEC=""
if [ -f "$MODULE/bridges/bin/desktop-gpu-host" ]; then
    CODEC=$(codec_id_from "$MODULE/bridges/bin/desktop-gpu-host")
fi
if [ -z "$CODEC" ] && [ -f "$MODULE/third_party/graphics/codec-id.txt" ]; then
    CODEC=$(tr -d "[:space:]" < "$MODULE/third_party/graphics/codec-id.txt")
fi
test "${#CODEC}" -eq 64 || { echo "build-desktop-window: cannot determine codec id" >&2; exit 1; }
PY=${PYTHON:-}; if [ -z "$PY" ]; then if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi; fi
mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/assets" "$WORK"
"$PY" "$SRC/desktop/generate-adapters.py" "$GEN" "$WORK/gl-adapters.inc"
"$BIN/clang++" --target=aarch64-linux-android28 --sysroot="$SYSROOT" \
  -nostdinc++ -isystem "$SYSROOT/usr/include/c++/v1" -nostdlib++ \
  -std=c++17 -O2 -fPIC -DANDROID -DA10VM_NO_SNAPSHOTS -DA10VM_DESKTOP_WINDOW \
  "-DA10VM_DESKTOP_CODEC_ID=\"$CODEC\"" -I"$WORK" \
  -include "$SRC/compat/log/log.h" \
  -I"$SRC/compat" -I"$SRC/native" -I"$SRC/desktop" -I"$SRC/window" \
  -I"$GEN" -I"$DEF" -I"$SHIM" \
  -I"$Q/shared/OpenglCodecCommon" -I"$G/shared/OpenglCodecCommon" -I"$Q/guest" -I"$EMU" \
  -L"$UNWIND" -shared -Wl,-soname,liba10vm-desktop-window.so \
  "$SRC/window/WindowJni.cpp" "$SRC/desktop/DesktopRenderer.cpp" \
  "$SRC/desktop/DesktopInputChannel.cpp" "$SRC/desktop/DesktopGpuServer.cpp" \
  "$SRC/desktop/GpuBackend.cpp" "$SRC/desktop/GuestGpuSync.cpp" \
  "$SRC/desktop/SharedTextureSync.cpp" "$SRC/desktop/FramebufferCopy.cpp" \
  "$SRC/desktop/ColorPixels.cpp" "$SRC/desktop/DesktopPresenter.cpp" \
  "$SRC/desktop/DesktopDecoder.cpp" "$SRC/native/SocketStream.cpp" "$SRC/native/CodecPlatform.cpp" \
  "$Q/shared/OpenglCodecCommon/ChecksumCalculator.cpp" \
  "$EMU/android/base/files/Stream.cpp" \
  "$GEN/gl2_dec.cpp" "$GEN/gl2_server_context.cpp" \
  "$GEN/renderControl_dec.cpp" "$GEN/renderControl_server_context.cpp" \
  "$SYSROOT/usr/lib/aarch64-linux-android/libc++_static.a" \
  "$SYSROOT/usr/lib/aarch64-linux-android/libc++abi.a" \
  -lEGL -lGLESv3 -landroid -llog -ldl -lm \
  -o "$OUT/jniLibs/arm64-v8a/liba15vm-desktop-window.so"
# p1 client is an app-side bootstrap input (from the audited reference APK); carry it
# into the tree we hand to Gradle so the app keeps loading it.
P1="$MODULE/../runtime-inputs/native-display/jniLibs/arm64-v8a/liba10vm-p1-client.so"
if [ -f "$P1" ]; then cp "$P1" "$OUT/jniLibs/arm64-v8a/liba15vm-p1-client.so"; fi
# native-display (shared module) hardcodes the A10 names in its verifier and in the
# System.loadLibrary fallback, so ship byte-identical copies under those names too.
# The manifest lists the A15 names: those are what the A15 runtime verifies and loads.
cp "$OUT/jniLibs/arm64-v8a/liba15vm-desktop-window.so" "$OUT/jniLibs/arm64-v8a/liba10vm-desktop-window.so"
if [ -f "$OUT/jniLibs/arm64-v8a/liba15vm-p1-client.so" ]; then
    cp "$OUT/jniLibs/arm64-v8a/liba15vm-p1-client.so" "$OUT/jniLibs/arm64-v8a/liba10vm-p1-client.so"
fi
"$PY" - "$OUT" "$CODEC" <<'PYEOF'
import hashlib, json, os, sys
root, codec = sys.argv[1], sys.argv[2]
files = []
for name in ('liba15vm-p1-client.so', 'liba15vm-desktop-window.so'):
    path = os.path.join(root, 'jniLibs', 'arm64-v8a', name)
    if not os.path.isfile(path):
        continue
    data = open(path, 'rb').read()
    files.append({'name': name, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
manifest = {
    'backend': 'native-arm64-gles-surface',
    'rootRequired': False,
    'desktopImplemented': True,
    'inputProtocol': 'touch-v2',
    'targetHz': 120,
    'width': 1080,
    'height': 2160,
    'density': 480,
    'codec': codec,
    'files': files,
}
open(os.path.join(root, 'assets', 'native-display-inputs.json'), 'w').write(json.dumps(manifest, indent=2) + '\n')
for item in files:
    print('  ' + item['name'] + ' ' + str(item['bytes']) + ' ' + item['sha256'][:12])
PYEOF
echo "desktop-window: $(wc -c < "$OUT/jniLibs/arm64-v8a/liba15vm-desktop-window.so" | tr -d ' ') bytes, codec $CODEC"