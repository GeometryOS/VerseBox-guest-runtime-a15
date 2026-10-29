#!/bin/sh
# Fetch the pinned goldfish/qemu graphics sources used by
# bridges/build-graphics-a15.sh.
#
# They are the same repositories and commits the container-probe/A10 and A9
# builds use (third_party/graphics/container/sources.sha256 is verified after
# the download), and they land in third_party/graphics/a9/upstream, which is
# gitignored. Everything else the graphics build needs is committed:
#   third_party/graphics/container/                  (patches + sources)
#   third_party/graphics/a9/{generated,definitions,framework,patches}
#
# Usage: sh scripts/fetch-graphics-sources.sh
set -eu
MODULE=${MODULE:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
OUT="$MODULE/third_party/graphics/a9/upstream"
LIST="$MODULE/third_party/graphics/container/sources.txt"
MANIFEST="$MODULE/third_party/graphics/container/sources.sha256"
QEMU=https://android.googlesource.com/platform/external/qemu/+/9e4088bf54f2a11dd7898b757c08e2c473315460
GOLDFISH=https://android.googlesource.com/device/generic/goldfish-opengl/+/d291bbd969a8cdb7bf9ac6f7d46af5b3d2b6c309
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
    curl --fail --silent --show-error --max-time 60 --retry 3 --retry-delay 5 \
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
printf 'graphics sources: %s files under %s\n' "$(find "$OUT" -type f | wc -l)" "$OUT"
