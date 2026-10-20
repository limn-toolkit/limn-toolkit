# ADR 074: A field is a caption, a control and the line under it

- **Status:** Accepted and implemented, 2026-10-18. Proposed the same day: the owner chose on
  2026-10-18 that the anatomy around a form's control enters before 1.0 (a caption, a line of
  help, a validation message and a mark for a required field), and answered every question of
  section 4 with its recommendation the same day. Built the same day in its three stages (section
  8), with the dated notes there where the build departs from the text. What is still owed is the
  live reader run of section 7, over a gallery entry and a script built for it (section 8.5).
- **Date:** 2026-10-18
- **Scope:** the caption above a control, the helper line and the validation message under it, the
  required mark; how the validation state reaches the control's border and the three platforms'
  readers; what it does with the toolkit's inputs (a masked text field, a combo box, a date
  field, a compound control); the colour of a line of text that follows the palette; and what it
  replaces in the Showcase and the guides.
- **New public API:** `limn.components.Field`; `limn.components.Validation` in place of
  `TextField.Validation` (question 2); `limn.components.Tone` and `Label.setTone` (question 9);
  and, from the build (section 8.2), `Label.tone()`, `Tone.colorIn(Theme)` and
  `TextArea.validation()`.
- **Compatibility:** `TextField.Validation` moves; every other change is additive. Two bridges
  gain the translation of two states the model already has (section 2.6).

---

## 0. What this rests on

Read at `e3f602f5` (`claude/review-fixes`). No probe was run.

**The Showcase writes the anatomy by hand, four ways.**

- `Labelled.above(caption, control[, placed])`, a caption over a control in a `Column` with a gap
  of 4, the caption bound with `setLabelFor`; `Page.field` and `Page.fieldWithValue`
  wrap it. 56 calls of `Page.field` and 58 of `Labelled.above`, in 14 files.
- `Page.message(text, ink, icon, field)`: a wrapped `Label` in the `LABEL` role, coloured by
  `Page.tint`, with an icon, bound to the field with `setDescriptionFor`; 8 calls.
