# ADR 084: Code and Markdown are shown by projects outside the toolkit, and the toolkit carries only what they need

- **Status:** **Accepted, and the toolkit's part built**, 2026-10-31. Proposed the same day at the
  owner's request, in the owner's scope (section 1); the owner took the recommendation of all three
  questions (section 8): Roboto Mono bundled in `limn-fonts` as an artifact of its own, no syntax
  colours in the theme, no heading level yet. Stage 1 is built (section 9), and stage 2, the
  code-block project, with a defect it found in `ScrollView` fixed (section 10); owed: the owner's
  publication of `limn-fonts-roboto-mono` 3.001.0 and the dependency line that takes it, then
  stage 3; the owner took both recommendations of section 10.
- **Date:** 2026-10-31
- **Scope:** what the toolkit must offer so that a read-only code block with coloured syntax, and
  a Markdown reader that shows such blocks, can be written against its public API alone. The code
  block, its languages and the reader are not part of the toolkit: they are two private projects,
  never published and perhaps never used, whose only purpose is to consume the toolkit and show
  what it must offer.
- **New public API:** in `limn-toolkit`, a paragraph direction and a list item's number on
  `ParagraphStyle`, and a monospace family on `Font`; in `limn-fonts`, the artifact
  `limn-fonts-roboto-mono` (section 8). Nothing in `limn.components`.
- **Compatibility:** additive. One change of behaviour in ADR 083's text: a line separator
  (U+2028) breaks the line inside its paragraph instead of starting a new paragraph (section 3.2).
- **Relates to:** [ADR 041](041-a-table-is-columns-over-a-list-the-application-owns.md) (a
  Markdown table), [ADR 067](067-four-components-before-the-contract.md)
  (rich text and Markdown refused for the toolkit), [ADR 081](081-non-text-contrast-holds-in-every-palette-and-the-systems-contrast-is-followed.md)
  (colours in every palette), [ADR 083](083-a-label-can-show-styled-text-built-from-pieces-and-shaped-as-one-paragraph.md)
  (styled text in a label, which this builds on).

---

## 0. What this rests on

### 0.1 How programs that show code colour it

Every one separates two steps: the text is cut into tokens, each of a kind (keyword, string,
comment), and a theme turns each kind into a style, a colour nearly always, an italic sometimes,
a bold rarely. The cutting is done in one of three ways.

