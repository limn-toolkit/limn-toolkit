# ADR 083: A label can show styled text, built from pieces and shaped as one paragraph

- **Status:** **Accepted and implemented**, 2026-10-30. Proposed and accepted the same day: the
  owner set the scope (section 1) and answered the seven questions of section 4 (section 6), and
  the build followed (section 7, with what departed from the text). Owed: the owner's publication
  of `limn-fonts-roboto` 2.138.1 and the catalog line that takes it (section 7.4), and the
  owner took the three choices a review of the whole branch left (section 7.6). This reopens,
  for display only, the "rich text" that ADR 067 refused; an editor stays out (the owner: a distant
  future). [ADR 084](084-code-and-markdown-are-shown-by-projects-outside-the-toolkit-and-the-toolkit-carries-only-what-they-need.md)
  (2026-10-31) makes a line separator (U+2028) a line break inside its paragraph, and adds a
  paragraph's direction and a list item's number.
- **Date:** 2026-10-30
- **Scope:** text in more than one style inside one `Label`: weight, slant, size, family, colour,
  background, underline, strike-through, raised and lowered text; per paragraph its alignment,
  indents, spacing and a list marker. How it is built so that a translation can move the styled
  part, how a paragraph of several styles is shaped, broken into lines and drawn, what a weight
  between regular and bold needs from the faces, and what reaches a printed page and a reader.
- **New public API, all in `limn-toolkit`:** in `limn.graphics`, `StyledText`, `TextStyle`,
  `ParagraphStyle`, `StyledLayout`, `Ink` and `TextDecorations`, a weight on `Font`,
  `TextRuler.shapeRun` and `TextRuler.decorations`, and `Canvas.drawText` over a range; in
  `limn.components`, `Label.setText(StyledText)`, `Label.styledText()`,
  `Label.setIconPosition` with `Label.IconPosition`, and `Tone` as an `Ink` (section 7).
- **Compatibility:** additive, except `Font`'s record components (section 6, question 2): a
  record pattern of four components over `Font` no longer compiles.
- **Relates to:** [ADR 006](006-i18n.md) (a translated string with arguments),
  [ADR 031](031-a-run-of-text-is-shaped-once-and-the-widget-holds-the-result.md) (a shaped run is
  a value), [ADR 033](033-a-number-is-localized-when-it-is-formatted.md) (list numbers),
  [ADR 045](045-a-widget-is-one-of-ten-shapes-and-the-bridge-translates-the-shape.md) (what a
  reader is given), [ADR 070](070-access-letters-come-from-the-translated-text.md) (a mark inside
  translated text), [ADR 074](074-a-field-is-a-caption-a-control-and-the-line-under-it.md)
  (`Tone`), [ADR 078](078-a-document-is-widgets-cut-into-pages-and-every-page-leaves-as-pdf.md)
  (a label cut between lines on a page).

---

## 0. What this rests on

Read at `1ad01bfa`. Two probes were run on the host (0.3, 0.4); their sources are not kept.

### 0.1 What the three Java toolkits offer, and what is worth taking

| | Worth taking | Worth avoiding |
| --- | --- | --- |
| SWT `StyledText` | ranges of a style that follow the text; per-line alignment, indent, wrap indent, justify, bullet, tab stops, spacing; underline single, double, squiggle and link; `rise` for raised text; styles computed on demand (`LineStyleListener`) | a range is an offset into the text, so a translation breaks it; computing styles on demand switches the range API off; a style inherits nothing |
| `java.awt.font` | a style is a map of only the attributes that change, the rest inherited; continuous weight; superscript and subscript from the font; tracking; strong and weak carets | drawing only, no paragraph model |
| Swing `StyledDocument` | named styles with a parent a style resolves through; character and paragraph attributes apart | the View and EditorKit machinery |
| JavaFX `RichTextArea` (incubating since 24), `TextFlow` | paragraph attributes as a first-class set (alignment, first-line indent, space above, below, left and right, line spacing, bullet, direction); a builder for a style | `TextFlow` lays each `Text` node out on its own: a colour changed inside an Arabic word cuts the word's joining |

What none of them solves is translation. Every one of them styles by offset, and an offset is a
fact about one language's sentence.

### 0.2 What Limn has, and where it stops

