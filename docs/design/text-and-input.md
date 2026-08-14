# Text and input

Background for `TextField`, `TextArea`, `PasswordField` and `Spinner`.

## Why the password dot is drawn rather than typed

It was a per-step glyph table once (MIDDLE DOT at the dense steps, BULLET at MEDIUM,
BLACK CIRCLE at the display steps), on the theory that a dot carries no optical size of
its own and needs a per-step ink ratio. The theory was fine; the glyphs were not.

Measured as fractions of the em, that ramp's ink was 0.11 / 0.11 / 0.20 / 0.90 / 0.90.
Nothing in the bundled faces sits between BULLET and BLACK CIRCLE, so the ramp was a
4.5× cliff in its own middle. Against a type ramp spanning only 11→19 pt, the painted
dot came out at 1.2 pt at the smallest step and 17.1 pt at the largest: the mask dwarfed
the text it stood for at one end and nearly vanished at the other, and MEDIUM (the
identity step) held the smallest solid mark in the toolkit.

Worse, `U+25CF` is absent from the bundled last-resort face. Roboto has `U+00B7` and
`U+2022` and no BLACK CIRCLE, so the large-step mask resolved through the per-code-point
fallback chain, which loads in the background. The first frame of a LARGE or XLARGE
password field in a fresh process painted a row of `.notdef` boxes. That was captured,
not hypothesised.

A drawn circle has no quantisation and needs no font: one ratio, and the type ramp
supplies the size. 0.36 em is where the faces normally used for password fields put
their bullet: Verdana's BULLET is 0.365 em, Helvetica's and Arial's BLACK CIRCLE 0.430,
the macOS system font's BULLET 0.191 and its BLACK CIRCLE 0.789.

## The TextArea content inset

Horizontal and vertical insets are different tokens on purpose. Horizontally the area
uses the same `fieldPadH` a `TextField` insets by, so a field and an area stacked in a
form put their first character on the same column; before that they were 4 pt apart at
MEDIUM and the ragged left edge showed in every form. Vertically it keeps `areaPad`,
which is a text margin rather than a vertical-centring pad; nothing outside the widget
has to agree with it.

The preferred box is derived per step to hold a roughly constant amount of visible
content rather than a constant box. Spending one column on the wider horizontal pad
tilts the ramp slightly, because `fieldPadH` climbs faster than `areaPad` did. Restoring
a flat column count would mean re-deriving the box, not changing the widget.

## Scrollbars do not scale

`ScrollBar.thickness()` is 15 pt at every size step, and the bars overlay content rather
than insetting it. At the dense steps the bar therefore floats over more live text than
at MEDIUM: 7 pt against 3 for the vertical bar in a `TextArea`. This is the accepted
cost of one scrollbar geometry process-wide. Where the last column matters at a dense
step, put a trailing margin in the surrounding layout.
