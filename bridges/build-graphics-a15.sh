#!/bin/sh
# Build the Android 15 (API 35) guest graphics layer.
#
# This is the API 35 port of container-probe/graphics/build-desktop.sh (A10) and
# guest-runtime-a9/bridges/build-graphics-a9.sh (A9). The codec / wire protocol
# is unchanged, so what differs is only the compile target and the link closure:
#
#   * every object targets aarch64-linux-android35 and includes the API 35
#     bionic, libcutils, libutils, liblog and libsystem headers;
#   * the runtime side of the link closure is the source-built API 35
#     libc/libdl/libm plus the Redroid 15 libc++ and the ROM's
#     libnativewindow / libhardware / libsync / libcutils / libutils / liblog;
#   * no A9/A10 object or library is linked or renamed.
#
# Why the HAL modules are rebuilt instead of reusing the image:
#   The A15 image still ships HIDL composer@2.1 + allocator@2.0 service
#   binaries, and those load `hwcomposer`/`gralloc` through hw_get_module (the
#   image's libhardware does consult ro.hardware.<class>, and guest-a15.prop
#   sets ro.hardware.{egl,gralloc,hwcomposer}=emulation/ranchu/ranchu). The
#   module *behind* them is what the project must supply: the image only has
#   redroid/cros/gbm modules, which render through /dev/dri and cannot reach the
#   host GPU endpoint that desktop-gpu-host proxies. So the project provides
#   ranchu EGL/GLES/gralloc/HWC exactly like A9 and A10 do.
#
# Sources (all inside this repository, see BUILD.md 2.1):
#   * third_party/graphics/container/  copy of container-probe/graphics
#     (sources + platform/desktop patches + sources.txt/sha256);
#   * third_party/graphics/a9/{generated,definitions,framework,patches}
#     the emugen output and the framework HAL headers, committed;
#   * third_party/graphics/a9/upstream  pinned goldfish/qemu sources, fetched by
#     scripts/fetch-graphics-sources.sh (gitignored; verified against
#     third_party/graphics/container/sources.sha256).
#   vendored/graphics/sources.txt (the pinned list) is compared with the
#   container copy before use.
#
# Outputs (bridges/graphics/lib): libOpenglSystemCommon.so, libEGL_emulation.so,
# libGLESv1_CM_emulation.so, libGLESv2_emulation.so, gralloc.ranchu.so,
# hwcomposer.ranchu.so, codec-id.txt.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
BR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$BR/../.." && pwd)
. "$BR/bridges/compile.sh"          # NDK, API=35 target, source runtime, crt_so
set +u

UP="$MODULE/runtime/upstream"
B="$UP/bionic/libc"
ROM="$MODULE/rootfs/system/lib64"
# Vendored graphics sources (committed with this repository):
#   third_party/graphics/container      copies of container-probe/graphics
#   third_party/graphics/a9/{generated,definitions,framework,patches}
#   third_party/graphics/a9/upstream    fetched by scripts/fetch-graphics-sources.sh
SRC="$MODULE/third_party/graphics/container"
A15GFX="$MODULE/third_party/graphics/a9"
OUT="$MODULE/bridges/graphics"
D="$OUT/derived"
OBJ="$OUT/obj"
mkdir -p "$OUT/lib" "$OUT/bin" "$OBJ" "$D/common" "$D/encoder" "$D/egl" \
         "$D/gralloc" "$D/hwc2"

unset LD_LIBRARY_PATH LD_PRELOAD || true

