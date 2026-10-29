#!/bin/sh
# Refresh the pinned goldfish/qemu graphics sources used by
# bridges/build-graphics-a15.sh.
#
# The tree is COMMITTED at third_party/graphics/a9/upstream (255 files, 6.2 MB).
# This script exists only for a checkout that deliberately pruned it.
#
# Why committed instead of fetched: gitiles rate-limits bulk downloads
# (HTTP 429), and the A10-era container/sources.txt does not list every file the
# A15 graphics build consumes (system/hwc2/EmuHWC2.cpp is one), so a fetch alone
# can succeed partially. The required files are therefore checked below and a
# missing one is a hard error, not a warning.
set -eu
MODULE=${MODULE:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
OUT="$MODULE/third_party/graphics/a9/upstream"
LIST="$MODULE/third_party/graphics/container/sources.txt"
MANIFEST="$MODULE/third_party/graphics/container/sources.sha256"
QEMU=https://android.googlesource.com/platform/external/qemu/+/9e4088bf54f2a11dd7898b757c08e2c473315460
GOLDFISH=https://android.googlesource.com/device/generic/goldfish-opengl/+/d291bbd969a8cdb7bf9ac6f7d46af5b3d2b6c309

if [ -f "$OUT/goldfish/system/hwc2/EmuHWC2.cpp" ]; then
    echo "graphics sources: already present under $OUT"
    exit 0
fi

test -s "$LIST"
test -s "$MANIFEST"
for tool in curl base64 sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || { echo "fetch-graphics-sources: missing $tool" >&2; exit 1; }
done
mkdir -p "$OUT"
while read -r repository path; do
    case "$repository" in
        ''|'#'*) continue ;;
        qemu) base=$QEMU ;;
        goldfish) base=$GOLDFISH ;;
        *) printf 'Unknown source: %s\n' "$repository" >&2; exit 1 ;;
    esac
    destination="$OUT/$repository/$path"
    if [ -s "$destination" ]; then continue; fi
    mkdir -p "${destination%/*}"
    temporary="$destination.part"
    curl --fail --silent --show-error --max-time 60 --retry 5 --retry-delay 10 \
        "$base/$path?format=TEXT" -o "$temporary.b64"
    base64 -d "$temporary.b64" > "$temporary"
    test -s "$temporary"
    mv "$temporary" "$destination"
    rm "$temporary.b64"
    sleep 1
done < "$LIST"

(cd "$OUT" && sha256sum --check --status "$MANIFEST") || {
    printf 'Pinned graphics source verification failed.\n' >&2
    exit 1
}
missing=
for required in \
    goldfish/system/hwc2/EmuHWC2.cpp \
    goldfish/system/hwc2/MiniFence.cpp \
    goldfish/system/egl/egl.cpp \
    goldfish/system/egl/eglDisplay.cpp \
    goldfish/system/gralloc/gralloc.cpp \
    goldfish/system/OpenglSystemCommon/FormatConversions.cpp \
    goldfish/shared/OpenglCodecCommon/GLSharedGroup.cpp \
    goldfish/system/GLESv2_enc/GL2Encoder.cpp \
    qemu/android/android-emugl/shared/OpenglCodecCommon/ChecksumCalculator.cpp; do
    [ -f "$OUT/$required" ] || missing="$missing $required"
done
if [ -n "$missing" ]; then
    printf 'graphics sources incomplete; missing:%s\n' "$missing" >&2
    printf 'restore third_party/graphics/a9/upstream from the repository (it is committed).\n' >&2
    exit 1
fi
printf 'graphics sources: %s files under %s\n' "$(find "$OUT" -type f | wc -l)" "$OUT"
