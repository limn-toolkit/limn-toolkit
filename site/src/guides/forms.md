---
title: "Forms"
description: "Fields, validation, choices and actions: a complete sign-up form, and the pattern behind each part of it."
---

A form in Limn is a `Column` of fields. There is no form object to bind to and no
validation framework to configure: a field is a widget, a validation rule is a listener,
and submitting is a method call.

This page builds the form in the picture, one piece at a time.

{% shot form "The form below, rendered by Limn in its light palette." %}

## A field is a caption and a control

Every field on that form has the same shape, so it is worth one helper. `STRETCH` is what
makes the control fill the column instead of shrinking to fit its own text.

{% snippet guide:form-field %}

Use it for every control that takes a value: text fields, password fields, combo boxes and
spinners all behave the same way here. The `setLabelFor` call is the one line that is not
layout: it makes the caption the control's *name*, so a screen reader says "Email, text field"
rather than "text field". A caption that merely sits above a field names nothing, and
[Accessibility](/docs/accessibility/) says why.

## The controls

| Control | For |
| --- | --- |
| `TextField` | one line of text, with a placeholder and an optional leading icon |
| `PasswordField` | the same, masked |
| `TextArea` | several lines, with its own scrollbars |
| `SearchField` | a text field with search affordances |
| `ComboBox` | one choice from a list |
| `Spinner` | a number with steppers |
| `DateField` | a date, a time, or both, typed into segments the language orders |
| `DatePicker` | the same field with a calendar behind a button; `ofRange()` for a period |
| `CalendarView` | a month grid on its own, for a screen that shows one |
| `Slider` | a number on a range |
| `Checkbox` | a boolean, as a box or as a switch |
| `RadioButton` | one of several, grouped by a `ButtonGroup` |

Each has two channels, and they answer two different questions. The fluent `onX` slot is the
**handler**: the application's one response to the *user* operating the widget. It never runs
for a value the application wrote itself, so a form that fills its fields from a record cannot
echo back into its own model. The validation rule below is a handler, and the form sets the
email field's starting state explicitly for exactly that reason: `setText` is the application's
write, and the rule did not run for it.

Anything that must *mirror* a widget, wherever the change came from — a character counter, a
detail pane, a two-way binding — **watches** it instead. A watcher hears every change with its
origin. The count under the name field is one:

{% snippet guide:form-counter %}

The label is written once from the field and then follows it: the `setText` that fills the
form, and every keystroke after it, both reach the watcher. `observeChanges` hands back a
`Subscription`, and cancelling it is the one way to stop watching; a widget's watchers
otherwise live as long as the widget does.

A handler slot holds one handler: registering a second one throws, and `null` clears it. Two
parties that both want to hear the same widget are both watching, and watching has no such limit.

## Dates and times

A date is the one field a form cannot be written without and the one a text field is wrong for.
Limn has three classes for it, and four shapes come out of them: a field types a date, a picker
is that field with a calendar behind a button, and either of them carries a clock as well.

{% shot dates "The four shapes, a period, and the grid on its own." %}

{% snippet guide:date-shapes %}

**The value is always ISO.** Everything these widgets hand out and take in is a `LocalDate`, a
`LocalTime` or a `LocalDateTime`, so an application stores what it asked for and never writes a
branch about calendars. What is *drawn* is a separate axis: the month names, the year number and
the length of a month come from the calendar system resolved for the widget's language, which is
the Gregorian one everywhere until a locale carries a `u-ca` extension. A Thai user driving a
Buddhist calendar picks a day out of a grid headed with a year 543 greater, and the application
gets the ISO date.

**The segments and the separators are the language's own.** The same field reads day, month, year
in Portuguese, month, day, year in American English and year, month, day in Japanese, because the
order comes from the locale's own short pattern rather than from a format string in the
application. A two-digit year in that pattern is widened to four: the order and the separators are
what the locale genuinely owns, and a two-digit year in something a person types is an ambiguity
worth refusing.

**Typing beats clicking, and both work.** Up and Down adjust the segment the caret is in; Left and
Right move between segments; digits fill the current segment and roll on to the next, so
`31122026` commits the last day of 2026 without a separator being typed. `Ctrl/Cmd+V` parses what
is on the clipboard, which is where a date pasted out of a spreadsheet is understood. `Alt+Down`
opens the calendar, the arrows then drive the grid, `Enter` picks and `Esc` closes.

### A period

{% snippet guide:date-range %}

Two fields and one grid: the first click in the calendar anchors the period, the second closes it,
and the days between are drawn as a band. `range()` answers `null` until both ends are filled — a
period with one end is not a period, and is not published as one.

### Which days may be picked

{% snippet guide:date-rules %}

