---
title: "Printing and PDF"
description: "An order, an invoice or a report as a document of your own widgets: cut into pages, saved as PDF, printed through the system's own dialog, and shown in a preview first."
---

Printing in Limn is done with the widgets you already have. You build a `Document`, add the
labels, tables and charts you would put on a screen, and the toolkit cuts them into pages. The
same document is saved as PDF, printed through the system's own print dialog, or shown in a
preview, and all three show the same pages.

## A document

{% snippet guide:print-document %}

A document is a column of blocks. Every block takes the width of the page inside the margins
and as much height as it needs. When a block does not fit in what is left of a page, it is cut
where it can be cut:

- **Lines of text.** A wrapping label is cut between two of its lines, never through one.
- **Children.** A column is cut between its children, and inside a child that can itself be cut;
  a grid between its rows.
- **Rows.** A list, a table or a tree added to the document as a block of its own is cut
  between rows. Every row is printed, a page's worth at a time, however many rows there are, and
  a table's header is at the top of every page.

A block added with `keepTogether` is never cut: it moves whole to the next page. `pageBreak()`
starts a new page. A block taller than a whole page with nowhere to cut it is sliced at the
page's height, which can cut through a line; give it a place to cut.

The header and the footer are functions of the page. Each page gets its own, made when the page
is drawn, because a page's number is only known once the document is cut, and the count only
once it is cut to the end. The tallest one sets the room every page keeps for it.

### The widgets are the document's

A widget is in one place at a time, and a document draws each block in a scene of its own that
no window shows. So build the document's widgets for the document, over the same data as your
screen, rather than lending it the ones in your window. A table is two lines of code over the
list your screen's table already shows.

On a page nothing has the keyboard and nothing is under the mouse, so there is no focus ring, no
caret, no hover and no scroll bar. Every animation has finished, and a chart shows its final
values. A selected row prints selected, so leave the selection out if you don't want it on paper.

### What a page looks like

A document unit is 1/96 inch, which is what a browser prints with. A body label of 13 units
prints at 9.75 points, the size of body text on paper. Paper sizes are in `Paper`: the ISO sizes,
Letter, Legal and Tabloid, any size in millimetres or points, and `Paper.forLocale`, which picks
Letter in the regions that use it and A4 everywhere else.

The pages are painted in the light palette of your theme's family, because paper is white and a
dark page spends a page of toner on its background. `document.theme(...)` sets any other palette,
your window's own included. `document.locale(...)` sets the language the pages are laid out in;
it also sets the direction, so an Arabic or Hebrew document mirrors as a window does.

## Saving it as PDF

{% snippet guide:print-save %}

`writePdf` hands back a [background work](/docs/background-work/). The pages are recorded at the
call, on the UI thread, and the file is written on a worker once you start the work. The file is
written beside its final name first and moved into place, so a write that fails or is cancelled
leaves no half file behind. There is also an overload that writes to an `OutputStream`, for a PDF
you attach to an e-mail or send somewhere.

The text in the PDF is real text. Each glyph is stored as its outline, together with the
characters it stands for, so a reader can select, copy and search it, in every script Limn draws.
What PDF cannot hold is drawn another way:

- A soft shadow, or an icon, is a picture at the document's resolution (`rasterDpi`, 300 by
  default).
- A glass or blur effect is its plain tint.
- A video or a 3D view is left out, with a warning. Print a still image of it instead.

## Printing it

{% snippet guide:print-print %}

`print` opens the system's own print dialog, owned by your window. macOS shows its print panel as
a sheet with a preview of the pages, Linux shows the desktop's dialog through its print portal,
and Windows shows its print dialog. The dialog chooses the printer, the copies, the pages and the
paper. Every system can also save the job as PDF from there: macOS has a PDF button, Windows has a
printer that writes PDF, and the Linux dialogs can print to a file.

If the user picks different paper in the dialog, the system scales the pages to fit and does not
lay the document out again. To offer a choice of paper, offer it in your own window, before the
user clicks Print.

`PrintResult` says what happened. `PRINTED` means the system took the job; no system tells a
program whether paper actually came out. `UNAVAILABLE` means there is no printing under this
window: a headless window, or a Linux desktop without a print portal. `window.printer().isAvailable()`
answers that before you ask, so you can grey out a menu item.

## A preview

{% snippet guide:print-preview %}

`PrintPreview` is a widget. It shows the document's pages as sheets you scroll through, at a zoom
that fits the width, fits a whole page, or is a percentage of the paper's real size. It replays
the same recording the PDF is written from, so what it shows is what the file and the printer get.
Put it in a window or a dialog of your own, next to your own Print and Paper buttons; the
Showcase's Printing page does exactly this. Call `refresh()` after the data behind the document
changes.

The user zooms the preview as in a document viewer. A pinch on a trackpad zooms around the
fingers, and so does the wheel with Command held on macOS or Control elsewhere. With the preview focused, `+` zooms in, `-` zooms
out and `0` returns to the paper's real size. The arrows and Page Up and Page Down scroll the
pages. A zoom the user made is announced as a `VALUE` change from the user, so a toolbar that
shows the zoom can watch for it and read `zoomPercent()`.

The preview is part of the toolkit rather than the system because only one of the three systems'
print dialogs shows an application's pages.

## A PDF you already have

{% snippet guide:print-received %}

The window's printer prints any PDF file or bytes through the same dialog, with no document: an
invoice your application received from a service, or a report it saved earlier.
