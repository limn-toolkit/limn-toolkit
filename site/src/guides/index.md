---
title: "Documentation"
description: "Everything you need to build a desktop application with Limn: install, layout, forms, theming, and shipping."
---

Limn is a UI toolkit for desktop Java. You add one dependency, write plain Java, and get
a window with your own widgets in it. There is no markup language, no annotation processor
and no build plugin; a screen is a method that returns a widget.

## Start here

If you have never run it, [Install and first window](/docs/install/) takes about five
minutes and ends with a window on screen. [Widgets and scenes](/docs/widgets/) is the
twenty-minute version of everything else: the four ideas the rest of the guide builds on.

## Building a screen

[Layout](/docs/layout/) and [Forms](/docs/forms/) are the two pages most applications need
first, and both carry a complete worked example with the screenshot it produces.
[Lists and scrolling](/docs/lists-and-scrolling/) covers long content;
[Menus and dialogs](/docs/menus-and-dialogs/) covers the parts that leave the window, the
system's file choosers, handing a link or a file to the system's own programs, and an icon in
the system's tray;
[Drag and drop](/docs/drag-and-drop/) covers rows that rearrange, what is dragged to and from
other programs, and the clipboard; [Printing and PDF](/docs/printing/) covers saving a report
as PDF, printing it through the system's dialog and showing a preview first; and
[Charts](/docs/charts/) covers bars, lines and donuts.

## Making it yours

[Theming](/docs/theming/) is colour, shape, depth and density, including building a palette of your own and letting your users build one. [Text and languages](/docs/text-and-languages/)
is what to do when your users are not all reading English, and [Accessibility](/docs/accessibility/)
is what a screen reader says about your window and the one call that makes it say the right
thing. [Images and media](/docs/images-and-media/)
covers pictures, video and the 3D viewport, [Animations](/docs/animations/) covers making
something move, and [Your own widgets](/docs/custom-widgets/) covers a control the toolkit does
not have.

## Coming from another toolkit

If you already write [Swing](/docs/from-swing/), [JavaFX](/docs/from-javafx/) or
[SWT](/docs/from-swt/), the page for it maps each class and habit you know to its Limn
counterpart, shows the everyday tasks written in both, and says plainly what Limn does not
have.

## Shipping

[Background work](/docs/background-work/) is the one rule that will bite you if you skip it:
anything slow goes off the UI thread, and the toolkit gives you a shape for that.
[Performance](/docs/performance/) says what a frame costs and how to keep it small,
[Testing](/docs/testing/) drives a screen from a test with no display, and
[Packaging](/docs/packaging/) turns the result into something you can send someone.
[Troubleshooting](/docs/troubleshooting/) is where to look when a machine you sent it to will
not start it, or a message you have not met before appears.

:::note[Looking for the API?]
Every class, method and parameter is in the [API reference](/api/). This guide explains how
the pieces fit together; the reference is what you keep open while you type.
:::
