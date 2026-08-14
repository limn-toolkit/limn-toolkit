# Media

Third-party sample media, redistributed with Limn under its own licence. Nothing here is Limn's
work and nothing here is covered by Limn's Apache-2.0 licence.

It lives at the repository root rather than under `site/` because it has two consumers that must
not depend on each other: the website's demonstrations, and the video test suite, which is Java and
cannot reach into the website's tree.

## Big Buck Bunny

| File | Resolution | Duration | Size |
| --- | --- | --- | --- |
| `Big_Buck_Bunny_360_10s_1MB.mp4` | 640 × 360 | 10 s | 0.95 MB |
| `Big_Buck_Bunny_720_10s_2MB.mp4` | 1280 × 720 | 10 s | 1.89 MB |
| `Big_Buck_Bunny_1080_10s_5MB.mp4` | 1920 × 1080 | 10 s | 5.00 MB |

H.264 video in an ISO base media container (`avc1` / `isom`).

**© 2008 Blender Foundation: <https://peach.blender.org/>, <https://www.bigbuckbunny.org/>**
**Licensed under the Creative Commons Attribution 3.0 Unported licence.** The full text is in
`LICENSE-CC-BY-3.0.txt` beside this file; the licence is also at
<https://creativecommons.org/licenses/by/3.0/>. It permits redistribution, modification and
commercial use, and requires that the Blender Foundation be credited.

**Provenance.** These are not the Blender Foundation's own files. They are ten-second excerpts
transcoded with FFmpeg and published by <https://test-videos.co.uk/bigbuckbunny/mp4-h264>, which
states that they are free to use on the same terms as the original and links both the licence and
the source. The originals are much larger (the Blender Foundation's own H.264 rendition on the
Internet Archive is 249 MB for 480p), and the whole point of these is to be small enough to sit in
a repository.

Downloaded 2026-08-11, verified by digest:

```
77145c94c11f3754207499158df22406e1fe7635553c1c86dc5e881dfeb32016  Big_Buck_Bunny_360_10s_1MB.mp4
6c92c730490901544a5564ed80f0f708fd582b2aacac580af2166578a5510611  Big_Buck_Bunny_720_10s_2MB.mp4
e131ad42621442758a3acb899bfbdfeeab0b40c7e2f7c7e66683f58a09a99aee  Big_Buck_Bunny_1080_10s_5MB.mp4
```

The upstream file names are kept exactly as published. A renamed file is a file somebody has to
take on trust; these can be fetched again and compared.

## Why these files and not a clip this repository makes

The FFmpeg build carries no H.264 **encoder** and deliberately never will: the encoder is x264,
which is GPL, and the licence position in `docs/adr/011` depends on no GPL component being present.
So a clip that exercises the decoder this project actually ships cannot be produced by this project.
That is the gap these fill: the shipping `player` profile decodes h264, hevc, vp9 and vp8, and until
now nothing in the repository could prove the first of them.

## Adding more

Anything added here needs three things, in this order: a licence that permits redistribution, the
attribution that licence requires written into this file and into the repository's `NOTICE`, and a
recorded digest. Media without all three does not belong in a repository somebody else will clone.
