#!/usr/bin/env python3
"""Select Android 15's ARM64 libc sources from the pinned Blueprint files.

Compared with the Android 9 selector, Android 15 describes libc through
`defaults` + `filegroup` + per-variant `static`/`shared` blocks instead of one
flat source list, and pulls three external modules:

    gwp_asan            external/gwp_asan
    libunwind-exported  external/libunwind_llvm

The selector follows `defaults`, `whole_static_libs` and `:filegroup` references,
glob-expands ARM64 sources, and fails loudly on an unknown module instead of
silently producing a short list (a short list links, then crashes on the device).

Usage:
    python runtime/select-libc-sources.py <upstream> shared > build/generated/libc-sources.txt
    python runtime/select-libc-sources.py <upstream> static > build/generated/static-sources.txt
"""
import json
import pathlib
import re
import sys

# The unwinder lives in external/llvm-project/libunwind on AOSP 15, which is not
# fetchable as a standalone archive (gitiles returns 404 for that subtree). The
# NDK's own LLVM libunwind.a for aarch64 is the same implementation built for
# bionic, so build-libc.sh links it whole-archive into libc.so and this selector
# leaves the symbol ownership to that archive.
EXTERNAL_PRELINKED = {
    "libunwind-exported",
}

# Soong-provided defaults that only toggle properties, not sources.
CONFIG_DEFAULTS = {
    "linux_bionic_supported",
    "linux_glibc_supported",
    "libc_bootstrap",
    "libc_bootstrap_static",
    "libc_bootstrap_dynamic",
}


class Blueprint:
    def __init__(self, path):
        self.path = path
        self.directory = path.parent
        token_pattern = r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/|\+=|\w+|[{}\[\]:=,+()]'
        source = path.read_text(encoding="utf-8")
        self.tokens = []
        end = 0
        for match in re.finditer(token_pattern, source, re.S):
            if source[end:match.start()].strip():
                raise ValueError("Unparsed Blueprint text at %s:%d" % (path, end))
            token = match.group()
            if not token.startswith(("//", "/*")):
                self.tokens.append(token)
            end = match.end()
        self.position = 0
        self.variables = {}
        self.modules = {}
        while self.position < len(self.tokens):
            kind = self.take()
            if self.position < len(self.tokens) and self.tokens[self.position] == "{":
                module = self.value()
                if kind in ("cc_library", "cc_library_static", "cc_library_shared",
                            "cc_defaults", "filegroup", "cc_library_headers",
                            "cc_prebuilt_library_shared"):
                    name = module.get("name")
                    if name is None:
                        # soong_config_module_type instances have no name field we
                        # can use; they are not libc sources.
                        continue
                    if name in self.modules:
                        raise ValueError("Duplicate module %s in %s" % (name, path))
                    self.modules[name] = module
            else:
                operation = self.take()
                value = self.value()
                if operation == "=":
                    self.variables[kind] = value
                elif operation == "+=":
                    self.variables[kind] += value
                else:
                    raise ValueError("Unexpected assignment %s in %s" % (operation, path))

    def take(self, expected=None):
        token = self.tokens[self.position]
        self.position += 1
        if expected is not None and token != expected:
            raise ValueError("Expected %s, got %s in %s" % (expected, token, self.path))
        return token

    def value(self):
        token = self.take()
        if token == "{":
            result = {}
            while self.tokens[self.position] != "}":
                key = self.take()
                self.take(":")
                result[key] = self.value()
                if self.tokens[self.position] != "}":
                    self.take(",")
            self.take("}")
        elif token == "[":
            result = []
            while self.tokens[self.position] != "]":
                result.append(self.value())
                if self.tokens[self.position] != "]":
                    self.take(",")
            self.take("]")
        elif token.startswith('"'):
            result = json.loads(token)
        elif token in ("true", "false"):
            result = token == "true"
        elif token.isdecimal():
            result = int(token)
        elif self.position < len(self.tokens) and self.tokens[self.position] == "(":
            # Soong function call in a value position, e.g.
            #   cflags: select(release_flag("X"), { true: [...], default: [] })
            # The selector does not evaluate build configuration; the call is
            # skipped and contributes nothing. Sources are never expressed
            # through select() in the files this tool parses.
            depth = 0
            while self.position < len(self.tokens):
                item = self.take()
                if item == "(":
                    depth += 1
                elif item == ")":
                    depth -= 1
                    if depth == 0:
                        break
            result = []
        else:
            result = self.variables[token]
        if self.position < len(self.tokens) and self.tokens[self.position] == "+":
            self.take("+")
            result = result + self.value()
        return result


