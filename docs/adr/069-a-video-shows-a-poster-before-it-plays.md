# ADR 069: A video shows a poster before it plays

- **Status:** Accepted, 2026-10-15. The owner chose the poster in the toolkit, the first picture
  by default, a picture at a time or an image of the application's as the alternatives, and the
  time over a picture's number.
- **Date:** 2026-10-15
- **Scope:** what a `VideoView` shows before anyone has pressed play, with a stream of its own or
  with a `MediaPlayer`.
- **New public API:** `limn.video.Poster`; `VideoView.setPoster` and `poster()`;
  `MediaPlayer.cue(long)`.
- **Compatibility:** additive. A view showing a `MediaPlayer` that was never started showed the
  bar colour and now shows the first picture: the behaviour `VideoView` already promised for a
  stream, extended to the player.

---

## 1. Why

The Showcase's video page opened on a black box. It plays through a `MediaPlayer`, for the
picture and the sound together. A player nobody has started decodes nothing, so the view had
nothing to show. The view's own documentation says a paused video is a still frame, not an empty
box, but that held only for a stream shown without a player. A business application that lists
recorded meetings or product clips shows each one stopped until it is chosen, and a list of black
boxes reads as a list of failures.

## 2. Decision

**`Poster`** says what the view shows before playback has shown a picture of its own:

| `Poster` | Shows |
|---|---|
| `firstPicture()` (the default) | the stream's first picture |
| `at(Duration)` | the picture at that time, reached by an exact seek, as a stream's `seek` lands |
| `image(Image)` | a picture of the application's, fitted to the box as the video would be |
| `none()` | the bar colour |

- **Playback starts at the beginning whatever the poster was.** A poster at three seconds is a
  cover, not a starting point.
- **A poster is shown until playback shows a picture of its own**: from when a source or player is
  installed until the first press of play. Once the video has played, a pause holds the picture
  it reached, as before.
- **By time, not by picture number,** the owner's choice. A stream can only be moved to an instant;
  it keeps no index of its pictures. A number would cost decoding every picture before it on every
  opening, and on a stream of variable rate the same number lands at different instants. At a
  constant rate, picture *n* is at *n* / rate.
- **When the stream cannot go there, the poster falls back.** A stream that cannot seek shows its
  first picture for `at`. A time past the end shows nothing, and the bar colour stays.

**`MediaPlayer.cue(long micros)`** is how a player produces a poster without playing. On a player
that has not started, it begins decoding at that time with no sound and no clock. It hands the
first picture over once through `takePicture`, and the player stays `IDLE`. `start()` then plays
from the beginning, seeking back first if the cue was elsewhere. It is public because an
application that draws a player's pictures itself wants the same thing.

## 3. What is not done

- **Thumbnails of a whole file list**, one decoder per row: an application opens a stream per
  file and closes it after the poster. Nothing here keeps decoders open for a list.
- **A poster after the end.** A video that ended keeps its last picture, as before.

## 4. Tests

- The four posters on a stream and on a player.
- Playback from the beginning after a poster at a time, with nothing heard before play.
- The fallbacks when the stream cannot seek or the time is past the end.
- The Showcase's page, seen showing its first picture.
