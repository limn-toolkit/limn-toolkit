# Audio fixtures

- `tone.ogg`: 5512 stereo frames (0.25 s) of a 440 Hz tone at 22050 Hz, Ogg Vorbis, written with
  libsndfile 1.2.2 (the one bundled in the `soundfile` 0.13.1 wheel).
- `tone-lying-length.ogg`: the same file with its last page's granule position set to 2^30 and the
  page CRC recomputed, so the file claims about thirteen hours; its audio is unchanged.

Both were generated for this repository; no third-party recording.
