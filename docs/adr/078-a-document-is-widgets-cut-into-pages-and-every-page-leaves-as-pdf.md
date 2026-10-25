# ADR 078: A document is widgets cut into pages, and every page leaves as PDF

- **Status:** Accepted and implemented, 2026-10-22. Every question was answered by the owner the
  same day (section 1); section 12's measurements were taken on the four guests and the host, and
  route A was measured to work on Windows; section 15 records what was built, where it departed,
  and what stays open.
- **Date:** 2026-10-22
- **Scope:** what a business application prints or saves as PDF: an order, an invoice, a report of
  a table that runs to many pages, a chart. How the application describes it (widgets, in a
  document of pages), how the toolkit cuts it into pages, how a page becomes PDF, how a PDF reaches
  a printer through the system's own print dialog on each of the three systems, and how the user
  sees the pages before printing.
- **New public API (proposed), all in `limn-toolkit`** (section 3.1):
  - the package `limn.print`: `Document`, `Paper`, `PageInfo` and `PrintPreview`;
  - `NativeWindow.printer()` returning a `limn.backend.Printer`, which hands a PDF to the system's
    print dialog; `PrintResult`; `Path2D.forEach` to read a path's curves back;
  - in the SPI: `PrinterPeer` and `GlyphOutlines`.
- **Compatibility:** additive for an application. `NativeWindow` gains `printer()` with no
  default, as `launcher()` has none, so every implementation of a window says what it answers; the
  toolkit's own are updated here and the test windows answer `Printer.NONE`. `Canvas` is unchanged.
- **Relates to:** [ADR 018](018-an-image-leaves-through-an-encoder-and-a-readback-names-its-colour-space.md)
  (an encoder in Java that needs no window), [ADR 020](020-background-work-is-a-job-with-a-lifecycle.md)
  (the work that writes the file), [ADR 031](031-a-run-of-text-is-shaped-once-and-the-widget-holds-the-result.md)
  (what a shaped run carries), [ADR 041](041-a-table-is-columns-over-a-list-the-application-owns.md)
  and [ADR 055](055-the-list-the-table-and-the-tree-walk-their-rows-with-one-engine.md) (the rows a
  page cuts between), [ADR 056](056-an-application-hands-an-address-or-a-file-to-the-system-through-its-window.md)
  (a window's door to the system, whose shape `printer()` follows, and whose Linux file-descriptor
  limit this record meets again).

---

## 0. Why, and what exists

Printing is what a business application does at the end of most of its tasks: the order is
printed for the warehouse, the invoice is saved as PDF and attached to an e-mail, the month's
report goes to the manager on paper, the table on screen is "exported to PDF" because that is the
format everyone can open. A desktop toolkit that cannot do this sends its application to a second
library with a second text engine, a second layout and a second idea of what a font is, and the
printed page then looks like another program's.

Limn has none of it. A search of the code and the documents at `63780536` for print, printer,
PDF, paper, page setup and dpi finds nothing about printing; the nearest thing is
`GpuRenderer.captureFramebuffer`, a window's pixels as a PNG.

What exists that this record builds on, read at `63780536`:

- **One drawing interface.** Every widget paints through `limn.graphics.Canvas`, in logical units,
  with one production implementation (`GlCanvas` in the backend). The interface is small enough to
  have a second: rectangles, rounded rectangles, ellipses, lines and paths with round caps and
  joins; a `Paint` sealed to a colour and two-stop linear and radial gradients; a rectangular and a
  rounded clip; translate, scale and rotate; opacity and an isolated layer; three blend modes;
  images, tinted images and masks; shaped text; a shadow; five backdrop effects; and
  `drawSurface` for the GPU textures of video and 3D.
- **A scene that needs no window to lay out and paint.** `new Scene(root)`, `layoutPass(w, h)` at
  any size, and `renderFrame(canvas)` into any canvas: the performance budget of ADR 062 already
  paints whole scenes into a counting canvas this way. A canvas that reports a larger
  `contentScale` gets larger icon bitmaps, so a page at 300 dpi is a canvas that says so.
- **Text that reaches the canvas as glyphs.** Widgets draw a `ShapedText`: glyph ids, positions and
  advances in visual order, the clusters back to the characters, and runs each naming a face by a
  number only the backend's font store can turn back into a face. HarfBuzz shapes it, through the
  LWJGL binding the backend already loads; that binding also exposes `hb_font_draw_glyph`, which
  hands out a glyph's outline as move, line, quadratic, cubic and close, for TrueType, CFF and CFF2
  outlines alike and with a variable font's axes applied. Nothing in the repository calls it yet.
- **Encoders in Java.** `PngEncoder` writes through `java.util.zip.Deflater`, deterministically and
  without a window (ADR 018). PDF's streams are the same Flate.
- **Doors to the system on all three.** COM through LWJGL's `JNI` on Windows, `objc_msgSend` on
  macOS, and a D-Bus client in Java that speaks to the desktop portal on Linux (the file chooser,
  the launcher, the appearance). That client cannot pass a file descriptor (ADR 056 §4), which is
  what the portal's print call takes (section 8).