- **Regular expressions with a state per line.** A grammar is a stack of contexts ("inside a
  string", "inside a comment"); each line is cut from the state the line before ended in, the
  state at each line's start is kept, and an edit cuts again from the edited line until a line
  ends in the state it ended in before. Cheap, incremental, and blind to structure: nested
  constructs come out approximate. The most common way, and the formats with hundreds of
  languages are of this kind.
- **An incremental parser.** A real syntax tree, parsed again after an edit from the part that
  changed; queries over the tree tag nodes with the names a theme styles. Exact, and the same tree
  serves folding and indentation, at the cost of a native parser per language.
- **The compiler's view,** from a language server: whether a name is a type, a field or a
  parameter. Layered over one of the two above, asynchronous, and no business of a toolkit.

What reaches the screen is the same in all three: runs of a style per line, only the visible
lines shaped and painted, a monospace face with tab stops at a multiple of columns, lines unbroken
with a horizontal scroll, and every line read left to right with the bidirectional control
characters shown, so that a string cannot reorder the code around it.

### 0.2 Editing is most of the cost

An editor colours again at each key: it keeps the state at every line start, cuts again from the
edited line until the state agrees, and an opened comment colours the file to its end. The colours
then live with the caret, the selection, the input method's composition, undo and soft wrap, inside
`TextArea`'s three thousand lines and its per-key budget (ADR 062). A block that is only read is cut
once, when its text is set, with no state kept. The owner chose the second: a Markdown reader with
code blocks, nothing edited (section 1).

### 0.3 What the toolkit has, and where it stops

Read in the code on 2026-10-31.

- **ADR 083 draws a code block already.** A block is a `StyledText` of one paragraph per line, a
  piece per token with its ink; `Canvas.drawText` over a range paints a colour without shaping
  again, and the PDF writer prints it. Inline code is a piece with a family and a background.
- **The parts around it exist:** a `Card` or `Surface` for the block's panel, a `ScrollView` with
  a horizontal axis around a label that does not wrap, `Separator` for a rule, `ImageView` for an
  image, `Table` for a table (ADR 041), and `Scene.clipboard().setText` for a copy button.
- **No monospace face.** None is bundled, and no family names "the monospace face": an
  application can only name a system family it hopes is there, or load a file with
  `Fonts.load(Path)`, which takes a path and not a resource in a jar.
- **A paragraph's direction is always detected.** `StyledLayout` reads it from the paragraph's
  first strong character, the base's direction when there is none. A line of code that begins with
  a comment or a string in Arabic is laid out right to left, its `//` at the right.
- **A line separator starts a paragraph.** `StyledText` opens a new paragraph at U+2028 as at
  `\n` and U+2029, so a line broken inside a paragraph takes the paragraph's spacing, its first-line
  indent again, and in a list a marker of its own. Unicode separates the two: U+2028 separates
  lines, U+2029 paragraphs.
- **A numbered list always starts at 1.** A list is a run of list paragraphs and its count starts
  with the run. A reader shows a code block as a widget between two labels, so the items after a
  block inside a numbered list start a run of their own and count "1." again where the document
  says "3.".
- **No colours for syntax in the theme.** `Theme` has a fixed set (text, muted text, primary,
  danger, success, warning, info and the surfaces) and no way for a library to add its own.
- **A title has no level.** `Label.Role.TITLE` is published as a heading, without the level a
  reader announces ("heading, level 2").
- **A tab is not handled.** The character goes to the shaper as any other, and what is drawn is
  whatever the face has for U+0009. A code block in a monospace face can turn tabs into spaces when
  it builds its text, the column being exact, so the toolkit needs no tab stops for this.

---

## 1. The owner's scope (2026-10-31)

- Something basic: a Markdown reader with code blocks, read-only. No editor; `TextArea` is not
  touched.
- The toolkit keeps only the support that is needed. The code-block component and the languages
  it colours live in another repository; so does the Markdown reader. Both are private and not
  published, and may never be used: they are consumers that exist to validate what the toolkit
  must offer, and only what they find lands in the toolkit.

---

## 2. Decision

### 2.1 Two projects outside, on the public API only

- **The code-block project:** a component that shows a code block, read-only. It cuts the text of
  a few languages into tokens with lexers of its own, written by hand (cut once, no state per
  line; a language it does not know is shown uncoloured, in the monospace face), turns tabs into
  spaces, shows the bidirectional control characters as visible marks, and builds a `StyledText`
  shown by a `Label` that does not wrap, in a horizontal `ScrollView`, on a panel, with a copy
  button.
- **The reader project:** a Markdown reader built on a CommonMark parser of its choosing. It maps
  each block to the toolkit's widgets (headings, paragraphs, emphasis and inline code to labels of
  styled text; code blocks to the component above; quotes, rules, images and tables to the
  existing widgets) and is the document the support is measured against.
- **They depend on nothing internal.** The module system already refuses a `limn.*.internal`
  package to them; a need for one is a finding, answered in the toolkit's public API or not at all.
- **What they find comes back as records.** A gap the projects meet is written into this record
  (a section 9, when there is one) and decided here, not patched in the projects.

### 2.2 What the toolkit adds

1. **A monospace family:** `Font.MONOSPACE_FAMILY`, resolved like `DEFAULT_FAMILY`, to the
   bundled Roboto Mono (question 1, section 8), so `TextStyle.of().family(Font.MONOSPACE_FAMILY)`
   is inline code anywhere.
2. **A paragraph's direction set by the paragraph** (section 3.1): `ParagraphStyle.direction`, the
   default still detected.
3. **A line separator that breaks the line and not the paragraph** (section 3.2).
4. **A list item's number** (section 3.3): `ParagraphStyle.listNumber(int)`, the count going on
   from it.

Nothing else. Syntax colours (question 2) and heading levels (question 3) wait for what the
projects show (section 8).

---

