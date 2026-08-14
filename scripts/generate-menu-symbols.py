#!/usr/bin/env python3
"""Builds the menu key-symbol face the accelerator hints are drawn with.

A macOS menu spells a shortcut in symbols: Cmd+Shift+S is written as an
unbroken run of glyphs. Roboto has none of them and neither do the Noto
fallbacks the toolkit already vendors, so a hint written that way would draw a
row of .notdef boxes, which is worse than the words it replaced.

No single Noto face carries the whole set. Measured against the twenty code
points below: Noto Sans Symbols 2 has nine, Noto Sans Symbols eight (including
the Control caret the other lacks), Noto Sans Math the last three. So the face
this writes is a *merge*, and the merge is only reasonable because all three
sources share unitsPerEm 1000 and every glyph in the set is a simple outline:
no scaling and no component recursion, so the glyph bytes are copied verbatim.
Both facts are checked at build time rather than assumed; a future source that
breaks either stops the build instead of shipping a distorted symbol.

The output is a few kilobytes and is committed, unlike the CJK and emoji faces
that this script's sibling fetches. That is the point of subsetting it: the
heavy fallbacks arrive on a background parse, so a menu opened in the first
moments of a run would draw boxes where its hints belong, and a menu is exactly
what a new user opens first.

    ./scripts/generate-menu-symbols.py [--force]

Requires nothing but the standard library, like every generator here. Sources
are pinned to a commit and verified against a SHA-256 before a byte is parsed.
"""

import argparse
import hashlib
import struct
import sys
import urllib.request
from pathlib import Path

# The built Noto binaries, pinned. Moving the pin means changing the commit and
# all three digests together, then re-running and looking at `--scene fonts`.
PIN = "7b316148e5b3d1c701db0138fb33a9e613573ade"
BASE = f"https://raw.githubusercontent.com/notofonts/notofonts.github.io/{PIN}/fonts"

# Priority order: the first source that has a code point supplies it. Symbols 2
# leads because the modifier keys (the glyphs in nearly every hint) are its.
SOURCES = [
    ("NotoSansSymbols2", f"{BASE}/NotoSansSymbols2/unhinted/ttf/NotoSansSymbols2-Regular.ttf",
     "c4a0a80f0041ce4be81e2478faad22776d23edb98ae3f0d19bd37044820ecf9d"),
    ("NotoSansSymbols", f"{BASE}/NotoSansSymbols/unhinted/ttf/NotoSansSymbols-Regular.ttf",
     "6eea9cb4cd39269ea9f95ba5c2735f80ae74049dfc9e1a7c932a5cfc8f0c3030"),
    ("NotoSansMath", f"{BASE}/NotoSansMath/unhinted/ttf/NotoSansMath-Regular.ttf",
     "b127e84699212b6b2ef50aff58e0ebebeec04ffe6db1b9eb9e209c8c3d97b4aa"),
]

# The key symbols a menu row can show, and what each one is. Every one of them
# is in the BMP, which is what lets the output carry a single format 4 cmap.
CODE_POINTS = {
    0x2318: "Command",
    0x2325: "Option",
    0x2303: "Control",
    0x21E7: "Shift",
    0x21EA: "Caps Lock",
    0x23CE: "Return",
    0x2324: "Enter",
    0x232B: "Delete (backwards)",
    0x2326: "Delete (forwards)",
    0x21E5: "Tab",
    0x238B: "Escape",
    0x2423: "Space",
    0x21DE: "Page Up",
    0x21DF: "Page Down",
    0x2196: "Home",
    0x2198: "End",
    0x2190: "Left",
    0x2191: "Up",
    0x2192: "Right",
    0x2193: "Down",
}

FAMILY = "Limn Menu Symbols"
DEST = (Path(__file__).resolve().parent.parent
        / "limn-backend-lwjgl/src/main/resources/limn/backend/lwjgl/fonts")
OUTPUT = DEST / "LimnMenuSymbols.ttf"

