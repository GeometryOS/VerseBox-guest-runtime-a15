#!/usr/bin/env python3
"""Download the Redroid Android 15 (64-bit only) arm64 image without Docker.

Docker is not installed on this machine, so the layer blobs are fetched from the
Docker Registry HTTP API exactly like `scripts/fetch-redroid9.py` does for A9:
the manifest list is resolved to linux/arm64, every layer is downloaded with
parallel HTTP Range requests, and each blob is verified against the digest the
registry publishes.  A resumable `.part` file keeps a failed download from
starting over.

Usage:
    python scripts/fetch-redroid15.py            # download everything
    python scripts/fetch-redroid15.py --check    # resolve manifest only
    REDROID_TAG=15.0.0_64only-latest python scripts/fetch-redroid15.py
"""
import hashlib
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "redroid/redroid"
# The 64-bit-only Android 15 image.  Redroid publishes per-version tags as well
# (15.0.0_64only-<date>); pin the exact one in REDROID_TAG once it is known so a
# rebuilt "latest" cannot silently change the guest userland.
TAG = os.environ.get("REDROID_TAG", "15.0.0_64only-latest")
ARCH = "arm64"
DOWNLOAD = ROOT / "downloads"
AUTH = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull"
REGISTRY = "https://registry-1.docker.io/v2/{repo}"

MANIFEST_ACCEPT = (
    "application/vnd.oci.image.index.v1+json, "
    "application/vnd.docker.distribution.manifest.list.v2+json, "
    "application/vnd.oci.image.manifest.v1+json, "
    "application/vnd.docker.distribution.manifest.v2+json"
)
IMAGE_ACCEPT = (
    "application/vnd.oci.image.manifest.v1+json, "
    "application/vnd.docker.distribution.manifest.v2+json"
)


def token():
    with urllib.request.urlopen(AUTH.format(repo=REPO), timeout=60) as response:
        return json.load(response)["token"]


def get_json(url, bearer, accept):
    request = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + bearer,
        "Accept": accept,
    })
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response), response.headers.get("Docker-Content-Digest")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 << 20), b""):
            value.update(block)
    return value.hexdigest()


