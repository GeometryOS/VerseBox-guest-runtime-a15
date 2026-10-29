#!/usr/bin/env python3
"""Expand the Redroid 15 APEX archives into ordinary directories.

Android 15 ships ART, framework resources and many system components inside
`/system/apex/*.apex`.  A real device mounts those files through `apexed`; this
project runs the guest userland without init, so the payloads must be expanded
into a plain directory tree before `prepare-a15.py` can see them.

An APEX is a zip whose `apex_payload.img` member is an ext4 filesystem image.
The image is read here with a small read-only ext4 parser (extents, 64-bit
sizes, linear directories, fast and slow symlinks).  It deliberately does not
implement journal replay, checksums or writing: the payloads are immutable and
read once.

Output layout follows the mounted layout the guest expects:

    rootfs/system/apex/com.android.art.apex   (original zip, kept)
        -> rootfs/apex/com.android.art/...    (expanded payload)

Every extracted entry is appended to `rootfs-index.json` so the stager can
recreate modes, ownership, hard links and symlinks in the APK manifest.

Usage:
    python scripts/extract-apex.py [--only com.android.art] [--verify]
"""
import json
import os
import posixpath
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TREE = ROOT / "rootfs"
APEX_SOURCE = TREE / "system" / "apex"
APEX_TARGET = TREE / "apex"
INDEX = ROOT / "rootfs-index.json"

EXT4_MAGIC = 0xEF53
EXT4_EXTENTS_FL = 0x00080000
EXT4_INLINE_DATA_FL = 0x10000000
S_IFMT = 0xF000
S_IFDIR = 0x4000
S_IFREG = 0x8000
S_IFLNK = 0xA000


