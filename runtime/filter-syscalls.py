#!/usr/bin/env python3
"""Drop generated syscall stubs that the project's own bridges implement.

Android 15 generates every syscall stub with gensyscalls.py. The A15 runtime
already replaces a subset of them in vendor/runtime/filesystem (container path
and socket redirection), so linking both definitions is a duplicate-symbol
error. This filter removes exactly the ENTRY(name) ... END(name) blocks whose
name is defined by a vendor object; it fails when the input no longer looks like
gensyscalls output.

Usage:
    filter-syscalls.py <syscalls.S> <vendor-symbols.txt> <output.S>
"""
import re
import sys

ENTRY = re.compile(r"^\s*ENTRY\(([A-Za-z_][A-Za-z0-9_]*)\)")
END = re.compile(r"^\s*END\(([A-Za-z_][A-Za-z0-9_]*)\)")


def main():
    source, symbols, output = sys.argv[1:4]
    with open(symbols, encoding="utf-8") as handle:
        excluded = {line.strip() for line in handle if line.strip()}
    with open(source, encoding="utf-8") as handle:
        lines = handle.read().splitlines()

    result = []
    removed = []
    index = 0
    entries = 0
    while index < len(lines):
        match = ENTRY.match(lines[index])
        if match is None:
            result.append(lines[index])
            index += 1
            continue
        entries += 1
        name = match.group(1)
        if name not in excluded:
            result.append(lines[index])
            index += 1
            continue
        while index < len(lines) and END.match(lines[index]) is None:
            index += 1
        if index >= len(lines):
            raise SystemExit("filter-syscalls: unterminated ENTRY(%s)" % name)
        removed.append(name)
        index += 1
        while index < len(lines) and not lines[index].strip():
            index += 1

    if entries == 0:
        raise SystemExit("filter-syscalls: no ENTRY blocks in " + source)
    with open(output, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(result) + "\n")
    print("filtered syscalls: " + ", ".join(sorted(removed)))


if __name__ == "__main__":
    main()
