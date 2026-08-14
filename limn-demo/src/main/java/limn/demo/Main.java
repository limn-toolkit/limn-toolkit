package limn.demo;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.concurrent.Ui;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limn UI demo. Scenes: {@code testcard} (shapes, gradients, clip,
 * transforms through the batched SDF canvas) and {@code text} (glyph
 * atlas specimens: size ladder, metrics, kerning, Unicode, gradient/rotated/
 * scaled text), plus {@code --screenshot} with {@code --scale} forcing
 * 1.0/1.25/1.5/2.0 for HiDPI verification.
 */
public final class Main {

    /**
     * Every scene {@code --scene} accepts, in the order the diagnostic lists them.
     *
     * <p>One list, because there were two: this check and the "unknown scene" message each
     * carried their own hand-written copy, and the message's had drifted to about a third of the
     * real set. The README used to carry a third copy and now points here instead.
     */
    private static final java.util.List<String> SCENES = java.util.List.of(
            "kitchen", "kitchen-light", "kitchen-dialog", "kitchen-dialog-inscene",
            "kitchen-loading", "kitchen-toggle", "kitchen-3d", "kitchen-files", "kitchen-video",
            "kitchen-icons", "video", "icons", "icons-light", "icons-search",
            "dialog-decorated", "dialog-opaque", "dialog-glass", "textfield-selected",
            "textfield-ime", "password-ramp", "fonts", "fonts-switched", "ellipsis",
            "textarea-scroll", "textarea-ime", "tabs", "tabs-overflow", "combo-overflow",
            "showcase", "showcase-light", "dialog-open", "forms", "forms-light", "forms-popup",
            "components", "components-light", "widgets", "list", "animations", "cursors",
            "sprites", "audio", "controls", "control-sizes", "control-sizes-audit",
            "newcontrols", "newcontrols-light", "colorpicker", "colorpicker-light", "split",
            "split-light", "split-states", "split-states-light", "perf", "menu", "menu-dark",
            "viewport3d", "viewport3d-light", "gltf", "shadows", "ibl", "debugdraw", "blend3d",
            "normalmap", "bloom", "surface", "testcard", "text", "files", "files-light",
            "export", "export-light", "charts", "charts-light", "theme-editor",
            "theme-editor-light", "glass", "glass-light");

    private static final int LOGICAL_WIDTH = 800;
    private static final int LOGICAL_HEIGHT = 640;

    /** Frames rendered before capturing, so the swapchain is warmed up. */
    private static final int SCREENSHOT_WARMUP_FRAMES = 3;

    private Main() {
    }

    /**
     * Captures one settled state of the split scene into {@code base} with
     * {@code suffix} spliced before the extension. The {@code requestRender} is not
     * decoration: a settled fade leaves nothing damaged, and the frame
     * {@code captureNextFrame} asks for would then read back a stale buffer.
     */
    private static void captureSplitState(limn.scene.Scene scene, NativeWindow window,
                                          java.nio.file.Path base, String suffix) {
        scene.requestRender();
        java.nio.file.Path file =
                java.nio.file.Path.of(base.toString().replace(".png", suffix + ".png"));
        window.captureNextFrame(file);
        System.out.println("Split state screenshot: " + file.toAbsolutePath());
    }