## 3. The three changes to the text

### 3.1 A paragraph's direction

`ParagraphStyle.direction(ShapedText.Direction)` fixes the paragraph's base direction; a paragraph
that sets none is detected as now. Code is written left to right whatever its strings and
comments hold, so the code block sets `LTR` on every line; a reader of a right-to-left document
keeps its prose detected and its code left to right. The alignment rule of ADR 083 §7.2 applies as
it is: a paragraph that sets a direction and no alignment takes the base's alignment, read in the
base's direction.

### 3.2 U+2028 breaks a line inside its paragraph

A Markdown hard break (two spaces or a backslash at a line's end) is a line break inside one
paragraph. `StyledText` will break the line at U+2028 and keep the paragraph: no spacing, no
first-line indent again, no new list marker, the next line starting at the start indent. `\n`,
`\r\n` and U+2029 keep starting a paragraph. The reader text keeps the separator as a line break.

### 3.3 A list item's number

`ParagraphStyle.listNumber(int n)` gives a numbered item the number `n`, and the items after it in
the same run count on from it; a bullet ignores it. The reader sets it on the first item after a
code block (and on a list whose first item is not 1, which CommonMark allows), so "3." follows
"2." across the block. At a deeper level it numbers that level (`c.`, `iii.`).

---

## 4. Stages

1. **The toolkit's part,** in one change: the three text changes of section 3 with their tests,
   and the monospace family. The `limn-fonts-roboto-mono` artifact comes first, published by the
   owner, then its catalog line; until then the family resolves to the default face.
2. **The code-block project:** the component, five languages to begin (JSON, XML, SQL, Java, shell
   are the suggestion), renders in light, dark and high contrast sent to the owner.
3. **The reader project:** a test document holding every CommonMark block, one in a right-to-left
   language with code blocks inside it, and a numbered list interrupted by a block.
4. **The findings** written here (section 9) and decided.

---

## 5. Questions for the owner

1. **The monospace face.**
   - *Recommended:* bundle Roboto Mono in `limn-fonts`, as an artifact of its own
     (`limn-fonts-roboto-mono`), and resolve `Font.MONOSPACE_FAMILY` to it. Same metrics on every
     system, the same family as the default face, two variable files (upright and italic, `wght`
     100 to 700) that ADR 083 already draws at any weight. Its licence is the SIL Open Font
     License, as the Noto faces already shipped are; the files and their size are pinned and
     measured when the artifact is made.
   - *Or:* resolve the family to the system's own monospace face. Nothing shipped, but a
     different face and different metrics on each system, and on Linux the answer depends on the
     installed fonts.
   - *Or:* nothing in the toolkit; the project brings its own face. Then `Fonts` needs a way to
     load a face from bytes or a resource, since `Fonts.load` takes only a path.
2. **Colours for syntax.**
   - *Recommended:* not now. The project takes them from the theme's colours (primary, info,
     success, warning, danger, muted text), which already follow light, dark and high contrast
     (ADR 081) and are legible as text. If they prove too few, a later answer adds a syntax
     palette to `Theme` and to the theme editor.
   - *Or:* a syntax palette in `Theme` now: eight or so kinds, in every palette, in the theme
     format and the theme editor.
3. **A title's level for readers.**
   - *Recommended:* not now. The reader's headings are announced as headings, without a level. A
     level means a change to the accessibility model and to the three bridges, and a live reader
     run on each system.
   - *Or:* now, as `Label.setHeadingLevel(int)`.

---

## 6. How it is verified

- **The toolkit, with no window:** a paragraph set left to right whose first strong character is
  Arabic is laid out left to right, and one that sets nothing is laid out right to left as before;
  a line broken at U+2028 adds no paragraph spacing, repeats no first-line indent and draws one
  marker for its item; a numbered run whose second item says 5 reads "1., 5., 6." and its reader
  text says so; the monospace family resolves to a face whose advances are all equal.
- **The projects:** their own tests, and renders of the test document in the three palettes and in
  a right-to-left interface, sent to the owner.

---

## 7. Out of scope

