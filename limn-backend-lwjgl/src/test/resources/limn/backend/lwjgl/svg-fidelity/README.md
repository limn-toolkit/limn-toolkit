# How faithfully SVG is drawn

What `SvgFidelityTest` holds (ADR 066 section 7).

- `suite/`: 30 tests of each of six kinds of the resvg test suite (shapes, painting, paint
  servers, masking, structure, filters), each an SVG and the PNG the suite expects of it, taken
  at an even step through the kind's tests in name order, so each feature is sampled. Unchanged
  but for their names: `kind--feature--test`. MIT (`LICENSE-resvg-tests.txt`). Text is left out:
  the suite's pictures of it use fonts of its own.
- `real/`: 18 files of the kind an application ships: six illustrations of empty states and six
  interface icons from an open-source design system's package (MIT,
  `LICENSE-illustrations-and-icons.txt`), and six colour emoji from an open emoji font's sources
  (Apache 2.0, `LICENSE-emoji.txt`), two of them written with internal XML entities and in
  ISO-8859-1. Each PNG beside one is what a browser drew of it at 128 pixels on its longer side:
  Chrome 152 on 2026-10-14, the file loaded as an image and drawn to a canvas. Not resvg's
  picture: resvg reads no CSS custom properties, and the illustrations paint through them.

A picture is right when no more than 5% of the pixels either picture paints differ by more than
40 levels, both laid on white. Measured with lunasvg 3.5.0 and the toolkit's reading: of 30,
shapes 29, painting 20, paint servers 27, masking 22, structure 16, filters 6; every real file.