class Selector:
    def __init__(self, upstream, mode):
        self.upstream = upstream
        self.mode = mode
        self.modules = {}
        self.selected = set()
        self.visited = set()
        self.generated = set()
        self.prelinked = set()
        self.load(upstream / "bionic/libc/Android.bp")
        self.load(upstream / "bionic/libc/async_safe/Android.bp")
        self.load(upstream / "bionic/libc/system_properties/Android.bp")
        self.load(upstream / "system/core/property_service/libpropertyinfoparser/Android.bp")
        self.load(upstream / "bionic/libm/Android.bp")
        self.load(upstream / "external/gwp_asan/Android.bp")
        self.load(upstream / "external/scudo/Android.bp")
        self.load(upstream / "external/arm-optimized-routines/Android.bp")
        self.load(upstream / "external/llvm-libc/Android.bp")
        self.load(upstream / "external/libunwind_llvm/Android.bp")

    def load(self, path):
        if not path.is_file():
            raise SystemExit("missing blueprint: " + str(path))
        blueprint = Blueprint(path)
        for name, module in blueprint.modules.items():
            self.modules.setdefault(name, (module, blueprint.directory))

    def visit(self, name):
        # Fully-qualified module names ("//external/llvm-libc:llvmlibc") come from
        # modules outside bionic/libc. Every such repository is loaded up front.
        if name.startswith("//"):
            name = name.rpartition(":")[2]
        if name in self.visited:
            return
        self.visited.add(name)
        if name not in self.modules:
            # Soong configuration defaults (linux_bionic_supported, ...) only set
            # properties and contribute no sources. Anything else is a missing
            # module and must fail: silently skipping it would produce a libc that
            # links but crashes later.
            if name in CONFIG_DEFAULTS or name.endswith("_supported"):
                return
            if name in EXTERNAL_PRELINKED:
                self.prelinked.add(name)
                return
            raise ValueError("unknown module referenced by libc: " + name)
        module, directory = self.modules[name]
        layers = [module]
        for default in module.get("defaults", []):
            self.visit(default)
            if default not in self.modules:
                continue
            default_module, default_directory = self.modules[default]
            layers.append(default_module)
            layers.append(default_module.get(self.mode, {}))
            layers.append(default_module.get("arch", {}).get("arm64", {}))
        layers.append(module.get("arch", {}).get("arm64", {}))
        layers.append(module.get("multilib", {}).get("lib64", {}))
        # Soong's bionic target block carries the malloc wrappers and the
        # _BIONIC cflags for external allocators; ignoring it silently drops
        # wrappers_c_bionic.cpp and the resulting libc has no malloc family.
        layers.append(module.get("target", {}).get("bionic", {}))
        layers.append(module.get(self.mode, {}))
        for default in module.get("defaults", []):
            if default in self.modules:
                default_module, _ = self.modules[default]
                layers.append(default_module.get("target", {}).get("bionic", {}))
        for layer in layers:
            for dependency in layer.get("whole_static_libs", []):
                self.visit(dependency)
        excluded = set()
        for layer in layers:
            excluded.update(layer.get("exclude_srcs", []))
        for layer in layers:
            for pattern in layer.get("srcs", []):
                if pattern.startswith(":"):
                    self.visit_filegroup(pattern[1:])
                    continue
                if pattern in excluded:
                    continue
                matches = sorted(directory.glob(pattern))
                if not matches:
                    raise ValueError("missing %s source: %s" % (name, pattern))
                for path in matches:
                    if path.is_dir():
                        continue
                    self.selected.add(path.relative_to(self.upstream).as_posix())

    def visit_filegroup(self, name):
        if name in self.visited:
            return
        self.visited.add(name)
        if name not in self.modules:
            # Android 15 generates the syscall stubs with gensyscalls.py at build
            # time (genrule syscalls-arm64) instead of checking in one .S per
            # syscall. build-libc.sh runs the generator and appends the result;
            # skipping it here is explicit, not silent.
            if name.startswith("syscalls-"):
                self.generated.add(name)
                return
            raise ValueError("unknown filegroup: " + name)
        module, directory = self.modules[name]
        for pattern in module.get("srcs", []):
            if pattern.startswith(":"):
                self.visit_filegroup(pattern[1:])
                continue
            matches = sorted(directory.glob(pattern))
            if not matches:
                raise ValueError("missing %s source: %s" % (name, pattern))
            for path in matches:
                if not path.is_dir():
                    self.selected.add(path.relative_to(self.upstream).as_posix())

    def run(self, root):
        self.visit(root)
        if self.generated:
            sys.stderr.write("generated by build: %s\n" % ", ".join(sorted(self.generated)))
        for path in sorted(self.selected):
            if not (self.upstream / path).is_file():
                raise ValueError("missing selected input: " + path)
            print(path)


if __name__ == "__main__":
    upstream = pathlib.Path(sys.argv[1]).resolve()
    mode = sys.argv[2] if len(sys.argv) > 2 else "shared"
    if mode not in ("shared", "static"):
        raise SystemExit("mode must be shared or static")
    Selector(upstream, mode).run("libc")
