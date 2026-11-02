#!/bin/sh
# Build the project's legacy audio HAL for Android 15: audio.primary.default.so.
#
# The Redroid 15 image only ships the AOSP stub module (vendor/lib64/hw/
# audio.primary.default.so): it accepts and paces PCM but has no hardware, so
# every playback path is silent. The AIDL audio HAL service
# (vendor/bin/hw/android.hardware.audio.service) loads that legacy module
# through hw_get_module("audio.primary"); replacing the file with this module
# routes PCM to the host through $A10VM_STATE_ROOT/audio.sock, exactly like A9
# and A10 do.
#
# The source is the same HostAudioHal.cpp/HostAudioPcm.cpp pair A9/A10 use
# (byte-identical to guest-runtime/native). It declares
# AUDIO_DEVICE_API_VERSION_2_0; the legacy struct is append-only, so the 2_0
# prefix keeps the same layout under the A15 header.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
. "$(dirname -- "$0")/compile.sh"

OUT="$MODULE/bridges"
OBJ="$OUT/obj/audio-hal"
# API 35 audio HAL headers (android-15.0.0_r36; audio_common-base.h comes from
# the android15-release branch because it is a generated file that the r36 tag
# does not carry). Building against A10 headers gave the same source a smaller
# audio_hw_device_t/audio_stream_out than the A15 audio HAL service expects, and
# the service died with SIGSEGV right after "Loaded primary audio interface"
# (r133). Provenance: vendor/audio/sources.sha256.
SYS_HDR="$MODULE/vendor/audio/include"
HW_HDR="$MODULE/vendor/audio/include"
mkdir -p "$OBJ" "$OUT/lib"

test -f "$LIBC" || { echo "run runtime/build-libc.sh first (missing $LIBC)" >&2; exit 1; }
test -f "$HW_HDR/hardware/audio.h" || { echo "missing legacy hardware/audio.h under $HW_HDR" >&2; exit 1; }
test -f "$SYS_HDR/system/audio.h" || { echo "missing system/audio.h under $SYS_HDR (run guest-runtime/fetch-audio-headers.sh)" >&2; exit 1; }

platform_compile "$MODULE/vendor/native/HostAudioHal.cpp" "$OBJ/host-audio.o" -I"$SYS_HDR" -I"$HW_HDR"
platform_compile "$MODULE/vendor/native/HostAudioPcm.cpp" "$OBJ/host-audio-pcm.o" -I"$SYS_HDR" -I"$HW_HDR"

crt_so
builtins=$(clang --target=$TARGET --print-libgcc-file-name)
ld.lld -shared -soname audio.primary.default.so -z now -z relro -z max-page-size=4096 \
    --no-undefined --exclude-libs ALL --hash-style=gnu --eh-frame-hdr --build-id=sha1 \
    "$OUT/obj/crt/crtbegin_so.o" "$OBJ/host-audio.o" "$OBJ/host-audio-pcm.o" \
    "$LIBCXX" "$LIBC" "$LIBM" "$LIBDL" "$builtins" \
    "$OUT/obj/crt/crtend_so.o" -o "$OUT/lib/audio.primary.default.so"
llvm-readelf -hlWd "$OUT/lib/audio.primary.default.so" > "$OUT/lib/audio.primary.default.elf.txt"
llvm-nm -u "$OUT/lib/audio.primary.default.so" > "$OUT/lib/audio.primary.default.undefined.txt"
echo "audio-hal: $OUT/lib/audio.primary.default.so"
