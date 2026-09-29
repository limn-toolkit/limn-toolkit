# ADR 047: The system's appearance and motion preferences reach the application

- **Status:** Accepted and implemented, 2026-09-29. Written as a proposal before 0.8.0 at the
  owner's request. §0's readings were taken on the four guests on 2026-09-29, and the owner answered
  §4 the same day, taking all three proposals. Built as `SystemPreferences` and `Motion`, with a
  reader in each backend (`MacSystemPreferences`, `WindowsSystemPreferences`,
  `PortalAppearanceWatch`). §5's live reading was taken the same day: with a reader running, each
  preference was turned on and off on each guest. Fedora, Ubuntu and Windows heard all six changes;
  the Mac guest heard the appearance and, in the owner's Display panel, motion and contrast; each
  change arrived once. The demo followed the Mac's appearance on screen.
- **Date:** 2026-09-23, decided 2026-09-29
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

The table above is what the platforms document. What was read on 2026-09-29 corrects three of its
cells. On each guest a watcher logged what the route answered and which signal arrived while each
preference was turned on and back off. The probes and their logs are kept outside the repository.

| Guest | Appearance | Reduced motion | Increased contrast | Change signal |
| --- | --- | --- | --- | --- |
| Ubuntu 24.04 GNOME, portal 1.18.4 | `org.freedesktop.appearance` `color-scheme`: 1 dark, 2 light, **0 no preference, which is Ubuntu's default** | **no portal key**; `org.gnome.desktop.interface` `enable-animations`, read through the same portal | `org.freedesktop.appearance` `contrast` 1 (turning it on also switches `gtk-theme` to HighContrast) | `SettingChanged`, each change **twice** (two portal backends) |
| Fedora 44 KDE, portal 1.22.1 | `color-scheme` 1 or 2 | `org.freedesktop.appearance` `reduced-motion` 1 when the animation speed is instant (`AnimationDurationFactor` 0) | Plasma has no such setting; `contrast` stays 0 | `SettingChanged` |
| Windows 11 | `AppsUseLightTheme`, 0 dark and 1 light | `SPI_GETCLIENTAREAANIMATION` false | `SPI_GETHIGHCONTRAST` `HCF_HIGHCONTRASTON`. It turns `SystemUsesLightTheme` to 0 but **leaves `AppsUseLightTheme` at 1**, so contrast is not implied by the scheme | `WM_SETTINGCHANGE`: `ImmersiveColorSet` for the scheme, wParam `0x1043` for animation, `0x43` for contrast, reaching a hidden top-level window |
| macOS 27 | `NSApp.effectiveAppearance`, Aqua or DarkAqua | `accessibilityDisplayShouldReduceMotion` | `accessibilityDisplayShouldIncreaseContrast` | KVO on `effectiveAppearance` fires for **all three**; `AppleInterfaceThemeChangedNotification` and `NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification` arrive from the panel |

How each was flipped matters, and is recorded. On Linux the flips went through `gsettings` and
KDE's own tools with `--notify`. On Windows the registry was written and `ImmersiveColorSet`
broadcast, as the Settings app does; the system sent that same broadcast by itself when high
contrast came on. The two `SystemParametersInfo` calls were made with `SPIF_SENDCHANGE`. On macOS
the appearance was set through System Events' appearance preferences, and reduced motion and
increased contrast were turned on and off by the owner in the Display panel. Two routes were tried
and rejected. SkyLight's `SLSSetAppearanceThemeLegacy` changes the appearance and fires KVO, but
posts no distributed notification. `libAccessibility`'s `_AXSSetReduceMotionEnabled` stores the
value, but `NSWorkspace` never sees it and nothing is posted. So a test cannot drive macOS's
accessibility flags from outside the panel.

The backend already has the three routes this needs: the Objective-C runtime on macOS (the
accessibility bridge), Win32 calls through LWJGL on Windows, and a D-Bus client on Linux (the AT-SPI
bridge). No new native library is needed.

## 1. Decision

### 1.1 One observable axis, fed by the backend

The preferences are a process-wide fact, like the language and the control size, so they take the
same shape: a value the toolkit holds, read with an accessor and watched with
`observeChanges(Runnable)`, which returns a `Subscription`. It is `limn.scene.SystemPreferences`, a
record beside `ControlSize` and `LayoutDirection`, the other process-wide axes.

```java
SystemPreferences p = SystemPreferences.current();
p.colorScheme();       // LIGHT, DARK, or NO_PREFERENCE
p.reduceMotion();      // true when the user asked for less motion
p.increaseContrast();  // true when the user asked for more contrast
SystemPreferences.observeChanges(() -> ...);
```

A backend reads the platform and calls `SystemPreferences.report(…)` on the UI thread, at start
and whenever the platform signals. The toolkit keeps the value and tells its listeners only when it
changed: one flip is two portal signals on Ubuntu, and on macOS KVO fires for every flag. A backend
that cannot read a preference reports `NO_PREFERENCE` or `false`. The headless backend reports
nothing, which leaves `SystemPreferences.NONE`, so tests stay deterministic. A test that wants a
preference reports it itself. This replaces the sketch's slot in `BackendServices`, which would
have been a pull reader and a second listener list for something the backend already pushes.

The routes follow §0's readings. macOS observes `effectiveAppearance` by KVO and also listens for
the accessibility display notification; the appearance is dark when its name says so. Windows reads
`AppsUseLightTheme` (absent is `NO_PREFERENCE`) and the two SPI values, and a hidden top-level
window of the backend's hears `WM_SETTINGCHANGE`. Linux reads the portal's `ReadAll` on its own
session connection and follows `SettingChanged`. Reduced motion comes from
`org.freedesktop.appearance` `reduced-motion` where the portal has it, and otherwise from
`org.gnome.desktop.interface` `enable-animations`.

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

This is the one change in behaviour, and the owner took it (§4). One helper,
`limn.animation.Motion.duration(seconds)`, answers zero while motion is reduced. `Transition`
reads it, and so do the popups' and dialogs' fades, which run on tickers of their own, so a menu,
a list, a date picker or a dialog opens and closes at once. The two animations that say work is
still going on, the progress bar's indeterminate sweep and the tree's loading spinner, **step**:
they move four times a second instead of every frame.

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

## 4. The owner's answers, 2026-09-29

1. §1.3 goes in: the toolkit's transitions honour reduced motion by default.
2. `NO_PREFERENCE` is a value of its own. Ubuntu's default answers it (§0), so it was not
   hypothetical.
3. The demo follows the system appearance by default and keeps its theme picker, which takes over
   once the user picks.

## 5. Verification, when built

- A headless test per preference: the value installed, the change delivered once on the UI thread,
  the subscription cancelled.
- A `Transition` test: with reduced motion on, a transition's first frame is its last.
- A live reading on each guest: flip the setting in the desktop's panel with the demo open, and see
  the change arrive, recorded in this record's amendments.