# The OFL permits a subset and a merge and requires the licence and the copyright
# notices to travel with what they produced, which is why both source projects
# are named below and not just the one most glyphs came from.
#
# These faces carry no Reserved Font Name, so the output could legally keep a Noto
# name; it deliberately does not, because it is not any of them. Naming it Noto
# would attribute a merge of three projects to one of them.
#
# Pinned like the fonts, and NOT taken from the notofonts.github.io repository the
# binaries come from: that repository's own LICENSE is Apache 2.0, covering its
# tooling, and shipping it beside these glyphs would state the wrong licence for
# them in the one file whose whole job is to state the right one.
LICENCES = [
    ("https://raw.githubusercontent.com/notofonts/symbols/"
     "866ec5e8d715f2ee60efa5ff7495acc218d459a7/OFL.txt",
     "b118dd41337806a5d4797052c77caf3bd096aed783e5eb21b4d11154351e1ac0"),
    ("https://raw.githubusercontent.com/notofonts/math/"
     "fbb2a1334f1d693c3c863b3b694ffadf75094b36/OFL.txt",
     "403a95275b469061b7d4371c328e0ada3bc7d63328abe2e88aad5cd243b2fe21"),
]
LICENCE_FILE = DEST / "LimnMenuSymbols-LICENSE.txt"