- `Page.withMessage(control, message)`: the control over its message in a `Column`; 10 calls.
- `Page.tint(label, ink)`: re-colours a label after every change of palette, because a colour
  read when the page is built is the palette of that moment (measured rgb(247,118,142) for a light
  page's danger of rgb(198,40,40)); 5 calls. The forms guide's own example writes
  `emailMessage.setColor(Theme.current().danger())`, the trap `Page.tint` exists to avoid.

**The inputs differ in what they already say about validity.**

| Control | State it holds | What it draws | What it publishes |
| --- | --- | --- | --- |
| `TextField`, `PasswordField`, `SearchField`, `TextArea` | `Validation`: `NONE`, `ERROR`, `WARNING`, `SUCCESS`, `INFO`, set by the application | the border in the state's colour | `INVALID` while `ERROR` |
| `DateField` | its own validity: incomplete, out of range, a refused day; an empty field is valid ("a required one is the form's business") | a `danger` border while invalid | `INVALID`, and the reason as its description |
| `DatePicker` | its fields' validity | a `danger` border around both | through its fields |
| `Spinner` | none (it flashes `danger` on a refused step) | — | — |
| `ComboBox`, `TokenBox`, `Slider`, `RangeSlider`, `Checkbox`, `RadioButton` | none | — | — |

`DateField` announces `Change.Aspect.VALIDITY` and answers `validationMessage()`; `TextField`
announces the same aspect from `setValidation`.

**The model has the states; two bridges never translate them.**

- `Accessible.State` has `INVALID` ("this node's value has failed validation") and `REQUIRED`
  ("this node must be filled in").
- **Linux** maps both: `INVALID_ENTRY` (bit 36) and `REQUIRED` (bit 33), and a change is sent as
  `object:state-changed:invalid-entry` or `:required` (`AtspiStates`, `AtspiEvents`).
- **Windows** declares the id of `IsRequiredForForm` (30025) in `UiaIds` and serves it nowhere:
  `UiaProperties.valueOf` has no case for it, and none for `IsDataValidForForm`. A Windows reader
  hears neither state.
- **macOS** serves neither: no attribute of `AxElementClass` reads either state, and
  `AxNotifications` deliberately tells neither as a value change.
- **Nothing in the toolkit publishes `REQUIRED`.** An uncut search of both modules finds the
  constant, the Linux bit, and one test that names it.

**One description per node, and how it is chosen.** The walk gives a node one description: what
the widget declared, replaced by a bound describer's text (`setAccessibleDescribedBy`, which
`Label.setDescriptionFor` sets), replaced by an explicit `setAccessibleDescription`, with the
tooltip only when none of them said anything (`AccessibleWalk.applyOverrides`). A bound caption is
carried from a composite to the inner control the keyboard lands on (`accessibleLabelTarget`: a
date picker's caption names its field); **a bound describer is not**, so a message bound to a
`DatePicker` describes the picker's group, which publishes no node of its own when it holds one
field.

**The mask** (ADR 067 §5, amended 2026-10-16) describes nothing to a reader: what the field
expects is the application's to say, "with a tooltip or `setAccessibleDescription`".

## 1. Why

Forms are most of what a business application is, and every field on every form needs the same
four things around its control: what it is called, how to fill it, what is wrong with it, and
whether it may be left empty. The toolkit gives the pieces (a label that names, a label that
describes, a border that changes colour) and leaves the assembly to each application, which the
Showcase did four ways and the guide a fifth, one of them with the stale-colour defect. Web
component libraries give every input the same anatomy. On a desktop the anatomy is the same and
the reason is the same; what has to be decided here is whether it lives on each input or around
any of them, and what each platform's reader is told.

## 2. Decision (proposed)

### 2.1 A container around any control

**`Field` is a final container** in `limn.components`, holding one control, or one widget that
holds the control (*2026-10-18: built as written; `setValidation(state)` alone is the state with
no message, section 8.2*):

```java
public final class Field extends Widget<Field> {
    public Field(I18nString label, Widget<?> control);
    public Field(String label, Widget<?> control);
    public Field(@Nullable I18nString label, Widget<?> control, Widget<?> placed);

    public Field setLabel(@Nullable I18nString label);      // and String
    public Field setHelperText(@Nullable I18nString text);  // and String
    public Field setRequired(boolean required);
    public Field setValidation(Validation state);
    public Field setValidation(Validation state, @Nullable I18nString message); // and String
    public Field clearValidation();                         // NONE, no message

    public Widget<?> control();
    public @Nullable String label(), helperText(), validationMessage();   // as they read (2026-10-19)
    public @Nullable I18nString labelSource(), helperTextSource(), validationMessageSource();
    public boolean isRequired();
    public Validation validation();
}
```

- **`control`** is what the caption names and the states are published on; **`placed`** is what
  is laid out, when the control sits in a wrapper (a field beside a switch, a `SizedBox`). The
  two-argument form places the control itself. `placed` must hold `control`; anything else throws.
- **Why a container and not setters on each input** (question 1): one class serves the thirteen
  inputs of the forms guide's table, `TokenBox` besides (*2026-10-18: `TokenBox` is a layout box
  sized by a size token, not an input; the record mistook its name, section 8.2*), and any widget of an application's own; a compound control (an amount beside its
  currency, a row of radio buttons) gets the same caption, line and states, which setters on an
  input could not give it; and an input's box stays the input's, so a `Grid(2)` of captions and
  controls, or a row of fields of one width, keeps aligning on the controls.
- **Required does not validate.** `setRequired(true)` draws the mark and publishes `REQUIRED`;
  whether an empty required field is an error, and when to say so, stays the application's, as
  `DateField` already holds ("a required one is the form's business").
- **The state is the application's.** `Field` runs no rule. `setValidation` is called from the
  application's handler or watcher, as `TextField.setValidation` is today.

### 2.2 Layout and paint

- **A column:** the caption line, the control, the line under it. The caption line is the label
  in the step's label font, muted, as the guide's `field` draws it, followed by the required mark;
  `gapLabel` (6 at `MEDIUM`) under it; the control stretched to the field's width; `gapLabel`;
  the line under it in the label font, wrapping.
- **The line under the control** shows the validation message while the state is not `NONE` and
  a message is set, in the state's tone, with the state's glyph before it (question 5); otherwise
  the helper text, muted; otherwise nothing (question 4).
- **It keeps its height** (question 7): a field with helper text or a validation set at least
  once reserves one line under the control, so a form does not move by a line when the first
  error appears, which the forms guide asks of every message. A field with neither reserves
  nothing.