def download(url, bearer, target, size, workers=8):
    """Download one blob with parallel HTTP Range requests."""
    partial = target.with_suffix(target.suffix + ".part")
    partial.unlink(missing_ok=True)
    with partial.open("wb") as stream:
        stream.truncate(size)
    chunks = []
    span = (size + workers - 1) // workers
    for index in range(workers):
        start = index * span
        if start >= size:
            break
        chunks.append([start, min(start + span, size) - 1])
    lock = threading.Lock()
    written = [0]
    errors = []
    started = time.monotonic()
    last_report = [started]

    def worker(start, end, number):
        position = start
        tries = 0
        while position <= end:
            try:
                headers = {"Authorization": "Bearer " + bearer,
                           "Range": "bytes=%d-%d" % (position, end)}
                request = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(request, timeout=120) as response:
                    if response.status != 206:
                        raise RuntimeError("range request ignored: HTTP %d" % response.status)
                    handle = open(partial, "r+b")
                    try:
                        while position <= end:
                            block = response.read(min(1 << 20, end - position + 1))
                            if not block:
                                raise RuntimeError("truncated range at %d" % position)
                            handle.seek(position)
                            handle.write(block)
                            position += len(block)
                            with lock:
                                written[0] += len(block)
                                now = time.monotonic()
                                if now - last_report[0] >= 5:
                                    rate = written[0] / (now - started) / (1 << 20)
                                    print("  %5.1f%%  %.1f/%.1f MiB  %.1f MiB/s" % (
                                        written[0] * 100.0 / size, written[0] / (1 << 20),
                                        size / (1 << 20), rate), flush=True)
                                    last_report[0] = now
                    finally:
                        handle.close()
                tries = 0
            except Exception as failure:
                tries += 1
                if tries > 6:
                    with lock:
                        errors.append("chunk %d at %d: %s" % (number, position, failure))
                    return
                time.sleep(2 * tries)

    threads = [threading.Thread(target=worker, args=(start, end, number), daemon=True)
               for number, (start, end) in enumerate(chunks)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    if errors:
        raise RuntimeError("; ".join(errors))
    if partial.stat().st_size != size:
        raise RuntimeError("size mismatch: %d != %d" % (partial.stat().st_size, size))
    os.replace(partial, target)


def resolve(bearer):
    url = REGISTRY.format(repo=REPO) + "/manifests/" + TAG
    index, index_digest = get_json(url, bearer, MANIFEST_ACCEPT)
    if "manifests" in index:
        matches = [item for item in index["manifests"]
                   if item.get("platform", {}).get("architecture") == ARCH
                   and item.get("platform", {}).get("os") == "linux"]
        if not matches:
            available = sorted({
                "%s/%s" % (item.get("platform", {}).get("os"), item.get("platform", {}).get("architecture"))
                for item in index["manifests"]})
            raise SystemExit("no linux/%s manifest in %s (available: %s)"
                             % (ARCH, TAG, ", ".join(available)))
        chosen = matches[0]
        manifest, manifest_digest = get_json(
            REGISTRY.format(repo=REPO) + "/manifests/" + chosen["digest"], bearer, IMAGE_ACCEPT)
        return index_digest, chosen["digest"], manifest
    return index_digest, index_digest, index


def main():
    check = "--check" in sys.argv
    bearer = token()
    index_digest, manifest_digest, manifest = resolve(bearer)
    layers = manifest.get("layers", [])
    print("repository=%s tag=%s arch=%s" % (REPO, TAG, ARCH))
    print("index=%s" % index_digest)
    print("manifest=%s config=%s" % (manifest_digest, manifest.get("config", {}).get("digest")))
    print("layers=%d compressed=%.1f MiB" % (
        len(layers), sum(layer["size"] for layer in layers) / (1 << 20)))
    for number, layer in enumerate(layers):
        print("  layer %d %s %d bytes" % (number, layer["digest"], layer["size"]))
    if check:
        return

    DOWNLOAD.mkdir(parents=True, exist_ok=True)
    records = []
    for number, layer in enumerate(layers):
        media = layer["mediaType"]
        if not media.endswith("tar+gzip") and not media.endswith("tar"):
            raise SystemExit("unsupported layer media type: " + media)
        suffix = ".tar.gz" if media.endswith("gzip") else ".tar"
        target = DOWNLOAD / ("%s-%d%s" % (TAG, number, suffix))
        expected = layer["digest"].split(":")[1]
        if target.is_file() and digest(target) == expected:
            print("cached " + target.name, flush=True)
        else:
            print("downloading %s (%.1f MiB)" % (target.name, layer["size"] / (1 << 20)),
                  flush=True)
            download(REGISTRY.format(repo=REPO) + "/blobs/" + layer["digest"], bearer,
                     target, layer["size"])
            actual = digest(target)
            if actual != expected:
                raise SystemExit("layer digest mismatch: %s != %s" % (actual, expected))
            print("verified " + target.name, flush=True)
        records.append({"path": str(target), "name": target.name, "sha256": expected,
                        "bytes": layer["size"], "mediaType": media})

    # The config blob carries the image's Entrypoint/Cmd/Env.  Redroid's arm64
    # layer is a full Android rootfs; the config tells the stager which init
    # binary the image expects, so keep it next to the layers.
    config = manifest.get("config", {})
    config_name = TAG + "-config.json"
    config_path = DOWNLOAD / config_name
    if config.get("digest") and (not config_path.is_file() or
                                 digest(config_path) != config["digest"].split(":")[1]):
        request = urllib.request.Request(
            REGISTRY.format(repo=REPO) + "/blobs/" + config["digest"],
            headers={"Authorization": "Bearer " + bearer})
        with urllib.request.urlopen(request, timeout=120) as response:
            config_path.write_bytes(response.read())
        actual = digest(config_path)
        if actual != config["digest"].split(":")[1]:
            raise SystemExit("config digest mismatch: %s" % actual)
    image_config = json.loads(config_path.read_text(encoding="utf-8")) if config_path.is_file() else {}

    (DOWNLOAD / "manifest.json").write_text(json.dumps({
        "repository": REPO,
        "tag": TAG,
        "architecture": ARCH,
        "indexDigest": index_digest,
        "manifestDigest": manifest_digest,
        "config": {"name": config_name, "sha256": config.get("digest", "").split(":")[-1]},
        "entrypoint": image_config.get("config", {}).get("Entrypoint"),
        "cmd": image_config.get("config", {}).get("Cmd"),
        "env": image_config.get("config", {}).get("Env"),
        "layers": records,
    }, indent=2) + "\n", encoding="utf-8")
    print("done: " + str(DOWNLOAD / "manifest.json"))


if __name__ == "__main__":
    main()
