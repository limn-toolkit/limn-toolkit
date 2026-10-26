# ADR 062: The performance budget counts work, and time is a report

- **Status:** Accepted and implemented, 2026-10-12. The unit of §1 is the owner's choice, from
  priced options.
- **Date:** 2026-10-12
- **Scope:** what `check` holds the toolkit's performance to, and what is only reported. It is
  the second of the two gates the road map sets before 1.0.
- **New public API:** `HeadlessWindow.takeFrameRequests()` in `limn-test`.
- **Compatibility:** none broken.

---

## 0. Why not milliseconds

The road map asked for benchmarks with a noise floor that fail when they regress: at rest,
scrolling a hundred thousand rows, a sort, a tree's branch opened and closed, startup. A
benchmark in milliseconds answers for the machine that ran it. The machine this toolkit is
written on is far above what its applications run on, so a ceiling set here is one a slow
machine passes ten times over before anybody is told, and the same ceiling on a shared CI runner
fails on a busy afternoon for no change in the code. The owner asked for a budget that is not
tied to his hardware.

## 1. Decision: what fails is a count of work

`check` fails on counts that are the same number on every machine:

- widgets painted by a frame, and drawing commands it issued, counted by a canvas that draws
  nothing;
- times the application's own code is called for content: a tree's model, a column's value, a
  list's row widget;
- keys a sort reads;
- bytes a step allocates, the least of several runs;
- frames a window is asked for.

Each scenario holds two things. **The cost does not follow the size of the content:** the same
step over ten thousand rows and over a hundred thousand paints the same widgets, issues the same
commands, calls the application the same number of times, and allocates within a tenth. **The
cost stays under a ceiling** written beside the scenario with the count it was set from: a
seventh of room over an exact count, a half over bytes.

| Scenario | Held to |
|---|---|
| A notch of the wheel over a `Table` of 100 000 | same as over 10 000; 8 cell values read, 96 commands, 28.7 kB |
| A notch of the wheel over a `ListView` of 100 000 | same as over 10 000; 4 row widgets asked for, 52 painted, 51 commands, 42.5 kB |
| A branch of a `Tree` of 100 000 opened and closed | same as among 10 000; 67 calls to the model, 182 commands in two frames, 273 kB |
| A sort of 100 000 rows | 1.82 keys read per row per doubling, of the 2 a merge sort can need; none read by the frames after |
| A screen nobody touches | no frame asked for; a frame that had nothing to do allocates 208 bytes |
| The Showcase's first screen | 146 widgets, 195 commands, 1.03 MB to build and draw; once its opening has played, no frame asked for |

The whole budget runs in under a second, in `limn-toolkit` (`limn.budget.WorkBudgetTest`) and
`limn-demo` (`ShowcaseBudgetTest`), over a probe kept in the test fixtures.

## 2. What was done to trust it

- **Two regressions were planted and refused.** A frame asked for on every frame failed both
  rest scenarios. A tree rebuilt whole on every opening failed with 100 119 calls to the model
  where 67 are allowed. The second was first let through: the probe measured an opening and a
  closing as alternate runs and kept the cheaper, so both are now one step.
- **The same counts on a machine twenty times slower.** The tests were run with the virtual
  machine's compiler off, which makes the sort take 1.6 s where it takes 0.08 and removes every
  allocation the compiler elides. All passed with no ceiling changed.

## 3. What a count does not see, declared

A loop inside the toolkit that walks every row, calls nothing of the application's, draws
nothing and allocates nothing is seen by no count here. Such a loop is caught by the report of
§4 and by reading, not by `check`. A ratio of times between two sizes on the same machine was
considered for it and left out: at the tens of microseconds a headless frame takes, it is noise
on a shared runner, and this repository already has a timing test that fails under load.

Time on the graphics card is not counted either: a command is one command whatever it costs to
fill. The report measures it.

## 4. Time is a report, from the weakest machine of the lab