- **The required mark** is an asterisk after the caption, in `danger` (question 6), drawn and not
  part of the caption's text, so the control's name stays "Email" and not "Email *".
- **The label above, only** (question 8). A caption beside its control is a `Grid(2)` of a `Label`
  bound with `setLabelFor` and a `Field` built with a `null` label, which keeps the helper, the
  message and the states.
- **Right to left** (ADR 032): the caption and the line start at the leading edge and the mark
  follows the caption's end; the glyph leads the message.
- **What a frame repaints** (ADR 043): a message or a state that changes the line's text repaints
  the line and the control's border, with a layout only when the line's height changes; a palette
  change repaints, and nothing re-colours by hand.

### 2.3 The validation state, on the control

- **`Validation` becomes a type of its own,** `limn.components.Validation` with the five constants
  `TextField.Validation` has today (question 2). `TextField`, `TextArea` and `Field` take it.
- **`Field.setValidation` sets the control's border** where the control draws one, through an
  internal interface in `limn.components.internal` that the bordered inputs implement (question
  3): `TextField` and its subclasses and `TextArea` (through their own `setValidation`, so the
  two cannot disagree), `ComboBox`, `TokenBox`, `Spinner`, `DateField` and `DatePicker`
  (*2026-10-18: without `TokenBox`, which draws nothing; and a registry by class rather than an
  interface, section 8.2*). Each
  draws the larger of its own state and the field's, `ERROR` above `WARNING` above the rest, so a
  `DateField` holding a refused day stays red whatever the field says. A control with no border (a
  check box, a slider, a row of radio buttons) is left as it is; the line under it carries the
  state.
- **A date field speaks for itself.** A `Field` around a `DateField` or a `DatePicker` that has no
  message of the application's shows the control's own `validationMessage()` in the `ERROR` tone
  while it is invalid, following its `VALIDITY` changes (question 11). An application message
  replaces it.
- **The tone of a state:** `ERROR` is `danger`, `WARNING` `warning`, `SUCCESS` `success`, `INFO`
  `info`, from the palette at paint time.

### 2.4 A colour that follows the palette

`Page.tint` exists because `Label` holds a `Color`. Proposed (question 9):
`limn.components.Tone { NEUTRAL, ACCENT, INFO, SUCCESS, WARNING, DANGER }` and
`Label.setTone(@Nullable Tone)`, resolved at paint time like `setMuted`, an explicit `setColor`
still winning. `Field` draws its line with it, the Showcase's legend dots and status lines use it,
and ADR 075's `Badge` takes the same enum.

### 2.5 The inputs