    /** One-line summary of a display for the startup log. */
    private static String describeDisplay(limn.backend.Display d) {
        return String.format("%s [%s]%s: current %s, %d modes, work area %s, scale %.2f",
                d.name(), d.id(), d.isPrimary() ? " (primary)" : "", d.currentResolution(),
                d.availableResolutions().size(), d.workArea(), d.contentScale());
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--gl-info")) {
            // Diagnostic: no scene and no frame, and it exits non-zero where the
            // machine gives no context, so a script can tell without parsing.
            System.exit(GlInfo.run() ? 0 : 1);
        }
        if (args.length > 0 && args[0].equals("--bench")) {
            // Heavy-screen benchmark (Swing twin: scripts/bench/SwingBench.java).
            Bench.run(args.length < 2 || !args[1].equals("off"));
            return;
        }
        if (args.length > 0 && args[0].equals("--gadget")) {
            // Transparent desktop gadget: bouncing, draggable, spinning cube(s).
            if (args.length > 2 && args[1].equals("shot")) {
                CubeGadget.run(args[2]); // one headless frame with alpha, for verification
            } else {
                // --gadget [n] [precise]: n cubes; "precise" starts with the
                // exact silhouette hit-test instead of the grab box ('P' toggles).
                int count = 1;
                boolean precise = false;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("precise")) {
                        precise = true;
                    } else {
                        try {
                            count = Math.max(1, Integer.parseInt(args[i]));
                        } catch (NumberFormatException ignored) {
                            // plain --gadget: one cube
                        }
                    }
                }
                CubeGadget.run(count, precise);
            }
            return;
        }
        DemoOptions options = DemoOptions.parse(args);
        boolean screenshotMode = options.screenshotFile() != null;
        String scene = options.scene().equals("default") ? "kitchen-light" : options.scene();
        if (!SCENES.contains(scene)) {
            System.err.println("unknown scene: " + scene);
            System.err.println("available: " + String.join(", ", SCENES));
            System.exit(2);
        }

        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(new WindowConfig(
                    "Limn UI: Kitchen Sink", LOGICAL_WIDTH, LOGICAL_HEIGHT, !screenshotMode, true));

            float monitorScale = window.contentScale();
            if (options.scale() > 0) {
                // Force the render scale: size the window so the framebuffer is
                // exactly logical * forcedScale device pixels on this monitor.
                window.setSize(Math.round(LOGICAL_WIDTH * options.scale() / monitorScale),
                        Math.round(LOGICAL_HEIGHT * options.scale() / monitorScale));
                window.overrideContentScale(options.scale());
            }

            System.out.printf("HiDPI: contentScale=%.2f (monitor %.2f) | logical=%.0fx%.0f pt | framebuffer=%dx%d px%n",
                    window.contentScale(), monitorScale, window.logicalWidth(), window.logicalHeight(),
                    window.framebufferWidth(), window.framebufferHeight());
            window.setContentScaleListener(newScale ->
                    System.out.printf("Monitor content scale changed to %.2f; re-rendering%n", newScale));

            // Normalized display info (limn.backend.Display/Resolution): the
            // window knows which display it is on, and every display exposes its
            // modes/work area the same way.
            limn.backend.Display currentDisplay = window.display();
            System.out.println("Displays:");
            for (limn.backend.Display d : backend.displays()) {
                System.out.printf("  %s%s%n", describeDisplay(d),
                        d.equals(currentDisplay) ? "  ← this window" : "");
            }

            // The process default is the ROOT of the inheritance chain, so it must be set
            // before any scene is constructed: a scene measures against whatever it resolves
            // to, and a widget that already memoized would need an epoch bump to notice.
            if (options.controlSize() != null) {
                limn.scene.ControlSize.setProcessDefault(options.controlSize());
                System.out.println("Control size: process default = " + options.controlSize());
            }

            // The APPLICATION installs decoders, not the backend, and not the toolkit. Which
            // decoders exist is what an application ships and is licensed for, and the install
            // order is the probe order, so a backend that installed its own would quietly take
            // priority over these.
            VideoScene.installDecoders();

            // Before any scene is constructed, because it decides whether a video pane arms a
            // ticker at all: a frozen pane decodes to one picture and never asks for another frame.
            VideoScene.setInitialSource(options.videoSource());
            if (options.videoFrame() >= 0) {
                VideoScene.setStaticFrame(options.videoFrame());
                System.out.println("Video: frozen on picture " + options.videoFrame());
            }
            // A screenshot must not start an audio device: a player times its pictures by where the
            // soundtrack has reached, so the capture would depend on it and two runs of the same
            // command would differ. Without one the video tab takes the path it shipped with.
            if (options.screenshotFile() != null) {
                VideoScene.setSoundAllowed(false);
            }

            // Set before any scene exists for the same reason: a screenshot must capture
            // the language it was asked for, not the machine's with a relayout on top.
            if (options.locale() != null) {
                limn.i18n.I18n.setLocale(options.locale());
                System.out.println("Locale: " + options.locale().toLanguageTag());
            }

            limn.scene.Scene widgetScene = null;
            limn.components.ComboBox formsCombo = null;
            java.util.function.Supplier<limn.components.Dialog> dialogOpener = null;
            java.util.function.Supplier<limn.components.ComboBox> dialogComboRef = null;
            Runnable loadTrigger = null;
            Runnable afterLayout = null;
            MenuScene.Built menuBuilt = null;
            if (scene.equals("widgets")) {
                widgetScene = WidgetsScene.create();
            } else if (scene.equals("list")) {
                widgetScene = ListScene.create();
            } else if (scene.equals("animations")) {
                widgetScene = AnimationsScene.create();
            } else if (scene.equals("cursors")) {
                widgetScene = CursorsScene.create();
            } else if (scene.equals("sprites")) {
                widgetScene = SpritesScene.create();
            } else if (scene.equals("audio")) {
                widgetScene = AudioScene.create();
            } else if (scene.equals("controls")) {
                widgetScene = ControlsScene.create();
            } else if (scene.equals("control-sizes")) {
                widgetScene = ControlSizesScene.create();
            } else if (scene.equals("control-sizes-audit")) {
                widgetScene = ControlSizeAuditScene.create();
            } else if (scene.startsWith("colorpicker")) {
                widgetScene = ColorPickerScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("split")) {
                widgetScene = SplitPaneScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("newcontrols")) {
                widgetScene = NewControlsScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("viewport3d")) {
                widgetScene = Viewport3DScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("icons")) {
                widgetScene = scene.equals("icons-search")
                        ? IconsScene.create(false, "arrow")
                        : IconsScene.create(scene.endsWith("-light"));
            } else if (scene.equals("gltf")) {
                widgetScene = GltfScene.create(false);
            } else if (scene.equals("shadows")) {
                widgetScene = Viewport3DScene.shadowScene(false);
            } else if (scene.equals("ibl")) {
                widgetScene = Viewport3DScene.iblScene(false);
            } else if (scene.equals("debugdraw")) {
                widgetScene = Viewport3DScene.debugDrawScene(false);
            } else if (scene.equals("blend3d")) {
                widgetScene = Viewport3DScene.blendScene(false);
            } else if (scene.equals("normalmap")) {
                widgetScene = Viewport3DScene.normalMapScene(false);
            } else if (scene.equals("bloom")) {
                widgetScene = Viewport3DScene.bloomScene(false);
            } else if (scene.equals("surface")) {
                widgetScene = Viewport3DScene.customSurfaceScene(false);
            } else if (scene.equals("video")) {
                widgetScene = VideoScene.create(false);
            } else if (scene.startsWith("theme-editor")) {
                widgetScene = ThemeEditorScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("glass")) {
                widgetScene = GlassScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("charts")) {
                widgetScene = ChartsScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("export")) {
                widgetScene = ExportScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("files")) {
                widgetScene = FilesScene.create(scene.endsWith("-light"));
            } else if (scene.equals("perf")) {
                widgetScene = PerfScene.create();
            } else if (scene.startsWith("menu")) {
                menuBuilt = MenuScene.create(!scene.equals("menu-dark"));
                widgetScene = menuBuilt.scene();
            } else if (scene.startsWith("components")) {
                widgetScene = ComponentsScene.create(scene.endsWith("-light"));
            } else if (scene.startsWith("forms")) {
                FormsScene.Built built = FormsScene.create(scene.endsWith("-light"));
                widgetScene = built.scene();
                formsCombo = built.combo();
            } else if (scene.startsWith("showcase") || scene.equals("dialog-open")) {
                ShowcaseScene.Built built = ShowcaseScene.create(scene.endsWith("-light"));
                widgetScene = built.scene();
                dialogOpener = built.openDialog();
            } else if (scene.startsWith("kitchen")) {
                KitchenSinkScene.Built built = KitchenSinkScene.create(scene.equals("kitchen-light"));
                widgetScene = built.scene();
                dialogOpener = built.openDialog();
                dialogComboRef = built.dialogCombo();
                loadTrigger = built.triggerLoad();
                if (scene.equals("kitchen-dialog-inscene")) {
                    afterLayout = built.useInSceneDialogs(); // flip 'Internal' before opening
                } else if (scene.equals("kitchen-toggle")) {
                    afterLayout = built.toggleTheme(); // toggle after first layout, then capture
                } else if (scene.equals("kitchen-3d")) {
                    afterLayout = built.openThreeDTab(); // open the 3D tab, then capture
                } else if (scene.equals("kitchen-files")) {
                    afterLayout = built.openFilesTab(); // open the Files tab, then capture
                } else if (scene.equals("kitchen-video")) {
                    afterLayout = built.openVideoTab(); // open the Video tab, then capture
                } else if (scene.equals("kitchen-icons")) {
                    afterLayout = built.openIconsTab(); // open the Icons tab, then capture
                }
            } else if (scene.equals("textfield-selected")) {
                CaptureScenes.Built built = CaptureScenes.textfieldSelected(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("password-ramp")) {
                CaptureScenes.Built built = CaptureScenes.passwordRamp(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("textfield-ime")) {
                CaptureScenes.Built built = CaptureScenes.textfieldIme(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("fonts")) {
                CaptureScenes.Built built = CaptureScenes.fonts(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("fonts-switched")) {
                CaptureScenes.Built built = CaptureScenes.fontsSwitched(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("textarea-ime")) {
                CaptureScenes.Built built = CaptureScenes.textareaIme(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("ellipsis")) {
                CaptureScenes.Built built = CaptureScenes.ellipsis(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("textarea-scroll")) {
                CaptureScenes.Built built = CaptureScenes.textareaScroll(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("tabs")) {
                CaptureScenes.Built built = CaptureScenes.tabsAlignment(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("tabs-overflow")) {
                CaptureScenes.Built built = CaptureScenes.tabsOverflow(false);
                widgetScene = built.scene();
                afterLayout = built.afterLayout();
            } else if (scene.equals("combo-overflow")) {
                CaptureScenes.ComboBuilt built = CaptureScenes.comboOverflow(false);
                widgetScene = built.scene();
                formsCombo = built.combo();
            } else if (scene.startsWith("dialog-")) {
                limn.backend.WindowStyle style = switch (scene) {
                    case "dialog-decorated" -> limn.backend.WindowStyle.DECORATED;
                    case "dialog-opaque" -> limn.backend.WindowStyle.UNDECORATED_OPAQUE;
                    default -> limn.backend.WindowStyle.UNDECORATED_TRANSLUCENT;
                };
                CaptureScenes.DialogBuilt built = CaptureScenes.dialogWithStyle(style);
                widgetScene = built.scene();
                dialogOpener = built.opener();
            }
            // After the scene, never before: every scene sets the palette it wants while it
            // builds, so an override applied earlier would simply be overwritten. Widgets read
            // the theme as they paint, so replacing it here needs no rebuild, but sizes and
            // typography come from layout, hence the relayout.
            if (options.theme() != null) {
                limn.components.Theme.setCurrent(options.theme());
                if (widgetScene != null) {
                    widgetScene.root().markNeedsLayout();
                    // Widgets read the theme as they paint, but the colour cleared behind them
                    // was copied out of the palette when the scene was built, so it alone stays
                    // stale: a light palette on a dark canvas. Only opaque backgrounds are
                    // replaced: a scene that cleared to transparent did so to let the desktop
                    // through, and that is not a palette choice to override.
                    if (widgetScene.background().a() >= 1f) {
                        widgetScene.setBackground(options.theme().background);
                    }
                }
                System.out.println("Theme: " + options.theme().name);
            }
            if (widgetScene != null) {
                widgetScene.bind(window); // input + invalidation
            }
            limn.scene.Scene boundScene = widgetScene;
            Runnable afterFirstFrame = afterLayout;

            boolean popupCapture = screenshotMode
                    && (scene.equals("forms-popup") || scene.equals("combo-overflow"));
            // Modal fade / async load capture: settle over real time, so we
            // trigger and capture on a timer instead of via warmup frames.
            boolean dialogCapture = screenshotMode && dialogOpener != null
                    && (scene.equals("kitchen-dialog") || scene.startsWith("dialog-"));
            // The same dialog as an overlay, with its dropdown open: the card is drawn
            // inside the host window while the dropdown is a window of its own, owned by
            // that host, the pair a scene modal has to leave alone, or the combo opens
            // into a window that is never shown and never takes a click.
            boolean inSceneDialogCapture = scene.equals("kitchen-dialog-inscene")
                    && screenshotMode && dialogOpener != null && dialogComboRef != null;
            boolean loadingCapture = scene.equals("kitchen-loading") && screenshotMode && loadTrigger != null;
            // The perf footer latches its readout once per second; wait for a few
            // ticks so the capture shows populated numbers and sparklines.
            boolean perfCapture = scene.equals("perf") && screenshotMode;
            // Kitchen 3D tab: open it, then wait like perf so the footer's 3D
            // gauges (GPU/draws/tris) latch real values before the capture.
            boolean threeDCapture = scene.equals("kitchen-3d") && screenshotMode;
            // Controls: inject focus + a spinner-button hover so the capture shows
            // the interaction states (slider focus ring, spinner hover/focus border).
            boolean controlsCapture = scene.equals("controls") && screenshotMode;
            // Menu: right-click to open the in-scene context menu, then hover the
            // "Export" item so the capture shows the cascade + checked items.
            boolean menuCapture = scene.startsWith("menu") && screenshotMode;
            // Tab overflow: the reveal scroll + indicator slide animate; capture settled.
            boolean tabsOverflowCapture = scene.equals("tabs-overflow") && screenshotMode;
            // Charts animate their values in over half a second, and the tooltip is half
            // the API: settle, hover a bar, then capture.
            boolean chartsCapture = scene.startsWith("charts") && screenshotMode;
            // Split: the divider's three lit states are pointer/focus states, so one
            // still frame cannot show them. Its own scene name, shooting three files;
            // `--scene split` stays a single plain capture.
            boolean splitCapture = scene.startsWith("split-states") && screenshotMode;
            boolean deferredCapture = popupCapture || dialogCapture || loadingCapture
                    || perfCapture || threeDCapture || controlsCapture || menuCapture
                    || tabsOverflowCapture || inSceneDialogCapture || splitCapture
                    || chartsCapture;
            AtomicInteger frames = new AtomicInteger();
            AtomicInteger frameNo = new AtomicInteger();
            // Scenes showcasing CJK/emoji glyphs race the BACKGROUND fallback-font
            // load (Noto CJK + color emoji parse off-thread and heal .notdef boxes
            // when they arrive): hold the warmup capture until the CJK family shows
            // up in the catalog (the same fold-in installs the emoji) or a
            // timeout passes (fallback binaries not bundled → capture as-is).
            java.util.function.BooleanSupplier fontFallbacksSettled;
            boolean fontFallbackScene = switch (scene) {
                case "fonts", "fonts-switched", "textfield-ime", "textarea-ime" -> true;
                default -> false;
            };
            if (screenshotMode && fontFallbackScene) {
                long deadlineNanos = System.nanoTime() + 2_000_000_000L;
                fontFallbacksSettled = () -> limn.graphics.Fonts.available().contains("Noto Sans CJK")
                        || System.nanoTime() - deadlineNanos > 0;
            } else {
                fontFallbacksSettled = () -> true;
            }
            window.setFrameCallback((renderer, frame) -> {
                if (boundScene != null) {
                    // Forward rePresent AND the GPU-time sample: the backend's
                    // anti-flicker double-present must repaint the SAME frame, and
                    // dropping gpuFrameMs here would starve the GPU gauge (Scene.bind
                    // wires both; this replacement callback has to preserve them).
                    boundScene.renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs());
                } else if (scene.equals("text")) {
                    TextScene.paint(renderer.canvas());
                } else {
                    TestCard.paint(renderer.canvas());
                }
                // Post-layout setup (e.g. scroll) runs once after the first
                // real layout, then the scene is re-rendered for capture.
                if (afterFirstFrame != null && frameNo.incrementAndGet() == 1) {
                    afterFirstFrame.run();
                    window.requestFrame();
                }
                if (screenshotMode && !deferredCapture) {
                    if (!fontFallbacksSettled.getAsBoolean()) {
                        window.requestFrame(); // keep pumping until the fallback fonts land
                    } else if (frames.incrementAndGet() >= SCREENSHOT_WARMUP_FRAMES) {
                        renderer.captureFramebuffer(options.screenshotFile());
                        System.out.printf("Screenshot (%dx%d px, scale %.2f, scene '%s') saved to %s%n",
                                frame.framebufferWidth(), frame.framebufferHeight(), frame.contentScale(),
                                scene, options.screenshotFile().toAbsolutePath());
                        window.requestClose();
                    } else {
                        window.requestFrame();
                    }
                }
            });

            if (dialogCapture) {
                // The modal is its own window; open it, then capture that window.
                java.util.function.Supplier<limn.components.Dialog> opener = dialogOpener;
                java.util.concurrent.atomic.AtomicReference<limn.components.Dialog> dlg =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Ui.postDelayed(() -> dlg.set(opener.get()), 50);
                Ui.postDelayed(() -> {
                    limn.components.Dialog d = dlg.get();
                    // Main window (dimmed by the modal scrim).
                    window.captureNextFrame(options.screenshotFile());
                    System.out.println("Dialog screenshot (parent dimmed): "
                            + options.screenshotFile().toAbsolutePath());
                    // The modal window itself.
                    if (d != null && d.modalWindow() != null) {
                        java.nio.file.Path modalPng = java.nio.file.Path.of(
                                options.screenshotFile().toString().replace(".png", "-modal.png"));
                        d.modalWindow().captureNextFrame(modalPng);
                        System.out.println("Modal window: " + modalPng.toAbsolutePath());
                    } else {
                        System.err.println("modal did not open!");
                    }
                }, 500); // after the fade-in settles
                Ui.postDelayed(window::requestClose, 800);
            }

            if (inSceneDialogCapture) {
                java.util.function.Supplier<limn.components.Dialog> opener = dialogOpener;
                java.util.function.Supplier<limn.components.ComboBox> comboRef = dialogComboRef;
                Ui.postDelayed(opener::get, 50);
                Ui.postDelayed(() -> {
                    limn.components.ComboBox combo = comboRef.get();
                    if (combo != null) {
                        combo.open();
                    }
                }, 350);
                Ui.postDelayed(() -> {
                    // Host window: the overlay, its scrim and the card with the combo in it.
                    window.captureNextFrame(options.screenshotFile());
                    System.out.println("In-scene dialog screenshot: "
                            + options.screenshotFile().toAbsolutePath());
                    limn.components.ComboBox combo = comboRef.get();
                    if (combo != null && combo.popupWindow() != null) {
                        java.nio.file.Path popupPng = java.nio.file.Path.of(
                                options.screenshotFile().toString().replace(".png", "-popup.png"));
                        combo.popupWindow().captureNextFrame(popupPng);
                        System.out.printf("Dropdown window (modal-blocked: %s): %s%n",
                                combo.popupWindow().isModalBlocked(), popupPng.toAbsolutePath());
                    } else {
                        System.err.println("the dialog's dropdown did not open!");
                    }
                }, 700);
                Ui.postDelayed(window::requestClose, 950);
            }

            if (controlsCapture && boundScene != null) {
                limn.scene.Scene cs = boundScene;
                // Click the first spinner to focus it, then hover its up-button.
                Ui.postDelayed(() -> {
                    cs.focusTraverse(false); // focus the first slider → slider focus ring (#1)
                    cs.mouseMoved(147, 333); // hover the first spinner's up-button (#2)
                    cs.inputBatchEnded();
                }, 150);
                Ui.postDelayed(() -> {
                    window.captureNextFrame(options.screenshotFile());
                    System.out.println("Controls screenshot: " + options.screenshotFile().toAbsolutePath());
                }, 450);
                Ui.postDelayed(window::requestClose, 650);
            }

            if (chartsCapture && boundScene != null) {
                limn.scene.Scene cs = boundScene;
                Ui.postDelayed(() -> {
                    cs.mouseMoved(250, 150); // a bar in the revenue chart
                    cs.inputBatchEnded();
                }, 700); // after the entry animation settles
                Ui.postDelayed(() -> {
                    window.captureNextFrame(options.screenshotFile());
                    System.out.println("Charts screenshot: " + options.screenshotFile().toAbsolutePath());
                }, 950);
                Ui.postDelayed(window::requestClose, 1150);
            }

            if (splitCapture && boundScene != null) {
                limn.scene.Scene ss = boundScene;
                java.nio.file.Path base = options.screenshotFile();
                java.util.concurrent.atomic.AtomicReference<float[]> inner =
                        new java.util.concurrent.atomic.AtomicReference<>();
                // Three files, one per lit state, so focus / hover / drag can be
                // compared as colours. The outer divider takes focus and the inner one
                // takes the pointer, so the first two frames carry two tints each; the
                // press then moves focus to the divider it landed on, which is what
                // leaves the third frame showing the drag tint on its own.
                Ui.postDelayed(() -> {
                    // Tab order here: sidebar list, inner divider, outer divider. The
                    // inner one's centre comes from the focused widget rather than from
                    // written-down coordinates, which the layout could invalidate.
                    ss.focusTraverse(false);
                    ss.focusTraverse(false);
                    limn.scene.Widget d = ss.focusedWidget();
                    inner.set(new float[] {
                            d.localToSceneX() + d.width() / 2, d.localToSceneY() + d.height() / 2});
                    ss.focusTraverse(false);
                    ss.inputBatchEnded();
                }, 150);
                Ui.postDelayed(() -> captureSplitState(ss, window, base, "-focus"), 400);
                Ui.postDelayed(() -> {
                    ss.mouseMoved(inner.get()[0], inner.get()[1]);
                    ss.inputBatchEnded();
                }, 550);
                Ui.postDelayed(() -> captureSplitState(ss, window, base, "-hover"), 800);
                Ui.postDelayed(() -> {
                    ss.mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0,
                            inner.get()[0], inner.get()[1]);
                    ss.inputBatchEnded();
                }, 950);
                Ui.postDelayed(() -> captureSplitState(ss, window, base, "-drag"), 1200);
                Ui.postDelayed(window::requestClose, 1400);
            }

            if (menuCapture && menuBuilt != null) {
                // The context menu is usually its own native window, which the parent's
                // screenshot would not show, so capture that window. Where the platform cannot
                // place one the menu is drawn inside the owner scene instead, and then the
                // parent's screenshot is the only one there is.
                MenuScene.Built mb = menuBuilt;
                java.util.concurrent.atomic.AtomicReference<limn.components.PopupMenu> ref =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Ui.postDelayed(() -> ref.set(mb.openContext().apply(300f, 300f)), 100);
                Ui.postDelayed(() -> {
                    limn.components.PopupMenu popup = ref.get();
                    if (popup == null || !popup.isOpen()) {
                        System.err.println("menu did not open!");
                    } else if (popup.popupWindow() != null) {
                        popup.popupWindow().captureNextFrame(options.screenshotFile());
                        System.out.println("Menu (native window) screenshot: "
                                + options.screenshotFile().toAbsolutePath());
                    } else {
                        window.captureNextFrame(options.screenshotFile());
                        System.out.println("Menu (in scene) screenshot: "
                                + options.screenshotFile().toAbsolutePath());
                    }
                }, 450);
                Ui.postDelayed(window::requestClose, 650);
            }

            if (tabsOverflowCapture) {
                // Let the reveal scroll + indicator slide settle, then capture.
                Ui.postDelayed(() -> {
                    window.captureNextFrame(options.screenshotFile());
                    System.out.println("Tabs-overflow screenshot: " + options.screenshotFile().toAbsolutePath());
                }, 700);
                Ui.postDelayed(window::requestClose, 900);
            }

            if (perfCapture || threeDCapture) {
                // Let a few 1 Hz latches populate the gauges + sparklines, then capture.
                Ui.postDelayed(() -> {
                    window.captureNextFrame(options.screenshotFile());
                    System.out.println("Perf screenshot: " + options.screenshotFile().toAbsolutePath());
                }, 3200);
                Ui.postDelayed(window::requestClose, 3500);
            }

            if (loadingCapture) {
                // Trigger the async load, then capture mid-flight: the spinner
                // animating proves the UI stays fluid during background work.
                Runnable trigger = loadTrigger;
                Ui.postDelayed(trigger, 50);
                Ui.postDelayed(() -> {
                    window.captureNextFrame(options.screenshotFile());
                    System.out.println("Loading screenshot: " + options.screenshotFile().toAbsolutePath());
                }, 700);
                Ui.postDelayed(window::requestClose, 900);
            }

            if (popupCapture && formsCombo != null) {
                // Open the combo popup (its own translucent window), capture
                // both windows, then close, all without showing anything.
                limn.components.ComboBox combo = formsCombo;
                java.nio.file.Path mainPng = options.screenshotFile();
                java.nio.file.Path popupPng = java.nio.file.Path.of(
                        mainPng.toString().replace(".png", "-popup.png"));
                Ui.postDelayed(combo::open, 150);
                Ui.postDelayed(() -> {
                    if (!combo.isOpen()) {
                        System.err.println("popup did not open!");
                    } else if (combo.popupWindow() != null) {
                        combo.popupWindow().captureNextFrame(popupPng);
                        System.out.println("Popup screenshot: " + popupPng.toAbsolutePath());
                    } else {
                        // Drawn in the owner scene: there is no second window, and the main
                        // screenshot below is where the list appears.
                        System.out.println("Popup is in the scene; see the main screenshot");
                    }
                    window.captureNextFrame(mainPng);
                    System.out.println("Main screenshot: " + mainPng.toAbsolutePath());
                }, 400);
                Ui.postDelayed(() -> {
                    combo.close();
                    window.requestClose();
                }, 700);
            }

            if (options.exitAfterMillis() > 0) {
                Ui.postDelayed(() -> {
                    System.out.printf("Auto-close after %d ms (Ui.postDelayed woke the loop on timeout)%n",
                            options.exitAfterMillis());
                    window.requestClose();
                }, options.exitAfterMillis());
            }

            // Round trip worker pool → UI thread, the canonical path for heavy work.
            Ui.async(() -> Thread.currentThread().getName())
                    .thenAccept(workerName -> {
                        Ui.checkUiThread();
                        System.out.printf("Ui.async ok: work on '%s', callback on UI thread '%s'%n",
                                workerName, Thread.currentThread().getName());
                    });

            backend.runEventLoop();
        }
    }

}