- **A run of text is shaped once, in one font.** `ShapedText` (`limn.graphics`) answers carets,
  hit tests, selection boxes, advances and line fits, bidi included, but holds one `font()`.
  `Label` keeps one per paragraph and wraps it with `LineBreaks`, shared with `TextArea`.
- **The shaper already shapes a run inside a longer context.** `HarfBuzzShaper` hands HarfBuzz the
  whole string and shapes only the run (`HarfBuzzShaper.java:378`), which is how a run boundary
  keeps contextual forms. So shaping a weight change inside an Arabic word with the word around
  it as context costs a new entry in the SPI, not a new mechanism.
- **A shaped run is painted in one paint.** `Canvas.drawText(ShapedText, x, y, Paint)`
  (`Canvas.java:663`) has no range. A colour change inside a run has nowhere to go.
- **`Font` is a record of four components:** `family`, `size`, `isBold`, `isItalic`
  (`Font.java:19`). Nothing between regular and bold can be asked for.
- **The bundled family has four faces:** Roboto Regular, Bold, Italic and Bold Italic
  (`limn-fonts-roboto` 2.138.0, about 350 KB each).
- **Translated text takes arguments:** `I18nString.withArguments` (ADR 006). A mark inside a
  translation has a precedent: the `&` of an access letter (ADR 070).
- **A label is cut between its lines on a page** through `LineCuts` (ADR 078, `Label.java:194`).
- **No bridge gives a reader the attributes of any text.** Windows serves no `TextPattern` at all
  (`UiaPatterns.java:17`, "deferred"). AT-SPI answers `GetAttributeRun` and
  `GetDefaultAttributes` with an empty map (`AtspiTree.java:1280`). macOS serves no attributed
  string.

### 0.3 Probe: what the HarfBuzz the backend ships can say about a face

HarfBuzz 3.4.3 from LWJGL, the version `gradle/libs.versions.toml` pins, run against Roboto
Regular and the variable face the demo ships (`limn-demo/fonts/Inter-Variable.ttf`):

| Question | Answer |
| --- | --- |
| underline offset and thickness (`hb_ot_metrics_get_position`) | −150 and 100 font units (of 2048) |
| strike-through offset and thickness | 512 and 102 |
| superscript and subscript: vertical offset, size | 977 and 1331; 287 and 1331 |
| does `wght` reach the shaping (`hb_font_set_variations`)? | yes: "Waltz, bad nymph" advances 16542 at 100, 17401 at 400, 17795 at 600, 18496 at 900 |
| is HarfBuzz's own rasterizer in the shipped native? | yes: `hb_raster_draw_glyph` is exported by `libharfbuzz-raster` |

So the decoration metrics come from each face, not from a fraction of the size. A variable face
is *shaped* at any weight. stb_truetype, which draws every glyph today, does not read variation
tables, though: it would draw a variable face at its default instance, with the advances of
another weight. A variable instance has to be drawn by HarfBuzz (question 4).

### 0.4 Defect found: a system family's regular face was chosen by file order

`FontStore.pickFace` took, for regular, the first face that was neither bold nor italic, and for
bold, the first whose style name contains "bold". On this Mac, a system family of twelve faces
lists Bold, Demi Bold, Italic, Medium, Regular, Heavy and Ultra Light in that order, and asking
it for regular drew its **Medium**. A second family of the same kind declares "Book" and "Roman"
both at weight 400, and its Book stood in for its regular. **Fixed in this branch, with tests**
(`FacePickTest`), because weights from 100 to 900 need the same piece:

- `SystemFonts` reads each face's `OS/2` `usWeightClass`, `usWidthClass` and `fsSelection` slant
  bits, and falls back to the style name where the table is missing or out of range.
- `pickFace` keeps the request's side of the weight line before its slant, as the bundled
  resolver does. Within that it takes the nearest weight in the order CSS Fonts level 4 §5.2
  searches (for 400: 400, 500, lighter, heavier; for 700: heavier, then lighter), then the width
  nearest normal, then the plain style name.
- Measured on the same two system families: Regular and Bold for the first, Roman and the face
  declared 800 for the second. The backend's suite passes (1484 tests).

### 0.5 Translation decides how styled text is built

"Total: **R$ 1.234,00**, due **tomorrow**" in German or Japanese puts the amount and the date
elsewhere in the sentence. A style stored as `(7, 18)` is wrong as soon as the sentence is
translated. A styled *argument* of the translated pattern lands wherever the translation put
`{0}`.

---

