#!/usr/bin/env python3
"""Rewrites the backend's FfiShapes.java from the callback signatures its sources use.

Under Java 25 LWJGL builds a native callback with the platform's foreign-function
linker, which reads the C function's arguments from a Java method: the callback's
type must be a @FunctionalInterface whose one abstract method has the C
parameters. Ffi.callback and Wl.on take a signature as a string ("ppi>v": a
pointer, a pointer, a 32-bit integer, no result), so every distinct string needs
its own interface, and FfiShapes holds them with the one closure that answers
them all. Ffi.callback refuses a signature that has no shape, and FfiShapesTest
finds every signature in the sources and fails on one that has none: this script
is what that test sends you to.

It reads every Ffi.callback("...") and wl.on/wl.ignore("...") in the backend's
sources, writes one interface for each distinct signature, the map from
signature to interface, and the closure's method for each, and keeps the file's
header, its Javadoc and the closure's fixed members as they are. Run again on an
unchanged tree it writes the same bytes.

    ./scripts/generate-ffi-shapes.py

Requires nothing but the standard library, like every generator here.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "limn-backend-lwjgl" / "src" / "main" / "java"
TARGET = SOURCES / "limn" / "backend" / "lwjgl" / "transfer" / "FfiShapes.java"

JAVA_ARGUMENT = {"p": "long", "i": "int"}
JAVA_RESULT = {"p": "long", "i": "int", "v": "void"}


def signatures():
    found = set()
    for source in SOURCES.rglob("*.java"):
        text = source.read_text(encoding="utf-8")
        found |= {m.group(1) for m in re.finditer(r'Ffi\.callback\("([a-z>]+)"', text)}
        # A listener's events answer nothing: its signature gains the ">v".
        found |= {m.group(1) + ">v" for m in re.finditer(r'wl\.(?:on|ignore)\("([a-z]+)"', text)}
    for signature in found:
        arguments, _, result = signature.partition(">")
        if not arguments or set(arguments) - set(JAVA_ARGUMENT) or result not in JAVA_RESULT:
            sys.exit(f"generate-ffi-shapes: a signature this script cannot write: {signature!r}")
    return sorted(found, key=lambda s: (s.index(">"), s))


def shape(signature):
    arguments, _, result = signature.partition(">")
    return arguments.upper() + "_" + result.upper()


def method(signature):
    arguments, _, result = signature.partition(">")
    return arguments + "_" + result


def parameters(signature):
    arguments = signature.partition(">")[0]
    return ", ".join(f"{JAVA_ARGUMENT[c]} a{i}" for i, c in enumerate(arguments))


def main():
    all_signatures = signatures()
    text = TARGET.read_text(encoding="utf-8")
    # The header ends where the first shape begins; the closure's fixed members end where its
    # first shape method begins, after answer().
    head = text[:text.index("    /** {@code ")]
    closure_start = text.index("    /** The closure: the vector form")
    closure_end = text.index("        @Override\n        public ", text.index("private long answer("))
    closure = re.sub(r"implements [A-Z0-9_, ]+ \{",
                     "implements " + ", ".join(shape(s) for s in all_signatures) + " {",
                     text[closure_start:closure_end])
    out = [head]
    for s in all_signatures:
        result = s.partition(">")[2]
        out.append(f"    /** {{@code {s}}} */\n    @FunctionalInterface\n    interface {shape(s)} extends Shape {{\n"
                   f"        {JAVA_RESULT[result]} {method(s)}({parameters(s)});\n    }}\n\n")
    out.append("    /** Each signature's shape. */\n    static final Map<String, Class<? extends Shape>> SHAPES = Map.ofEntries(\n")
    out.append(",\n".join(f'            entry("{s}", {shape(s)}.class)' for s in all_signatures) + ");\n\n")
    out.append(closure)
    for s in all_signatures:
        arguments, _, result = s.partition(">")
        names = ", ".join(f"a{i}" for i in range(len(arguments)))
        body = {"v": f"answer({names});", "i": f"return (int) answer({names});"}.get(result, f"return answer({names});")
        out.append(f"        @Override\n        public {JAVA_RESULT[result]} {method(s)}({parameters(s)}) {{\n"
                   f"            {body}\n        }}\n\n")
    written = "".join(out).rstrip("\n") + "\n    }\n}\n"
    if written != text:
        TARGET.write_text(written, encoding="utf-8")
        print(f"generate-ffi-shapes: {len(all_signatures)} shapes written to {TARGET.relative_to(ROOT)}")
    else:
        print(f"generate-ffi-shapes: {len(all_signatures)} shapes, already up to date")


if __name__ == "__main__":
    main()
