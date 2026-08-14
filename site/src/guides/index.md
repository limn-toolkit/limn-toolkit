---
title: "Documentation"
description: "Everything you need to build a desktop application with Limn: install, layout, forms, theming, and shipping."
---

Limn is a UI toolkit for desktop Java. You add two dependencies, write plain Java, and get
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
[Menus and dialogs](/docs/menus-and-dialogs/) covers the parts that leave the window; and
[Charts](/docs/charts/) covers bars, lines and donuts.

## Making it yours

[Theming](/docs/theming/) is colour, shape and density, including building a palette of your own and letting your users build one. [Text and languages](/docs/text-and-languages/)
is what to do when your users are not all reading English. [Images and media](/docs/images-and-media/)
covers pictures, video and the 3D viewport.

## Shipping

[Background work](/docs/background-work/) is the one rule that will bite you if you skip it:
anything slow goes off the UI thread, and the toolkit gives you a shape for that.
[Packaging](/docs/packaging/) turns the result into something you can send someone.

:::note[Looking for the API?]
Every class, method and parameter is in the [API reference](/api/). This guide explains how
the pieces fit together; the reference is what you keep open while you type.
:::