The demo's bench (`--bench on|off`: a form of 750 widgets, at rest, animating, scrolling) is run
by hand on the weakest guest of the lab and fails nothing. Measured 2026-10-12 on the Ubuntu
guest, two cores, 4 GB, software rendering forced (`LIBGL_ALWAYS_SOFTWARE=1`), X11:

| | Partial rendering | Full repaint |
|---|---|---|
| First frame on screen | 0.49 to 0.70 s | 0.55 s |
| Frames in the last 2 s at rest | 0 | not read |
| Processor at rest | 1.3 to 1.7 % | 3.9 % |
| Animation, ten bars and a label at 60 Hz | 56 to 59 frames/s, 0.9 to 1.4 ms a frame, 7 to 10 % | 64 frames/s, 1.0 ms, 29 % |
| Scrolling the form | 46 to 50 frames/s, 1.1 to 1.3 ms a frame, 47 to 53 % | 48 frames/s, 1.4 ms, 42 % |

The window is repainted about twenty times in the two seconds after it shows, while the desktop
places it, and not once after: the bench now reports the second half of its rest apart
(`idle_paints_last_2s`), which is the number that says whether a screen rests.

The macOS guest, four cores and 8 GB, has no graphics card at all and draws every pixel of a
display at twice the density on the processor. Its first run was thrown away: its session was
locked, and a locked session paints nothing. Measured the same day with the session open:

| | Partial rendering | Full repaint |
|---|---|---|
| First frame on screen | 1.76 s | 1.69 s |
| Frames at rest, four seconds | 3 | 2 |
| Animation, ten bars and a label at 60 Hz | 64 frames/s, 0.2 ms a frame, 12 % | 11 frames/s, 85 ms a frame, 25 % |
| Scrolling the form | 6 frames/s, 84 ms a frame, 25 % | 5 frames/s, 90 ms a frame, 25 % |

Partial rendering is what keeps a small change at full rate on that machine, and a scroll, which
repaints the whole form, is filled at six frames a second with it or without: the cost there is
pixels, which no count of §1 sees and no change in the toolkit's own work would move. It is the
case the owner's open decision about drawing at single density under software rendering on
macOS is about.

## 5. Consequences

- A change that makes a frame dearer fails `check` on any machine, with the count and the
  ceiling in the message. A ceiling is raised only for growth that was meant, where it is
  written.
- The shared rows engine of ADR 055 is measured: the three lists cost the rows on screen.
- A test of an application can hold its own screens to the same: `takeFrameRequests()` for
  rest, `scene.metrics().paintedWidgets()` for a frame, `AllocationProbe` for bytes.

## Note, 2026-10-25: two construction checks and the hostile SVGs are counted too

Two tests still held the toolkit's work to a clock, and both failed on a loaded machine with
nothing changed. `SurfaceBudgetTest` held the shader IR's checks of a chain of a thousand loops
and of a nest two hundred deep to two seconds each; `SvgHostileCorpusTest` held the refusal of
each hostile file to one (1,235 ms once, the count that refuses it in place).

- **Nodes walked.** Every walk of the shader IR in its package (the checks of a loop and of a
  surface, hashing and comparing) takes a node's children from one method, and a test asks
  `Loops.walked` how many it took while its code ran, a count kept on that thread only while one
  asks. Each loop of the chain and of the nest is held, as it is built, to a multiple of what it
  holds: fourteen nodes for each link of the chain it ends (it walks twelve), sixteen for each
  loop under it and state the innermost adds up (at most 13.5). The constructions those tests
  were written against walked what a loop holds once again for every loop under it, and fail at
  the fourth and the third loop, in milliseconds, where they took 40 s and 11.8 s. The counts
  were the same with the virtual machine's compiler off, 24 s for what takes 0.6.
- **The count that refuses.** Each hostile file that spent the SVG reader without bound is held
  to being refused by the count of its kind of work, named in the refusal: var() substitution's
  writing, the steps of working out a SMIL chain or of matching a sheet, a selector's compounds,
  a sheet's selectors.

What §3 declares stays so. The SVG tests that read a crafted file within seconds guard the cost
of a step that is charged as one, which only a clock sees, and keep their bounds.
