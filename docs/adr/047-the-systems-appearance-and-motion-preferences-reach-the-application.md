# ADR 047: The system's appearance and motion preferences reach the application

- **Status:** Proposed, 2026-09-23. Written before 0.8.0 at the owner's request, to be decided and
  built after it. Nothing here is implemented; §0 lists what has to be read on each guest before any
  of it is.
- **Date:** 2026-09-23
- **Scope:** three preferences a user sets for every application on their desktop — a dark or light
  appearance, reduced motion, and increased contrast — and how they reach a Limn application: what
  each backend reads, what the toolkit exposes, and what the toolkit's own widgets do with them.
- **Compatibility:** additive. No existing call changes meaning; an application that ignores the new
  axis behaves as today, except that the toolkit's own transitions honour reduced motion (§1.3),
  which the owner may reject separately.

## 0. What is known, and what must be measured first

The accessibility guide says it plainly: the system accessibility settings are not surfaced to the
toolkit. An application that wants to follow the desktop's dark mode, or to stop animating for a user
who asked the system for less motion, has to write native code per platform. GLFW exposes neither
preference, so the backend has to read each platform directly, through the routes it already owns.

| Platform | Appearance | Reduced motion | Increased contrast | Change signal |
| --- | --- | --- | --- | --- |
| macOS | `NSApp.effectiveAppearance` (Aqua or Dark Aqua) | `NSWorkspace.accessibilityDisplayShouldReduceMotion` | `accessibilityDisplayShouldIncreaseContrast` | `AppleInterfaceThemeChangedNotification`; `NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification` |
| Windows | `AppsUseLightTheme` under `HKCU\...\Themes\Personalize` | `SPI_GETCLIENTAREAANIMATION` | `SPI_GETHIGHCONTRAST` | `WM_SETTINGCHANGE` (`ImmersiveColorSet`, and the SPI change) |
| Linux | portal setting `org.freedesktop.appearance` `color-scheme` | a portal key or `org.gnome.desktop.interface` `enable-animations` | portal `contrast` | the portal's `SettingChanged` signal |

Every cell above is what the platform documents, not what was read. Before this record is accepted,
each has to be read on the guests the accessibility work already uses, the way every constant in
ADR 039 was: flip the setting in the desktop's own panel and record what the route answers and which
signal arrives. The Linux reduced-motion row is the least certain: whether the portal carries a key
for it, and from which version, is the first thing to read; GNOME's `enable-animations` is the
fallback a GNOME session certainly has.

The backend already has the three routes this needs: the Objective-C runtime on macOS (the
accessibility bridge), Win32 calls through LWJGL on Windows, and a D-Bus client on Linux (the AT-SPI
bridge). No new native library is needed.

## 1. Decision (proposed)

### 1.1 One observable axis, fed by the backend

The preferences are a process-wide fact, like the language and the control size, so they take the
same shape: a value the toolkit holds, read with an accessor and watched with
`observeChanges(Runnable)`, which returns a `Subscription`.

```java
SystemPreferences p = SystemPreferences.current();
p.colorScheme();       // LIGHT, DARK, or NO_PREFERENCE
p.reduceMotion();      // true when the user asked for less motion
p.increaseContrast();  // true when the user asked for more contrast
SystemPreferences.observeChanges(() -> ...);
```

A backend installs the reader through `BackendServices`, as it installs everything else, and calls
back on the UI thread when the platform signals a change. A backend that cannot read a preference
reports `NO_PREFERENCE` or `false`, which is also what the headless backend reports, so tests stay
deterministic.

### 1.2 The toolkit does not switch the theme by itself

Which palette to use is the application's decision. A dark system does not mean every application
wants a dark window, and the theme an application ships may be its brand. The toolkit reports; the
application follows if it wants to, in one line:

```java
SystemPreferences.observeChanges(() -> pick(SystemPreferences.current()).apply(scene));
```

The theming guide shows that line, and the demo follows the system by default so the behaviour is
visible.

### 1.3 The toolkit's own motion honours reduced motion

A `Transition` finishes in one frame while `reduceMotion()` is true: a hover, a focus ring, a
fade, a popup's opening. That is what native toolkits do, and the user asked for it for every
application. An application animation built on `Transition` gets it without a line; one that drives
its own clock reads `reduceMotion()`. A widget whose motion carries information, a progress sweep or
an indeterminate spinner, keeps it but slows or steps it, and its own Javadoc says which.

This is the one change in behaviour, and it is separable: the owner may decide the toolkit only
reports and leaves every transition as it is.

### 1.4 Increased contrast is reported, not acted on

The toolkit ships a High Contrast palette, and `ThemeAudit` holds every shipped palette to contrast
bars. Whether a system that asks for more contrast should move an application onto that palette is
the application's choice, as in §1.2.

## 2. What it costs

- Three backend readers and three change listeners, each small, each verified live on its guest.
- One public type in the toolkit, one new service slot in `BackendServices`, and a headless answer
  in `limn-test`.
- `Transition` reads one flag per animation start.
- The theming and accessibility guides each gain a section; the accessibility guide's sentence
  that these settings are not surfaced goes.

## 3. What this is not

- Not a system text scale. A desktop's text-size setting interacts with control sizes and layout,
  and deserves its own record.
- Not an accent colour. The portal and macOS offer one; following it is a theming question, not a
  preference the toolkit should report before an application can use it.
- Not a per-window setting: every platform above reports these for the whole session.

## 4. Open questions for the owner

1. Whether §1.3 goes in: the toolkit's transitions honouring reduced motion by default.
2. Whether `NO_PREFERENCE` is worth a value of its own, or `colorScheme()` answers only light or
   dark, with light where the platform says nothing.
3. Whether the demo follows the system appearance by default, or keeps its own theme picker as the
   only control.

## 5. Verification, when built

- A headless test per preference: the value installed, the change delivered once on the UI thread,
  the subscription cancelled.
- A `Transition` test: with reduced motion on, a transition's first frame is its last.
- A live reading on each guest: flip the setting in the desktop's panel with the demo open, and see
  the change arrive, recorded in this record's amendments.
