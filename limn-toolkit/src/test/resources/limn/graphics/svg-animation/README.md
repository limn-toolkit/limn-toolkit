# Animated SVG, against a browser

The animation the toolkit reads out of an SVG and plays (ADR 066 section 5), checked against
what a browser computes for the same files at the same moments.

- `files/`: 104 animated SVGs. `smil-*` and `css-*` are the two halves of the svg-spinners
  collection (MIT, `LICENSE-svg-spinners.txt`), the same indicators written once in SMIL and once
  in CSS; `loader-*` is the SVG-Loaders collection (MIT, `LICENSE-svg-loaders.txt`). Unchanged
  but for the prefix of their names.
- `browser.json`: for every file, at 0, 130, 470, 1010, 1730 and 2900 ms of document time, the
  value a browser computes for every attribute the toolkit moves: the element's index in document
  order (the root first, animation elements not counted), the attribute, and the value: numbers,
  an RGBA colour, a keyword, or for `transform` the element's own matrix, its parent's CTM
  inverted times its own, so a CSS transform carries its origin in it.

`browser.json` was written on 2026-10-14 by Chrome 152: each file inserted into a page, its SMIL
paused and set with `setCurrentTime`, its CSS animations paused and set by `currentTime`, and the
values read with `getComputedStyle`, `SVGAnimatedLength.animVal` and `getCTM`. On that day the
toolkit's values matched all 3,014 that can be compared. `SvgAnimationBrowserTest` holds them to
it.
