---
title: "Background work"
description: "Keeping the window responsive: the one rule, the shape the toolkit gives you, and cancellation."
---

## The rule

There is one UI thread, and it is the thread drawing your window. Every millisecond you
spend on it is a millisecond the window is not repainting or answering the mouse. Anything
that reads a file, calls a service, decodes an image or grinds through a list of ten
thousand things has to happen somewhere else.

Widgets may only be touched from the UI thread, and touching one from anywhere else throws
immediately with a clear message, which is deliberate. The alternative is a rendering
glitch three seconds later that nobody can reproduce.

## The shape

`Ui.work(…)` describes a piece of background work, and every callback you attach runs back
on the UI thread, so a handler can touch widgets directly:

```java
job = Ui.work(progress -> repository.load(id))
        .onProgress(bar::setProgress)
        .onSuccess(list::setItems)
        .onFailure(error -> status.setText(error.getMessage()))
        .deliverIf(view::isShowing)
        .start();
```

Read that in order:

- **The body runs on a worker pool.** It gets a `Progress` handle and returns a value. Any
  exception it throws, checked or not, is routed to `onFailure` instead of killing the
  worker.
- **`onProgress` takes a fraction** from 0 to 1, reported by the body through
  `progress.report(…)`. Deliveries are coalesced, so a body that reports every row does not
  flood the UI thread.
- **Exactly one terminal callback runs**: `onSuccess` or `onFailure`, never both, never
  twice.
- **`deliverIf` is the guard** for a result arriving after the view has gone. When it
  answers false, nothing is delivered.
- **`start()` returns a `Job`.** Register everything before you call it, because the handlers are
  read once.

## Cancellation

The `Job` is why this exists rather than a plain future. Keep it in a field and withdraw the
request when it stops mattering:

```java
if (job != null) {
    job.cancel();
}
job = Ui.work(progress -> search(query)).onSuccess(results::setItems).start();
```

That is a search box that answers the question last asked, rather than whichever answer
happened to arrive last.

Cancel on the UI thread, which is where the reasons to cancel arrive anyway. Done there, no
callback runs after `cancel()` returns.

A cancelled job is not interrupted: a body already running keeps going until it returns or
notices `progress.isCancelled()`, and its result is discarded rather than delivered. Check
that flag in any loop that could run long:

```java
Ui.work(progress -> {
    for (int i = 0; i < rows.size(); i++) {
        if (progress.isCancelled()) {
            return null;
        }
        index(rows.get(i));
        progress.report((double) i / rows.size());
    }
    return null;
});
```

## Just getting back to the UI thread

When you already have a thread and only need to hand a result over:

```java
Ui.post(() -> label.setText(text));
```

`Ui.postDelayed(action, millis)` is the same with a delay. `Ui.async(supplier)` returns a
`CompletionStage` for the cases where you genuinely do not need cancellation or progress,
but reach for `Ui.work` first; the moment a view can close while a request is in flight, you
wanted the `Job`.

## Loading things

The loaders that touch the disk all have an asynchronous form, and it is the one to use once
a window is on screen: `Images.loadAsync`, `Images.fromResourceAsync`, `Sounds.loadAsync`
and `Sounds.fromResourceAsync`. Each does the read and the decode on the worker pool and
completes on the UI thread, so `thenAccept` may touch widgets.

Native file dialogs are the deliberate exception: they block the UI thread while they are
open, exactly as they do in every other desktop application.