## 1. The owner's decisions (2026-10-30)

1. **Display only.** No editor, no caret, no input method, no undo; an editor is a distant future.
2. **On `Label`:** `Label.setText(StyledText)`, no widget of its own, so wrapping, `maxLines`,
   the ellipsis, alignment, tone, translation, `labelFor` and the page cuts are the label's.
3. **No interaction:** no span that can be clicked, no selection, no copy.
4. **Paragraphs and lists:** styles inside a line; per paragraph alignment, indents, spacing
   before and after, line spacing; bulleted and numbered lists with a hanging indent.
5. **Weights from 100 to 900, without breaking `Font`:** a new constructor and `withWeight`; the
   record and `isBold` stay.

---

## 2. Decision

### 2.1 Styled text is a value built from pieces

```java
// Built in code:
StyledText summary = StyledText.builder()
        .append("Total: ")
        .append(amount, TextStyle.of().weight(600))
        .append(", due ")
        .append(date, TextStyle.of().tone(Tone.WARNING))
        .build();

// Built from a translation, the styled part placed by the translator:
StyledText due = StyledText.of(Strings.TOTAL_DUE,            // "Total: {0}, due {1}"
        StyledText.span(amount, TextStyle.of().weight(600)),
        StyledText.span(date, TextStyle.of().tone(Tone.WARNING)));

label.setText(due);
```

- **Immutable, like `I18nString` and `ShapedText`.** A label holds it and re-lays it out when the
  width, the locale, the font or the palette changes.
- **No offsets in the public API.** Pieces and arguments carry their style. `StyledText.text()`
  is the plain text, and that is what `Label.text()` answers.
- **A style holds only what it changes.** An attribute a piece does not set comes from the
  paragraph, and a paragraph's from the label (`setFont`, `setTone`, `setColor`, `setStrong`,
  `setItalic`). So a theme or default-family switch still reaches a styled label, as it reaches
  a plain one today.
- **No markup.** No `<b>`, no Markdown, no HTML parsed anywhere. An argument moves with its
  `{n}`, which is the placement a translator needs (question 7).

### 2.2 What a piece of text can say (`TextStyle`)

| Attribute | Values | Note |
| --- | --- | --- |
| family, size | a family name; a size in points or a scale of the paragraph's | |
| weight | 100 to 900 | section 2.5 |
| italic | yes or no | |
| ink | a `Tone` (resolved at paint) or a `Color` | a tone follows the palette, as `Label.setTone` does |
| background | a `Tone` or a `Color` | painted behind the piece's selection boxes, so it is right in bidi text |
| underline | none, single, double, dotted, wavy; its own ink | position and thickness from the face (0.3) |
| strike-through | yes or no; its own ink | from the face |
| baseline | normal, superscript, subscript | offset and size from the face (0.3) |
| tracking | extra space between letters, in ems | never inside a cursive script, which would break its joining |

Left out: shadows and outlines on text, text along a path, OpenType feature switches,
small caps. None of them is common in a business application, and each is a paint or shaping
path of its own.

### 2.3 What a paragraph can say (`ParagraphStyle`)

- **Alignment:** start, centre, end, justified. Start and end follow the paragraph's direction.
  A paragraph that sets none takes the label's `HAlign`.
- **Indents:** start and end, in points; a first-line indent, negative for a hanging indent.
- **Spacing:** before and after, in points; line spacing as a multiple of the tallest run's line.
- **List:** a marker and a level. Bullets (•, ◦, ▪ by level) or numbers (1., a., i. by level),
  the digits localized as ADR 033 localizes a number, counted per level across consecutive list
  paragraphs. The marker hangs in the start indent, on the side the lines are aligned to (7.5).
- A `StyledText` holds paragraphs: `builder().paragraph(ParagraphStyle)` starts one, and a `\n`
  inside a piece starts one with the same style.

### 2.4 A paragraph is shaped as one, split only where the font changes

1. **Bidi over the whole paragraph** (`java.text.Bidi`, as ADR 031), never per piece.
2. **A shaping run is a stretch with one font** (family, size, weight, slant, baseline) **and one
   bidi level.** Ink, background, underline and strike-through split no run: a colour changed
   inside an Arabic word leaves the word's glyphs exactly as they are without it.
3. **Each run is shaped with the paragraph as its context.** The shaper already does this
   (0.2), so contextual forms survive a weight change inside a word.