- **A masked text field.** The helper text is where the expected form is said ("Eleven digits,
  as on the card"), which ADR 067's amendment left to a tooltip or a description; the reader hears
  it as the field's description (section 2.6), and it is shown whether or not the field has the
  focus, where the mask's guide shows only with it. `isMaskComplete()` and the value are
  unchanged.
- **A combo box**, editable or not, single or of many: the caption names the node a reader lands
  on (the combo, or its editor where `accessibleLabelTarget` says so), the border takes the state,
  and the states and the description land on the same node.
- **A date field and a date picker** take the caption on the field the keyboard lands on, as
  today, and the description and the states follow it there (section 2.6).
- **A spinner, a slider, a range slider:** the caption, the line and the states on the control;
  the range slider's two thumbs are each named by their own words and carry the states each
  (*2026-10-18: the states land on the range slider's group, where its caption lands, and not on
  each thumb; section 8.2*).
- **A check box** keeps its own caption: a `Field` with a `null` label gives it a helper line, a
  message and the required state (a box that must be ticked to go on).
- **A compound control** (a `Row` of an amount and a currency, a `Column` of radio buttons) has no
  node of its own: the field publishes a `GROUP` around it, named by the caption, described by the
  line, carrying the states, as `Expander` makes its content a named group.

### 2.6 What each platform's reader is given

On the node the caption names (the control, or its label target):

| | Windows (UI Automation) | macOS (AppKit) | Linux (AT-SPI) |
| --- | --- | --- | --- |
| Name | the caption (`LabeledBy` the caption's node) | the caption | the caption (`labelled-by`) |
| Description | the line: `HelpText`, and `DescribedBy` its node | `AXHelp`, and a linked element | the description, and `described-by` |
| Required | `IsRequiredForForm` **(new)** | `AXRequired` **(new)** | `REQUIRED` (mapped, never produced until now) |
| Invalid | `IsDataValidForForm` false **(new)** | no attribute of AppKit's own is known; read on the guest first **(open)** | `INVALID_ENTRY` (mapped) |

- **The description is the line under the control,** whichever it shows: the message while there
  is one, the helper otherwise. One string, since the model holds one; the toolkit joins no two
  translated texts into a sentence it would have to punctuate in 21 languages.
- **The states:** `REQUIRED` while `setRequired(true)`; `INVALID` while the state is `ERROR`
  (`WARNING`, `SUCCESS` and `INFO` publish nothing, as `TextField` already decides, since a reader
  would announce a field that passed as failing). They reach a control that is not `TextField`
  through an internal hook of `SceneAccess` that the walk applies with the other overrides, on the
  label target.
- **A describer follows the caption.** The walk carries `setAccessibleDescribedBy`, and the states
  above, through `accessibleLabelTarget` as it already carries the caption, so a message bound to a
  `DatePicker` describes the field a reader is on. The change is the walk's and serves a
  `Label.setDescriptionFor` written without a `Field` too.
- **The bridges' two translations.** Windows answers `IsRequiredForForm` and `IsDataValidForForm`
  from the states and raises a property change when either moves; the second id is read from the
  SDK on the guest before it is written, as `UiaIds`' other constants were. macOS answers
  `isAccessibilityRequired`. For invalid, AppKit's protocol has no attribute of its own: the
  attribute some web engines publish is read on the guest with VoiceOver before anything is
  written; if VoiceOver reads none from a native element, macOS says nothing more than the message
  in the description, which already says what is wrong (question 10).
- **The caption line's mark is not read.** The state says it on all three systems, in the system's
  own words, so the toolkit adds no "required" string.

### 2.7 Focus, keyboard and access letters

`Field` takes no focus and handles no key. An `&` in its caption marks the access letter of the
control it names (ADR 070), as `Label.setLabelFor` already does: Alt and the letter focus the
control, or a composite's first part that takes the keyboard.

### 2.8 Strings

None. The caption, the helper and the message are the application's; the required state is the
system's word.

## 3. What it replaces

- **The Showcase:** `Labelled.above`, `Page.field`, `Page.message`, `Page.withMessage` and
  `Page.tint` go; their 56 + 58 + 8 + 10 + 5 calls become `Field`s, `setValidation` and
  `Label.setTone`. `BasicsScene`'s `say` helper and the `checked` lines become
  `field.setValidation(state, message)`. `DatesScene`'s delivery line becomes the date field's own
  message. `Labelled.below` (a caption under a specimen, not a form field) and
  `Labelled.aboveWithValue` (a bar with its value on the caption's line) stay the demo's.
- **The site's form example** (`FormExample`): the `form-field` and `form-validation` snippets
  become a `Field`, and the stale `setColor(Theme.current().danger())` goes with them.
- **The forms guide:** "A field is a caption and a control" and "Validation" are rewritten around
  `Field`; the paragraph on the line that keeps its height stays, as what `Field` does.
- **The accessibility guide** keeps `setLabelFor` and `setDescriptionFor` as the idiom under a
  `Field`, for a caption that is not a form's, and says what a required and an invalid field are
  on each system.
- **The theming guide** names `Tone`.

## 4. Questions for the owner

Answered by the owner on 2026-10-18: every question took its recommendation. Each keeps its
recommendation and cost as put; the answer follows it.

1. **A container, or setters on each input?**
   - *Recommended:* the `Field` container (section 2.1).
   - *Why:* one class for thirteen inputs and any of an application's own, a compound control
     served alike, and inputs whose boxes stay theirs for alignment.
   - *Cost:* one more widget in each form's tree, and a wrapper the application writes around
     each control.
   - *The alternative:* `setLabel`, `setHelperText`, `setRequired` and a message on each input:
     about fifty setters over thirteen classes, each input measuring its caption and its line, a
     compound control left without them, and a `Grid(2)` that can no longer align controls whose
     boxes include their captions.
   - **Answer:** the `Field` container, as recommended.
2. **`Validation` a type of its own?**
   - *Recommended:* yes, `limn.components.Validation`, `TextField.Validation` removed.
   - *Cost:* every `TextField.Validation` in the repository renamed, mechanical; no consumers
     outside it.
   - *The alternative:* `Field` takes `TextField.Validation`, which reads as if a combo box's
     state belonged to the text field.
   - **Answer:** yes, `limn.components.Validation`, `TextField.Validation` removed.
3. **The border of the inputs that have no validation today.**
   - *Recommended:* through an internal interface only `Field` reaches.
   - *Why:* the API grows by one class and not by a setter on six.
   - *The alternative:* a public `setValidation` on `ComboBox`, `TokenBox`, `Spinner`, `DateField`
     and `DatePicker`, as on `TextField`, for an application that colours a border without a
     `Field`: five public setters, each with its tests.
   - **Answer:** through an internal hook only `Field` reaches, as recommended (built as a
     registry by class rather than an interface the inputs implement: section 8.2).
4. **The message and the helper together?**
   - *Recommended:* the message replaces the helper while it shows, on screen and for a reader.
   - *Why:* one line, so the form's height is stable, and one description.
   - *The alternative:* both lines while there is a message, the helper under it: the form grows
     by a line on the first error, and the reader is given the message alone or the two joined.
   - **Answer:** the message replaces the helper while it shows, on screen and for a reader.
5. **A glyph before the message?**
   - *Recommended:* yes, drawn by the toolkit for each state (a circle with a bar for an error, a
     triangle for a warning, a check for success, an "i" for information), as it draws the
     expander's arrow, since the toolkit carries no icon set.
   - *Why:* the state then does not depend on colour alone.
   - *Cost:* four small paths and their right-to-left placement.
   - *The alternative:* no glyph, or an icon the application passes with each message, as the
     Showcase does from its own icon pack.
   - **Answer:** yes, a glyph drawn by the toolkit for each state.
6. **The required mark.**
   - *Recommended:* an asterisk after the caption, in `danger`.
   - *The alternative:* the asterisk in the caption's own muted colour; or no mark on required
     fields and "(optional)" on the others, which needs a string in 21 languages.
   - **Answer:** an asterisk after the caption, in `danger`.
7. **Does the line keep its height?**
   - *Recommended:* yes, once a field has helper text or has shown a message.
   - *The alternative:* the line collapses when empty, and the form moves on each error.
   - **Answer:** yes, once a field has helper text or has shown a message.
8. **A caption beside the control?**
   - *Recommended:* not in this record: `Grid(2)` of a bound `Label` and a captionless `Field`.
   - *Cost of adding it:* a position setter and a caption width every field of a form would have to
     share, which is a grid's job, not a field's.
   - **Answer:** not in this record: a `Grid(2)` of a bound `Label` and a captionless `Field`.
9. **`Tone` and `Label.setTone` public?**
   - *Recommended:* yes, shared with `Badge` (ADR 075).
   - *Why:* any line of coloured text in an application has `Page.tint`'s defect today.
   - *The alternative:* the tone stays inside `Field` and `Badge`, and an application colours other
     text by hand.
   - **Answer:** yes, both public, and `Tone` shared unchanged with ADR 075's `Badge`.
10. **Invalid on macOS, if AppKit has no attribute VoiceOver reads.**
    - *Recommended:* nothing more than the message in the description, which says what is wrong.
    - *The alternative:* the toolkit writes the word "invalid" before the message on macOS alone, a
      string in 21 languages and a sentence composed by the toolkit.
    - **Answer:** nothing more than the message in the description (section 8.3 for what was
      read on the guest, and what is still to be heard).
11. **A date field's own message shown by its `Field`?**
    - *Recommended:* yes, in the error tone, until the application sets one of its own.
    - *Why:* the reason is already written and translated; the Showcase copies it by hand today.
    - *The alternative:* the application shows it, as now.
    - **Answer:** yes, in the error tone, until the application sets one of its own.

## 5. Not in this record

- **A form layout** with breakpoints, spans or a column count of its own: refused (ADR 049, 050;
  the owner's simple-layout rule). A form is a `Column` or a `Grid(n)` of fields.
- **A validation framework**, a binder, rules declared on a field, a form that submits: the rule
  stays a handler or a watcher of the application's.
- **Clearing a message on change, validating late:** the guide's advice, the application's code.
- **Master-detail** (refused 2026-10-18), and what was refused earlier: toasts and notifications,
  a link widget, rich text, a switch class, persistence.
- **A prefix or suffix slot** on the field: `TextField` has its leading icon and trailing button.

## 6. Stages, and what each must prove

- **Stage 1: the model and the bridges.** `Validation` moved; the walk carries a describer and the
  form states through `accessibleLabelTarget`; Windows answers `IsRequiredForForm` and
  `IsDataValidForForm` and raises their changes; macOS answers `AXRequired`, and invalid as the
  guest's reading decides. Tests: the per-bridge synthetic tests (`UiaShapesTest`,
  `AxShapesTest`, `AtspiShapesTest`) gain a required and an invalid node of the `TEXT` shape and
  pin what each bridge serves; a message bound to a `DatePicker` describes its field.
- **Stage 2: `Field` and `Tone`.** Tests: layout, both directions, the line reserved and not, the
  mark outside the name; each of the thirteen inputs in a `Field` publishes the caption, the line
  and the states on the node a reader lands on, and `TextContract`, `ValueContract`,
  `PopupOwnerContract` and `ToggleContract` still pass over them; a compound control gets its
  group; the bordered inputs draw the larger state; a `DateField`'s own message shown and
  replaced; a palette change re-colours every line with no application code; the damage contract's
  row (a message change repaints the line and the border, a layout only when the line's height
  changes).
- **Stage 3: the Showcase, the site's example and the guides** (section 3).

## 7. What must be measured or heard

- **Readers, narrowly.** The shapes cover every node a field publishes; what they do not cover is
  two state translations no reader has heard from this toolkit: `REQUIRED` on all three systems
  (Linux maps it, nothing has ever produced it), and `INVALID` on Windows and macOS (Linux's
  `INVALID_ENTRY` has been published by `TextField` since ADR 039; whether Orca was heard saying it
  is not in the records read here, so the same run confirms it). One script per reader, NVDA,
  VoiceOver and Orca: Tab to a required empty field, then to an invalid one with its message,
  then fix it. Heard: the caption, "required", "invalid" (or the system's word), the message once,
  and the invalid state gone after the fix.
- **On macOS, before anything is written:** whether VoiceOver reads any attribute of invalidity
  from a native element (question 10).
- **Contrast:** each built-in palette's four tones as text in the label font on `surface` and
  `background`, against 4.5 to 1, by the theme's tests, before the tones are used for text; the
  Showcase draws its messages in them today without that check.

## 8. Built, 2026-10-18

All three stages of section 6, on branch `claude/adr-074-field`, in six commits: `Validation`
moved; the walk's describer and form states; the two bridges; `Tone` and `Label.setTone`;
`Field`; the Showcase, the site's examples and the guides.

### 8.1 What was built

- **Stage 1.** `limn.components.Validation` (the five constants, unchanged), every use renamed.
  The walk's redirect remembers the composite it came from and, at the carrier the composite
  names, reads three things off it: the caption, the bound describer and the form states, each
  the carrier's own where it has one. The states reach the model through an internal hook,
  `SceneAccess.fieldControl(control, held, required, invalid)`, stored with the widget's other
  accessible overrides and applied by the walk with them: `REQUIRED`, `INVALID` (only ever added,
  so a text field's own error stands), and a `GROUP` role for a control that declares none.
  Windows answers `IsRequiredForForm` (30025) and `IsDataValidForForm` (30103, the inverse of
  `INVALID`) on the getter and raises both as property changes; macOS answers
  `isAccessibilityRequired`, offered by the gate only where the node is required. Linux was
  already mapped and is unchanged.
- **Stage 2.** `Tone { NEUTRAL, ACCENT, INFO, SUCCESS, WARNING, DANGER }` with
  `Tone.colorIn(Theme)` (muted text, primary, info, success, warning, danger), the one resolution
  `Label.setTone` paints with and ADR 075's `Badge` will ask; an explicit `setColor` wins, a tone
  wins over `setMuted`, a disabled label is drawn disabled. `Field` as section 2.1 writes it; its
  parts are four children (the caption, the asterisk, what is placed, the line), all `Label`s but
  the placed one, laid out by the field itself. The glyphs are four stroked drawings in an
  `Icon` of the field's own. The bordered inputs show the state through
  `limn.components.internal.FieldControls`, which also carries a date's own message.
- **Stage 3.** Section 3, with the counts it gave; and a reader entry for section 7 (8.5).

### 8.2 Where the build departs from the text above

- **`TokenBox` is not an input** (sections 2.1 and 2.3, question 3). It is a layout box that sizes
  its child by a size token and draws nothing; the record took its name for a control. The
  bordered inputs are `TextField` and its subclasses, `TextArea`, `ComboBox` (an editable one
  through its editor, which draws its frame), `Spinner`, `DateField` and `DatePicker`.
- **A registry by class, not an interface the inputs implement** (section 2.3). A public class
  that implements an interface puts its method on the class's public face, and a package that is
  not exported does not hide it there. Each bordered class registers, from its static
  initializer, how it shows a field's state (`FieldControls.ownBorder`), and the two date classes
  what they say when they refuse a value (`ownMessage`); a subclass inherits its parent's entry.
  Nothing is held per instance. A text field and a text area register their own `setValidation`,
  as the record asked.
- **The range slider's states are on its group**, where its caption lands, and not on each thumb
  (section 2.5). The walk carries what a field says to the one node a caption names; carrying it
  to two would need a second kind of redirect for one widget. Each thumb keeps its own name.
- **A compound control was already a group when a caption named it.** A widget with no role that a
  bound caption names is published as a named group by the walk as it stood; the field's own
  rule (a control with no role becomes a `GROUP`) is what serves a field with no caption.
- **`setValidation(state)` alone is the state with no message**, so the line shows the helper;
  `clearValidation()` is `setValidation(NONE)`. The record listed the signatures and not this.
- **An empty line is not a node** and describes nothing: while the reserved line is empty the
  label is ignored by the walk and unbound, so no empty static node and no empty description
  are published.
- **`Label.setIcon` now lays out in place**, as `setText` does. The damage contract's row for a
  field (a message arriving with its glyph) repainted the window through the full layout the
  icon asked for; in place it repaints the line and the border, with a layout reaching further
  only when the line's height changes (section 2.2's own promise).
- **`TextArea.validation()`** was added beside `TextField.validation()`; the area had a setter and
  no getter.
- **macOS, invalid** (question 10, section 7). The selector `isAccessibilityRequired` was read on
  the guest before it was installed, as the constants test requires: the whole AppKit dump
  regenerated on the macOS guest, now at macOS 27.0 (26A428), and nothing already in it moved. The
  same dump asked for `NSAccessibilityInvalidAttribute` and AppKit does not export it: AppKit has
  no attribute of its own for an invalid value. Whether VoiceOver reads the `AXInvalid` string
  some web engines publish was not heard (8.5); as answered, the message in the description is
  what macOS gets.
- **Windows' second id was already read.** `IsDataValidForForm` is 30103 in the guest's type
  library and interop dumps of 2026-09-13 (readings/windows-dump-uia-typelib.txt); it had not been
  taken into `UiaIds` because nothing asked.
- **Contrast** (section 7). The four state tones clear 4.5 to 1 as text on `surface` and on
  `background` in every built-in palette, now held by `ThemeContrastTest`; nothing had to move.
- **The caption is the label font, muted, as written**, which is a change on every Showcase page:
  `Labelled.above` drew the body font, not muted, four points above the control; a field draws
  the label font, muted, `gapLabel` (6) above. `Labelled.aboveWithValue`, which stays, now draws
  its caption the same way, and the buttons-and-inputs page's other captions (customer type,
  billing cycle, invoices, access) too, so a page reads as one form.
- **Found and not fixed:** `DayMark` takes a `Color`, so a calendar's day marks hold the palette of
  the moment they were made, the defect `Tone` removes from labels; the Showcase's and the
  guide's marks are built from `Theme.current()`. A `DayMark` of a `Tone` is a change of that
  class's public face and was left out of this record.

### 8.3 Section 6's tests, and where they are

- Stage 1: `FormStatesAccessibilityTest` (a message bound to a `DatePicker` describes its field;
  the states land where the caption lands; a text field keeps its own `INVALID`; a role-less
  control becomes a group): four of its five cases fail against the walk before the change.
  `UiaShapesTest`, `AxShapesTest` and `AtspiShapesTest` each gain a required and invalid `TEXT`
  node; the Windows and macOS cases fail against the bridges before the change.
- Stage 2: `LabelToneTest` (two of three fail without the paint change); `ThemeContrastTest`'s
  state tones; `FieldTest` (layout in both directions, the line reserved and kept, the asterisk
  after the caption's end, a captionless check box, the placed control, each bordered input's
  border, a refused day staying red, a date's own message shown and replaced, a palette change
  re-colouring the line; the border and date-message cases fail with the registry emptied);
  `FieldAccessibilityTest` (the thirteen inputs of the forms guide's table, an editable combo, a
  combo of many, a column of radio buttons and an amount with its currency, each carrying the
  caption, the line and the states on the node a reader lands on; the asterisk outside the name;
  the access letter); `FieldContractsTest` (the text, value, popup-owner and toggle contracts
  over controls in a required field with a message); and rows in `DamageContractTest` (failed
  with a full window repaint before `setIcon` went in place), `NotificationContractTest` and
  `AccessibleCoverageTest`.
- Stage 3: the demo's suite, `ShowcaseDocumentFieldsTest` reading the field's message, and
  `ReaderStepsTest` running the new script headlessly.

### 8.4 What it replaced

`Page.field` (both), `Page.message`, `Page.withMessage`, `Page.tint` and its weak map, and the
four `Labelled.above`; every caller in the Showcase, the accessibility gallery and the site's
three examples is a `Field`. `Labelled` keeps `caption` (which now carries the snippet the
accessibility guide quotes), `aboveWithValue` and `below`. The forms guide's two sections, the
accessibility guide's naming and description paragraphs and the theming guide are rewritten as
section 3 says. Five Showcase strings, in all 21 bundles, for the variations the
buttons-and-inputs page did not have: a masked field's helper, a credit limit that is an amount
and its currency, and a required check box with its helper.

### 8.5 What is still owed

- **The reader run of section 7**, on the guests. The gallery has an entry for it, "Fields,
  required and invalid", and a script, `field-states`, which `ReaderStepsTest` already runs
  headlessly: the run starts on the empty required email, described by its helper; Tab to the
  postal code, required and invalid, described by its message; End; the fifth digit typed, after
  which the postal code is required, not invalid, and described by its helper again. On each
  guest, with its reader on (NVDA on Windows, VoiceOver on macOS, Orca on Fedora and Ubuntu), run
  `limn-demo --reader field-states` (pt-BR by default; `--real-keys` to send the keys through the
  system), with the recipes of the earlier runs. Heard, per section 7: the caption, "required"
  (or the system's word), "invalid" on the postal code, the message once, and the invalid state
  gone after the fix. The guest recipes owe a section for the new id.
- **On macOS, in the same run:** whether VoiceOver says anything of the postal code's invalidity
  beyond the message, which with no attribute published it should not; if a native reading of
  `AXInvalid` is wanted, it is a probe over a native field first, as question 10 put it.

## Note, 2026-10-18: `Tone.ACCENT` is the accent as text

ADR 075 (section 12.1) found that `primary`, solved for a fill at 3:1, falls under body text's
4.5:1 on a raised surface in eleven of the fifteen built-in palettes. The owner chose a derived
`Theme.primaryText()`, primary moved toward the text colour only until it clears 4.5:1 on the
canvas, a card and a popover, and `Tone.ACCENT` resolves to it, so a label in the accent tone reads
in every palette. `ThemeContrastTest` now holds ACCENT among the state tones.

## Note, 2026-10-19: the text getters answer strings

The audit of 2026-10-19 (AP-4) found `label()`, `helperText()` and `validationMessage()` returning
`I18nString` where every other widget's text getter of that kind returns the `String` as it reads
and pairs it with a `…Source()` getter for the value behind it (`Label` and `Button`'s `text()` and
`textSource()`, `MenuItem`'s `label()` and `labelSource()`, `Card`'s `title()`): `String caption =
field.label()` did not compile where `button.text()` and `card.title()` do, and a return type is
frozen at 1.0. The owner chose the common shape (his decision 14): `label()`, `helperText()` and
`validationMessage()` return the text in the language in effect, `label()` with an access
letter's `&` mark taken out as the caption shows it, and `labelSource()`, `helperTextSource()` and
`validationMessageSource()` return the `I18nString`. The sketch in section 2.1 is amended in place.
`DateField.validationMessage()` still returns its `I18nString` (its keys are tested), which this
decision did not cover.
