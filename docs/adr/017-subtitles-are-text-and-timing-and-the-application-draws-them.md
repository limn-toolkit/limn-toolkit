# ADR 017: Subtitles are text and timing, the application asks by position, and nothing is burned into the picture

- **Status:** Accepted, 2026-08-05. Implemented as phase 9 of the video player: the subtitle
  section of `limn_ffmpeg.c`, `SubtitleCues`, `FfmpegMedia.SubtitleTrack` and the Video tab's cue
  overlay.
- **Date:** 2026-08-05
- **Scope:** how a container's subtitle tracks are listed and selected, in what shape a cue reaches
  an application, what happens to the markup a subtitle format carries, what happens to a track that
  is a picture rather than text, and what the decoders cost.
- **Audience:** whoever draws a subtitle, adds a subtitle format, or is asked why this toolkit will
  not burn one into the video. Extends ADR 012, which decided that a container type is where a
  second track arrives, and ADR 015, which decided how a codec earns its megabyte. Applies ADR 009
  §6's argument about the transport a third time.

---

## 0. The finding that decides the shape of this work

**Burning a subtitle into the picture is refused, and the reason is not taste.** The two FFmpeg
filters that do it declare, in the `configure` of the pinned 7.1.5 tree:

```
subtitles_filter_deps="avformat avcodec libass"
ass_filter_deps="libass"
```

This build is `--disable-avfilter --disable-swscale`, has zero compiled filters, and does not ship
`libavfilter` at all. Turning that path on means **libass, and with it fontconfig, freetype and
harfbuzz**: four external libraries with their own build systems, which is ADR 015 §4's libdav1d
argument arriving a second time.

**And it would cost more than it costs.** A filter graph works on CPU frames, so burning into a
VideoToolbox picture means downloading the IOSurface (3.1 MB a picture at 1080p, 12.4 MB at 4K),
filtering it and uploading it again. Phase 8c's zero-copy path would be off whenever subtitles were
on. The text would be rasterised at the *video's* resolution and then scaled by the letterbox, so it
is soft on a HiDPI display; it would bypass Limn's own font fallback and shaping, so a Japanese
subtitle would be tofu where the toolkit beside it draws it correctly; and once burned it cannot be
turned off, moved, restyled, selected or read by anything.

So the picture is left alone, and what crosses is text and an interval. Everything below follows
from that.

## 1. Decision

1. **The application asks by position.** `SubtitleCues.activeAt(micros)` answers *what is on screen
   at this moment*. The two alternatives are priced in §2.
2. **A cue is plain text.** Markup is removed on the far side of the boundary and the raw dialogue
   line is not published (§3).
3. **A bitmap track is listed and refused by name** (§4). No bitmap decoder is in either profile.
4. **Nothing is selected when a container opens**, whatever the file marks as its default: whether a
   viewer wants subtitles is not a fact about the file.
5. **The subtitle side never demultiplexes.** Cues ride the pictures' demuxing (§2).
6. **Four text decoders (`movtext`, `subrip`, `ass`, `webvtt`) measured at 896 bytes together**
   (§5). Nothing in `limn-toolkit` changed.

## 2. How a cue reaches the application, and what the other two would have cost

### Rejected: a source read the way video is read

Its claim was consistency: `readCue()` answering `PENDING`/`CUE`/`END` with the same
borrow-and-release discipline as `readFrame()`. The consistency is superficial, and two properties
say why.

**A picture is megabytes borrowed from a pool and must be released exactly once; a cue is a short
string with no lifetime at all.** The whole apparatus `readFrame` exists to carry (the slot, the
epoch, the recycler, the three ways to get the release wrong that `docs/design/video.md` lists)
would be ceremony over an immutable Java object.

**A picture is presented at an instant; a cue occupies an interval.** "What is on screen at *t*" is
well posed for cues and meaningless for pictures. A source hands over cues in arrival order and
leaves the caller to hold them, decide which is current, and drop them on a seek: the same thirty
lines in every application, with the seek half being the half that is easy to get wrong. Written
once here it is asserted once here.

### Rejected: the player pushes cues

ADR 009 §6 declined to ship a transport component because deciding *when* is presentation policy.
Deciding when a cue is current is that same decision, and it needs a clock the toolkit would then
have to reconcile with the wall clock, the master position and the seek epoch. It would also put
"is this viewer's subtitle on screen" inside a player that has no idea what the window looks like.

### Taken: poll by position, with the three costs paid explicitly

**A window has to be held, and it is held in Java.** Cues are immutable objects already, the query
is a scan of a short list, and the seek logic is testable without a native library. The shim
decodes; `SubtitleCues` remembers.

