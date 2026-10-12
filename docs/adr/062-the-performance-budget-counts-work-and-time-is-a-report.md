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

The macOS guest, which has no graphics card at all, was the machine meant for this; its session
was locked, a locked session paints nothing, and its numbers were thrown away.

## 5. Consequences

- A change that makes a frame dearer fails `check` on any machine, with the count and the
  ceiling in the message. A ceiling is raised only for growth that was meant, where it is
  written.
- The shared rows engine of ADR 055 is measured: the three lists cost the rows on screen.
- A test of an application can hold its own screens to the same: `takeFrameRequests()` for
  rest, `scene.metrics().paintedWidgets()` for a frame, `AllocationProbe` for bytes.
