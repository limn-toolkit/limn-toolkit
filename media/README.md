# Media

Third-party sample media, redistributed with Limn under its own licence. Nothing here is Limn's
work and nothing here is covered by Limn's Apache-2.0 licence.

It lives at the repository root rather than under `site/` because it has two consumers that must
not depend on each other: the website's demonstrations, and the video test suite, which is Java and
cannot reach into the website's tree.

## Big Buck Bunny

| File | Picture | Sound | Duration | Size |
| --- | --- | --- | --- | --- |
| `Big_Buck_Bunny_640x360_10s.mp4` | H.264, 640 × 359, 24 per second | AAC, stereo | 10 s | 2.05 MB |

The picture is 640 × 359, a row short of the name: the original is, whatever its own name says,
and nothing here was encoded again. The name is kept close to the original's so that it can be
found again.

One excerpt, with its picture **and its sound**: a sample of video is here to validate both, and
the three excerpts this folder held before were picture alone.

**© 2008 Blender Foundation: <https://peach.blender.org/>, <https://www.bigbuckbunny.org/>**
**Licensed under the Creative Commons Attribution 3.0 Unported licence.** The full text is in
`LICENSE-CC-BY-3.0.txt` beside this file; the licence is also at
<https://creativecommons.org/licenses/by/3.0/>. It permits redistribution, modification and
commercial use, and requires that the Blender Foundation be credited.

**Provenance.** Cut from the Blender Foundation's own file, `BigBuckBunny_640x360.m4v`, as
published in `BigBuckBunny_640x360.m4v.zip` at
<https://download.blender.org/peach/bigbuckbunny_movies/> (downloaded 2026-10-10, 121,284,117
bytes, SHA-256 `7118242b6728d40c871479c5b3c0f0fb27d748089df15d7f1b469f297c74a2d6`). The excerpt
is the ten seconds from 0:10 to 0:20, which begin on a key frame and are where the opening music
is loudest. **Nothing was encoded again:** the picture and the sound are the original's own
samples, copied into an MP4 by `scripts/media/cut.swift`, which asks the system's media framework
for a passthrough export:

```
swift scripts/media/cut.swift BigBuckBunny_640x360.m4v Big_Buck_Bunny_640x360_10s.mp4 10 10
```

Digest of the excerpt as committed:

```
cfe423021094d8e5dbc7bbaba78b876d97fa5449b9862619f41b25a22bf0fa08  Big_Buck_Bunny_640x360_10s.mp4
```

## Why this file and not a clip this repository makes

The FFmpeg build carries no H.264 **encoder** and deliberately never will: the encoder is x264,
which is GPL, and the licence position in `docs/adr/011` depends on no GPL component being present.
So a clip that exercises the decoder this project actually ships cannot be produced by this project.
That is the gap this fills: the shipping `player` profile decodes h264, hevc, vp9 and vp8, and until
now nothing in the repository could prove the first of them.

## Adding more

Anything added here needs three things, in this order: a licence that permits redistribution, the
attribution that licence requires written into this file and into the repository's `NOTICE`, and a
recorded digest. Media without all three does not belong in a repository somebody else will clone.