- **Editing code,** and `TextArea`: section 0.2.
- **Parsing Markdown in the toolkit:** ADR 067's refusal stands; the reader parses it.
- **A link that can be followed.** ADR 083 gave the label no interaction, so a link in the reader
  is drawn (its colour, an underline) and not activated. The reader will show what that costs.
- **Selecting part of a code block** with the pointer: the copy button covers the common case.
- **A block of thousands of lines.** `StyledLayout` shapes all of its text, which suits the tens or
  hundreds of lines of a document and not a whole source file.
- **Tab stops in the text engine:** section 0.3, the last item.

---

## 8. The owner's answers (2026-10-31)

All three recommendations taken.

1. **The monospace face:** Roboto Mono, bundled in `limn-fonts` as the artifact
   `limn-fonts-roboto-mono`, its two variable files (upright and italic) pinned by digest with its
   licence, and `Font.MONOSPACE_FAMILY` resolved to it. Asked whether it should rather join
   `limn-fonts-roboto`, the owner kept it apart: Roboto Mono is a project of its own whose release
   is v3.001 (2025-04-09) where Roboto's is v2.138, and `limn-fonts` versions each artifact after
   its upstream (`3.001.x` here), so one jar would carry a number that names only half of it and
   a change to either face would release both. Apart is not optional: the backend always takes it,
   as it takes `limn-fonts-noto-scripts`, and an application declares nothing. About 380 KB (the
   upright file 183,700 bytes and the italic 196,792 in the packaged build read that day).
2. **Colours for syntax:** not now. The code-block project takes the theme's existing colours; a
   syntax palette in `Theme` and the theme editor waits for what the projects show.
3. **A title's level for readers:** not now. Headings are announced as headings, without a level.

---

## 9. What was built (2026-10-31)

Stage 1, the toolkit's part, and the font artifact.

- **`limn-fonts-roboto-mono` 3.001.0,** prepared in the `limn-fonts` repository beside the twelve
  Roboto faces: upstream's v3.001 (`googlefonts/RobotoMono`, tag commit `111eb14e`), its two
  variable files renamed without their brackets (`RobotoMono-Variable.ttf`,
  `RobotoMono-VariableItalic.ttf`) and its `OFL.txt`, each pinned by digest in
  `scripts/fetch-fonts.sh`, the POM naming the OFL, the jar naming itself `limn.fonts.roboto.mono`,
  and the publish workflow taking the module. Read from the files: `wght` 100 to 700 with 400 the
  default, OS/2 weight 400, width 5, one advance in each file. The jar is 272 KB.
- **The upright and the italic are not the same width:** 1229 units of 2048 (0.600 em) against
  1202 (0.587 em). A monospace face keeps its columns only within one slant; the `Font` Javadoc and
  the text guide say so, and the code-block project should colour code without italic.
- **`Font.MONOSPACE_FAMILY`** (`"monospace"`) resolves in `FontStore` to Roboto Mono where the jar
  is on the class path, described to the weight search as two variable faces, so a weight inside
  100 to 700 draws that instance and 900 draws 700; without the jar it resolves to the default
  family. The store's description of the range is checked against the files' `fvar`
  (`MonospaceFamilyTest`, which runs its Roboto Mono half only where the jar is present: run here
  with the local build on the test class path, every case passing).
- **A bundled variable face is drawn at its weight,** as a system one already was: the bundled
  search now asks the picked face for its instance.
- **`ParagraphStyle.direction`, `ParagraphStyle.listNumber`** and **U+2028 as a line break inside
  its paragraph**, as section 3 has them; a line ending at a separator is never justified, two
  separators leave an empty line, and `text()` and the reader text write the separator as `\n`.
- **The gallery's "Styled text" entry** shows a reference number in the monospace family on a
  raised surface, and was filmed with the local jar: drawn in Roboto Mono.
- **Owed:** publishing `limn-fonts-roboto-mono` 3.001.0, then `runtimeOnly` of it in
  `limn-backend-lwjgl` and its catalog line; until then every application draws the alias in the
  default family. Then stages 2 and 3, outside the toolkit.


---

## 10. Stage 2: the code-block project (2026-10-31)

