#!/usr/bin/env python3
"""Compares two directories of PNG captures, file by file, and says how far apart each pair is.

ADR 003's phase 4 holds the OpenGL ES path to the desktop one by pictures: the same scenes captured
under ANGLE on a Windows runner and under desktop OpenGL (Mesa's software rasteriser) on a Linux
one. Two rasterisers never agree to the last bit on antialiased edges, so a difference is measured
rather than refused: the share of pixels off at all, the share off by more than a visible step,
and the largest channel difference. A pair fails only when the visible share passes --fail-share,
which is what content missing or drawn elsewhere looks like, not a rounding.

    compare-captures.py <reference-dir> <candidate-dir> [--step 24] [--fail-share 0.02]

Standard library only, so that it runs on any runner without an install step.
"""

import argparse
import os
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(path + " is not a PNG")
    at, idat = 8, b""
    width = height = channels = 0
    while at < len(data):
        length = struct.unpack(">I", data[at:at + 4])[0]
        kind = data[at + 4:at + 8]
        body = data[at + 8:at + 8 + length]
        if kind == b"IHDR":
            width, height, depth, colour = struct.unpack(">IIBB", body[:10])
            if depth != 8 or colour not in (2, 6):
                raise ValueError(path + ": only 8-bit RGB and RGBA captures are read")
            channels = 4 if colour == 6 else 3
        elif kind == b"IDAT":
            idat += body
        at += 12 + length
    raw = zlib.decompress(idat)
    stride = width * channels
    pixels = bytearray()
    previous = bytearray(stride)
    position = 0
    for _ in range(height):
        kind = raw[position]
        line = bytearray(raw[position + 1:position + 1 + stride])
        position += 1 + stride
        for x in range(stride):
            left = line[x - channels] if x >= channels else 0
            up = previous[x]
            corner = previous[x - channels] if x >= channels else 0
            if kind == 1:
                line[x] = (line[x] + left) & 255
            elif kind == 2:
                line[x] = (line[x] + up) & 255
            elif kind == 3:
                line[x] = (line[x] + ((left + up) >> 1)) & 255
            elif kind == 4:
                p = left + up - corner
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
                line[x] = (line[x] + (left if pa <= pb and pa <= pc else up if pb <= pc else corner)) & 255
        pixels += line
        previous = line
    return width, height, channels, pixels


def compare(reference, candidate, step):
    w1, h1, c1, a = read_png(reference)
    w2, h2, c2, b = read_png(candidate)
    if (w1, h1) != (w2, h2):
        return None, f"sizes differ: {w1}x{h1} against {w2}x{h2}"
    channels = min(c1, c2, 3)
    off = visible = worst = 0
    for i in range(w1 * h1):
        d = max(abs(a[i * c1 + k] - b[i * c2 + k]) for k in range(channels))
        if d:
            off += 1
            worst = max(worst, d)
            if d > step:
                visible += 1
    total = w1 * h1
    return visible / total, f"{w1}x{h1}: {100 * off / total:.3f}% differ, {100 * visible / total:.3f}% by more than {step}, worst {worst}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference")
    parser.add_argument("candidate")
    parser.add_argument("--step", type=int, default=24)
    parser.add_argument("--fail-share", type=float, default=0.02)
    args = parser.parse_args()
    names = sorted(n for n in os.listdir(args.reference) if n.endswith(".png"))
    if not names:
        sys.exit("no PNG under " + args.reference)
    failed = []
    for name in names:
        candidate = os.path.join(args.candidate, name)
        if not os.path.isfile(candidate):
            print(f"✗ {name}: no candidate capture")
            failed.append(name)
            continue
        share, line = compare(os.path.join(args.reference, name), candidate, args.step)
        ok = share is not None and share <= args.fail_share
        print(("✓ " if ok else "✗ ") + name + ": " + line)
        if not ok:
            failed.append(name)
    if failed:
        sys.exit(f"{len(failed)} of {len(names)} captures differ by more than {100 * args.fail_share:.1f}% "
                 f"of their pixels: {', '.join(failed)}")


if __name__ == "__main__":
    main()