4. **Lines are broken over the whole paragraph:** `BreakIterator` under the locale, each run's
   `fitEnd` and `advanceTo` for the width, the trailing space hanging past the margin as
   `LineBreaks` does today. The line's ascent and descent are the largest of its runs, and every
   run sits on the line's baseline.
5. **Painting walks the line's runs in visual order** and paints each colour's glyph range, then
   the decorations from the face's metrics.

In the SPI: `TextRuler.shape(paragraph, start, end, font, direction)` shapes a run in its
context, and `Canvas.drawText(shaped, charStart, charEnd, x, y, paint)` paints a range. The GL
canvas and the PDF writer implement the range; a canvas that does not inherits a default that
clips to the range's selection boxes. `TextMetrics` gains the face's underline, strike-through
and super/subscript metrics.

### 2.5 A weight is a number, and the face nearest to it draws

- `Font` gains `weight` (100 to 900) and `withWeight(int)`. `new Font(family, size, bold,
  italic)` keeps working (bold is 700, else 400), and `isBold()` keeps answering, true from 600
  up. Question 2 says what this costs a record.
- **A system or application-loaded family** draws the face nearest the weight, chosen by
  `pickFace` (0.4), generalized from 400 and 700 to any weight.
- **The bundled family has four faces,** so 500 and 600 draw Regular and Bold until more faces
  ship (question 3).
- **A variable face is shaped at its weight already** (0.3). It is drawn at its weight only once
  its glyphs are drawn by HarfBuzz (question 4); until then it draws its nearest named instance
  if it has one, else its default.

### 2.6 What a label does with it

- Wrap, `maxLines` and the ellipsis work as they do: the ellipsis takes the style of the piece it
  replaces, and a list marker never wraps away from its first line.
- **A page cuts between its lines,** through the `LineCuts` the label already registers, so
  print and PDF break where the screen breaks.
- **A reader gets the plain text,** with each list marker as text ("1.", "•"), which is what a
  label gives a reader today. Attributes reach no reader: no bridge serves them for any text
  (0.2), and serving them is `TextPattern` on Windows, an attributed string on macOS and AT-SPI's
  attribute runs, which belong in an ADR about text and readers, not in this one. Since the shape
  is unchanged (ADR 045, `TEXT`), no live reader run is owed.

### 2.7 Out of scope

Editing, a caret, selection and copy; a span that can be clicked; widgets or icons inside the
text (question 5); tables; tab stops; reading or writing RTF, HTML or Markdown; a file format of
the toolkit's; vertical text.

---

## 3. Stages

| Stage | What | Rough size |
| --- | --- | --- |
| 0 | **Done in this branch:** a face's weight read from its `OS/2`, and `pickFace` by weight (0.4) | 180 lines + tests |
| 1 | `Font.weight`; `pickFace` for any weight; `TextMetrics` decorations; the SPI's shaping of a run in context and painting of a range, on the GL canvas and the PDF writer | about 800 lines |
| 2 | `StyledText`, `TextStyle`, `ParagraphStyle`; the paragraph layout of 2.4 with lists | about 1500 lines |
| 3 | `Label.setText(StyledText)`, the page cuts, the reader's text; a Showcase page; the text guide; the three migration guides, which say today there is no styled text | about 700 lines |

Tests add about as much again as stages 1 to 3.

---

## 4. Questions for the owner

1. **Where the types live.** *Recommended:* `limn.components`, beside `Label` and `Tone`, because
   an ink is a `Tone` and `limn.graphics` must not know the components. The layout is internal.
2. **What a weight costs `Font`.** A record cannot gain a component without its record patterns
   changing arity: an application that wrote `if (f instanceof Font(var family, var size, var
   bold, var italic))` stops compiling, while every constructor call, accessor, `equals` and the
   binary interface keep working. *Recommended:* accept that, since a record pattern over `Font`
   is unlikely to exist anywhere. The alternative leaves `Font` alone and puts the weight only in
   `TextStyle`, so a `TextField` or a plain label could never be semibold.
3. **More faces of the bundled family.** Roboto 2.138.0 has statics at 100, 300, 400, 500, 700
   and 900. *Recommended:* add Medium and Medium Italic to `limn-fonts-roboto` (about 0.7 MB),
   the weight a business interface uses most after bold. A 600 then still draws the 700, as CSS
   would. All eight missing faces would cost about 2.8 MB.