**A scrub must not leave a stale cue up, and a *subtitle epoch* is what makes sure.**
`placeDemuxer`, the one place a container is really moved, clears the subtitle queue, marks the
decoder as owing a flush, and increments the epoch. Every read reports the epoch, including the
reads that produce nothing, so a seek reaches the window whether or not a cue happens to follow it.
`SubtitleTest.aSeekDoesNotLeaveACueFromWhereTheFilmUsedToBe` is the assertion, and removing the
increment fails it and one other.

**Reading ahead through a gap would have been fatal, and is designed out.** Video and audio each run
the demux loop when their own queue is empty. A subtitle track is silent between lines, so a
subtitle side that did the same would answer "is there a cue now?" by demultiplexing forward until
it met one: minutes of a film, queueing or dropping everything else on the way. So **the subtitle
side never demultiplexes**: the demux loop queues subtitle packets it meets, exactly as it does for
audio, and the read drains only what is already there. The consequence is stated on the SPI rather
than discovered: *a container whose video nobody reads produces no cues*. A player reads far enough
ahead that a cue is decoded well before the picture it belongs over is shown.

**Polling every frame allocates nothing.** `activeAt` returns the same immutable list instance for
as long as the active set has not changed, tracked as the half-open interval over which nothing can
change; a new list appears only when a cue starts or ends.

### The one artefact, stated rather than hidden

A cue that **straddles** the seek target (begins before it, ends after it) is recovered only if
its packet lies after the point the container landed on. Both seek modes land at or before the
target and decode forward, so it usually is; when it is not, the next cue is the first one seen.
There is no fix with one shared demuxer position, so it is documented on `SubtitleCues` instead of
being worked around.

### One more thing this bought

A cue whose container states no duration is published with an unknown end and **closed by the next
cue's start** when one arrives. That is the only reading that neither invents a length nor shows
nothing, and it is expressible *because* the window exists; a caller holding cues one at a time
could not do it without buffering, which is the window again.

## 3. What happens to the markup

**Every text subtitle decoder in this build produces ASS, whatever the file was.** `movtext`,
`subrip`, `ass` and `webvtt` all go through `ff_ass_add_rect`, so `AVSubtitleRect.ass` carries
`ff_ass_get_dialog`'s nine fields (`readorder,layer,Style,Name,ML,MR,MV,Effect,Text`) and
`rect->text` is empty, because for that type the ASS line is the authoritative one. This was read
out of the tree, not assumed, and confirmed by writing a clip and dumping what came back:

```
0,0,Default,,0,0,0,,{\i1}T0 C0{\r}\Nsecond line
```

**So the decision is: stripped, on the far side of the boundary, and the raw line is not
published.** The eight leading fields go, brace-delimited override runs go, `\N` becomes a line
break and `\n` and `\h` become a space. What arrives is what an ordinary text stack can draw.

Two reasons the raw line is not offered beside it. Its *shape* depends on which decoder libavcodec
chose, so publishing it would promise a format this SPI cannot name and cannot keep. And acting on
`{\an8}`, the tag that says "put this at the top", requires an ASS interpreter, which is libass,
which is the dependency §0 refuses; so a raw line would be markup an application could receive and
not act on, and the likeliest thing it would do with it is draw it. **A cue handed over with its
markup intact is a cue an application draws literally**, and that is the failure this phase existed
to avoid.

What is lost is real and worth naming: `{\an8}` on a sign near the top of frame, karaoke timing, and
per-line colour. An application that wants those needs an ASS renderer, and this SPI is not in its
way; it would read the file itself.

## 4. What happens to a bitmap track

PGS (Blu-ray), VobSub (DVD) and DVB subtitles are **paletted pictures with their own rectangle**,
not text. Two options existed and one was taken.

**Carrying them** means converting `AV_PIX_FMT_PAL8` to RGBA: a palette lookup in a loop, and
genuinely easy, so the cost is not the conversion. It is a second image lifetime across this
boundary, and a placement contract in the *video's* coordinate space that an application would then
have to map through a letterbox it did not compute. That is positioning policy arriving through the
back door.

**Refused by name.** `SubtitleTrack.text()` comes from the codec descriptor's
`AV_CODEC_PROP_TEXT_SUB`/`AV_CODEC_PROP_BITMAP_SUB` (a property of the *format*, not of the build),
and selecting a bitmap track throws with a message naming the codec and saying this SPI carries text
cues only. The track is still **listed**, the way an undecodable audio track is listed: a film with
one text track and one bitmap track has two subtitle tracks, and hiding one would say otherwise.

Reading the property rather than the linked decoder list is what makes the refusal survive someone
rebuilding with `pgssub` enabled; they get the refusal that names the real reason, rather than a
track that opens and shows nothing, which ADR 015's language for the Opus trap would call the worst
of the outcomes. `CodecBreadthTest` additionally keeps the three decoders out of the build, so the
two halves cannot disagree about what happened.

## 5. What the decoders cost, measured