# ---------------------------------------------------------------- source roots
pick() {
    label=$1
    shift
    for candidate in "$@"; do
        if [ -e "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    echo "missing $label; tried: $*" >&2
    exit 1
}
if [ -f "$MODULE/vendor/graphics/sources.txt" ] && ! cmp -s "$MODULE/vendor/graphics/sources.txt" "$SRC/sources.txt"; then
    echo "vendor/graphics/sources.txt differs from third_party/graphics/container/sources.txt" >&2
    exit 1
fi
GF="$A15GFX/upstream/goldfish"
QF="$A15GFX/upstream/qemu/android/android-emugl"
GEN="$A15GFX/generated"
DEF="$A15GFX/definitions"
FW="$A15GFX/framework"
if [ ! -d "$GF" ]; then
    echo "missing pinned goldfish/qemu sources under $A15GFX/upstream" >&2
    echo "run: sh scripts/fetch-graphics-sources.sh" >&2
    exit 1
fi
for required in "$GF/system/hwc2/EmuHWC2.cpp" "$GF/system/gralloc/gralloc.cpp" \
                "$GEN/gl2_enc.cpp" "$FW/hardware/libhardware/include/hardware/gralloc.h"; do
    [ -f "$required" ] || { echo "missing graphics source: $required" >&2; exit 1; }
done

# ---------------------------------------------------------------- codec id
# The id must equal the one baked into the desktop-gpu-host we ship, otherwise
# the guest codec and the host renderer reject each other's handshake.
codec_id_from() {
    grep -aoE '[0-9a-f]{64}' "$1" 2>/dev/null | head -n 1
}
CODEC_ID=""
if [ -f "$MODULE/bridges/bin/desktop-gpu-host" ]; then
    CODEC_ID=$(codec_id_from "$MODULE/bridges/bin/desktop-gpu-host")
fi
if [ -z "$CODEC_ID" ] && [ -f "$MODULE/third_party/graphics/codec-id.txt" ]; then
    CODEC_ID=$(tr -d '[:space:]' < "$MODULE/third_party/graphics/codec-id.txt")
fi
case "$CODEC_ID" in
    ????????????????????????????????????????????????????????????????) ;;
    *) echo "Cannot determine the desktop GPU codec id" >&2; exit 1 ;;
esac
printf '%s\n' "$CODEC_ID" > "$OUT/codec-id.txt"

# ---------------------------------------------------------------- derived tree
cp "$GF/system/egl/egl.cpp" "$GF/system/egl/eglDisplay.cpp" "$D/egl/"
cp "$GF/system/gralloc/gralloc.cpp" "$D/gralloc/"
cp "$GF/system/OpenglSystemCommon/FormatConversions.cpp" "$D/"
cp "$GF/system/hwc2/EmuHWC2.cpp" "$GF/system/hwc2/EmuHWC2.h" \
   "$GF/system/hwc2/MiniFence.cpp" "$GF/system/hwc2/MiniFence.h" "$D/hwc2/"
cp "$QF/shared/OpenglCodecCommon/ChecksumCalculator.cpp" \
   "$QF/shared/OpenglCodecCommon/ChecksumCalculator.h" "$D/common/"
cp "$GF/shared/OpenglCodecCommon/GLSharedGroup.cpp" \
   "$GF/shared/OpenglCodecCommon/GLSharedGroup.h" "$D/common/"
cp "$GF/system/GLESv2_enc/GL2Encoder.cpp" "$D/encoder/"