Built in the private repository `limn-text-lab` beside the toolkit's checkout (the owner chose one
repository with two modules: `codeblock` now, the reader in stage 3), with no remote. It takes the
toolkit as an application would, from `publishToMavenLocal` of this branch (`1.1.0-SNAPSHOT`), and
Roboto Mono from the local `limn-fonts` build; its module requires `limn.toolkit` and nothing
else, so an internal package does not compile there.

- **What it is:** `CodeBlock`, a `Card` whose title is the fence's language and whose header holds
  a "Copy" button, around a horizontal `ScrollView` of a `Label` of styled text. Hand-written
  lexers for JSON, XML, SQL, Java and shell, each cutting once with no state per line; an unknown
  language is shown uncoloured in the monospace family. Tabs become spaces to the next stop of
  four; the bidirectional controls, U+2028, U+2029 and the other control characters are drawn as a
  named mark (`<RLO>`, `<U+0007>`) in the danger tone with a wavy underline; the button copies the
  code as given. Thirteen token kinds drawn in five tones, nothing italic or bold. Forty tests,
  and renders of three pages in light, dark and high contrast and one in a right-to-left interface,
  sent to the owner.
- **Everything it needed was public.** The four additions of stage 1 carried it: the monospace
  family, `ParagraphStyle.direction(LTR)` on every line, and the alignment rule; U+2028 and
  `listNumber` wait for the reader.

What it found, each with what was done:

1. **A defect, fixed in the toolkit: a `ScrollView` sized by its content took its reserved bar's
   strip out of the content.** Scrolled sideways only, with `ScrollGutters.Layout.RESERVED`, in a
   column that lets it be as tall as its content, the view measured the content's height alone,
   and the layout then reserved the strip inside it: a one-line block came out cut in half by its
   own gutter. `onMeasure` now adds the strip an overflowing axis reserves to a side the view takes
   from its content (`ScrollViewTest`: two cases).
2. **"A label that does not wrap" shows one line.** Section 2.1 said the block is a label that does
   not wrap; with wrap off a `Label` shows its first line, by `setMaxLines`'s contract. The block
   turns wrap on inside a scroller that gives the label unbounded width, where the label breaks only
   at paragraph ends, which are the lines of the code. It works and the project's test holds it.
   *Taken (the owner, 2026-10-31):* no new API. `Label.setText(StyledText)` and `setWrap` say a
   label without wrap or a line limit shows one line, the guide "Text and languages" gives the
   scroller recipe for lines kept whole, and `LabelStyledTextTest` holds both.
3. **Two right-to-left tokens on one line change places.** In `s = "مرحبا"; // تحية`, the neutrals
   between the string and the comment (`; //`) sit between two right-to-left runs, the bidirectional
   algorithm makes them right-to-left too, and the string and the comment swap sides, which is the
   reordering section 0.1 says a code block must not allow. The project isolates each string or
   comment that holds right-to-left text, left to right (LRI … PDI): the toolkit's paragraph
   honours isolates and draws nothing for the two characters. A first-strong isolate is not enough:
   it takes the comment's direction from its Arabic, and `//` moves to its right. No toolkit change;
   the two characters do reach the label's reader text, where readers ignore them.
4. **In a right-to-left interface a horizontal scroller starts at its right end,** showing the end
   of a long line of code first. The block sets the scroller's layout direction to left to right,
   which is what `Widget.setLayoutDirection` is for. No toolkit change.
5. **The default overlay bar covers the last line.** The block reserves the strip and shows the bar
   while the code overflows (`RESERVED`, `ALWAYS`), which met finding 1. No toolkit change.
6. **Five tones do not keep thirteen kinds apart in every palette (question 2).** In the light
   palette the accent and info tones are both blue, so a keyword and a type read alike; in high
   contrast the accent (yellow) and the warning tone (amber) are close, so a keyword and a number
   read alike. Every kind stays legible as text, and the code stays readable. *Taken (the owner,
   2026-10-31):* still no syntax palette: stage 3's reader shows whether a document's code blocks
   suffer from it, and a palette in `Theme`, the theme format and the theme editor is the larger
   change.

Owed: stage 3.
