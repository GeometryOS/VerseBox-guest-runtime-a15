#!/bin/sh
# One-command Android 15 (API 35) pipeline.
#
#   image  ->  rootfs  ->  APEX  ->  API 35 runtime  ->  bridges  ->  prepared  ->  APK
#
# Every stage is skipped when its product already exists, so a rerun after a Java
# or prepared change costs only that stage. A15_FORCE=1 rebuilds the native
# runtime and bridges from scratch (it does NOT re-download the image).
#
# Environment:
#   PYTHON          python interpreter            (default: python3, then python)
#取消注释下边这行来全量构建
#   A15_FORCE=1     force the native stages

#   A15_SKIP_APK=1  stop after prepared (no Gradle)
#   REDROID_TAG     pin a non-rolling redroid tag (see BUILD.md section 2)
#
# Requirements: Python 3 (stdlib only), JDK + Android SDK for the APK, and for a
# native rebuild the Android NDK 29 plus a POSIX shell (this script itself).
set -eu
MODULE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$MODULE/.." && pwd)
cd "$MODULE"

PY=${PYTHON:-}
if [ -z "$PY" ]; then
    if command -v python3 >/dev/null 2>&1; then PY=python3
    elif command -v python >/dev/null 2>&1; then PY=python
    else echo "build.sh: no python3/python in PATH (set PYTHON=...)" >&2; exit 1
    fi
fi

FORCE=${A15_FORCE:-0}
step() { printf '\n== %s\n' "$*"; }

# ---------------------------------------------------------------- 1. image ----
if [ "$FORCE" = 1 ] || [ ! -f downloads/manifest.json ]; then
    step "fetch redroid 15 arm64 image (resumable, verifies every layer digest)"
    "$PY" scripts/fetch-redroid15.py
else
    step "image already downloaded (downloads/manifest.json)"
fi

# ---------------------------------------------------------------- 2. rootfs ---
if [ "$FORCE" = 1 ] || [ ! -f rootfs-index.json ]; then
    step "extract rootfs"
    "$PY" scripts/extract-rootfs.py
else
    step "rootfs already extracted (rootfs-index.json)"
fi

# ----------------------------------------------------------------- 3. APEX ----
# The .expanded markers are not enough to skip this step: rootfs-index.json is
# written by two different scripts (extract-rootfs.py rebuilds it from the image
# layers, extract-apex.py appends the expanded payloads), so a cache that keeps
# rootfs/apex but loses rootfs-index.json leaves the index without any APEX
# contents. Skipping on the marker alone then made prepare-a15.py fail with
#   Missing Android 15 program dalvikvm64 at /apex/com.android.art/bin/dalvikvm64
# (CI, 2026-10-30). Treat "the index does not mention the ART APEX" as "not
# expanded": extract-apex.py re-indexes an already-expanded tree in place.
apex_indexed() {
    [ -f rootfs-index.json ] && grep -q '"apex/com.android.art/bin/dalvikvm64"' rootfs-index.json
}
if [ "$FORCE" = 1 ] || [ ! -f rootfs/apex/com.android.art/.expanded ] || ! apex_indexed; then
    step "expand APEX payloads"
    "$PY" scripts/extract-apex.py
else
    step "APEX already expanded"
fi

# -------------------------------------------------------------- 4. sources ----
if [ "$FORCE" = 1 ] || [ ! -d runtime/upstream/bionic ]; then
    step "fetch API 35 sources (bionic / system/core / liblog / zlib ...)"
    sh runtime/fetch-a15-sources.sh
else
    step "API 35 sources already present (runtime/upstream)"
fi

# -------------------------------------------------------------- 5. runtime ----
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/lib/liba15vm-bootstrap.a ]; then
    step "static bionic"
    sh runtime/build-static.sh
fi
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/bin/source-linker64 ]; then
    step "private linker"
    sh runtime/build-linker.sh
fi
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/lib/libc.so ] || [ ! -f runtime/build/lib/libdl.so ]; then
    step "libc + libdl"
    sh runtime/build-libc.sh
fi
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/lib/libm.so ]; then
    step "libm"
    sh runtime/build-libm.sh
fi

# ------------------------------------------------- 6. host GPU endpoint --------
# prepare-a15.py needs bridges/bin/desktop-gpu-host. It is the host-side GPU
# endpoint (API-independent, shared by A9/A10/A15). By default the pinned copy
# under prebuilt/arm64 is installed (sha256-checked). A15_REBUILD_DESKTOP=1
# rebuilds it from the sources vendored in third_party/graphics, without the A10
# container pipeline (needs NDK 29 and the fetched graphics upstream).
if [ "${A15_REBUILD_DESKTOP:-0}" = 1 ]; then
    step "desktop GPU endpoint (rebuild from vendored sources)"
    [ -d third_party/graphics/a9/upstream/goldfish ] || sh scripts/fetch-graphics-sources.sh
    sh scripts/build-desktop-host.sh
else
    step "desktop GPU endpoint (pinned)"
    sh scripts/install-desktop-host.sh
fi

# --------------------------------------------------------------- 7. bridges ---
# Ordered: property/binder/devices/log are link inputs of the JNI bridge; the
# graphics layer provides the HAL modules prepare-a15.py stages.
bridge_product() {
    case "$1" in
        property)      printf '%s' bridges/lib/liba15vm-property-client.so ;;
        binder)        printf '%s' bridges/lib/liba15binder.so ;;
        devices)       printf '%s' bridges/lib/liba15vm-guest-devices.so ;;
        guest-log)     printf '%s' bridges/lib/liba15vm-guest-log.so ;;
        jni-register)  printf '%s' bridges/lib/liba15vm-jni-register.so ;;
        host-hal)      printf '%s' bridges/bin/host-devices ;;
        graphics-a15)  printf '%s' bridges/graphics/lib/libGLESv2_emulation.so ;;
    esac
}
for bridge in property binder devices guest-log jni-register host-hal graphics-a15; do
    product=$(bridge_product "$bridge")
    if [ "$FORCE" = 1 ] || [ ! -f "$product" ]; then
        if [ "$bridge" = graphics-a15 ] && [ ! -d third_party/graphics/a9/upstream/goldfish ]; then
            step "fetch pinned graphics sources"
            sh scripts/fetch-graphics-sources.sh
        fi
        step "bridge: $bridge"
        sh "bridges/build-$bridge.sh"
    fi
done

# ---------------------------------------------------- 7. assets + prepared -----
step "boot animation asset"
"$PY" scripts/make-bootanimation.py

step "prepared (manifest + assets + jniLibs)"
"$PY" scripts/prepare-a15.py

# ----------------------------------------------------------------- 8. APK -----
if [ "${A15_SKIP_APK:-0}" = 1 ]; then
    step "A15_SKIP_APK=1: stopping before Gradle"
    exit 0
fi
step "APK (app-debug.apk)"
cd "$ROOT"
if [ -x ./gradlew ]; then
    ./gradlew :app:assembleDebug
else
    sh ./gradlew :app:assembleDebug
fi
printf '\nA15 pipeline complete: %s\n' "$ROOT/app/build/outputs/apk/debug/app-debug.apk"