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
spinners all behave the same way here.

## The controls

| Control | For |
| --- | --- |
| `TextField` | one line of text, with a placeholder and an optional leading icon |
| `PasswordField` | the same, masked |
| `TextArea` | several lines, with its own scrollbars |
| `SearchField` | a text field with search affordances |
| `ComboBox` | one choice from a list |
| `Spinner` | a number with steppers |
| `Slider` | a number on a range |
| `Checkbox` | a boolean, as a box or as a switch |
| `RadioButton` | one of several, grouped by a `ButtonGroup` |

Each reports changes through a listener rather than an event object:

```java
TextField email = new TextField();
email.setPlaceholder("ada@example.com");
email.onChange(text -> model.setEmail(text));
```

## Validation

A field carries a validation state (`NONE`, `ERROR`, `WARNING`, `SUCCESS`, `INFO`) which
recolours its border. Setting it is your decision, made whenever you like: on change, on
blur, or only when the user presses the submit button.

{% snippet guide:form-validation %}

Two details in there earn their place. The message label exists whether or not it has text,
so the form does not jump by a line the first time a field fails. And the rule runs on
change, which means the error clears itself as soon as the user fixes it. A form that only
revalidates on submit makes people press the button to find out whether they are done.

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
