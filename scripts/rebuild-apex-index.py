"""Rebuild the APEX portion of rootfs-index.json without re-extracting.

extract-apex.py used to record APEX-internal paths relative to the APEX root
("bin/dalvikvm64"). Those keys collide between APEXes and do not resolve under
rootfs/. This script:
  1. drops every entry whose path does not exist under rootfs/ (the stale
     relative APEX keys, and only those);
  2. walks every expanded APEX and records apex/<name>/<relative> as a dir,
     file or symlink entry.

It does not touch the already extracted payload.
"""
import json
import os
import pathlib
import posixpath
import sys

MODULE = pathlib.Path(__file__).resolve().parent.parent
ROOT = MODULE / "rootfs"
INDEX = MODULE / "rootfs-index.json"


def main():
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    entries = index["entries"]
    removed = 0
    for key in list(entries):
        # lexists: a dangling symlink still exists as an index entry.
        if not os.path.lexists(str(ROOT / key)):
            del entries[key]
            removed += 1
    added = 0
    apex_root = ROOT / "apex"
    for apex in sorted(apex_root.iterdir()):
        if not apex.is_dir() or not (apex / ".expanded").is_file():
            continue
        name = apex.name
        for directory, dirnames, filenames in os.walk(apex):
            rel_dir = os.path.relpath(directory, apex).replace("\\", "/")
            if rel_dir == ".":
                rel_dir = ""
            for item in dirnames + filenames:
                path = posixpath.join(rel_dir, item) if rel_dir else item
                full = "apex/%s/%s" % (name, path)
                disk = apex / path
                stat = disk.stat()
                if disk.is_symlink():
                    entries[full] = {"path": full, "type": "symlink",
                                     "target": os.readlink(disk), "mode": 0o777,
                                     "uid": 0, "gid": 0, "mtime": 0}
                elif disk.is_dir():
                    entries[full] = {"path": full, "type": "dir",
                                     "mode": stat.st_mode & 0o7777,
                                     "uid": 0, "gid": 0, "mtime": 0}
                else:
                    entries[full] = {"path": full, "type": "file",
                                     "mode": stat.st_mode & 0o7777,
                                     "uid": 0, "gid": 0, "mtime": 0,
                                     "bytes": stat.st_size}
                added += 1
        print("indexed %s" % name)
    INDEX.write_text(json.dumps(index, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({"removedStale": removed, "addedApexEntries": added,
                      "total": len(entries)}))


if __name__ == "__main__":
    sys.exit(main())