4. **Drawing a variable face at its weight.** *Recommended:* later, in an ADR of its own. It
   means drawing those glyphs through HarfBuzz's rasterizer and keying the atlas by the instance,
   a change to the glyph path every label goes through, for faces the toolkit does not bundle.
5. **Icons inside the text.** *Recommended:* not now. A label already has an icon, and an inline
   one needs a baseline rule, a mirroring rule and a reader rule of its own.
6. **The list markers.** *Recommended:* the three bullets and the three numberings of 2.3,
   nothing configurable. A custom marker is a later addition that breaks nothing.
7. **Markup inside translated strings** (`"Total: <b>{0}</b>"`). *Recommended:* no. A styled
   argument moves with its placeholder, which covers what a translator needs, and a parser in
   every string would be a second language for translators to get wrong. ADR 070's `&` is one
   character with one meaning.

---

## 5. How it is verified

Each of these is a test with no window, on the headless ruler and the PDF writer:

- A colour changed in the middle of an Arabic word gives the same glyph ids as the word with no
  style.
- A weight changed in the middle of an Arabic word keeps the joining forms on both sides of the
  change.
- Two sizes on one line share its baseline, and the line is as tall as the taller.
- The underline and the strike-through sit where the face's metrics say.
- A styled paragraph breaks into the same lines on screen and in the PDF.
- A right-to-left list hangs its markers on the right, with localized digits.
- A translated pattern puts its styled argument where the translation put `{0}`.
- A styled label is read as its plain text, list markers included.

A render of the Showcase page goes to the owner at each stage before it closes.

---

## 6. The owner's answers (2026-10-30)

1. **Where the types live: `limn.graphics`,** against the recommendation, so a canvas of an
   application's own can lay styled text out too. What that cost, a `Tone` the graphics package
   cannot name, was paid back by his second answer: an `Ink` interface in `limn.graphics`, asked
   on every paint, which `Color` and `Tone` implement.
2. **`Font` gains a fifth component,** `weight`, accepting the record-pattern break.
3. **All eight further faces of Roboto 2.138** (Thin, Light, Medium, Black and their italics,
   about 2.8 MB), not only the Medium pair.
4. **A variable face drawn at its weight now,** not in a later ADR.
5. **No icon inside the text, but the label's own icon in four positions:** start, end, top and
   bottom.
6. **The list markers fixed by level.**
7. **No markup in translated strings.**

## 7. What was built

### 7.1 The weight, and the faces

- `Font(family, size, weight, isItalic)`, `withWeight`, `REGULAR` and `BOLD`; the old constructor
  kept, `isBold()` a method from 600 up, and `bold()` never lighter than the font it is called on.
- `SystemFonts` reads each face's `OS/2` weight, width and slant bits and its `fvar` `wght` range;
  `FontStore.pickFace` takes any weight (section 0.4, generalized), a variable face standing at
  the nearest point of its range. The bundled families are described to the same search, so
  Roboto picks among its faces as a system family does, and the eight new faces are registered
  only where the jar has them: with `limn-fonts-roboto` 2.138.0 a weight draws the nearest of four.
- **A second defect found by the build,** fixed with it: resident system faces were keyed by the
  family and the bold and italic flags, so a family's Regular and Medium shared a key and the
  second asked for came back as the first. They are keyed by file and index now
  (`FontWeightTest`).
- **A variable instance** is a view of its face (as the synthetic bold already was) with an id of
  its own, a HarfBuzz font of its own with the axis set, its advances from that font, its glyphs
  drawn by HarfBuzz's rasterizer (`HarfBuzzGlyphs`), and its outlines on a printed page from the
  same font. The rasterizer's bitmap was measured against stb's before it was used: the same box
  and coverage for Roboto's H at 32 pixels, stored bottom row first, which the code turns over.
  Where the rasterizer does not load, a variable face is shaped and drawn at its default instance
  alike. The native was already registered for a native image with the rest of LWJGL's.

### 7.2 The engine

