#!/usr/bin/env python3
"""Fail when a method in a release APK needs more than 255 Dalvik registers.

Android's verifier rejects a class whose method is wrongly encoded, and the whole class is lost at
load time. R8 9.2.14 mis-encodes methods with more than 256 registers: an instruction whose operand
is an 8-bit register field got the low byte of a register above v255 (`and-int/lit8 v8, v4, #3`
where v260 was meant). The 0.8.1 candidate fe24e12c shipped App's theme content lambda at 261
registers, and every release build crashed at launch with a VerifyError. Debug builds are not
affected (no R8), so only a check of the release DEX catches it.

The rule is simple on purpose: no method of the APK may need more than --limit registers (255 by
default, the most an 8-bit operand can name in full). A violation names the method in its
obfuscated and, with --mapping, its original form.

Usage:
    tools/release/check-dex-registers.py [--mapping mapping.txt] [--top N] APK [APK ...]

dexdump comes from the newest build-tools of the Android SDK named by --dexdump, $ANDROID_HOME,
$ANDROID_SDK_ROOT or local.properties' sdk.dir. Exit status: 0 clean, 1 a method over the limit,
2 a usage or tool error.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LIMIT = 255


class ToolError(Exception):
    """A missing input or tool: the APK was not checked."""


def sdk_dirs() -> list[Path]:
    dirs = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                dirs.append(line.split("=", 1)[1].strip())
    dirs.append(str(Path.home() / "Library" / "Android" / "sdk"))
    dirs.append(str(Path.home() / "Android" / "Sdk"))
    return [Path(d) for d in dirs if d]


def version_key(name: str) -> tuple:
    return tuple(int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", name))


def find_dexdump(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise ToolError(f"no dexdump at {path}")
        return path
    for sdk in sdk_dirs():
        tools = sdk / "build-tools"
        if not tools.is_dir():
            continue
        for version in sorted((d.name for d in tools.iterdir()), key=version_key, reverse=True):
            candidate = tools / version / "dexdump"
            if candidate.is_file():
                return candidate
    raise ToolError("dexdump not found; pass --dexdump or set ANDROID_HOME")


def methods_of(dexdump: Path, dex: Path):
    """Yields (class descriptor, method name, type descriptor, registers) for every method with code."""
    result = subprocess.run([str(dexdump), str(dex)], capture_output=True, text=True, errors="replace")
    if result.returncode != 0:
        raise ToolError(f"dexdump failed on {dex.name}: {result.stderr.strip()[:200]}")
    output = result.stdout
    cls = name = type_ = None
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("Class descriptor"):
            cls = stripped.split(":", 1)[1].strip().strip("'")
        elif stripped.startswith("name") and ":" in stripped:
            name = stripped.split(":", 1)[1].strip().strip("'")
        elif stripped.startswith("type") and ":" in stripped:
            type_ = stripped.split(":", 1)[1].strip().strip("'")
        elif stripped.startswith("registers") and ":" in stripped:
            yield cls, name, type_, int(stripped.split(":", 1)[1])


def java_name(descriptor: str) -> str:
    return descriptor[1:-1].replace("/", ".") if descriptor.startswith("L") else descriptor


class Mapping:
    """The original names R8's mapping.txt gives an obfuscated class and method."""

    _class_line = re.compile(r"^(\S+) -> (\S+):$")
    # [start:end:]returnType [qualifier.]name(args)[:line[:line]] -> obfuscatedName
    _member_line = re.compile(r"^\s+(?:(\d+:\d+):)?\S+ ([^\s(]+)\([^)]*\)(?::\d+(?::\d+)?)? -> (\S+)$")

    def __init__(self, path: Path):
        self.classes: dict[str, str] = {}
        self.methods: dict[tuple[str, str], list[str]] = {}
        original = obfuscated = None
        group_key = group_last = None

        def flush():
            if group_last is not None:
                self._add(obfuscated, group_last[1], group_last[0], original)

        with path.open(encoding="utf-8", errors="replace") as lines:
            for line in lines:
                if line.startswith("#"):
                    continue
                match = self._class_line.match(line)
                if match:
                    flush()
                    group_key = group_last = None
                    original, obfuscated = match.groups()
                    self.classes[obfuscated] = original
                    continue
                match = self._member_line.match(line)
                if not match or obfuscated is None:
                    continue
                line_range, name, target = match.groups()
                # Lines sharing a pc range and a target list the inlined frames innermost first; the
                # last one is the method the code belongs to.
                key = (line_range, target)
                if line_range is not None and key == group_key:
                    group_last = (name, target)
                    continue
                flush()
                group_key, group_last = key, (name, target)
        flush()

    def _add(self, obfuscated_class, obfuscated_name, name, original_class):
        if "." in name:
            qualifier, _, simple = name.rpartition(".")
            # R8 writes its synthesized frames as SimpleClass.method; other qualifiers are inlinees.
            if qualifier != original_class.rpartition(".")[2] and qualifier != original_class:
                return
            name = simple
        names = self.methods.setdefault((obfuscated_class, obfuscated_name), [])
        if name not in names:
            names.append(name)

    def describe(self, descriptor: str, method: str) -> str:
        cls = java_name(descriptor)
        original = self.classes.get(cls)
        if original is None:
            return f"{cls}.{method}"
        names = self.methods.get((cls, method)) or [method]
        return f"{original}.{' | '.join(names)}"