class Ext4:
    def __init__(self, path):
        self.handle = open(path, "rb")
        self.handle.seek(1024)
        superblock = self.handle.read(1024)
        if len(superblock) < 1024:
            raise ValueError("truncated ext4 superblock: %s" % path)
        (magic,) = struct.unpack_from("<H", superblock, 0x38)
        if magic != EXT4_MAGIC:
            raise ValueError("not an ext4 image (magic 0x%04x): %s" % (magic, path))
        self.inodes_count, = struct.unpack_from("<I", superblock, 0x00)
        self.blocks_count_lo, = struct.unpack_from("<I", superblock, 0x04)
        self.first_data_block, = struct.unpack_from("<I", superblock, 0x14)
        log_block_size, = struct.unpack_from("<I", superblock, 0x18)
        self.block_size = 1024 << log_block_size
        blocks_per_group, = struct.unpack_from("<I", superblock, 0x20)
        self.inodes_per_group, = struct.unpack_from("<I", superblock, 0x28)
        self.inode_size, = struct.unpack_from("<H", superblock, 0x58)
        self.feature_incompat, = struct.unpack_from("<I", superblock, 0x60)
        self.desc_size = 64 if self.feature_incompat & 0x80 else 32
        self.blocks_count = self.blocks_count_lo
        if self.feature_incompat & 0x80:
            high, = struct.unpack_from("<I", superblock, 0x150)
            self.blocks_count |= high << 32
        self.groups = (self.blocks_count - self.first_data_block + blocks_per_group - 1) // blocks_per_group
        self.group_desc = self.read_block(self.first_data_block + 1, self.desc_size * self.groups)
        self.cache = {}

    def close(self):
        self.handle.close()

    def read(self, offset, size):
        self.handle.seek(offset)
        return self.handle.read(size)

    def read_block(self, number, size=None):
        size = self.block_size if size is None else size
        return self.read(number * self.block_size, size)

    def inode_table(self, group):
        base = group * self.desc_size
        # ext4_group_desc: bg_inode_table_lo at 0x08, bg_inode_table_hi at 0x28
        # (the hi fields are not adjacent to the lo fields).
        lo, = struct.unpack_from("<I", self.group_desc, base + 0x08)
        hi = 0
        if self.desc_size >= 64:
            hi, = struct.unpack_from("<I", self.group_desc, base + 0x28)
        return (hi << 32) | lo

    def inode(self, number):
        if number in self.cache:
            return self.cache[number]
        if number < 1 or number > self.inodes_count:
            raise ValueError("inode out of range: %d" % number)
        group = (number - 1) // self.inodes_per_group
        index = (number - 1) % self.inodes_per_group
        offset = self.inode_table(group) * self.block_size + index * self.inode_size
        raw = self.read(offset, self.inode_size)
        if len(raw) != self.inode_size:
            raise ValueError("truncated inode %d" % number)
        mode, = struct.unpack_from("<H", raw, 0x00)
        size_lo, = struct.unpack_from("<I", raw, 0x04)
        flags, = struct.unpack_from("<I", raw, 0x20)
        blocks = raw[0x28:0x28 + 60]
        size_hi, = struct.unpack_from("<I", raw, 0x6C)
        size = size_lo | (size_hi << 32)
        record = {"number": number, "mode": mode, "size": size, "flags": flags,
                  "block": blocks, "raw": raw}
        self.cache[number] = record
        return record

    def extents(self, inode):
        """Return [(logical_block, physical_block, length)] from the extent tree."""
        result = []

        def walk(node, depth):
            magic, entries, _max, node_depth = struct.unpack_from("<HHHH", node, 0)
            if magic != 0xF30A:
                raise ValueError("bad extent header 0x%04x" % magic)
            for i in range(entries):
                base = 12 + i * 12
                if node_depth == 0:
                    logical, length, start_hi, start_lo = struct.unpack_from("<IHHI", node, base)
                    length = length & 0x7FFF
                    if length:
                        result.append((logical, (start_hi << 32) | start_lo, length))
                else:
                    _logical, leaf_lo, leaf_hi = struct.unpack_from("<IIH", node, base)
                    child = (leaf_hi << 32) | leaf_lo
                    walk(self.read_block(child), depth + 1)

        walk(inode["block"], 0)
        return result

    def read_file(self, inode):
        if inode["flags"] & EXT4_INLINE_DATA_FL:
            raise ValueError("inline-data inode is not supported (inode %d)" % inode["number"])
        data = bytearray(inode["size"])
        for logical, physical, length in self.extents(inode):
            start = logical * self.block_size
            if start >= inode["size"]:
                continue
            chunk = min(length * self.block_size, inode["size"] - start)
            block = self.read_block(physical, chunk)
            data[start:start + len(block)] = block
        return bytes(data)

    def readlink(self, inode):
        if inode["size"] < 60 and not (inode["flags"] & EXT4_EXTENTS_FL):
            return inode["block"][:inode["size"]].decode("utf-8", "replace")
        return self.read_file(inode).decode("utf-8", "replace")

    def directory(self, inode):
        data = self.read_file(inode)
        entries = []
        position = 0
        while position + 8 <= len(data):
            number, rec_len, name_len, file_type = struct.unpack_from("<IHBB", data, position)
            if rec_len < 8:
                break
            name = data[position + 8:position + 8 + name_len].decode("utf-8", "replace")
            if number and name not in (".", ".."):
                entries.append((name, number))
            position += rec_len
        return entries


def walk(image, inode_number, prefix, entries, target):
    """Recursively materialise an ext4 directory tree."""
    inode = image.inode(inode_number)
    for name, number in image.directory(inode):
        path = posixpath.join(prefix, name)
        child = image.inode(number)
        kind = child["mode"] & S_IFMT
        if kind == S_IFDIR:
            entries[path] = {"path": path, "type": "dir", "mode": child["mode"] & 0o7777,
                             "uid": 0, "gid": 0, "mtime": 0}
            (target / path).mkdir(parents=True, exist_ok=True)
            walk(image, number, path, entries, target)
        elif kind == S_IFLNK:
            entries[path] = {"path": path, "type": "symlink", "mode": 0o777,
                             "uid": 0, "gid": 0, "mtime": 0,
                             "target": image.readlink(child)}
        elif kind == S_IFREG:
            entries[path] = {"path": path, "type": "file", "mode": child["mode"] & 0o7777,
                             "uid": 0, "gid": 0, "mtime": 0, "bytes": child["size"]}
            destination = target / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            with open(long_path(destination), "wb") as output:
                output.write(image.read_file(child))
        else:
            entries[path] = {"path": path, "type": "special", "mode": child["mode"] & 0o7777,
                             "uid": 0, "gid": 0, "mtime": 0}


