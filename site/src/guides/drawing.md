---
title: "Drawing"
description: "The canvas a widget paints with: shapes, paths, lines, gradients, transforms, clips, layers, text as a path and pictures."
---

Every widget draws itself on a `Canvas`, in its `onPaint`, in its own coordinates from `(0, 0)` to
`width()` by `height()`; [Your own widgets](/docs/custom-widgets/) shows where that method sits and
how a widget asks for the room it draws in. This page is about the canvas itself. Each example
below is the body of an `onPaint` drawing in a box 160 points wide and 120 high, and each picture
is those same methods, drawn by the toolkit.

The canvas is the same on screen and on paper: a [printed document](/docs/printing/) draws
with it too, and writes every path, line, gradient and clip as the page's own, so what follows
prints as it shows.

## Shapes

{% snippet guide:drawing-shapes %}

A rectangle, a rounded rectangle, a circle and an ellipse are filled with `fill...` and outlined
with `draw...`, which takes the line's width before its paint. Colours are `Color` values; a
widget usually takes them from its theme, `Theme.of(this).primary()`, so that it follows a change
of palette.

{% shot drawing-shapes "Shapes, the two fill rules, a path read from SVG data with a rounded corner, and lines." %}

## Paths and their fill rules

Any other outline is a `Path2D`: `moveTo` starts a subpath, `lineTo`, `quadTo` and `cubicTo`
extend it, and `close` draws it back to its start.

{% snippet guide:drawing-path %}

The star is one outline that crosses itself, and its fill rule decides its middle. Under
`FillRule.NON_ZERO`, the default, a region the outline winds round is filled; under `EVEN_ODD`, a
region inside it twice is a hole. The same rule makes a hole of a subpath turning the other way
inside another, as the counter of a letter is.

A path also takes arcs and whole shapes, and reads the path data an icon or a drawing program
writes:

{% snippet guide:drawing-parse %}

`arcTo` rounds the corner between the line drawn so far and the next one; `arc` and
`ellipticalArcTo` draw arcs of a circle or an ellipse; `addRect`, `addRoundRect`, `addCircle`,
`addEllipse` and `addPath` add whole shapes, and `transformed` copies a path under an `Affine2D`.

## Lines

A line is a `Stroke`: its width, its ends, its corners and a dash pattern, passed with the call
as a paint is.

{% snippet guide:drawing-strokes %}

The ends are `BUTT`, `ROUND` and `SQUARE`; the corners `MITER`, cut to a bevel past its limit,
`ROUND` and `BEVEL`. A dash pattern alternates drawn and skipped lengths, and a dash of no length
with round ends is a dot. `drawLine`, `drawRect`, `drawRoundRect`, `drawCircle`, `drawEllipse` and
`drawPath` all take a `Stroke`. A translucent line is painted once where it crosses itself and
where its segments meet, not darker at each.

## Hit testing

A path answers in plain Java, with no canvas: its `bounds()`, and whether it `contains` a point
under its fill rule. A stroke's `outline` is the region a line along the path covers, so a hit on
the line is a hit on that.

{% snippet guide:drawing-hit %}

## Paints

Whatever takes a colour takes a `Paint`: a `Color`, a gradient, or a picture.

{% snippet guide:drawing-gradients %}

`LinearGradient` and `RadialGradient` blend two colours. A `Gradient` takes any number, each at a
position from 0 to 1, two at one position making a hard edge: linear, radial from a focus inside
its circle, or a sweep once round a centre. `withSpread` pads, repeats or reflects it past its
ends, and `withTransform` places it under a matrix of its own, which turns a radial gradient into
an ellipse.

{% snippet guide:drawing-pattern %}

An `ImagePaint` paints with a picture, repeated, mirrored or laid once on each axis
(`withTile`), under a matrix of its own, smooth or pixelated. Paints fill text and lines as they
fill shapes.

{% shot drawing-paints "Gradients of two and of many colours, a radial lit from a focus, a sweep, an image repeated as a hatch, and two transforms." %}

## Transforms

{% snippet guide:drawing-transforms %}

`translate`, `rotate`, `scale` and `shear` move the coordinates of what follows, and
`rotate(angle, cx, cy)` turns about a point. `save` and `restore` bracket a change, so that what
comes after draws as before. `concat` draws through any `Affine2D`, and `transform()` answers the
matrix in force from the window's corner, which `invert()` turns into the way back from the
window's coordinates to the drawing's.

## Clips

{% snippet guide:drawing-clips %}

`clipRect`, `clipRoundRect` and `clipPath` cut what follows to a shape, antialiased at its edge,
until the `restore` that undoes them; two clips leave their intersection. A path clips under its
fill rule, and a rectangle clipped under a turn is cut by its turned edges.

## Layers and blend modes

`saveLayer` draws a group into a picture of its own, composited at one opacity when it is
restored. Overlapping parts of a translucent group then do not show through each other, and the
layer's picture, which starts transparent, gives the blend modes that combine with what is
already drawn something to combine with.

{% snippet guide:drawing-layers %}

Inside a layer the Porter-Duff rules mean what their names say. A draw in `DESTINATION_OUT` takes
away what the group drew, by as much as its paint is opaque, which is an eraser; one in
`SOURCE_IN` paints only over what the group drew, which fills a shape drawn before.

{% snippet guide:drawing-blend %}

`MULTIPLY` darkens, `SCREEN` lightens, `ADDITIVE` adds light, and `DARKEN` and `LIGHTEN` keep the
darker or the lighter of the two. Each acts only where a draw covers, by as much as its
antialiased edge covers. Drawn in a layer, as here, a mode mixes the group's own drawings;
outside one it mixes with whatever is behind, the window's background included.

{% shot drawing-clips "Stripes seen through a star, a gradient cut to a rounded rectangle, an eraser and a fill inside a shape in two layers, and two discs multiplied." %}

## Text as a path

`drawText` places text; [Text and languages](/docs/text-and-languages/) covers fonts, shaping and
styled text. A shaped line's `outline(x, y)` is that text as a path, every glyph where `drawText`
would draw it, so a word can be stroked, dashed, filled with any paint or used as a clip.

{% snippet guide:drawing-text-path %}

## Pictures and images

A drawing made once and shown many times is a `Picture`: recorded once, and replayed by every
`drawPicture` in that canvas's coordinates, transform, clip and opacity, on screen, on a printed
page or inside another picture.

{% snippet guide:drawing-picture %}

`Images.render` draws into an `Image`, with the canvas a window draws with, for a thumbnail, a
chart exported as a picture, or a drawing kept to show again:

{% snippet guide:drawing-render %}

Each call waits for the picture to be finished and read back, a cost of its own besides the
drawing; [On a server](/docs/printing/#on-a-server) has the numbers, and how pictures are drawn
where there is no window at all.

{% shot drawing-text "A word stroked with a dashed line and filled through its letters, a picture of a pin stamped round a ring, and an image of twelve pixels drawn off screen and shown enlarged." %}
