# Architecture decision records

One file per decision, numbered in the order the decisions were taken and never renumbered. A
record is dated, decided once and read once: when a later decision changes it, the later record
says so in its own status line and the earlier one gains a note pointing forward, so the text that
was decided against stays readable as the reason it was. What belongs here rather than in a Javadoc
or a design note is [`docs/design/README.md`](../design/README.md)'s table.

The status column below is each record's own status line, shortened; the last column is where a
record's open items were closed, or which later record replaced part of it. A dash means nothing
has changed it yet.

| # | Title | Status | Closed or superseded by |
| --- | --- | --- | --- |
| [001](001-backend.md) | Native backend: LWJGL 3 (GLFW + OpenGL 3.3 core + stb) | Accepted, 2026-07-05 | — |
| [002](002-control-sizes.md) | Control sizes: an inherited, per-widget size axis | Accepted, 2026-07-29; implemented | The host link it names `setControlSizeHost` became `setInheritanceHost` when [032](032-layout-mirroring.md) gave the chain a second axis; the deprecated name was removed in 27b3a4e |
| [003](003-gles-angle.md) | GL ES 3.0 as the authored profile, ANGLE as the portable context | Proposed; deferred, not started | The Wayland track its §9.1 scheduled first landed on its own as [028](028-a-popup-on-wayland-is-drawn-in-the-window-that-owns-it.md) |
| [004](004-hdr-render-target.md) | The 3D target is linear HDR, and the tonemap moves to the composite | Accepted, 2026-08-01; implemented | — |
| [005](005-bloom.md) | Bloom belongs to the 3D pass, not to the composite | Accepted, 2026-08-01; implemented | — |
| [006](006-i18n.md) | i18n: a localizable string is a value the widget holds | Accepted and implemented, revision 2; §2.4 (Spinner's locale-aware entry) deliberately not delivered | §4's deferrals, one by one: shaping and bidi by [031](031-a-run-of-text-is-shaped-once-and-the-widget-holds-the-result.md), mirroring by [032](032-layout-mirroring.md), non-ASCII digits by [033](033-a-number-is-localized-when-it-is-formatted.md), collation and case mapping by [034](034-order-and-case-are-facts-about-a-language.md), per-subtree locale by [035](035-the-locale-is-a-property-of-the-subtree.md); what is still open is in [`docs/design/i18n.md`](../design/i18n.md) |
| [007](007-planar-video-upload.md) | Video crosses the SPI as planar YCbCr, and the matrix runs on the device | Accepted, 2026-08-03; implemented | — |
| [008](008-video-decoders-live-in-their-own-module.md) | Decoders live in their own module, the application installs them, and a stream may be described rather than located | Accepted, 2026-08-03; superseded in part | [030](030-the-toolkit-carries-the-widgets-and-the-native-decoder-carries-its-own-platform.md) moved `limn-video` into `limn-toolkit`; [027](027-a-codec-this-repository-cannot-encode-needs-a-file-it-did-not-make.md) replaced §5's "no media file is committed" rule |
| [009](009-the-video-view-paces-and-borrows.md) | The view paces its own pictures, and it borrows the stream rather than owning it | Accepted, 2026-08-03; implemented | — |
| [010](010-the-player-owns-a-thread-and-borrows-two-streams.md) | The player owns a thread and a ring, borrows the video, and takes the audio | Accepted, 2026-08-04; implemented | — |
| [011](011-ffmpeg-licence-and-linking.md) | LGPL-2.1, linked dynamically, and what a distributed decoder owes that an operating system's does not | Accepted, 2026-08-04; implemented | — |
| [012](012-the-shim-owns-the-pictures-and-the-container-carries-both-tracks.md) | The native lives in a module of its own, the shim owns the pictures, and a container type carries the second track | Accepted, 2026-08-04; implemented | [037](037-the-native-payload-is-an-artifact-and-versions-with-ffmpeg.md) took the native payload out of the repository; the module, the shim's ownership rules and the container type stand |
| [013](013-a-seek-moves-the-timeline-and-the-soundtrack-follows-it.md) | A seek moves the timeline, the soundtrack follows it, and the clock is told rather than left to infer | Accepted, 2026-08-04; implemented | — |
| [014](014-hardware-decode-is-a-seam-and-not-a-flag.md) | Hardware decode is a seam, and on one platform the seam is now cut | Accepted, 2026-08-05; route B implemented on macOS, the fallback everywhere else | — |
| [015](015-codec-breadth-and-how-an-unencodable-codec-is-tested.md) | Which codecs are worth their megabyte, and what evidence there is for one this project cannot encode | Accepted, 2026-08-04; implemented | [027](027-a-codec-this-repository-cannot-encode-needs-a-file-it-did-not-make.md) replaced §4's "no media file is committed" rule |
| [016](016-ten-bits-a-transfer-function-and-which-composite-a-picture-goes-through.md) | Ten bits, a transfer function, and which composite a picture goes through | Accepted, 2026-08-04; implemented | — |
| [017](017-subtitles-are-text-and-timing-and-the-application-draws-them.md) | Subtitles are text and timing, the application asks by position, and nothing is burned into the picture | Accepted, 2026-08-05; implemented | — |
| [018](018-an-image-leaves-through-an-encoder-and-a-readback-names-its-colour-space.md) | An image leaves through an encoder, and a readback names its colour space | Accepted, 2026-08-05; implemented | — |
| [019](019-a-shape-can-be-made-of-what-is-behind-it.md) | A shape can be made of what is behind it | Accepted, 2026-08-05; first step implemented, §7 lists what is not | — |
| [020](020-background-work-is-a-job-with-a-lifecycle.md) | Background work is a job with a lifecycle, and cancelling it stops the delivery rather than the thread | Accepted, 2026-08-06; implemented | — |
| [021](021-opening-a-container-is-not-a-frame-of-work.md) | Opening a container is not a frame of work | Accepted, 2026-08-06; implemented | — |
| [022](022-a-file-chooser-is-another-program.md) | A file chooser is another program, so the frame before it is the one that matters | Accepted, 2026-08-06; implemented | — |
| [023](023-a-posted-task-is-not-a-frame.md) | A posted task is not a frame | Accepted, 2026-08-06; implemented | — |
| [024](024-a-chart-is-a-widget-that-animates-values.md) | A chart is a widget, and what it animates is values | Accepted, 2026-08-10; implemented | — |
| [025](025-a-layout-can-be-contained.md) | A layout can be contained | Accepted, 2026-08-11; implemented | — |
| [026](026-what-a-modal-blocks-and-what-it-leaves-alone.md) | What a modal blocks, and what it leaves alone | Accepted, 2026-08-11; implemented | — |
| [027](027-a-codec-this-repository-cannot-encode-needs-a-file-it-did-not-make.md) | A codec this repository cannot encode needs a file it did not make | Accepted, 2026-08-11 | Supersedes the "no media file is committed" rule of [008](008-video-decoders-live-in-their-own-module.md) §5 and [015](015-codec-breadth-and-how-an-unencodable-codec-is-tested.md) §4 |
| [028](028-a-popup-on-wayland-is-drawn-in-the-window-that-owns-it.md) | A popup on Wayland is drawn in the window that owns it | Accepted, 2026-08-12; implemented | Closes the Wayland defect [003](003-gles-angle.md) §2.3 recorded |
| [029](029-a-palette-is-a-value-and-the-screen-that-builds-one-is-a-module.md) | A palette is a value, and the screen that builds one is a module | Accepted, 2026-08-10; implemented | — |
| [030](030-the-toolkit-carries-the-widgets-and-the-native-decoder-carries-its-own-platform.md) | The toolkit carries the widgets, and the native decoder carries one platform at a time | Accepted, 2026-08-18 | Supersedes the module-boundary half of [008](008-video-decoders-live-in-their-own-module.md) |
| [031](031-a-run-of-text-is-shaped-once-and-the-widget-holds-the-result.md) | A run of text is shaped once, and the widget holds the result | Accepted and implemented, closed 2026-08-31; §7.1 lists what is still not done | §7.1's largest item, mirroring, by [032](032-layout-mirroring.md); collation and case mapping by [034](034-order-and-case-are-facts-about-a-language.md); soft wrap in `TextArea` done 2026-09-01; the four scripts gained their Bold in 63f7869 |
| [032](032-layout-mirroring.md) | Layout mirroring: direction is an inherited axis, not a transform | Accepted and implemented, 2026-08-31; §9 records what the implementation settled | §8's digits by [033](033-a-number-is-localized-when-it-is-formatted.md), collation by [034](034-order-and-case-are-facts-about-a-language.md), per-subtree locale by [035](035-the-locale-is-a-property-of-the-subtree.md); the website mirrored 2026-09-01 (ffc88f1) |
| [033](033-a-number-is-localized-when-it-is-formatted.md) | A number is localized when it is formatted, and nowhere else | Accepted; implemented | Closes [006](006-i18n.md) §4's "non-ASCII digits" |
| [034](034-order-and-case-are-facts-about-a-language.md) | Order and case are facts about a language, and the language is the text's | Accepted; implemented | Closes [006](006-i18n.md) §4's "collation and case mapping" |
| [035](035-the-locale-is-a-property-of-the-subtree.md) | The locale is a property of the subtree, and the pass carries it | Accepted; implemented | Closes [006](006-i18n.md) §4's "per-subtree locale" |
| [036](036-a-font-is-an-artifact-and-its-version-is-the-fonts.md) | A font is an artifact, and its version is the font's | Accepted, 2026-09-01; implemented | — |
| [037](037-the-native-payload-is-an-artifact-and-versions-with-ffmpeg.md) | The native payload is an artifact, and it versions with FFmpeg | Accepted, 2026-09-02; implemented | — |
| [038](038-an-icon-pack-is-an-artifact-and-versions-with-its-icons.md) | An icon pack is an artifact, and it versions with its icons | Accepted, 2026-09-02; implemented | — |