apply_patch() {
    patch_file=$1
    relative=${D#"$MODULE"/}
    # The desktop patches carry hand-trimmed hunk headers, so GNU patch rejects
    # them; git apply --recount is what the A9/A10 builds used.
    if (cd "$MODULE" && git apply --recount --unsafe-paths --directory="$relative" "$patch_file"); then
        echo "patched $(basename "$patch_file")"
    elif (cd "$MODULE" && git apply --recount --unsafe-paths -R --check --directory="$relative" \
            "$patch_file" >/dev/null 2>&1); then
        echo "already $(basename "$patch_file")"
    else
        echo "patch failed: $patch_file" >&2
        exit 1
    fi
}
apply_patch "$SRC/platform.patch"
apply_patch "$SRC/desktop/guest.patch"
# Android 15's Skia RenderEngine emits a default precision statement for
# samplerExternalOES ("precision mediump samplerExternalOES;"). The emugl
# rewrite only blanks the #extension line and rewrites uniform declarations, so
# the leftover precision line made the driver reject the shader
# ("requires extension GL_OES_EGL_image_external to be enabled") and
# SurfaceFlinger aborted. A15-only: A9/A10 never emit that line.
apply_patch "$MODULE/bridges/patches/sampler-external-precision.patch"
# Round 50, second blocker: the endpoint advertises an ES2 extension list, but
# Adreno exposes the half-float color-buffer extensions. Without them Skia's caps
# refuse RGBA_F16 external textures and RenderEngine aborts with
# "Unable to generate SkImage" before SurfaceFlinger can register
# SurfaceFlingerAIDL. A15-only.
apply_patch "$MODULE/bridges/patches/gl-ext-half-float.patch"
apply_patch "$MODULE/bridges/patches/surfaceless-context.patch"
# A WebView page can issue glDrawElements(count>0, indices=NULL) while our
# GLClientState has no element buffer bound. Upstream goldfish treats the NULL
# as a real client array and calcIndexRange() dereferences it
# (mark.via SIGSEGV at libOpenglSystemCommon.so ldrh [x1], x1=0, 2026-11-02).
# Guard the four draw paths and report the VAO/EBO state on the guard line.
apply_patch "$MODULE/bridges/patches/drawelements-null-indices.patch"
# The EGL context is created as ES2 (the host endpoint reports ES 2.0 and only
# ES2 configs), but upstream goldfish's GL_VERSION string is hardcoded "OpenGL
# ES 3.0". Chromium/Skia therefore used ES3 paths (VAO/R8) and its ES3 context
# request failed with "eglCreateContext: Requested version is not supported"
# (mark.via, 2026-11-02). Report the version of the context that actually
# exists, and answer GL_SHADING_LANGUAGE_VERSION instead of INVALID_ENUM.
apply_patch "$MODULE/bridges/patches/gl-version-string.patch"
apply_patch "$SRC/desktop/config-handles.patch"
apply_patch "$SRC/desktop/texture-contexts.patch"
apply_patch "$SRC/desktop/format-conversions.patch"
apply_patch "$SRC/desktop/yuv-rgb-rows.patch"
apply_patch "$SRC/desktop/hwc.patch"
apply_patch "$SRC/desktop/idle.patch"
apply_patch "$SRC/desktop/native-image-lifetime.patch"
# The goldfish gralloc initialiser uses #if PLATFORM_SDK_VERSION >= 28 for the
# Q-only gralloc_module_t members (validateBufferSize/getTransportSize). The HAL
# headers available offline are the AOSP 9 ones, which do not declare those
# members, so the same correction the A9 build needed applies here.
apply_patch "$MODULE/third_party/graphics/a9/patches/gralloc-api28.patch"

# ---------------------------------------------------------------- compile flags
# PLATFORM_SDK_VERSION is pinned to 28 on purpose: the goldfish code selects
# header-dependent struct members from it, and the AOSP 9 headers are the only
# hardware/libhardware + frameworks/native headers available without network
# access. The HAL C ABI used (gralloc0 module, hwc2 getFunction descriptors)
# is the frozen vendor ABI; see STATUS.md for the verification plan.
gfx_cflags() {
    printf '%s\n' \
        --target=$TARGET --sysroot="$SYSROOT" \
        -fPIC -O2 -fno-stack-protector -fno-emulated-tls -mno-outline-atomics \
        -ffunction-sections -fdata-sections -U_FORTIFY_SOURCE \
        -DANDROID -DPLATFORM_SDK_VERSION=28 \
        -DA10VM_NO_SNAPSHOTS -DA10VM_NO_DMA -DEMULATOR_OPENGL_POST_O \
        -DWITH_GLES2 -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES \
        -DLOG_TAG=\"a15vm-gpu\" \
        "-DA10VM_DESKTOP_CODEC_ID=\"$CODEC_ID\"" \
        -Wno-deprecated-declarations -Wno-macro-redefined \
        -include "$QF/shared/OpenglCodecCommon/ErrorLog.h" \
        -include "$MODULE/bridges/graphics/compat/a15vm-compat.h" \
        -I"$MODULE/bridges/graphics/compat" \
        -I"$SRC/compat" -I"$SRC/native" -I"$SRC/desktop" -I"$D" -I"$D/common" \
        -I"$GEN" -I"$DEF" \
        -I"$GF/system/OpenglSystemCommon" -I"$GF/system/GLESv1_enc" \
        -I"$GF/system/GLESv2_enc" -I"$GF/system/include" -I"$GF/system/egl" \
        -I"$GF/system/hwc2" -I"$GF/shared/OpenglCodecCommon" \
        -I"$QF/shared/OpenglCodecCommon" \
        -I"$FW/frameworks/native/opengl/include" \
        -I"$FW/frameworks/native/libs/nativewindow/include" \
        -I"$FW/frameworks/native/libs/nativebase/include" \
        -I"$FW/frameworks/native/libs/ui/include" \
        -I"$FW/frameworks/native/libs/arect/include" \
        -I"$FW/hardware/libhardware/include" \
        -I"$UP/system/core/libsync/include" \
        -I"$UP/system/core/libcutils/include" -I"$UP/system/logging/liblog/include" \
        -I"$UP/system/core/libutils/include" -I"$UP/system/core/libsystem/include" \
        -I"$UP/system/core/include" \
        -I"$B" -I"$B/private" -I"$B/async_safe/include"
}

gfx_compile() {
    input=$1
    object=$2
    shift 2
    mkdir -p "$(dirname "$object")"
    # bionic and libc++ must both come from -isystem, in this order: libc++ ships
    # its own <math.h> shim, and a plain -I bionic/include would shadow it.
    # hwcomposer is built with -fno-rtti because the ROM's libutils.so hides
    # typeinfo for android::Thread.
    clang++ $(gfx_cflags) -nostdinc \
        -isystem "$UP/external/libcxx/include" \
        -isystem "$(clang -print-resource-dir)/include" \
        -isystem "$B/include" \
        -isystem "$B/kernel/uapi/asm-arm64" \
        -isystem "$B/kernel/uapi" \
        -isystem "$B/kernel/android/uapi" \
        "$@" -fexceptions ${RTTI_FLAG:--frtti} -std=gnu++17 -c "$input" -o "$object"
}

# ---------------------------------------------------------------- link closure
A15_LIBC="$MODULE/runtime/build/lib/libc.so"
A15_LIBM="$MODULE/runtime/build/lib/libm.so"
A15_LIBDL="$MODULE/runtime/build/lib/libdl.so"
A15_LIBCXX="$ROM/libc++.so"
ROM_LIBS="$ROM/libcutils.so $ROM/libutils.so $ROM/liblog.so"
crt_so
builtins=$(clang --target=$TARGET --print-libgcc-file-name)

link_guest() {
    name=$1
    shift
    ld.lld -shared -soname "$name" -z now -z relro -z max-page-size=16384 \
        --hash-style=gnu --eh-frame-hdr --build-id=sha1 --no-undefined \
        "$OUT/obj/crt/crtbegin_so.o" "$@" \
        "$A15_LIBCXX" $ROM_LIBS \
        "$A15_LIBM" "$A15_LIBDL" "$A15_LIBC" "$builtins" \
        "$OUT/obj/crt/crtend_so.o" -o "$OUT/lib/$name"
}

# ---------------------------------------------------------------- libOpenglSystemCommon
objects=
for source in \
    "$SRC/desktop/HostConnection.cpp" \
    "$SRC/desktop/GuestTextureContexts.cpp" \
    "$SRC/native/SocketStream.cpp" \
    "$SRC/desktop/YuvRgbRows.cpp" \
    "$SRC/native/CodecPlatform.cpp" \
    "$SRC/native/PixelReadback.cpp" \
    "$D/common/ChecksumCalculator.cpp" \
    "$D/common/GLSharedGroup.cpp" \
    "$D/FormatConversions.cpp" \
    "$D/encoder/GL2Encoder.cpp" \
    "$GF/system/GLESv2_enc/GL2EncoderUtils.cpp" \
    "$GF/system/GLESv2_enc/GLESv2Validation.cpp" \
    "$GF/shared/OpenglCodecCommon/GLClientState.cpp" \
    "$GF/shared/OpenglCodecCommon/GLESTextureUtils.cpp" \
    "$GF/shared/OpenglCodecCommon/IndexRangeCache.cpp" \
    "$GF/shared/OpenglCodecCommon/glUtils.cpp" \
    "$GF/shared/OpenglCodecCommon/goldfish_dma.cpp" \
    "$GF/system/GLESv1_enc/GLEncoder.cpp" \
    "$GF/system/GLESv1_enc/gl_enc.cpp" \
    "$GF/system/GLESv1_enc/GLEncoderUtils.cpp" \
    "$GF/system/GLESv1_enc/gl_client_context.cpp" \
    "$GEN/gl2_enc.cpp" \
    "$GEN/gl2_client_context.cpp" \
    "$GEN/renderControl_enc.cpp" \
    "$GEN/renderControl_client_context.cpp" ; do
    object="$OBJ/$(basename "$source" .cpp).o"
    gfx_compile "$source" "$object"
    objects="$objects $object"
done
# Android 15 removed the private bionic TLS slot (TLS_SLOT_OPENGL, bionic_tls.h)
# that ThreadInfo.cpp uses for its Android fast path. Compiling this single
# translation unit with -U__ANDROID__ selects the file's own thread_store path
# -- the code path non-Android builds already use -- instead of patching the
# pinned upstream source. The thread_store API itself is provided by
# bridges/graphics/compat (A15's libcutils no longer ships it).
gfx_compile "$GF/system/OpenglSystemCommon/ThreadInfo.cpp" "$OBJ/ThreadInfo.o" -U__ANDROID__
clang $(gfx_cflags) -std=gnu99 -c "$MODULE/bridges/graphics/compat/threads-compat.c" \
    -o "$OBJ/threads-compat.o"
objects="$objects $OBJ/ThreadInfo.o $OBJ/threads-compat.o"
link_guest libOpenglSystemCommon.so $objects

# ---------------------------------------------------------------- GLES + EGL + gralloc + HWC
for api in GLESv1 GLESv2; do
    case "$api" in
        GLESv1) source="$GF/system/GLESv1/gl.cpp"; name=libGLESv1_CM_emulation.so ;;
        GLESv2) source="$GF/system/GLESv2/gl2.cpp"; name=libGLESv2_emulation.so ;;
    esac
    gfx_compile "$source" "$OBJ/$api.o"
    link_guest "$name" "$OBJ/$api.o" "$OUT/lib/libOpenglSystemCommon.so"