macOS arm64, the `player` profile, FFmpeg shared libraries only, one configure-and-build per row on
2026-08-05. This is the method ADR 015 §2's table was produced with. Each row is that one decoder on
top of the shipped line.

| | uncompressed | delta |
|---|---|---|
| base (the `player` profile as ADR 015 left it) | 4 976 256 B | n/a |
| + movtext (tx3g, what an MP4 carries) | 4 976 560 B | **+304 B** |
| + subrip | 4 976 928 B | **+672 B** |
| + ass | 4 976 496 B | **+240 B** |
| + webvtt | 4 976 416 B | **+160 B** |
| **all four together** | **4 977 152 B** | **+896 B** |

**These are the cheapest rows in this repository by three orders of magnitude**: 0.018% of the
payload, against +30% for HEVC. Two things explain it: a text subtitle decoder is string
manipulation over ASS helpers the four of them share, and what code they do add largely fits inside
the alignment slack the existing segments already carry, so the *file* grows by less than the source
would suggest. Payload is what the table measures and payload is what that number is.

**The measurement was verified against the linked library and not against the flag**, which is ADR
015 §3's tier-2 rule applied to its own evidence: 896 bytes for four decoders is exactly the shape
of a result that means configure silently dropped them. It did not; `config_components.h` has all
four at 1, and `CodecBreadthTest` now asserts them out of the linked library by libavcodec's own
spelling (`mov_text`, not the `movtext` the flag uses). That asymmetry is itself a reason to read
the library.

The `full` profile additionally gets the **`movtext` encoder**, because no media is committed and
the only honest way to read a subtitle track is to write one. `ff_movtext_encoder` is in the pinned
tree and the mp4 muxer carries the `tx3g` tag, so **Matroska was not needed**; the round trip is an
ordinary MP4.

## 6. The entry point, since it was worth checking

`avcodec_decode_subtitle2` is **not deprecated** in ffmpeg-7.1.5. `libavcodec/avcodec.h` carries
exactly two `attribute_deprecated` markers (`ticks_per_frame` and `avcodec_close`), and neither is
on this path; `AVSubtitle`, `AVSubtitleRect` and `avsubtitle_free` are undecorated, and
`doc/APIchanges` names no successor. The send/receive pair that replaced the video and audio
`decode_*` calls has no subtitle counterpart in this tree, so the old-looking entry point is the
current one and no migration is pending.

## 7. What this costs, stated rather than discovered

- **A fourth mutex in the shim**, and one more entry in the lock order: the subtitle side may take
  the demuxer, and the demuxer is never held while taking it. The seek's flush is deferred through a
  flag for exactly that reason: `placeDemuxer` runs holding the demuxer and may not reach for
  another lock.
- **The selection must happen before anything reads a picture.** An unselected track's packets are
  freed as they are met, so a track selected after something has already read forward has missed
  what it read past, and the demuxer will not go back. This is not theoretical: it was the
  difference between a Kitchen Sink capture with a subtitle in it and one without.
- **Only one track is decoded at a time**, for ADR 012 §7's reason and one more: a second track's
  packets would be queued for a consumer that never polls them.
- **The window is the application's memory.** Cues that ended well behind the last time asked about
  are discarded, so a two-hour film does not accumulate its script; a caller that asks about a time
  far behind gets what is still held, which is what a seek is for.

## 8. What this deliberately is not

- **Not a subtitle widget, and not a styling or positioning policy.** Nothing in `limn-toolkit` or
  `limn-components` knows a subtitle exists. The Kitchen Sink draws one in about forty lines with
  the toolkit's own text stack (lower third, centred, a plate behind it), and those forty lines are
  the demonstration that the SPI is enough, as well as being exactly the choices that would have had
  to be invented and then argued about had a component tried to make them.
- **Not burning.** §0, and no download of a hardware picture was introduced anywhere.
- **Not an ASS renderer.** §3 says what is lost with it.
- **Not bitmap subtitles.** §4 says what it would take: a paletted conversion, an image lifetime, and
  a placement contract this SPI does not have.
- **Not external subtitle files.** A `.srt` sitting beside a film is a second input to open and pair
  by timeline, which is a different question from the one a container answers.
- **Not a language preference.** Which track a viewer wants is the application's, and the listing
  carries the two facts the file offers towards it: the default and the forced dispositions.

## Sources

- `configure` in the ffmpeg-7.1.5 tree the build script pins: the two `*_filter_deps` lines quoted
  in §0
- `libavcodec/ass.c`, `ff_ass_get_dialog`: §3's nine fields
- `libavcodec/decode.c`, `avcodec_decode_subtitle2`: where `sub->pts` and `end_display_time` come
  from, including the fill from the packet's duration
- `libavcodec/avcodec.h` and `doc/APIchanges`: §6
- §5's table was produced by configuring and building each row separately, then read back out of
  `config_components.h` and the linked library