- **A rule against what Java offers.** The build's architecture check refuses `java.awt.*` and
  `javax.swing.*` in every module, so the JDK's own printing (`java.awt.print`, and `javax.print`,
  which hands a job to the same AWT plumbing) is not available, and a PDF library would be a
  run-time dependency the toolkit has avoided for everything it writes.

## 1. The owner's answers

### 1.1. Answered (2026-10-22)

1. **Phase 1 delivers export to PDF, printing on the three systems and the preview, together.**
   Corporate users expect "Print…" and "Print preview" next to "Save as PDF".
2. **PDF stays, public.** The owner first set PDF aside, since every system's print dialog can
   already save a PDF (a button on macOS, a printer that writes PDF on Windows, "print to file" in
   the Linux desktops' dialogs). It came back on one fact: a page still has to reach the printer in
   some format, the Linux portal prints only a file (section 8.2), and route A on Windows reads a
   PDF. Since the writer is built anyway, `Document.writePdf` is public, and saving from the
   system's dialog works as well.
3. **In `limn-toolkit`, because it is small.** The owner first asked for an optional artifact,
   `limn-print-support`, so that an application that never prints would not carry the writer, the
   pagination and the preview, and then set the rule: negligible in size, it stays in the toolkit;
   considerable, it goes apart. Section 3.1 has the estimate, about 5,000 to 7,000 lines and 65 to
   90 KB of the jar, and on it the owner decided: in the toolkit.
4. **Windows: measure route A before choosing** (section 8.2, section 12 item 1). Route B is built
   only if the measurement refuses route A.
5. **No accessible PDF is planned now.** No tags, no PDF/UA, and the recording does not keep which
   widget drew what (section 11). A later record may take it up; it would redo the recording.
6. **Glyphs as outlines, written as Type 3 fonts with a map back to Unicode** (section 7.2), the
   recommendation. Text stays text: it can be selected, copied and searched, and it is sharp at
   any zoom. It works the same for every face Limn can load (TrueType, CFF, a variable font at its
   instance) with no font parser, because HarfBuzz already draws each outline. Embedding a subset
   of each font file would give smaller files for long text-heavy documents and hinted text in a
   viewer, but it needs a TrueType and a CFF subsetter written here (the LWJGL binding has no
   `hb-subset`), and a variable font cannot be embedded at an instance at all. A later 1.x can add
   it under the same API.
7. **The paper is painted with the light theme of the window's theme family** (section 5.2), the
   recommendation: `light()` for `dark()`, `limnLight()` for `limn()`, and `Document.theme(Theme)`
   for anything else, the window's own theme included. A dark window printed as it is spends a page
   of toner on the background, and a PDF is read later on a white page.

Answers 6 and 7 were given after the artifact question, which the owner had answered in their
place when they were first put.

### 1.2. Taken without a question

PDF/A (the archival profile) is left for later without a question: with glyphs as outlines the
remaining work is an sRGB output profile, XMP metadata and a validator run, and nobody has asked
for it.

Two choices are taken here rather than asked, because they are technical and the record can be
read against them: a document unit is 1/96 inch (section 5.1), and the system's print dialog may
scale a document to the printer's paper but never re-flows it (section 8.1).

## 2. What others do

Read on 2026-10-22, named by kind.

- **The JDK's own printing** asks the application for a page at a time through a callback
  that paints into an AWT graphics object; pagination is the application's, and PDF is not
  produced at all (a "print to file" printer does it). It needs AWT, which this toolkit does not
  load.
- **A Java scene-graph toolkit** prints one node per call onto the printer's page, scaled by
  the application, with the system's dialog; it offers no pagination of a table and no PDF.
- **A large C++ toolkit** has a painter that draws onto a printer or a PDF writer alike, the
  same painter its widgets use, and a document class that cuts rich text into pages; a table
  widget's own printing is left to the application, which renders it row by row. Its PDF writer
  embeds font subsets.
- **A browser engine** prints a page by laying it out again at the paper's width, cutting between
  lines and table rows, repeating a table's header group on each page, and writing PDF with font
  subsets. Its print preview is its own, the same on every system, ahead of the system's dialog.
- **Report libraries for business applications** take a template and a data source and produce
  pages with a header, a footer, "page n of m", a band per row and a group total; they are a second
  layout engine with its own fonts, which is the cost this record avoids.

What they agree on: the widgets' own painter draws the page; pagination cuts between rows and lines,
never through them; a table's header repeats; the preview is the toolkit's; the system's dialog
chooses the printer.

## 3. Decision

1. **A document is a column of widgets the toolkit cuts into pages** (section 6). The application
   builds the widgets for the document, the same components it uses on screen, over the same data;
   it does not lend widgets that are in a window.
2. **A page is recorded once and every output replays the recording** (section 7.1): the PDF
   writer, the preview, and on Windows the printer if route A is refused.
3. **The PDF is written in Java**, deterministic for the same input, with text as glyph outlines
   that keep their Unicode (the owner's answer 6).
4. **Printing hands that PDF to the system's own dialog**, owned by the window, on each of the
   three systems (section 8). The dialog chooses the printer, the copies, the range and the paper;
   the toolkit never draws a print dialog.
5. **The preview is a Limn widget**, the same on every system (section 9).
6. **What has no vector form becomes a picture at the document's resolution, and what has no
   picture at all is left out with a warning** (section 7.3); nothing fails a document.

### 3.1. Where it lives, and what it weighs

Everything is in `limn-toolkit` and the backend; there is no artifact of its own. The estimate the
owner decided on (2026-10-22) compares each part with code this repository already has, in its
own style of documentation, and converts lines to bytes by the toolkit's own jar on that day:
153,555 lines of source compile to 2.0 MB of compressed classes, about 13 bytes a line. It is an
estimate by analogy, good to about half again either way, not a measurement.

| Part | Lines | Compared with |
|---|---|---|
| the recording canvas | 700 to 900 | the sixty-odd methods of `Canvas` |
| the PDF writer's core: objects, cross-reference, Flate, resources | 900 to 1,200 | `PngEncoder` (300), `GifWriter` (393) |
| drawing as PDF: curves, shadings, clips, opacity, images, rasterized shadows | 1,200 to 1,600 | |
| text: Type 3 fonts, the maps to Unicode, `ActualText` | 600 to 900 | `HarfBuzzShaper` (540) |
| `Document`, `Paper`, pagination, header and footer | 1,200 to 1,800 | `Rows` (888) |
| `PrintPreview` | 600 to 900 | `Expander` (704), `Card` (1,701) |
| **the part an optional artifact would have held** | **5,000 to 7,000, about 65 to 90 KB** | 3 to 4 % of the toolkit's 2.35 MB jar, 0.2 % of the default install's 34.4 MB |

What the toolkit and the backend carry in either case: the hooks in the components (every row of a
list, a table or a tree, the break points of section 6.2, a scene with no focus, hover, caret or
scroll bar), 600 to 1,000 lines; the API and the SPI of printing, about 300; the doors of section
8.2, about 300 on macOS, 500 to 650 on Linux with the descriptor, and 700 to 1,000 on Windows by
route A, to which route B would add 1,500 to 2,500, also in the backend.

An application that never prints pays the jar's bytes and nothing at run time: no class of
`limn.print` is loaded until one is used. Kept in the toolkit, the pagination reaches the
components' internals (the rows engine of ADR 055 is `limn.components.internal`) as any other
package of the module does, with no export, qualified or not, and no second artifact to version
and publish. This follows the accessibility bridges, about 7,300 lines with no native payload,
which were brought back into the backend because there was no payload for a split to keep apart;
there is none here either.

**Why the door to the system is useful on its own.** It is reached from a window like the launcher
and the file choosers, and an application can print a PDF it received (an invoice from a service)
with `window.printer().print(pdf)` without building a `Document`.

## 4. The API

```java
Document order = Document.of(Paper.A4)                  // default: Paper.forLocale(locale)
        .marginsMm(18, 15, 18, 15)                      // top, right, bottom, left, as Insets
        .title("Order 4512")                            // the PDF's title, the print job's name
        .header(page -> new Label("ACME Distribution"))
        .footer(page -> new Label(page.number() + " / " + page.count()))
        .add(customerCard)                              // any widget
        .add(itemsTable)                                // cut between rows, header repeated
        .keepTogether(totalsBox)                        // moved whole to the next page if it does not fit
        .pageBreak()
        .add(new DonutChart(byCategory));

Work<Path> export = order.writePdf(file);              // a Work of ADR 020: progress, cancel
order.writePdf(outputStream);
order.print(window);                                    // CompletionStage<PrintResult>

PrintPreview preview = new PrintPreview(order);         // a widget: pages, zoom, page n of m

// a PDF the application already has
window.printer().print(pdfFile, "Invoice 0091");        // CompletionStage<PrintResult>
window.printer().isAvailable();
```

- **`Document`** is built on the UI thread and holds widgets, so it is used on the UI thread; what
  runs elsewhere is the writing of bytes (section 10). `print(window)` writes the PDF and hands it
  to `window.printer()`.
- **`header` and `footer` are functions of a `PageInfo`** (number, count, the paper) because a
  page's number is known only once the document is cut, and the count only once it is cut to the
  end; they run once per page, after pagination.
- **Margins** are in millimetres (`marginsMm`) or points (`marginsPt`), as the paper is.
- **`Paper`** has the ISO A and B sizes, Letter, Legal and Tabloid, `Paper.of(width, height)` in
  millimetres or points, and `landscape()`. `Paper.forLocale(Locale)` is Letter for the regions
  that use it (the United States, Canada, Mexico, the Philippines, and the others CLDR lists) and A4
  elsewhere.
- **`PrintResult`** is `PRINTED` (the system took the job; no system says whether paper came
  out), `CANCELLED` (the user closed the dialog), `UNAVAILABLE` (no printing on this desktop: a
  headless window, a Linux desktop with no portal) or `FAILED` with the reason logged. As with
  `Launcher`, a print never throws for what the document or the file holds; it throws for a null
  and off the UI thread.
- **`Printer.isAvailable()`** answers without asking, so an application can grey out "Print…".
  Export is always available.

## 5. The page

### 5.1. Units, and the resolution of what is not a vector

**A document unit is 1/96 inch**, the convention a browser prints with. Limn's units are tuned for
a screen at 100 %, where a body label is 13 units: at 1/96 inch it prints at 9.75 points, a body
size on paper, and a screen built for 96 dpi keeps its proportions on paper. At 1/72 inch the
same label would print at 13 points, a large-print page. `Paper` and margins are given in
millimetres or points and converted.

**What becomes a picture is drawn at 300 dpi** by default (`Document.rasterDpi(int)`), so the
canvas reports a `contentScale` of 300/96: an SVG icon, which the toolkit draws as a bitmap of the
size it is asked for, arrives at print resolution without any change to `Icon`.

### 5.2. The scene a document is painted in

A document's widgets live in a scene of their own that no window shows, painted with the light
theme of the window's family unless `Document.theme(Theme)` says otherwise (the owner's
answer 7). In it:

- **Nothing has focus, nothing is hovered, nothing is pressed**: no focus ring, no caret, no hover
  wash, no pressed state.
- **Nothing scrolls.** A scroll bar is not painted; a `ScrollView` placed in a document shows its
  content at full height, since a scroll position means nothing on paper.
- **No animation runs.** Every transition is at its end and a chart's values are its final ones;
  an animated image shows its first frame (ADR 065's still picture).
- **A selection is painted as it is.** A table's selected rows print selected; an application that
  prints a list without its selection builds the table without one, which it does anyway because the
  document's widgets are its own (section 3, item 1).
- **The locale and the direction are the document's**, set with `Document.locale(Locale)` and
  defaulting to the window's; a right-to-left document mirrors as a window does (ADR 032), its
  header and footer included.

## 6. Cutting into pages

### 6.1. The flow

The document's blocks are laid out top to bottom in the page's content box (the paper less its
margins, less the header's and the footer's heights, each measured once at the content width).
Each block is measured at the content width with an unbounded height:

1. **It fits in what is left of the page**: it is placed and the next block follows.
2. **It does not fit, and it can be cut** (section 6.2): it is cut at the last break point that
   fits, the part above is placed, and the rest continues at the top of the next page.
3. **It does not fit and cannot be cut, or was added with `keepTogether`**: it moves to the next
   page.
4. **It is taller than a whole page and cannot be cut**: it is sliced at the page's height, each
   slice a clip of the same painting. This is the last resort, said in the guide, because a slice
   may pass through a line of text.

A block narrower than the content box is placed by its own alignment; a block whose minimum width
is wider is scaled down to fit, never clipped, and the scale is logged once per document.

`pageBreak()` ends the page. There are no columns, no floats and no text flowing around a picture
(section 11): the flow is a column, as the layouts of this toolkit stay simple.

### 6.2. Where a widget can be cut

A widget that can be cut answers, at a given width, where its break points are: offsets from its
top between which it may be cut without cutting anything in half. The toolkit's own answer:

| Widget | Break points | What repeats on the next page |
|---|---|---|
| `Label`, a wrapping text | between lines | nothing |
| `TextArea` (read for printing) | between lines | nothing |
| `Column`, `Grid` | between children (or rows), and inside a child that can be cut | nothing |
| `ListView` | between rows | nothing |
| `Table` | between rows | the header row, and frozen columns stay frozen |
| `Tree` | between rows, every row shown expanded as it is in the tree | nothing |
| `Card`, `Field`, `Expander` | inside their content | nothing |

**Rows are every row, not the realized ones.** On screen the list, the table and the tree realize
only the rows in view (ADR 055), and their measured height is their visible rows. In a document a
row engine's measure is the sum of every row's real height, realized a page at a time and released
after it is recorded, so a table of 20,000 rows never holds 20,000 rows of widgets at once.

The break-point contract is internal in phase 1, inside the toolkit (section 3.1): an
application's own widget is measured whole and moved or sliced as in section 6.1.
Publishing it is additive and waits until the toolkit's own answers have been used.

### 6.3. Two passes

Pagination measures and cuts without painting; then every page is painted and recorded with its
`PageInfo` known, count included. A document's header and footer are measured once, before the
first pass, at the content width; one that would be taller on some page (a long title under a
number with more digits) is measured with the largest count the first pass can reach, so a page
never re-flows after its footer is written.

## 7. The recording and the PDF writer

### 7.1. The recording

A page is painted into a recording canvas, internal to `limn.print`, that keeps each call with its
arguments resolved to the page's coordinates and state. The recording is the one picture of a page:
the PDF writer reads it, the preview replays it into a window's canvas, and route B of section 8.2
would replay it into Direct2D. Nothing paints a page twice.

`Path2D` gains `forEach(PathVisitor)`, the verbs and points in order, because a path read back
through `flatten` is only straight lines; this is the one change to a published drawing type, and it
is additive.

### 7.2. Text

A glyph is written as its outline, in a Type 3 font per face and size of design (the owner's
answer 6), with:

- **the outline drawn by HarfBuzz** through a new SPI, `GlyphOutlines`, that the backend implements
  over `hb_font_draw_glyph` for the face number a `ShapedText` run carries: quadratics become
  cubics, a variable font is drawn at its instance, synthetic bold is a stroke the width of the
  coverage spread the screen uses;
- **a map back to Unicode** for each glyph, from the run's clusters, so selecting, copying and
  searching the PDF give the text; where a cluster's glyphs do not map one to one in logical order
  (Arabic joining, Indic reordering, a ligature), the run is wrapped in an `/ActualText` span with
  the cluster's characters, which is what a search and a copy use;
- **each glyph written once per document** and referenced on every page that uses it.

A colour emoji, which the toolkit draws from the font's bitmap strike, is written as that bitmap.
A string drawn with `drawText(String, …)` is shaped first by the installed ruler, as `GlCanvas`
does.

### 7.3. What maps to what

| Canvas | PDF |
|---|---|
| rectangles, rounded rectangles, ellipses, lines, paths | paths; rounds and ellipses as cubic arcs; round caps and joins |
| `Color`; `LinearGradient`, `RadialGradient` (two stops) | a colour; an axial or radial shading with `/Extend` at both ends |
| `clipRect`, `clipRoundRect` | a clip path |
| translate, scale, rotate; a 4×4 layer with no perspective | `cm` |
| `setOpacity`; `saveLayer` with opacity | `ExtGState` alpha; a transparency group |
| `MULTIPLY` | the `Multiply` blend mode |
| `drawImage`, tinted, masks | an image with a soft mask, each `Image` written once per document |
| shaped text | section 7.2 |
| **`drawShadow`** | a picture of the blurred shape at the raster resolution, with its alpha |
| **a 4×4 layer with perspective** | its content as a picture at the raster resolution |
| **`ADDITIVE`** | drawn as `Screen`, the nearest PDF blend, logged once |
| **the five backdrop effects** | their tint, the fallback `Canvas` already defines, because there is nothing painted behind them to blur on paper |
| **`drawSurface`** (video, 3D) | nothing, logged once per widget; a widget that wants to print a frame prints an `Image` the application took |
| `readBack` | `null`, which the contract allows |

The colour space is sRGB, written as such. The file is PDF 1.7, readable by every viewer in use,
with the document's title, its language (from the document's locale) and "Limn" as the producer; no
date unless the application sets one, so the same document gives the same bytes, which is how it is
tested (section 13).

### 7.4. Size

Pages are written as they are recorded and released, the cross-reference table at the end, so a
long document is written in the memory of one page and the images and glyphs it shares with the
others. Streams are Flate-compressed. A glyph's outline is written once, so a document's text
costs its glyph count in the outlines plus its characters on the pages; a picture costs its pixels
at 300 dpi.

## 8. Printing on each system

### 8.1. What every system does the same

`print` shows the system's own print dialog, owned by the window, as the file choosers are; the
user chooses the printer, the copies, the pages and the paper; the toolkit hands the PDF to the
system, which spools it. **A document is laid out once, on the document's paper.** If the user
picks other paper in the dialog, the system scales the pages to fit, never up; the document is not
re-flowed to the dialog's paper, because macOS shows its preview of pages that already exist and
the three systems cannot be made to re-flow at the same moment of their dialogs. An application
that wants another paper sets it on the document, which it can offer as a choice of its own ahead
of "Print…".

Saving as PDF from the dialog is the system's: macOS's PDF button, the Windows printer that writes
PDF, the Linux dialogs' "print to file". It writes what the system made of the job, which for a PDF
handed in is that PDF printed again; `Document.writePdf` writes the document's own file, with its
text, and needs no dialog.

### 8.2. How each system is asked

| | the dialog | the PDF reaches the printer by |
|---|---|---|
| **macOS** | the print panel as a sheet on the window, with the system's preview of the pages | PDFKit: a `PDFDocument` over the bytes, its `printOperationForPrintInfo:scalingMode:autoRotate:` run modal for the window; the call is checked against the macOS 11 floor in section 12 |
| **Linux** | the desktop portal's `PreparePrint`, parent the window, which returns the settings and a token | the portal's `Print` with the token and a file descriptor of the PDF; the desktop's print service takes PDF as its native job format |
| **Windows** | `PrintDlgEx`, owner the window | route A or route B, below; both go to a print job through Direct2D's print control (`ID2D1PrintControl`, Windows 8 and later) on a package target made for the printer and the print ticket the dialog chose |

- **Windows, route A.** The system's PDF renderer (`Windows.Data.Pdf`) draws each page of the
  PDF into a Direct2D device context whose target is a command list, and the print control takes
  the command list as a page. The renderer's native interface is documented for store-packaged
  applications; whether a plain desktop process may activate it, and whether a page drawn this way
  reaches the printer as vectors and text rather than a bitmap, are the two facts section 12
  measures before this route is taken (the owner's answer 4).
- **Windows, route B.** The recording is replayed into a Direct2D device context directly: paths as
  geometries, glyphs as the same outlines as the PDF's, pictures as bitmaps. No PDF is read; a
  second implementation of drawing is written and kept, and `window.printer().print(pdfFile)` for a
  PDF the application received would then have no Windows path, since nothing on that route reads
  a PDF.
- **Linux, the descriptor.** The toolkit's D-Bus client sends over a Java socket channel, which
  cannot carry a descriptor. The print call needs one: a connection of its own, opened and written
  through libc (`socket`, `connect`, `sendmsg` with `SCM_RIGHTS`) through the `Ffi` the backend
  already has. It also closes the gap ADR 056 §4 left for the portal's `OpenFile`, which is not
  taken up here.
- **Linux without a portal** answers `UNAVAILABLE`: the toolkit does not talk to the print service
  directly, because a dialog drawn by Limn listing printers is the dialog this record decided not to
  draw, and inside a sandbox the service is not reachable anyway. Export works and the launcher
  opens the file (ADR 056).
- **The thread.** Each system's dialog runs as the file choosers' do since 0.11: the window waits,
  the UI thread does not block, and the result completes on the UI thread.

## 9. The preview

`PrintPreview` is a widget that shows a document's pages as sheets on a neutral background, one or
two side by side, with zoom (fit the page, fit the width, a percentage), the current page and the
count, and keys to move between pages. It replays the recordings into the window's canvas, so what
it shows is what the PDF holds, pixel for pixel at the window's scale. A screen reader hears it as
a group of pages with "page 3 of 12"; the page's own text is not published to it (the owner's
answer 5).

The preview is the toolkit's because only macOS's dialog previews an application's pages: the
portal's dialog and `PrintDlgEx` show none. An application puts it in a window or a dialog of its
own, with its own buttons for paper and printing; the Showcase gets a page that does this.

## 10. Threads and cost

- **On the UI thread:** building the document, pagination, painting and recording, a page at a
  time in posted tasks (ADR 023) so a long document does not hold a frame.
- **On a worker:** compressing and writing the bytes, and handing them to the system.
- `writePdf` is a `Work` (ADR 020): progress in pages, cancel between pages, a partly written file
  deleted on cancel or failure.
- **The budget (ADR 062)** gains counts that do not follow the document's length: drawing commands
  and widgets realized per page of a long table, and the bytes of a glyph written for the second
  time (zero).

## 11. What is not done

- **No print dialog drawn by Limn**, no list of printers, no printer's capabilities, no silent
  printing to a named printer: each is a different call on each system, Linux's sandbox has none,
  and none is common to the three.
- **No re-flow to the dialog's paper** (section 8.1).
- **No page-setup dialog.** macOS and Windows have one and the portal has none; the application
  chooses the paper in its own interface.
- **No accessible PDF** (the owner's answer 5): no structure tree, no tags, no PDF/UA, and no record
  of which widget drew what. A PDF written here is read by a screen reader as its text in page
  order, which the Unicode maps of section 7.2 give.
- **No PDF/A** for now (section 1.2).
- **No columns, floats, footnotes, widows and orphans rules, or a table of contents.** A report
  that needs them is a report engine; this is the page of a business application.
- **No PDF reading, forms, signatures, encryption, links or bookmarks.**
- **No colour management beyond sRGB**, no CMYK, no spot colours.
- **Export with no backend.** Text needs the shaping ruler and the glyph outlines, which the backend
  provides once it is initialized; a server with no display cannot initialize it on Linux, so
  producing PDFs in a headless server process is out of phase 1.

## 12. Measured on the guests before this is accepted

Probes with no toolkit window first, as for the tray, on the four guests of the lab:

1. **Windows 11 (the ARM64 guest):** whether a desktop process activates `Windows.Data.Pdf` and draws
   a page into a Direct2D command list through its native interface; whether that page printed to
   the system's PDF printer keeps text as text (read back with the same renderer) or arrives as a
   bitmap; the job's size for a ten-page table. The answer chooses route A or B.
2. **Fedora KDE (Wayland) and Ubuntu GNOME (X11):** `PreparePrint` and `Print` through the portal
   with a descriptor passed by `sendmsg`, the dialog parented to the window, printing to a file and
   to the guest's queue; what each desktop's portal does with a token on the second call.
3. **macOS 26 (the guest):** the PDFKit print operation as a sheet on a GLFW window, with the panel's
   preview, cancelled and confirmed; the availability of each call against the macOS 11 floor read
   from the SDK's headers.
4. **The PDF itself:** opened, its text extracted and searched by an oracle on each system that
   already has one — PDFKit on the macOS host, `Windows.Data.Pdf` on Windows, the PDF utilities a
   GNOME desktop installs — for Latin, Arabic, Devanagari and CJK text, a 20,000-row table and a
   page of every component of the Showcase.

## 13. Tests

- **The writer:** byte-for-byte golden files for small documents (deterministic output), a parse of
  every file written by the suite that checks the cross-reference offsets, the object graph and that
  every font, image and shading referenced is defined.
- **Text:** the extracted text of a page equals the text that was shaped, by the oracle of
  section 12 where the host has one and by the writer's own maps everywhere.
- **Pagination:** break points of each widget in section 6.2; a table's header on every page; no row
  cut; `keepTogether`; a slice only when nothing can be cut; `count` equal on every page; the same
  document at A4 and Letter.
- **The print scene:** no focus ring, caret, hover or scroll bar in any recorded page.
- **The budget:** the counts of section 10 for documents of 10 and 1,000 pages.
- **Each backend door:** the strings and structures it builds (the portal's options, the print
  ticket's paper) checked without a printer; `Printer.NONE` in every test window.

## 14. Steps

1. The recording canvas, `Path2D.forEach`, `GlyphOutlines` in the backend.
2. The PDF writer over the recording: shapes, paints, clips, layers, images, text, the rasterized
   items; the golden tests and the oracle on the host.
3. `Document`, `Paper`, `PageInfo`, the print scene, pagination and the break points of section 6.2,
   rows realized a page at a time.
4. `PrintPreview`, and the Showcase page.
5. Printing: macOS, then Linux (the descriptor), then Windows by the route section 12 chose.
6. The guide (Printing and PDF), the CHANGELOG, and the reader runs the preview needs on the
   three systems.

## 15. As built (2026-10-22)

Every step is built: `limn.print` in `limn-toolkit`, at 4,107 lines against the 5,000 to 7,000 of
section 3.1's estimate, and the three doors in the backend, at about 1,900 lines. What
the build departed from, and what it found, in the order of the sections above.

### 15.1. Departures

- **The pages are recorded when they are asked for, all of them, and held until they are
  written.** Sections 7.4 and 10 had a page recorded in a posted task and written in the memory of
  one page. Recording is synchronous on the UI thread when `writePdf`, `print` or a preview asks,
  and the PDF is written page by page on a worker from the recordings; a recording holds calls and
  references to immutable values (shaped lines, images), not pixels. Recording in posted tasks is a 1.x change behind the same API.
- **A document records its pages again once a face its text asked for has loaded.** Not in the
  record, and found by the first document with Arabic and Japanese in it: a script's fallback face
  is loaded in the background on its first need, so the first recording drew boxes where those
  lines were, which a window heals on its next frame and a PDF would have kept. `GlyphOutlines`
  gained `settled()`, a stage the backend completes when no face is loading; `print` and a preview
  wait for it and record again, and `writePdf`'s work waits for it before writing, recording again
  on the UI thread. The work waits only for a load asked for before it began, so it never waits on
  a worker it keeps busy (a single-worker pool deadlocked the first version). Each waits ten
  seconds at most, the UI thread not at all: a load stuck past that left a print that never showed
  its dialog nor answered, and a PDF's work holding a worker, so the pages are then recorded with
  the faces that have loaded, with a warning.
- **A Type 3 font carries a font descriptor and a real box.** Section 7.2 did not say so. Without
  them the macOS PDF reader extracts no text at all from a Type 3 font, ToUnicode map or not,
  measured with a hand-written minimal file before and after.
- **What a page's clip hides entirely is not recorded.** A paragraph cut across three pages is one
  label painted three times under three clips, and a PDF reader extracting text reads a clipped
  line as well as a shown one, so every page carried the whole paragraph's text. The recording
  canvas drops text, rectangles and images whose box lies wholly outside the clip.
- **The header and the footer are measured after each cut, not once.** Section 6.3 measured them
  once, with the largest count the first pass could reach. They are measured for every page of
  each cut, and a cut that makes one taller is followed by another at the taller height, up to
  three more; the last cut is always made at the heights the pages are drawn with, so the content
  never runs under an edge. An edge a fourth count would make taller still is drawn in the room
  the last cut kept, cut at its foot, with a warning. (The first build stopped after its third
  measure and drew a header that grew then over the top of the content cut for the old one.)
- **Every block takes the content box's width.** Section 6.1 placed a narrower block by its own
  alignment and scaled a wider one down; a block is laid out at the content width, and a widget
  that should be narrower is wrapped in a row or a sized box. Scaling is not built.
- **Rows are cut only in a list, a table or a tree that is a block of its own.** Inside another
  block it prints the box it measures to, its visible rows; `keepTogether` on one does the same.
  `TextArea` gives no line cuts and is cut above or below it.
- **The colour space is DeviceRGB.** Section 7.3 wrote sRGB "as such"; an ICC output profile is the
  PDF/A work section 1.2 left for later, and the viewers in use read DeviceRGB as sRGB.

### 15.2. How the page scene is made

`Theme.of(widget)` answers the document's palette for a widget in a page's scene, through a
palette the scene carries (`SceneAccess.printWith`); `Surface` asked `Theme.current()` and now asks
through the widget like every other component. A `ScrollBar` draws nothing on a page. A page's
scene runs on a clock the document moves and at a time scale of 1,000, so three settling frames
take every transition to its end. A list, a table and a tree each register a view of their row
engine (`PrintRows`), and a label its line boundaries (`LineCuts`), from their static
initializers, in internal packages, so no public type names an internal one.

### 15.3. The three doors, measured on the guests (section 12, items 1 to 3)

- **Windows, route A, on Windows 11 ARM64 (build 26100), with an x64 and an ARM64 JDK.** A plain
  desktop process activates `Windows.Data.Pdf`, from the desktop session and from session 0 alike;
  the renderer draws a page into a Direct2D command list, and the print control prints it with the
  text as text: the system's PDF printer wrote embedded fonts and no image, and the host's reader
  extracted every line. A ten-page table (a 74,510-byte PDF in) was a 131,460-byte job in 734 ms,
  125 of them the renderer's; through the door with the dialog confirmed, 130,610 bytes. So route A
  is the route and route B is not built. `PrintDlgExW` runs on a thread of its own, owned by the
  window, with the document's paper in its starting `DEVMODE` (a standard paper when it is within a
  millimetre, a custom one otherwise) and its page count; the chosen range is printed, and the
  `DEVMODE` becomes the job's print ticket. Cancelling the dialog answers `CANCELLED`; Escape on
  the system's "save the print output as" prompt answered `FAILED` (`0x8007003F` from the package
  target) and is now mapped to `CANCELLED`, mapped from the measured codes and not run again since.
  **On Windows 11 `PrintDlgEx` shows the system's modern print dialog**, hosted outside the process:
  it disables the window while it is open, it has no preview ("this application does not support
  print preview"), and its title is the process's name, which for an application run on a bare JDK
  is the JDK's. A packaged application is named by its own executable.
- **macOS, on the macOS guest (27.0).** The print panel opens as a sheet on the GLFW window, with
  the system's preview of the pages, paper and orientation; Escape answers `CANCELLED` on the UI
  thread, and "Save as PDF" answers `PRINTED` with a file whose text the host's reader extracts. A
  window not shown runs the panel as a window of its own, application-modal. Every call is on the
  macOS 11 floor: nothing used needs more than 10.7, read from the SDK's headers. Building the panel
  the first time holds the main thread 436 to 702 ms, which is AppKit's and cannot leave it.
- **Linux, on Ubuntu 24.04 GNOME (X11) and Fedora 44 KDE (Wayland).** The portal's `PreparePrint`
  and `Print`, with the PDF as a sealed memory file passed over a connection of the door's own
  (`socket`, `connect` and `sendmsg` with `SCM_RIGHTS` through libc, SASL with unix-fd negotiation);
  the toolkit's D-Bus client gained the header field and an encoder for it, and its shared
  connections still never negotiate descriptors. Under X11 the dialog is the portal's, transient for
  the window and modal; under Wayland it opens with no parent, since GLFW exports no handle the
  portal can take. "Print to file" answered `PRINTED` on both with a byte-for-byte copy of the PDF,
  read by the guests' PDF utilities; both portals took the token on the second call without showing
  the dialog again. The GNOME portal answers Escape on `PreparePrint` with 2 where its Cancel button
  answers 1; both are `CANCELLED`, since no job was handed over, while a 2 from `Print` stays
  `FAILED`. `isAvailable()` reads the portal's `version` once, waiting at most 250 ms, and keeps the
  answer, read again in the background after a minute.

### 15.5. What stays open

- **A real printer queue on Linux.** Neither Linux guest has one, and making one changes the guest.
- **Ubuntu under Wayland**, and a parent for the portal's dialog under Wayland, which needs an
  exported toplevel GLFW does not give.
- **Escape on the Windows save prompt** answering `CANCELLED` is mapped from the measured codes and
  not run since.
- **A screen reader over the preview.** It publishes a group and a scroll pane, shapes every bridge
  already reads, so no reader run was made for it.
- **Found while measuring, not caused by printing, and closed the same day:** on Windows ARM64
  with an ARM64 JDK, an automation client crashed the process with
  `RPC_E_CANTCALLOUT_ININPUTSYNCCALL` inside the system's automation core, once in the window's
  accessibility bridge and once on the print thread while the PDF printer's prompt was open. The
  first is fixed in the bridge (ADR 039's amendment of 2026-10-22); the second is the same first
  answer of the process made by a system dialog, the file choosers' as well as the printer's
  prompt, and is fixed by starting UI Automation from the multithreaded apartment before the first
  system dialog (`UiaClientWarmUp`, ADR 039's second amendment of that day). Neither was in
  printing's own code: moving the print to the multithreaded apartment changed nothing, since the
  print system then shows the prompt on a thread of its own. The refusal is an exception the system
  raises and catches itself; an ARM64 JVM carrying OpenJDK's change JDK-8348862 ends the process at
  its first raise, which a native program with that `jvm.dll` started in it showed (ADR 039).

### 15.4. Measured on the host (section 12, item 4)

The macOS PDF reader on the host, as the oracle: a five-page document of real components with the
bundled fonts (a heading, a wrapped Portuguese paragraph, an Arabic and a Japanese line, a card, a
badge, a check box, a button, a donut chart and a table of 100 orders, with a header and a
"Página n de 5" footer) is 55 KB; every line is extracted, accents included, the Arabic in reading
order through its `ActualText`, the table's header on each page and every order once. Pictured, the
pages match the window's drawing of the same widgets. The guests' readers are item 4's remainder.
