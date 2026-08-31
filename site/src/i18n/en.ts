/**
 * The source of truth for every string on the site. A key exists here before it exists
 * anywhere else, and a key that is missing here fails the build rather than reaching a
 * page. The toolkit's own `I18nString` guarantees a raw key never renders, and the site
 * advertising it must not be weaker than the thing it advertises.
 *
 * Keys are one namespace, prefixed by the page that owns them.
 *
 * The groups that are not written out here (the top bar's labels and the privacy prompt's
 * text) are spread in from `shared.mjs`, because the Javadoc injector is a plain Node
 * script that renders both and cannot read this file. The note there explains the trade.
 */
import { SHARED_EN } from "./shared.mjs";

export const en = {
  ...SHARED_EN,

  "site.tagline": "A UI toolkit for desktop Java.",

  "nav.licence": "Licence",
  "nav.privacy": "Privacy",
  "nav.repository": "GitHub",
  "nav.skipToContent": "Skip to content",
  "footer.linksLabel": "Project links",

  "codeBlock.copy": "Copy",
  "codeBlock.copied": "Copied to the clipboard",

  "language.label": "Language",

  // ------------------------------------------------------------------- home
  "home.title": "Limn: a UI toolkit for desktop Java",
  "home.description":
    "Build desktop applications in Java with your own widgets, layout, text, charts, media and 3D. One dependency, JDK 17, Windows, macOS and Linux. Apache-2.0.",

  "home.hero.eyebrow": "Desktop UI for Java",
  "home.hero.headline": "Desktop apps in Java, drawn from scratch.",
  "home.hero.sub":
    "Limn draws its own pixels. Widgets, layout, text, charts, media and a 3D viewport, in one dependency, with no Swing, no JavaFX and no native toolkit underneath.",
  "home.hero.cta": "Get started",
  "home.hero.secondary": "Browse the components",
  "home.hero.meta": "JDK 17 · Windows, macOS, Linux · Apache-2.0",
  "home.hero.caption": "The demo application, rendered by Limn during this build.",

  "home.install.eyebrow": "Five minutes",
  "home.install.heading": "One dependency and a main method",
  "home.install.body":
    "No markup language, no annotation processor, no build plugin. Add the backend, which brings the toolkit with it, write plain Java, and you have a window.",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "On macOS the JVM needs <code>-XstartOnFirstThread</code>. It is the one platform quirk you will meet on day one, so it is here rather than three clicks deep.",
  "home.install.more": "Read the install guide",

  "home.features.eyebrow": "What you get",

  "home.features.components.heading": "A component set you do not have to build",
  "home.features.components.body":
    "Buttons, fields, lists, tabs, menus, dialogs, split panes, a colour picker, bar, line and donut charts, and a virtualized list where a million rows cost what twenty do. Every one of them reads its colour, its shape and its density from the theme, so nothing is hardcoded and a compact mode is one line.",
  "home.features.components.link": "See all of them",

  "home.features.layout.heading": "Layout that fits in your head",
  "home.features.layout.body":
    "Four widgets and one marker: a column stacks, a row spreads, a stack overlays, padding insets, and Expanded says who takes the space that is left. That is the whole vocabulary; there is no constraint solver to configure and no layout manager to install.",
  "home.features.layout.link": "Read the layout guide",
  "home.features.layout.caption": "A window built from a column, a row, a split and one Expanded.",

  "home.features.forms.heading": "Forms without a framework",
  "home.features.forms.body":
    "A field is a widget, a validation rule is a listener, and submitting is a method call. Nothing to bind, nothing to register, and validation states that recolour a field the moment the user fixes it.",
  "home.features.forms.link": "Read the forms guide",
  "home.features.forms.caption": "Captions, validation, a choice and the actions row.",

  "home.features.media.heading": "Video and 3D are widgets too",
  "home.features.media.body":
    "A physically-based 3D viewport and a video player, composited as ordinary widgets: a scroll view clips them, a stack draws over them, and they take part in layout like a label does.",
  "home.features.media.link": "Read the media guide",
  "home.features.media.caption": "The 3D viewport, composited into an ordinary window.",

  "home.themes.heading": "Your identity, not the toolkit's",
  "home.themes.body":
    "Your application should look like your product, not like the library it was built with. A theme is plain data (every colour, the corner radius, the size step every control inherits), and one call swaps it at runtime. Load the typeface your brand ships from a file you control, and none of the toolkit's own look survives.",
  "home.themes.link": "How theming works",
  "home.themes.caption":
    "One screen, seven themes. The code behind every strip is identical.",
  "home.themes.alt":
    "The same dense screen of controls rendered seven times side by side, each strip in a different palette, size step and typeface.",

  "home.languages.heading": "In your users' languages",
  "home.languages.body":
    "Text is measured with the same advances it is drawn with, and the font fallback runs per character, so Latin, Greek, Cyrillic and CJK mix in one string without you choosing a face. Input methods compose inside the field, and editing moves by grapheme cluster, so combining marks and multi-part emoji are never split.",
  "home.languages.alt":
    "The same screen captured in Japanese, Simplified Chinese, Korean and Russian, quilted into one window.",
  "home.languages.link": "Read the text guide",
  "home.languages.caption": "The same screen, captured in four languages during this build.",

  "home.limits.eyebrow": "Before you commit",
  "home.limits.heading": "What Limn does not do",
  "home.limits.body":
    "Every toolkit trades something. These are the trades, stated up front, because finding them out in week three is worse than reading them now.",
  "home.limits.scripts.heading": "Text is shaped; layout is not mirrored",
  "home.limits.scripts.body":
    "Arabic, Hebrew, Devanagari and Thai join, reorder and place their marks wherever text is drawn — inside Label, TextField and TextArea, and on every button, tab, menu item and placeholder around them — and the ar and he bundles ship. What a right-to-left language does not get is the layout: insets, alignment, which side a scrollbar sits on, where a popup opens and which way an arrow key moves outside a text field all read left to right whatever the language.",
  "home.limits.a11y.heading": "No screen-reader bridge",
  "home.limits.a11y.body":
    "Keyboard navigation and focus rings are complete, but nothing is exposed to the platform's accessibility APIs. If a screen reader has to work, this is not the toolkit for that application yet.",
  "home.limits.version.heading": "Pre-1.0",
  "home.limits.version.body":
    "The API still moves between releases, and OpenGL is the only rendering path. Pin your version and read the release notes.",

  "home.closing.heading": "A window on screen in five minutes",
  "home.closing.body":
    "The install guide ends with a running program. Everything after that is the guide, the component gallery and the API reference.",

  // ------------------------------------------------------------- components
  "components.title": "Limn: Components",
  "components.description":
    "Every Limn component, rendered by the toolkit itself in both palettes, beside the code that produced each picture.",
  "components.eyebrow": "The set",
  "components.heading": "Components",
  "components.lede":
    "Every picture here was rendered by the toolkit during this build, and every snippet is the code that produced the picture beside it.",
  "components.filterLabel": "Filter components",
  "components.filterPlaceholder": "Filter…",
  "components.empty": "Nothing matches that.",
  "components.showCode": "Code",
  "components.play": "Play",
  "components.stop": "Stop",
  "components.videoNote":
    "The video view uses the pure-Java test source, so it shows the widget working rather than codec coverage. No native decoder is involved in this picture.",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn: Showcase",
  "showcase.description":
    "Whole screens rendered by the toolkit: the demo application, the 3D viewport, a form, a laid-out window, and the same screen in four languages.",
  "showcase.eyebrow": "Whole screens",
  "showcase.heading": "Showcase",
  "showcase.lede":
    "Not crops and not mock-ups. Each of these is a window the toolkit rendered while this site was being built.",
  "showcase.kitchen.heading": "The demo application",
  "showcase.kitchen.body":
    "Every component in one window, with a menu bar, tabs, a theme picker and a live performance footer.",
  "showcase.forms.heading": "A form",
  "showcase.forms.body":
    "Captions, a validated field, a choice, a switch and the actions row: the worked example from the forms guide.",
  "showcase.layout.heading": "A window laid out",
  "showcase.layout.body":
    "A toolbar, a sidebar beside a content pane, and a status line: the worked example from the layout guide.",
  "showcase.threeD.heading": "The 3D viewport",
  "showcase.threeD.body":
    "Physically-based materials under three lights, rendered to a linear high-dynamic-range target and composited as a 2D layer. A scroll view clips it like any other widget. Drag to orbit it, scroll to zoom.",

  "showcase.editor.heading": "The theme editor, as a widget you can ship",
  "showcase.editor.body":
    "A palette is data, so editing one is a screen; this one is a module your application can embed, not a tool that lives in our repository. Drag the corner slider and the window re-skins in the same frame: every field, button and well in the picture. The report beside it measures each ink against every surface it can land on, so a palette that fails contrast fails visibly.",
  "showcase.density.heading": "Every size step",
  "showcase.density.body":
    "The same five controls, five times, from XSMALL at the top down to XLARGE at the bottom. Not one of them is given a width, a font or a padding: each row is told a control size and nothing else, and the padding, the type, the corner radii and the hit targets all move together.",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn: Licence",
  "licence.description":
    "Apache-2.0, what the bundled components are licensed under, and an honest statement of the FFmpeg situation.",
  "licence.eyebrow": "Terms",
  "licence.heading": "Licence",
  "licence.lede":
    "Apache License 2.0, including an explicit patent grant. Commercial use, modification and redistribution are all permitted.",
  "licence.core.heading": "The toolkit itself",
  "licence.core.body":
    "<code>limn-toolkit</code> has no dependencies beyond the JDK, so for it Apache-2.0 is the whole story. The rendering backend adds LWJGL, which is BSD-3-Clause.",
  "licence.fonts.heading": "Fonts",
  "licence.fonts.body":
    "Roboto and the Noto fallback faces ship under the SIL Open Font License. Every bundled component is listed with its licence in the project's NOTICE file.",
  "licence.mp3.heading": "MP3 decoding is LGPL",
  "licence.mp3.body":
    "MP3 support comes from JLayer, which is LGPL-2.1 and is kept as an isolated jar behind the audio decoder interface. Exclude that one dependency if your distribution needs to avoid LGPL obligations; WAV and Ogg Vorbis keep working.",
  "licence.ffmpeg.heading": "FFmpeg video, and what ships with it",
  "licence.ffmpeg.body":
    "The optional H.264 decoder links a trimmed FFmpeg dynamically, built as LGPL-2.1-or-later. <b>Its native libraries ride in one classifier per desktop target, and in <code>natives-all</code> for a bundle that ships everywhere</b>, so a distribution that includes <code>limn-video-ffmpeg</code> is distributing FFmpeg, and the jar carries the licence text and the notice that requires. They are dynamically linked and replaceable, which is what that licence asks. Nothing else depends on this module: leave it out and every other media format keeps working.",
  "licence.notAdvice": "None of this is legal advice. Read the licences, and ask your own counsel.",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn: Privacy",
  "privacy.description":
    "What this site stores, what it does not, and how to change your choice. No cookies and no third-party requests until you allow measurement, which arrives switched off.",
  "privacy.eyebrow": "Privacy",
  "privacy.heading": "What this site stores",
  "privacy.lede":
    "Short version: with measurement off, which is how it arrives, there are no cookies, no third-party requests and nothing that identifies you. Allow it and the site loads Google Analytics, and only then. The long version is below, because a short version is only worth reading if the long one agrees with it.",
  "privacy.storage.heading": "Three values, in your browser",
  "privacy.storage.body":
    "The theme you pick is stored under <code>starlight-theme</code>, the language you pick under <code>limn-language</code>, and your answer to the privacy prompt under <code>limn-consent</code>. All three live in this browser's local storage, all three are read only by this site's own scripts, and clearing site data removes them. Everything on the site works with all three absent.",
  "privacy.language.heading": "How your language is chosen",
  "privacy.language.body":
    "Arriving at an English page, the site reads the languages your browser already advertises to every site you visit, and if one of them is published here it sends you to that translation. That list is read once, in your browser, to pick a URL: it is not stored and not transmitted. Choosing a language in the header is what records a choice, and from then on that choice is used instead of the browser's list. A translated address is never redirected away from, so a link someone sends you opens in the language they sent.",
  "privacy.cookies.heading": "Cookies only if you allow measurement",
  "privacy.cookies.body":
    "The site itself sets no cookie of any kind: with measurement off, nothing is attached to a request and nothing follows you to another site. Allow it and Google Analytics sets its own, <code>_ga</code> and <code>_ga_…</code>. Local storage is not a cookie: it is never transmitted, and a server cannot ask for it.",
  "privacy.analytics.heading": "Measurement, off until you allow it",
  "privacy.analytics.body":
    "The site uses Google Analytics, and only with your permission. The measurement switch in the privacy prompt is off by default, and that off is enforced rather than promised: the tag is shipped as a <code>text/plain</code> block, which no browser executes, and is turned into a running script at the moment you allow it and not before. Withdraw the permission and it is never loaded again.",
  "privacy.thirdParty.heading": "Nothing loaded from anywhere else, until you allow measurement",
  "privacy.thirdParty.body":
    "Every font, image, stylesheet and script comes from this domain. No web font service, no CDN, no embedded video and no social widget: with measurement off, reading a page here contacts exactly one server. Allow it and the tag is fetched from <code>googletagmanager.com</code> as well.",
  "privacy.hosting.heading": "What the host can see",
  "privacy.hosting.body":
    "The pages are static files on a hosting service. Like any web server, it can see the request itself (an IP address, the page asked for, the browser's user agent), and its own logging policy governs that. The project runs no server, no account system and no database, and receives none of it.",
  "privacy.change.heading": "Changing your answer",
  "privacy.change.body":
    "Your choice can be changed at any time, and it takes effect immediately. The same link is in the footer of every page.",
  "privacy.change.action": "Change your privacy choices",
  "privacy.noScript":
    "This button needs JavaScript. With scripting off, nothing optional runs in the first place, and clearing this site's data in your browser removes all three stored values.",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn: page not found",
  "notFound.eyebrow": "404",
  "notFound.heading": "That page does not exist",
  "notFound.body":
    "The link may be out of date, or the page may have moved. Everything the site has is one of these.",
  "notFound.home": "Go to the home page",
  "notFound.destinationsLabel": "Where to go instead",
  "notFound.components.heading": "Components",
  "notFound.components.body": "Every widget, rendered, with the code that produced each picture.",
  "notFound.showcase.heading": "Showcase",
  "notFound.showcase.body": "Whole screens the toolkit rendered while this site was built.",
  "notFound.docs.heading": "Documentation",
  "notFound.docs.body": "Install, layout, forms, theming and shipping: the guide.",
  "notFound.api.heading": "API reference",
  "notFound.api.body": "Every class and method, generated from the source.",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn: this page moved",
  "moved.eyebrow": "Moved",
  "moved.heading": "This page has a new home",
  "moved.body": "Getting started is part of the guide now. Taking you there…",
  "moved.link": "Go to the install guide",
} as const;

export type MessageKey = keyof typeof en;