def check(apks: list[Path], dexdump: Path, mapping: Mapping | None, limit: int, top: int) -> bool:
    clean = True
    for apk in apks:
        rows = []
        with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as scratch:
            dex_names = sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            if not dex_names:
                raise ToolError(f"{apk} holds no classes*.dex")
            for dex_name in dex_names:
                dex = Path(scratch) / dex_name
                dex.write_bytes(archive.read(dex_name))
                for cls, name, type_, registers in methods_of(dexdump, dex):
                    rows.append((registers, dex_name, cls, name, type_))
        rows.sort(key=lambda row: row[0], reverse=True)
        over = [row for row in rows if row[0] > limit]
        status = "FAIL" if over else "ok"
        print(f"{apk.name}: {status}: {len(rows)} methods in {len(dex_names)} dex, "
              f"most registers {rows[0][0] if rows else 0} (limit {limit})")
        for registers, dex_name, cls, name, type_ in over or rows[:top]:
            label = mapping.describe(cls, name) if mapping else f"{java_name(cls)}.{name}"
            marker = "  OVER" if registers > limit else "      "
            print(f"{marker} {registers:4d}  {dex_name} {java_name(cls)}.{name}{type_[:60]}"
                  f"{'...' if len(type_) > 60 else ''}")
            if mapping:
                print(f"              = {label}")
        clean = clean and not over
    return clean


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("apks", nargs="+", type=Path, help="release APKs to check")
    parser.add_argument("--mapping", type=Path, help="R8 mapping.txt of the build, for original names")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="most registers a method may need")
    parser.add_argument("--top", type=int, default=3, help="heaviest methods to list when all pass")
    parser.add_argument("--dexdump", help="path to dexdump (default: newest SDK build-tools)")
    args = parser.parse_args()
    for apk in args.apks:
        if not apk.is_file():
            print(f"error: no APK at {apk}", file=sys.stderr)
            return 2
    if args.mapping is not None and not args.mapping.is_file():
        print(f"error: no mapping file at {args.mapping}", file=sys.stderr)
        return 2
    try:
        dexdump = find_dexdump(args.dexdump)
        mapping = Mapping(args.mapping) if args.mapping else None
        return 0 if check(args.apks, dexdump, mapping, args.limit, args.top) else 1
    except (ToolError, OSError, zipfile.BadZipFile) as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