done

for source in "$D/egl/egl.cpp" "$D/egl/eglDisplay.cpp" "$GF/system/egl/ClientAPIExts.cpp"; do
    gfx_compile "$source" "$OBJ/$(basename "$source" .cpp).o"
done
link_guest libEGL_emulation.so "$OBJ/egl.o" "$OBJ/eglDisplay.o" "$OBJ/ClientAPIExts.o" \
    "$OUT/lib/libOpenglSystemCommon.so" "$ROM/libnativewindow.so"

gfx_compile "$D/gralloc/gralloc.cpp" "$OBJ/gralloc.o"
link_guest gralloc.ranchu.so "$OBJ/gralloc.o" "$OUT/lib/libOpenglSystemCommon.so" \
    "$ROM/libnativewindow.so" "$ROM/libhardware.so"

RTTI_FLAG=-fno-rtti
for source in "$D/hwc2/EmuHWC2.cpp" "$D/hwc2/MiniFence.cpp"; do
    gfx_compile "$source" "$OBJ/$(basename "$source" .cpp).o"
done
RTTI_FLAG=-frtti
link_guest hwcomposer.ranchu.so "$OBJ/EmuHWC2.o" "$OBJ/MiniFence.o" \
    "$OUT/lib/libOpenglSystemCommon.so" "$ROM/libhardware.so" "$ROM/libsync.so"

# ---------------------------------------------------------------- verification
for f in "$OUT"/lib/*.so; do
    llvm-readelf -hlWd "$f" > "$f.elf.txt"
done
echo "graphics: $(ls "$OUT"/lib/*.so | tr '\n' ' ')"
echo "codec-id: $CODEC_ID"
for f in "$OUT"/lib/*.so; do
    printf '%s  %s  %s\n' "$(wc -c < "$f" | tr -d ' ')" "$(sha256sum "$f" | cut -d' ' -f1)" "$(basename "$f")"
done