def long_path(path):
    absolute = str(path.resolve())
    if os.name == "nt" and not absolute.startswith("\\\\?\\"):
        return "\\\\?\\" + absolute
    return absolute


def main():
    only = None
    if "--only" in sys.argv:
        only = sys.argv[sys.argv.index("--only") + 1]
    verify = "--verify" in sys.argv
    if not APEX_SOURCE.is_dir():
        raise SystemExit("missing %s; run scripts/extract-rootfs.py first" % APEX_SOURCE)
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    entries = index["entries"]
    expanded = []
    for apex in sorted(APEX_SOURCE.glob("*.apex")):
        name = apex.name[:-len(".apex")]
        if only and name != only:
            continue
        target = APEX_TARGET / name
        marker = target / ".expanded"
        if marker.is_file() and not verify:
            print("cached %s (already expanded)" % name)
            expanded.append(name)
            continue
        with zipfile.ZipFile(apex) as archive:
            try:
                payload = archive.read("apex_payload.img")
            except KeyError:
                payload = None
                try:
                    payload_zip = archive.read("apex_payload.zip")
                except KeyError:
                    payload_zip = None
                if payload_zip is None:
                    print("skip %s: no apex_payload.img / apex_payload.zip" % name)
                    continue
        if payload is None:
            # Some APEXes carry the payload as a nested zip instead of ext4.
            import io
            with zipfile.ZipFile(io.BytesIO(payload_zip)) as nested:
                for item in nested.infolist():
                    path = posixpath.normpath(item.filename)
                    if item.is_dir():
                        (target / path).mkdir(parents=True, exist_ok=True)
                        entries["apex/%s/%s" % (name, path)] = {
                            "path": "apex/%s/%s" % (name, path), "type": "dir",
                            "mode": 0o755, "uid": 0, "gid": 0, "mtime": 0}
                    else:
                        data = nested.read(item)
                        destination = target / path
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        destination.write_bytes(data)
                        entries["apex/%s/%s" % (name, path)] = {
                            "path": "apex/%s/%s" % (name, path), "type": "file",
                            "mode": 0o644, "uid": 0, "gid": 0, "mtime": 0,
                            "bytes": len(data)}
            expanded.append(name)
            marker.write_text("apex=%s (zip)\n" % name, encoding="utf-8")
            print("expanded %s (zip payload)" % name)
            continue

        # Write the ext4 payload to a temporary file: the parser works on a real
        # file handle, and the payload is >30 MB for com.android.art.
        scratch = ROOT / "downloads" / (name + ".payload.img")
        scratch.parent.mkdir(parents=True, exist_ok=True)
        scratch.write_bytes(payload)
        image = Ext4(scratch)
        try:
            entries["apex/%s" % name] = {"path": "apex/%s" % name, "type": "dir",
                                         "mode": 0o755, "uid": 0, "gid": 0, "mtime": 0}
            target.mkdir(parents=True, exist_ok=True)
            before = set(entries)
            walk(image, 2, "", entries, target)
            # walk() records paths relative to the APEX root ("bin/...",
            # "lib64/..."). Several APEXes contain the same relative paths, so
            # the stager needs them lifted under apex/<name>/.
            lifted = [k for k in entries if k not in before]
            for key in lifted:
                entry = entries.pop(key)
                full = "apex/%s/%s" % (name, key)
                entry["path"] = full
                entries[full] = entry
            print("expanded %s: %d entries" % (name, len(lifted) + 1))
            marker.write_text("apex=%s\n" % name, encoding="utf-8")
        finally:
            image.close()
            scratch.unlink(missing_ok=True)
        expanded.append(name)

    if not verify:
        INDEX.write_text(json.dumps(index, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({"expanded": expanded, "apexDirectories": len(expanded)}, indent=2))


if __name__ == "__main__":
    main()
