#!/usr/bin/env python3
"""Extract the Redroid 15 layer(s) into a regular-file tree plus a full index.

Windows cannot create the guest's device nodes and usually cannot create its
symlinks without elevation, so this script extracts regular files only and keeps
every entry's type, mode, owner and link target in `rootfs-index.json`.  The A15
stager reconstructs symlinks and hard links in the APK manifest from that index.

The image is a Docker layer list, so the layers are applied in order and OCI
whiteouts are honoured.  Redroid's Android 15 images are currently a single
layer, but reading the manifest instead of hard-coding a file name keeps the
tool correct when that changes.

Usage:
    python scripts/extract-rootfs.py [--layer <tar.gz>] [--tree <dir>]
"""
import json
import os
import posixpath
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOWNLOAD = ROOT / "downloads"
MANIFEST = DOWNLOAD / "manifest.json"
TREE = ROOT / "rootfs"
INDEX = ROOT / "rootfs-index.json"

# Directories an Android image mounts at runtime; their contents come from the
# host or from GuestRuntime, never from the image layer.
VIRTUAL = {"dev", "proc", "sys", "data", "mnt", "acct", "cache", "config", "d",
           "bugreports", "metadata", "storage", "sdcard", "tmp", "run", "lost+found",
           "postinstall", "second_stage_resources"}


def normalize(name):
    name = name.replace("\\", "/")
    while name.startswith("./"):
        name = name[2:]
    name = name.lstrip("/")
    return posixpath.normpath(name) if name else ""


def allowed(path):
    if not path or path == ".":
        return True
    top = path.split("/", 1)[0]
    return top not in VIRTUAL


def long_path(path):
    absolute = str(path.resolve())
    if os.name == "nt" and not absolute.startswith("\\\\?\\"):
        return "\\\\?\\" + absolute
    return absolute


def layer_files():
    if "--layer" in sys.argv:
        return [Path(sys.argv[sys.argv.index("--layer") + 1])]
    if not MANIFEST.is_file():
        raise SystemExit("missing %s; run scripts/fetch-redroid15.py first" % MANIFEST)
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    layers = []
    for entry in manifest.get("layers", []):
        path = Path(entry.get("path", ""))
        if not path.is_file():
            fallback = DOWNLOAD / entry.get("name", "")
            if fallback.is_file():
                path = fallback
        if not path.is_file():
            raise SystemExit("missing layer blob: %s" % path)
        layers.append(path)
    if not layers:
        raise SystemExit("manifest has no layers")
    return layers


def remove_path(path):
    target = TREE / path
    if target.is_dir() and not target.is_symlink():
        for child in sorted(target.rglob("*"), reverse=True):
            if child.is_dir() and not child.is_symlink():
                child.rmdir()
            else:
                child.unlink(missing_ok=True)
        target.rmdir()
    elif target.exists() or target.is_symlink():
        target.unlink(missing_ok=True)


def main():
    layers = layer_files()
    TREE.mkdir(parents=True, exist_ok=True)
    entries = {}
    hardlinks = []
    total = 0
    for layer in layers:
        print("layer: %s (%.1f MiB)" % (layer.name, layer.stat().st_size / (1 << 20)),
              flush=True)
        with tarfile.open(layer, "r:*") as archive:
            for member in archive:
                path = normalize(member.name)
                if not allowed(path):
                    continue
                base = posixpath.basename(path)
                # OCI whiteout metadata: delete the named entry from lower layers.
                if base == ".wh..wh..opq":
                    directory = posixpath.dirname(path)
                    for key in [key for key in entries if key == directory or
                                key.startswith(directory + "/")]:
                        remove_path(key)
                        entries.pop(key, None)
                    continue
                if base.startswith(".wh."):
                    remove_path(posixpath.join(posixpath.dirname(path), base[4:]))
                    entries.pop(posixpath.join(posixpath.dirname(path), base[4:]), None)
                    continue
                mode = member.mode & 0o7777
                record = {"path": path, "mode": mode, "uid": member.uid, "gid": member.gid,
                          "mtime": int(member.mtime)}
                if member.isdir():
                    record["type"] = "dir"
                    entries[path] = record
                    (TREE / path).mkdir(parents=True, exist_ok=True)
                    continue
                if member.islnk():
                    record["type"] = "hardlink"
                    record["target"] = normalize(member.linkname)
                    entries[path] = record
                    hardlinks.append(record)
                    continue
                if member.issym():
                    record["type"] = "symlink"
                    record["target"] = member.linkname
                    entries[path] = record
                    continue
                if not member.isfile():
                    record["type"] = "special"
                    entries[path] = record
                    continue
                record["type"] = "file"
                record["bytes"] = member.size
                entries[path] = record
                destination = TREE / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise SystemExit("unreadable member: " + path)
                with open(long_path(destination), "wb") as output:
                    while True:
                        block = source.read(8 << 20)
                        if not block:
                            break
                        output.write(block)
                total += member.size
                if len(entries) % 2000 == 0:
                    print("  entries=%d files=%.1f MiB" % (len(entries), total / (1 << 20)),
                          flush=True)
    for record in hardlinks:
        source = TREE / record["target"]
        destination = TREE / record["path"]
        if not source.is_file():
            record["type"] = "unresolved-hardlink"
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            destination.unlink()
        try:
            os.link(long_path(source), long_path(destination))
        except OSError:
            destination.write_bytes(source.read_bytes())
    INDEX.write_text(json.dumps({"layers": [str(layer) for layer in layers],
                                 "entries": entries}, indent=1) + "\n", encoding="utf-8")
    counts = {}
    for record in entries.values():
        counts[record["type"]] = counts.get(record["type"], 0) + 1
    print(json.dumps({"entries": len(entries), "types": counts,
                      "bytes": sum(item.get("bytes", 0) for item in entries.values())},
                     indent=2))


if __name__ == "__main__":
    main()
