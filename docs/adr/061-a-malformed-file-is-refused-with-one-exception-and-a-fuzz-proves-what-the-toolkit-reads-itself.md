# ADR 061: A malformed file is refused with one exception, and a fuzz proves what the toolkit reads itself

- **Status:** Accepted and implemented, 2026-10-12. The two decisions of §1 and §2 are the owner's,
  taken from priced options after a survey of every reader in the tree.
- **Date:** 2026-10-12
- **Scope:** what happens when a file, a resource or a block of another program's bytes is not
  what its format says: the exception it is refused with, which readers are proved by a fuzz in
  `check`, and what is said of the readers that are native libraries. It is the first of the two
  gates the road map sets before 1.0.
- **New public API:** `limn.io.MalformedContentException`.
- **Compatibility:** a breaking change of what is thrown. The glTF loader, the theme format and
  an SVG threw `IllegalArgumentException`; the Y4M reader `IllegalStateException`; a font either;
  a picture and a sound whatever the decoder chose. All throw `MalformedContentException` now,
  which is neither of those.

---

## 0. What the survey found

Every place bytes from outside become a structure was read, and two kinds were found.

**Readers written in Java**, the toolkit's own: the glTF loader and its JSON reader, the theme
format, the Y4M reader, the WAV reader, the scanner of a font file's names, the character map
and colour strikes of the emoji font, the clipboard and drag formats of Windows, the list of
addresses the other systems drag, and the reader of the session bus's messages. One more is
Java and a library's: the MP3 decoder. Only the glTF loader had a fuzz.

**Decoders that are native libraries**: pictures, vector icons, font outlines and shaping, Ogg,
and video. Java sees a return code before and after. A file that makes one of them fail in its
own memory ends the process, and nothing in Java catches that. The font library says of itself
that it is not to be given fonts that are not trusted, and a font is handed to it not when the
file is loaded but when its first glyph is asked for.

**What a malformed file was refused with** differed by reader, and the interfaces a backend
implements promised only "a `RuntimeException`".

## 1. The gate: what is the toolkit's is proved, what is not is guarded and said

- **Every reader written in Java** has its defects fixed, its allocations held to what the bytes
  can back, and a deterministic mutation fuzz in `check`: valid seeds, each damaged in one of
  the ways a broken file is (cut short, a few bytes wrong, a length or an offset that lies, a
  part gone or doubled), and nothing may come out but the answer or the documented refusal.
  The seed of the mutations is fixed, so a failure is the same on every machine.
- **Every native decoder** has what Java can do around it: a ceiling checked before anything is
  allocated from a number in the file, and every failure it reports arriving as the same
  exception. It is not fuzzed: what a fuzz would find is in code that is not the toolkit's, and
  would stop `check` for a reason nobody here can fix.
- **What each takes is said in the guide** (§4).

## 2. One exception, the toolkit's own

`limn.io.MalformedContentException`, unchecked, with the format's name and, where the reader
knows it, the line or the offset it stopped at. It is what a reader refuses content with that
is not what its format says, and it is told apart from three things it is not:

| | arrives as |
|---|---|
| content that is malformed, damaged or cut short | `MalformedContentException` |
| a file that cannot be read | `IOException` or `UncheckedIOException` |
| a format, or a variety of one, that nothing here decodes | `UnsupportedOperationException` |
| a wrong argument of the caller's own | `IllegalArgumentException`, as ever |

It was chosen over using `IllegalArgumentException` everywhere so that a file's fault is never
taken for the caller's, and over leaving each reader its own so that an application opening a
user's file catches one thing.

**One reader stands outside**, and says so: what the video library cannot open arrives as its
own `FfmpegException`, the library not telling a damaged file from one it does not play.

## 3. What was built

| reader | what changed | fuzz |
|---|---|---|
| glTF, JSON | the new exception; a mesh's own refusal of arrays that disagree, which went out as the caller's exception, is the file's | was there |
| theme format | the new exception, with the line; what a theme refuses of its builder is the file's fault | yes |
| Y4M | the new exception | yes |
| WAV | the new exception | yes |
| MP3 | the new exception; what the library throws without declaring, an index past its own tables on a damaged frame, is contained; a decode of the whole file stops at 2<sup>28</sup> samples, a longer one being for streaming | yes |
| a font file's names | none needed | yes |
| emoji font tables | a lookup asked for during layout answers "no glyph" and never throws; the walks over strikes are bounded | yes |
| Windows clipboard and drag blocks | a bitmap shorter than its header and a list of files whose first offset wrapped the arithmetic threw an index error; a block read as it is asked for, in a drag, threw at the caller, and is now a read that failed | yes |
| lists of addresses | none needed | yes |
| session bus messages | a length in a message allocated up to two gigabytes; nesting had no limit; an array of elements of no size never ended; all three are refused | yes |
| blocks read whole from macOS and Windows | held to 512 MiB, as the other systems' were | — |
| pictures (native) | the new exception | no |
| vector icons (native) | the new exception; a side above 8192 pixels is refused; a size that is no number is malformed | no |
| font outlines (native) | the new exception at loading; a glyph whose box is above 4096 by 4096 is drawn as none | no |
| Ogg (native) | the new exception; the same ceiling on a whole decode | no |
| video (native) | an index the native side answers is held to its range | no |

Each fuzz was run against the reader as it was before its fix, where there was one, and failed
there: the Windows bitmap and file list, the bus's string length, and the MP3 library's index.

## 4. What the guide says each takes

- **From anywhere**, a user's file included: a picture, a sound, a video, a model, a theme. A
  damaged one is refused.
- **From the application alone, or from a source it trusts**: a font. The library that draws
  its outlines checks nothing a font says of itself.
- **A vector icon** is an application's resource; one from a user is rasterized by a native
  library with the guards above and no more.

## 5. What is not in this ADR

- A fuzz of the native decoders, in a process of their own.
- A font validated in Java before the native library sees it.
- Limits an application sets per call; the ceilings here are the toolkit's.