`setMinDate`, `setMaxDate` and `setDateFilter` exist on the field, on the grid and on the picker,
which fans them out to both of its parts. The two halves enforce them at different moments, and
the difference is deliberate: **the grid refuses the click** — the day is drawn disabled, the
keyboard skips it and a screen reader is given no verb for it — while **the field holds what was
typed** and marks itself invalid with a message saying which rule was broken. A field that snapped
a typed date to the nearest legal one would be throwing away what somebody wrote and telling them
nothing.

The filter runs once per painted cell, so it has to be cheap and it has to be pure; a filter that
queries a database is a filter that stalls a frame. `setDayMarks` decorates days with a dot and,
when it is given one, a phrase that joins what a screen reader says about that day — a dot alone
reaches everyone who looks and nobody who listens.

### What a screen reader gets

The grid is published as a **table**: six rows of seven cells under a row of column headers, which
are the same four roles a `Table` uses and are mapped on all three platforms. A day is named with
the whole date and not the bare number, because a cell heard on its own has to say what it is. The
field is a **group of spin buttons**, one per editable segment, each with its own name and its own
range — the caret is in one segment at a time, and a single text field publishing `31/12/2026`
would give a reader no way to say which part that is.

Reading right to left, the grid mirrors and the field does not. A grid is columns in reading order,
so the first day of the week moves to the edge reading starts from and Left and Right swap with it.
A date is a run of numbers, and a run of numbers keeps its own left-to-right order inside a
right-to-left line; what moves there is which side of the box the run sits against.

## Validation

A field carries a validation state (`NONE`, `ERROR`, `WARNING`, `SUCCESS`, `INFO`) which
recolours its border. Setting it is your decision, made whenever you like: on change, on
blur, or only when the user presses the submit button.

{% snippet guide:form-validation %}

Three details in there earn their place. The message label exists whether or not it has text,
so the form does not jump by a line the first time a field fails. It is bound to the field as
its *description*, the way the caption is bound as its name, so a screen reader says "Email,
text field, invalid, enter an address like…" instead of leaving the reason as a sentence
somewhere else on the screen. And the rule runs on change, which means the error clears itself
as soon as the user fixes it. A form that only revalidates on submit makes people press the
button to find out whether they are done.

:::tip[Validate late, clear early]
Showing an error before someone has finished typing reads as nagging. A good default is to
validate a field the first time it loses focus, and after that on every change, so the
first message arrives when they have moved on, and it disappears the moment they correct it.
:::

## Actions

Put the buttons in a `Row` with a spacer in front of them, and they sit against the trailing
edge whatever the form's width turns out to be.

{% snippet guide:form-actions %}

The primary action is the plain `Button`; `setSecondary(true)` gives the quieter one. Keep
one primary per form; if two buttons are both primary, neither is.

## The whole form

Everything above, assembled:

{% snippet guide:form %}

## When it scrolls

A form long enough to scroll needs two things said, and neither is the default:

{% snippet guide:form-scroll %}

The padding goes **inside** the scroll view. A viewport clips at its own edge, and a focused
control paints its ring outside its own box, so a field flush against that edge loses the
ring that says it is focused. Padding around the scroll view does not help: the clip travels
with the viewport, not with the box around it.

The bar takes a strip of its own rather than floating. Every row of a form ends in something
the reader is aiming at, and an overlay bar is drawn over the viewport with no knowledge that
the content has a margin, so it lands on the trailing edge of the fields. `RESERVED` narrows
the content instead, and nothing is ever painted under a bar.

:::note
The overlay default is right for what it was chosen for: an image, a video, a page of prose.
Anything that wants every point of width and loses nothing to a bar passing over it.
:::

## Submitting

There is no submit event. Read the values off the widgets you are holding and call your own
code:

```java
submit.onAction(() -> {
    Account account = new Account(name.text(), email.text(), plan.selectedItem());
    Ui.work(progress -> accounts.create(account))
            .onSuccess(created -> router.showAccount(created))
            .onFailure(error -> banner.setText(error.getMessage()))
            .start();
});
```

That `Ui.work(…)` is the important part: creating the account is a network call, and running
it directly in the button handler would freeze the window until it came back. See
[Background work](/docs/background-work/).

## Keyboard and focus

Tab moves between fields in tree order, so the order you add them is the order people move
through them, and Escape closes a `Dialog`. Focus is drawn as a ring outside the control, so
it never sits on top of the field's own border, and the ring's colour is solved against the
accent it surrounds, so you do not have to check it yourself.

A form does not submit on Enter by default. If you want that, subclass the field and handle
the key: `onKeyEvent` is the hook, and consuming the event stops it going any further:

```java
class SubmitOnEnter extends TextField {
    private final Runnable submit;

    SubmitOnEnter(Runnable submit) {
        this.submit = submit;
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (event.isPressed() && event.key() == Keys.ENTER) {
            submit.run();
            event.consume();
            return;
        }
        super.onKeyEvent(event);
    }
}
```
