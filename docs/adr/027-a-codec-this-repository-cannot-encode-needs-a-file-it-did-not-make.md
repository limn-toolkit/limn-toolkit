# ADR 027: A codec this repository cannot encode needs a file it did not make

- **Status:** Accepted, 2026-08-11.
- **Supersedes:** the "no media file is committed" rule, in [ADR 008](008-video-decoders-live-in-their-own-module.md) §5
  and [ADR 015](015-codec-breadth-and-how-an-unencodable-codec-is-tested.md) §4. Everything else in
  both stands.
- **Scope:** why a small sample corpus is now committed, what it is not permission for, and the
  defect that made the gap visible.

---

## 1. What the old rule bought, and where it ran out

"A test that needs a media file writes it first" is a good rule and most of it survives here. It
keeps generated fixtures out of git, it makes every test reproducible from source, and it means a
clone carries no opaque binary whose contents nobody can account for.

It has one hole, and the hole is exactly the codec that matters. The shipping `player` profile
decodes **h264, hevc, vp9 and vp8**. The `full` profile encodes **mpeg4 and aac**, and no more,
because an H.264 encoder is x264, x264 is GPL, and [ADR 011](011-ffmpeg-licence-and-linking.md)'s
entire position rests on no GPL component being present. That is not a gap waiting to be filled; it
is a decision, and it is the right one.

So the repository cannot produce a file in the format its own product exists to read. ADR 015 saw
this and answered with a third evidence tier: point `-Dlimn.video.test.clips` at a directory and
every file in it is decoded. It was correct, and it never ran. An opt-in tier with nothing to opt
into is a test that reports "skipped" forever, which reads like coverage and is not.

## 2. What made it urgent rather than tidy

The tier was enabled against real H.264 for the first time on 2026-08-11, and the run immediately
failed a *different* assertion: the committed `player` native had no `mov_text` decoder, so every
subtitle track in an MP4 advertised itself as decodable and then refused to open.

The binary had been compiled four hours before the commit that enabled the subtitle decoders and
had been shipped that way ever since (including in the artifact published earlier the same day).
Nothing caught it, because a developer machine that has built `full` gets `full` by default, and
`full` had the decoders. The profile that ships was the profile nobody ran.

Two lessons, and only the second is about media: a default that prefers the developer's build hides
the shipped one, and evidence that cannot run is not evidence.

## 3. Decision

**A small sample corpus is committed, under `media/`.** Today it is three ten-second Big Buck Bunny
excerpts: 640×360 to 1920×1080, 7.8 MB in total, © 2008 Blender Foundation under Creative Commons
Attribution 3.0, redistributed with the licence text beside them and the attribution in `NOTICE`.

**The codec-breadth tier now points there by default**, so decoding a real file is something the
build does rather than something a developer remembers. `-Dlimn.video.test.clips` still overrides
it with a larger corpus for anyone who has one.

## 4. What this is not permission for

- **Not generated media.** Anything this repository can produce, it still produces: the `full`
  profile writes its own MP4 for the round trip, the Y4M reader gets a file written on the way in,
  and the synthetic sources stay synthetic. Committing a fixture that a test could have made is
  still the wrong trade.
- **Not media in a module.** `media/` is at the repository root and no `limn-*` module reads from
  it at build time. Nothing here reaches a published jar; the modules' payload is unchanged.
- **Not media without paperwork.** Three things or it does not go in: a licence that permits
  redistribution, the attribution that licence requires (in `media/README.md` and in `NOTICE`,
  because a reader of one is not a reader of the other), and a recorded digest, so a file can be
  fetched again and compared rather than trusted.
- **Not a licence to grow.** The corpus is sized to prove a codec, not to be a library. The
  Blender Foundation's own H.264 rendition is 249 MB; these are 7.8 MB for the same evidence.

## 5. Consequences accepted

A clone is 7.8 MB larger and always will be, because git does not forget. That is the price of the
tier running, and it is cheap next to a decoder that ships a codec nobody has decoded.

`NOTICE` grows a third-party section, which is a cost and also a benefit: the audit was already
there, and an entry in it is how a redistributed work stays accounted for.