class Ttf:
    """The little of a TrueType file this needs to read: outlines and metrics."""

    def __init__(self, data):
        self.data = data
        if data[:4] not in (b"\x00\x01\x00\x00", b"true"):
            raise ValueError("not a TrueType file with glyf outlines")
        count = self.u16(4)
        self.tables = {}
        for i in range(count):
            record = 12 + 16 * i
            tag = data[record:record + 4].decode("latin1")
            self.tables[tag] = (self.u32(record + 8), self.u32(record + 12))
        for required in ("head", "hhea", "maxp", "hmtx", "cmap", "loca", "glyf"):
            if required not in self.tables:
                raise ValueError(f"no {required} table")
        head = self.tables["head"][0]
        self.units_per_em = self.u16(head + 18)
        self.long_loca = self.u16(head + 50) == 1
        self.num_glyphs = self.u16(self.tables["maxp"][0] + 4)
        self.num_h_metrics = self.u16(self.tables["hhea"][0] + 34)
        self.cmap = self._read_cmap()

    def u8(self, offset):
        return self.data[offset]

    def u16(self, offset):
        return struct.unpack_from(">H", self.data, offset)[0]

    def s16(self, offset):
        return struct.unpack_from(">h", self.data, offset)[0]

    def u32(self, offset):
        return struct.unpack_from(">I", self.data, offset)[0]

    def _read_cmap(self):
        """code point -> glyph id, from every format 4 and 12 subtable present."""
        mapping = {}
        base = self.tables["cmap"][0]
        for i in range(self.u16(base + 2)):
            sub = base + self.u32(base + 4 + 8 * i + 4)
            fmt = self.u16(sub)
            if fmt == 4:
                seg_x2 = self.u16(sub + 6)
                ends = sub + 14
                starts = ends + seg_x2 + 2
                deltas = starts + seg_x2
                ranges = deltas + seg_x2
                for s in range(seg_x2 // 2):
                    end = self.u16(ends + 2 * s)
                    start = self.u16(starts + 2 * s)
                    if start == 0xFFFF:
                        continue
                    delta = self.u16(deltas + 2 * s)
                    range_offset = self.u16(ranges + 2 * s)
                    for code in range(start, min(end, 0xFFFE) + 1):
                        if range_offset == 0:
                            glyph = (code + delta) & 0xFFFF
                        else:
                            at = ranges + 2 * s + range_offset + 2 * (code - start)
                            if at + 1 >= len(self.data):
                                continue
                            glyph = self.u16(at)
                            if glyph:
                                glyph = (glyph + delta) & 0xFFFF
                        if glyph:
                            mapping.setdefault(code, glyph)
            elif fmt == 12:
                for g in range(self.u32(sub + 12)):
                    group = sub + 16 + 12 * g
                    first, last, glyph = (self.u32(group), self.u32(group + 4),
                                          self.u32(group + 8))
                    if last - first > 0x10000:
                        continue  # a range that large is not a symbol block
                    for code in range(first, last + 1):
                        mapping.setdefault(code, glyph + (code - first))
        return mapping

    def glyph_bytes(self, glyph):
        """The raw glyf entry, or b'' for a glyph with no outline (a space)."""
        loca = self.tables["loca"][0]
        if self.long_loca:
            start, end = self.u32(loca + 4 * glyph), self.u32(loca + 4 * glyph + 4)
        else:
            start, end = self.u16(loca + 2 * glyph) * 2, self.u16(loca + 2 * glyph + 2) * 2
        if end <= start:
            return b""
        glyf = self.tables["glyf"][0]
        return self.data[glyf + start:glyf + end]

    def metrics(self, glyph):
        """(advanceWidth, leftSideBearing). The tail of hmtx repeats the last advance."""
        hmtx = self.tables["hmtx"][0]
        if glyph < self.num_h_metrics:
            return self.u16(hmtx + 4 * glyph), self.s16(hmtx + 4 * glyph + 2)
        advance = self.u16(hmtx + 4 * (self.num_h_metrics - 1))
        tail = hmtx + 4 * self.num_h_metrics + 2 * (glyph - self.num_h_metrics)
        return advance, self.s16(tail)

    def vertical(self):
        """(ascender, descender, lineGap) from hhea, in font units."""
        hhea = self.tables["hhea"][0]
        return self.s16(hhea + 4), self.s16(hhea + 6), self.s16(hhea + 8)


def verify(font, chosen):
    """Reads the built face back and checks each code point draws the outline it was given.

    A cmap that maps every code point to a non-zero glyph id looks correct to any coverage
    test while every glyph draws its neighbour's outline; an off-by-one in loca does exactly
    that, and the only place it shows is on screen. So the check is on the bytes, here, rather
    than on coverage anywhere else.
    """
    built = Ttf(font)
    for code, (data, advance, _) in chosen.items():
        glyph = built.cmap.get(code)
        if not glyph:
            raise SystemExit(f"✗ U+{code:04X} is not in the built cmap")
        got = built.glyph_bytes(glyph)
        if got != data + b"\0" * (-len(data) % 4):
            raise SystemExit(f"✗ U+{code:04X} came back with a different outline than it "
                             "went in with: the glyf/loca pair is misaligned")
        if built.metrics(glyph)[0] != advance:
            raise SystemExit(f"✗ U+{code:04X} came back with a different advance")
    if built.glyph_bytes(0):
        raise SystemExit("✗ .notdef has an outline; it must be empty so the text engine's own "
                         "missing-glyph box is what a reader sees")


def licence_text():
    """Both source projects' copyright lines, then the OFL body they share.

    The two OFL.txt files differ only in their first line, so the licence itself
    is written once; taking one project's copyright and dropping the other's is
    what the OFL's notice requirement exists to prevent.
    """
    copyrights = []
    body = None
    for url, sha in LICENCES:
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        digest = hashlib.sha256(data).hexdigest()
        if digest != sha:
            raise SystemExit(f"✗ {url} is not what the pin says it is\n"
                             f"  expected {sha}\n  got      {digest}")
        lines = data.decode("utf-8").splitlines()
        copyrights.append(lines[0].strip())
        rest = "\n".join(lines[1:]).strip()
        if body is None:
            body = rest
    return ("\n".join(copyrights) + "\n\n"
            + f"{OUTPUT.name} is a subset and merge of glyphs from Noto Sans Symbols 2,\n"
              "Noto Sans Symbols and Noto Sans Math, produced by\n"
              "scripts/generate-menu-symbols.py. It carries no Reserved Font Name.\n\n"
            + body + "\n")


def fetch(url, expected_sha, force):
    cache = Path(__file__).resolve().parent.parent / "build" / "menu-symbol-sources"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / url.rsplit("/", 1)[-1]
    if target.exists() and not force:
        data = target.read_bytes()
    else:
        print(f"↓ {url.rsplit('/', 1)[-1]}")
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        target.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    if expected_sha is None:
        print(f"  (no pin recorded yet; this build's digest is {digest})")
    elif digest != expected_sha:
        raise SystemExit(f"✗ {target.name} is not what the pin says it is\n"
                         f"  expected {expected_sha}\n  got      {digest}")
    return data


def collect(force):
    """code point -> (glyph bytes, advance, lsb), taken from the first source that has it."""
    fonts = []
    for name, url, sha in SOURCES:
        font = Ttf(fetch(url, sha, force))
        fonts.append((name, font))
    # The merge rests on one shared design space. Checked rather than trusted: a
    # source at a different unitsPerEm would land its symbols at the wrong size
    # beside the others, and nothing downstream could tell.
    units = {font.units_per_em for _, font in fonts}
    if len(units) != 1:
        raise SystemExit(f"✗ sources disagree on unitsPerEm: {units}; they cannot be merged "
                         "verbatim, and scaling outlines is not what this script does")

    chosen = {}
    origin = {}
    for code in CODE_POINTS:
        for name, font in fonts:
            glyph = font.cmap.get(code)
            if not glyph:
                continue
            data = font.glyph_bytes(glyph)
            # A composite refers to other glyphs by id, and those ids mean nothing
            # once the glyph is lifted out of its font. None of the twenty is one
            # today; this is what stops a future source from shipping garbage.
            if data and struct.unpack_from(">h", data, 0)[0] < 0:
                raise SystemExit(f"✗ U+{code:04X} is a composite glyph in {name}; copying it "
                                 "verbatim would point at glyph ids this face does not have")
            advance, lsb = font.metrics(glyph)
            chosen[code] = (data, advance, lsb)
            origin[code] = name
            break
    missing = [f"U+{c:04X} ({CODE_POINTS[c]})" for c in CODE_POINTS if c not in chosen]
    if missing:
        raise SystemExit("✗ no source carries " + ", ".join(missing))
    return chosen, origin, fonts[0][1], next(iter(units))


def table_checksum(data):
    padded = data + b"\0" * (-len(data) % 4)
    return sum(struct.unpack(f">{len(padded) // 4}I", padded)) & 0xFFFFFFFF


def build(chosen, ascender, descender, line_gap, units_per_em):
    """A TrueType file holding .notdef plus one glyph per code point."""
    codes = sorted(chosen)
    # Glyph 0 is .notdef by rule and is deliberately blank: this face is a
    # fallback, and a box drawn here would replace the box the text engine draws
    # for a code point nobody has (the same picture, one layer later).
    glyf = b""
    # Two zeros, not one. loca[i]..loca[i+1] is glyph i, so .notdef needs BOTH its start and
    # its (equal) end before the first real outline; with a single leading zero every glyph
    # draws the outline of the next one, the cmap still looks correct because it maps to a
    # non-zero id, and the only place it shows is on screen.
    loca = [0, 0]
    metrics = [(units_per_em // 2, 0)]
    for code in codes:
        data, advance, lsb = chosen[code]
        data += b"\0" * (-len(data) % 4)
        glyf += data
        loca.append(len(glyf))
        metrics.append((advance, lsb))
    num_glyphs = len(codes) + 1

    x_min = y_min = 32767
    x_max = y_max = -32768
    for code in codes:
        data = chosen[code][0]
        if not data:
            continue
        x_min = min(x_min, struct.unpack_from(">h", data, 2)[0])
        y_min = min(y_min, struct.unpack_from(">h", data, 4)[0])
        x_max = max(x_max, struct.unpack_from(">h", data, 6)[0])
        y_max = max(y_max, struct.unpack_from(">h", data, 8)[0])

    head = struct.pack(
        ">IIIIHHQQhhhhHHhhh",
        0x00010000, 0x00010000, 0, 0x5F0F3CF5, 3, units_per_em,
        0, 0,  # created / modified: zero, so two builds of one input are byte-equal
        x_min, y_min, x_max, y_max, 0, 8, 2, 1, 0)
    hhea = struct.pack(">IhhhHhhhhhhhhhhhH", 0x00010000, ascender, descender, line_gap,
                       max(a for a, _ in metrics), min(b for _, b in metrics), 0,
                       x_max, 1, 0, 0, 0, 0, 0, 0, 0, num_glyphs)
    # version 1.0: numGlyphs then thirteen limits. Only numGlyphs is read by the
    # rasterizer; the rest are generous constants so a validator sees a sane table.
    maxp = struct.pack(">IHHHHHHHHHHHHHH", 0x00010000, num_glyphs,
                       512, 32, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0)
    hmtx = b"".join(struct.pack(">Hh", a, b) for a, b in metrics)
    loca_table = b"".join(struct.pack(">I", offset) for offset in loca)

    # cmap, format 4, one segment per contiguous run plus the required 0xFFFF end.
    segments = []
    for code in codes:
        if segments and code == segments[-1][1] + 1:
            segments[-1][1] = code
        else:
            segments.append([code, code])
    segments = [(start, end) for start, end in segments]
    seg_count = len(segments) + 1
    ends = b"".join(struct.pack(">H", end) for _, end in segments) + b"\xff\xff"
    starts = b"".join(struct.pack(">H", start) for start, _ in segments) + b"\xff\xff"
    deltas = b""
    for start, end in segments:
        first_glyph = codes.index(start) + 1
        deltas += struct.pack(">H", (first_glyph - start) & 0xFFFF)
    deltas += struct.pack(">H", 1)
    range_offsets = b"\0\0" * seg_count
    search = 2 ** (seg_count.bit_length() - 1) * 2
    subtable = (struct.pack(">HHHHHHH", 4, 16 + 8 * seg_count, 0, seg_count * 2,
                            search, seg_count.bit_length() - 1, seg_count * 2 - search)
                + ends + b"\0\0" + starts + deltas + range_offsets)
    cmap = struct.pack(">HHHHI", 0, 1, 3, 1, 12) + subtable

    names = [(1, FAMILY), (2, "Regular"), (3, f"{FAMILY}; menu key symbols"),
             (4, FAMILY), (5, "Version 1.000"), (6, FAMILY.replace(" ", "")),
             (0, "Subset and merge of Noto Sans Symbols 2, Noto Sans Symbols and "
                 "Noto Sans Math, which are licensed under the SIL Open Font License 1.1."),
             (13, "SIL Open Font License 1.1"), (14, "https://openfontlicense.org")]
    name_records = b""
    name_strings = b""
    for name_id, text in names:
        encoded = text.encode("utf-16-be")
        name_records += struct.pack(">HHHHHH", 3, 1, 0x0409, name_id,
                                    len(encoded), len(name_strings))
        name_strings += encoded
    name = (struct.pack(">HHH", 0, len(names), 6 + 12 * len(names))
            + name_records + name_strings)

    # Built field by field rather than as one long format string: OS/2 is thirty-odd
    # members and a miscount silently shifts every one after it, which a rasterizer
    # that ignores the table would never reveal.
    os2 = struct.pack(">HhHHH", 4, units_per_em // 2, 400, 5, 0)   # version..fsType
    os2 += struct.pack(">hhhh", 650, 600, 0, 75)                   # subscript
    os2 += struct.pack(">hhhh", 650, 600, 0, 350)                  # superscript
    os2 += struct.pack(">hh", 50, 250)                             # strikeout
    os2 += struct.pack(">h", 0)                                    # sFamilyClass
    os2 += b"\0" * 10                                              # panose
    # Unicode range bits 29 (General Punctuation..Arrows) and 30 (Math/Technical),
    # which is where every code point in this face lives.
    os2 += struct.pack(">IIII", 0x60000000, 0, 0, 0)
    os2 += b"Limn"                                                 # achVendID
    os2 += struct.pack(">HHH", 0x0040, min(codes), max(codes))     # fsSelection, first/last
    os2 += struct.pack(">hhh", ascender, descender, line_gap)      # sTypo*
    os2 += struct.pack(">HH", ascender, -descender)                # usWin*
    os2 += struct.pack(">II", 1, 0)                                # ulCodePageRange
    os2 += struct.pack(">hhHHH", units_per_em // 2, ascender, 0, 0x20, 1)
    post = struct.pack(">IIhhIIIII", 0x00030000, 0, 0, 0, 0, 0, 0, 0, 0)

    tables = {"OS/2": os2, "cmap": cmap, "glyf": glyf, "head": head, "hhea": hhea,
              "hmtx": hmtx, "loca": loca_table, "maxp": maxp, "name": name, "post": post}
    tags = sorted(tables)
    count = len(tags)
    search = 2 ** (count.bit_length() - 1) * 16
    out = struct.pack(">IHHHH", 0x00010000, count, search,
                      count.bit_length() - 1, count * 16 - search)
    offset = 12 + 16 * count
    directory = b""
    body = b""
    for tag in tags:
        data = tables[tag]
        directory += struct.pack(">4sIII", tag.encode("latin1"),
                                 table_checksum(data), offset + len(body), len(data))
        body += data + b"\0" * (-len(data) % 4)
    font = bytearray(out + directory + body)
    # head.checkSumAdjustment is the file's own checksum, so it is written last
    # and computed with its own field zeroed, which it already is.
    head_offset = 12 + 16 * count + sum(
        len(tables[t]) + (-len(tables[t]) % 4) for t in tags[:tags.index("head")])
    adjustment = (0xB1B0AFBA - table_checksum(bytes(font))) & 0xFFFFFFFF
    struct.pack_into(">I", font, head_offset + 8, adjustment)
    return bytes(font)


def main():
    parser = argparse.ArgumentParser(description="Builds the menu key-symbol face.")
    parser.add_argument("--force", action="store_true",
                        help="re-download the sources instead of using the cached copies")
    args = parser.parse_args()

    chosen, origin, first, units_per_em = collect(args.force)
    ascender, descender, line_gap = first.vertical()
    font = build(chosen, ascender, descender, line_gap, units_per_em)
    verify(font, chosen)
    DEST.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(font)

    LICENCE_FILE.write_text(licence_text(), encoding="utf-8")

    by_source = {}
    for code, name in origin.items():
        by_source.setdefault(name, []).append(code)
    print(f"✓ {OUTPUT.name}: {len(chosen)} symbols, {len(font)} bytes, "
          f"unitsPerEm {units_per_em}")
    for name, codes in by_source.items():
        print(f"    {len(codes):2d} from {name}: "
              + " ".join(f"U+{c:04X}" for c in sorted(codes)))
    print(f"✓ {LICENCE_FILE.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
