#!/usr/bin/env python3
"""Regenerates limn-icons-tabler from a pinned upstream release.

Everything under limn-icons-tabler/src/main/{java,resources}/limn/icons/tabler is this
script's output. Edit the script, not the output.

    python3 scripts/generate-tabler-icons.py            # downloads the pinned tarball
    python3 scripts/generate-tabler-icons.py <dir>      # or uses an already-extracted one

The tarball is verified against SHA256 below before a byte of it is read. Bumping the
version means bumping both, running this, and running the module's tests, which rasterize
every icon that ships, so an upstream change that NanoSVG cannot draw fails the build
rather than shipping as an empty button.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

VERSION = "3.46.0"
TARBALL_URL = f"https://registry.npmjs.org/@tabler/icons/-/icons-{VERSION}.tgz"
TARBALL_SHA256 = "6d727ad0489854d2d7d07ba9baa6476af7ee415aaa2eba1adc0deab48556852b"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE = os.path.join(ROOT, "limn-icons-tabler")
JAVA_DIR = os.path.join(MODULE, "src", "main", "java", "limn", "icons", "tabler")
RES_DIR = os.path.join(MODULE, "src", "main", "resources", "limn", "icons", "tabler")

# Java keywords cannot be enum constants; no Tabler name currently collides, but a future
# release adding "new" or "class" would otherwise produce a module that does not compile.
JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
    "null", "_",
}


def fetch() -> str:
    """Downloads and verifies the pinned tarball; returns the extracted package directory."""
    tmp = tempfile.mkdtemp(prefix="tabler-")
    archive = os.path.join(tmp, "icons.tgz")
    print(f"fetching {TARBALL_URL}")
    urllib.request.urlretrieve(TARBALL_URL, archive)
    digest = hashlib.sha256(open(archive, "rb").read()).hexdigest()
    if digest != TARBALL_SHA256:
        raise SystemExit(f"checksum mismatch\n  expected {TARBALL_SHA256}\n  got      {digest}")
    with tarfile.open(archive) as tar:
        tar.extractall(tmp, filter="data")
    return os.path.join(tmp, "package")


def constant(name: str) -> str:
    text = re.sub(r"[^A-Za-z0-9]+", "_", name).upper().strip("_")
    if not text or text[0].isdigit():
        text = "I_" + text
    if text.lower() in JAVA_KEYWORDS:
        text += "_"
    return text


def type_name(category: str) -> str:
    words = re.sub(r"[^A-Za-z0-9]+", " ", category or "Uncategorised").split()
    return "Tabler" + "".join(word[:1].upper() + word[1:] for word in words)


def main() -> None:
    package = sys.argv[1] if len(sys.argv) > 1 else fetch()
    manifest = json.load(open(os.path.join(package, "icons.json")))

    # ---------------------------------------------------------------- resources
    os.makedirs(RES_DIR, exist_ok=True)
    blob = bytearray()
    index = []
    for style in ("outline", "filled"):
        directory = os.path.join(package, "icons", style)
        for file in sorted(os.listdir(directory)):
            if not file.endswith(".svg"):
                continue
            data = open(os.path.join(directory, file), "rb").read()
            index.append(f"{style}/{file[:-4]}\t{len(blob)}\t{len(data)}")
            blob += data

    with open(os.path.join(RES_DIR, "icons.blob"), "wb") as out:
        out.write(blob)
    with open(os.path.join(RES_DIR, "icons.index"), "w", encoding="utf-8") as out:
        out.write("\n".join(index) + "\n")
    shutil.copyfile(os.path.join(package, "LICENSE"), os.path.join(RES_DIR, "LICENSE.txt"))

    # ---------------------------------------------------------------- enums
    by_category: dict[str, list[str]] = {}
    for name in sorted(manifest):
        by_category.setdefault(manifest[name].get("category") or "", []).append(name)

    for stale in os.listdir(JAVA_DIR) if os.path.isdir(JAVA_DIR) else []:
        if stale.startswith("Tabler") and stale not in ("TablerIcon.java", "Tabler.java"):
            os.remove(os.path.join(JAVA_DIR, stale))
    os.makedirs(JAVA_DIR, exist_ok=True)

    filled = {line.split("\t")[0][len("filled/"):] for line in index if line.startswith("filled/")}
    types = []
    for category, names in sorted(by_category.items()):
        java_type = type_name(category)
        types.append((java_type, category or "Uncategorised", len(names)))
        used: set[str] = set()
        constants = []
        for name in names:
            text = constant(name)
            while text in used:
                text += "_"
            used.add(text)
            constants.append(f'    {text}("{name}")')
        body = ",\n".join(constants)
        label = category or "no category upstream"
        with open(os.path.join(JAVA_DIR, java_type + ".java"), "w", encoding="utf-8") as out:
            out.write(f'''package limn.icons.tabler;

/**
 * Tabler's <b>{label}</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all {sum(len(v) for v in by_category.values())}
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum {java_type} implements TablerIcon {{

{body};

    private final String iconName;

    {java_type}(String iconName) {{
        this.iconName = iconName;
    }}

    @Override
    public String iconName() {{
        return iconName;
    }}
}}
''')

    total = sum(count for _, _, count in types)
    print(f"blob {len(blob):,} bytes · {len(index):,} entries · "
          f"{len(types)} enums · {total:,} constants")
    for java_type, label, count in sorted(types, key=lambda t: -t[2])[:5]:
        print(f"  {java_type:<28} {label:<20} {count}")
    print(f"  filled variants available: {len(filled):,}")


if __name__ == "__main__":
    main()