- `TextRuler.shapeRun` (the backend shapes the run with HarfBuzz reading the whole paragraph),
  `TextRuler.decorations` (the face's `post` and `OS/2` through HarfBuzz) and `Canvas.drawText`
  over a range, on the GL canvas, the page recording and the PDF writer, which writes each glyph
  and each character of the extracted text once however many ranges paint a line.
- **`TextMetrics` was left alone.** The decorations are a record of their own asked of the ruler,
  since adding components to `TextMetrics` would have broken it the way the weight breaks `Font`.
- `StyledLayout` as section 2.4 has it, plus justification at spaces and tracking per cluster.
  Lines are not re-shaped at a break: a break falls between words, where no form joins across, and
  the paragraph's shaping is painted range by range.
- **An alignment rule the text did not state:** a paragraph's own alignment is read in the
  paragraph's direction, the base alignment in the base's (a label's direction), so a Latin
  paragraph in a right-to-left label sits against the right as the label's plain text does.

### 7.3 The label

- `Label.setText(StyledText)`: the label's font, role, tone and colour are the base; a piece in a
  `Tone` is resolved in the label's own theme (not `Theme.current()`), and a disabled label draws
  every piece disabled. `text()` is the plain text; what a reader hears is the plain text with
  the list markers, kept in step with the language at each layout.
- `Label.setIconPosition(START, END, TOP, BOTTOM)`, for plain and styled text alike: beside the
  text the icon overhangs the line box as before; above or below, the label is as tall as icon,
  gap and text, and the icon sits across the width as `HAlign` puts the lines.
- The gallery has a "Styled text" entry; the text guide, the three migration guides and the
  changelog say what there is.

### 7.4 What is owed

- **Publishing `limn-fonts-roboto` 2.138.1,** prepared in the `limn-fonts` repository on the
  branch `roboto-twelve-faces` (the eight faces pinned by digest from the same zip, the
  description, the version), then the catalog line `limn-fonts-roboto = "2.138.1"` here. Seen
  before publication with the local jar: the gallery entry's light piece drawn in Roboto Light.
- No live reader run: the label's shape and what it publishes are unchanged (ADR 045), and the
  text it publishes is covered by tests.

### 7.5 What a review found before the first commit (2026-10-31)

Two readers who had not written the code read it, one the toolkit's part and one the backend's;
thirteen defects, each confirmed in the code, fixed with a test where one could be written.

- **Justified right to left** started a line at the free room and then spread the same room at
  its spaces, so the line ran past the right edge; the room after a space went to the wrong side
  of it. A justified line now starts at its edge, and right to left the room goes to the left of
  the space.
- **A paragraph read in the other direction from the label** had its indent and marker at its
  own start and its lines aligned to the label's: a Hebrew item in a left-to-right label had its
  text at the left and its bullet at the right edge. The indent and the marker now stand at the
  edge the lines are aligned to (7.2's rule stands).
- **A marker wider than its indent** ("10." in a face, "1." in the tests' fixed ruler) began
  outside the box, which cut it. Every item of a list grows its indent by what its widest marker
  needs.
- **A typed placeholder** (`{0,number,#,##0.00}`, `{0,choice,…}`) with a styled argument made the
  format throw, and the label showed the pattern. Each argument is formatted by its placeholder's
  own format and only its place is marked.
- **A disabled label** drew a background in the disabled text colour, under text of that colour.
  A background is now drawn in the theme's disabled fill, as a disabled button is
  (`StyledLayout.paint` takes a second resolver for backgrounds).
- **An absolute size** on a piece was multiplied by its paragraph's scale; it no longer is.
- **A trailing line break** in a list item made an empty numbered item; an empty line in a list
  has no marker and is not counted.
- **Styled text on a label that names a control** still gave it an access letter from an `&`;
  styled text gives none, as its documentation says.
- **A right-to-left line of several pieces** was painted left to right, so a PDF read in content
  order had its words reversed; a line's glyphs are painted in reading order.
- **The width now comes before the weight** in `pickFace`, as font matching orders them: a family
  listing a "Condensed Black" beside its "Bold" drew 800 and 900 condensed.
- **A variable instance in use** did not refresh its face's place among the six resident system
  faces, so the face could be closed while on screen and read again at the next frame.
- **A bundled face missing from its jar** sent the search to the family's Regular; it now leaves
  the search, which lands on the next nearest (no test: the jar always has the faces it lists).
- **Bold text rescued by a fallback face** was recognized by "bold" in its face's name, which
  "Roboto Black" and an instance at 700 lack; a face's weight now decides, read from its `OS/2`.

### 7.6 What a review of the whole branch found (2026-10-31)

After ADR 084 was built, four reviewers read the branch whole, one each for correctness, hostile
input, cost and the public API, and a fifth agent per dimension tried to refute each finding in
the code. Forty-one findings were confirmed and none refuted, none grave; once duplicates are
merged, twenty-seven defects, all fixed, and three choices for the owner.

- **The measure and the layout disagreed by one unit in the last place.** A paragraph with an
  indent measured at indent plus text, and at that width the room was the width less the indent,
  which floating point can make a unit short of the text: a list item that fitted was cut with an
  ellipsis. A line now fits within a thousandth of a point (`StyledLayoutTest`, which fails
  without it).
- **A paragraph of many colours in one font was shaped once per colour,** each run merged into
  the shaping before it and the whole prefix shaped again: quadratic in the tokens of a code line.
  Stretches are gathered first and each shaped once.
- **A cut between clusters used the platform's graphemes,** which split what the shaper draws as
  one glyph (a Devanagari conjunct): the glyph was drawn whole past the line's end and the ellipsis
  over it. Cuts now fall on the shaper's own cluster stops.
- **A line separator went to the shaper,** where no face has it, which made the font store load
  every fallback the application carries and lay everything out again. It belongs to no shaping.
- **Underlines, strike-throughs and backgrounds broke** at every widened space of a justified
  line and after every tracked letter; a style that covers a whole piece now draws across its box.
- **A list item's own number repeated** on every paragraph that repeated its style
  (`paragraph()`, a line break in a piece), and a style given a number could not lose it. The
  number is the first paragraph's; `listNumber(0)` counts on; numbers stop at
  `ParagraphStyle.MAX_LIST_NUMBER` instead of wrapping negative; a number is never grouped
  ("1000.", not "1,000.").
- **A private-use character in a translated pattern** (an icon font's glyph) was read as an
  argument's mark. The mark is now a noncharacter followed by the private-use index.
- **A styled label read its text anew on every paint,** formatting every pattern; it is read
  once per language (`I18n.epoch()`). A layout at a width the unbroken one fits reuses it rather
  than shaping again; a change of style alone no longer tells readers of a new name.
- **Each style made a new font on every layout,** which missed the font store's memo, keyed by
  identity; a style that sets no font answers its base, and one that does keeps its last answer.
- **Every line was painted, wherever the clip was;** lines outside it are skipped. A line's
  order on screen and its justification are worked out once per width, and a range drawn on the
  screen, on a page or into a recording visits only its own glyphs (the screen's by bisection over
  a run's clusters, monotone at HarfBuzz's default level).
- **Hostile values:** a dotted or wavy underline far along a line looped forever (a float stopped
  moving); it steps by count. A size and a scale each allowed alone multiplied to infinity or to
  nothing; a font stays between 0.1 and 100,000 points and a line under a million. A face with no
  units per em, or raised-text figures that are not numbers, no longer throws; raised text stays
  between a third and a whole em. An `OS/2` offset near the top of an int wrapped a bounds check
  and threw on every shaping. A variable face made an instance per integer weight and kept it;
  instances are made at the nearest ten. A long paragraph no longer passes through the shaper's
  memo for short lines.
- **Smaller:** a monospace bold drawn where HarfBuzz did not load is drawn synthetic bold rather
  than regular; `StyledLayout.of` refuses a negative or NaN width and breaks after every cluster
  at 0; `TextStyle.toString` shows the line inks; two Javadocs said "in the text's ink" where an
  inherited line ink wins, and `ParagraphStyle`'s said start and end were always the paragraph's
  own, where a paragraph that sets no alignment follows the base (7.2).

Three choices were the owner's, and the owner took all three recommendations the same day:

1. **`Builder.append(StyledText)` into an empty builder keeps the first paragraph's style,** as a
   first `paragraph` call styles the first paragraph, so texts put together lose none of their
   styles; after text, the first paragraph still continues the open one.
2. **`TextStyle.bold()` is at least bold,** as `Font.bold()` is: 700, or the inherited weight
   where that is heavier, so a word made bold inside a heading set in Black stays Black.
   `weight(int)` sets a weight exactly; `isAtLeastBold()` tells the two apart.
3. **An `Ink` keeps its signature, with no palette.** An application's own ink reads
   `Theme.current()`; a label answers its tones in its own palette, and a canvas of the
   application's does the same through `StyledLayout.paint`'s resolver. `Ink`'s Javadoc says so.
   Putting the palette in the signature would change a public interface for a rare need.
