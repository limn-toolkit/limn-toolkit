package limn.demo;

import java.nio.file.Path;

/**
 * Command-line options of the demo.
 *
 * @param screenshotFile  PNG destination; non-null enables headless screenshot mode
 * @param scene           named scene to render
 * @param scale           forced content scale for screenshot scenes (0 = monitor's)
 * @param exitAfterMillis auto-close the window after this delay (0 = never);
 *                        exercises the Ui.postDelayed timeout wake-up in CI
 * @param controlSize     process default control size, or null for the built-in MEDIUM.
 *                        Renders any scene at any step, which is how a reviewer compares
 *                        the same scene across the ramp without a bespoke scene per step
 * @param locale          UI language, or null for the machine's: the screenshot axis for
 *                        translations, since a captured scene is the only way to see that
 *                        a language actually renders
 * @param videoFrame      picture the video scenes stand still on, or -1 to play. A video
 *                        capture is otherwise whatever picture the decoder happened to
 *                        reach; with this the pane decodes from the start of its stream to
 *                        exactly this one and stops, so the same command produces the same
 *                        PNG on any machine and at any speed
 * @param videoSource     which entry of the video tab's source picker to start on. A capture
 *                        otherwise only ever shows the first one, which leaves every other
 *                        decoder (including the one with a native behind it) with no way to
 *                        be looked at
 * @param direction       process default layout direction, or null for the built-in LTR.
 *                        Renders any scene mirrored, which is how a reviewer sees a layout that
 *                        is inside out; a picture is the wrong instrument for bidi correctness
 *                        and the right one for a screen laid out backwards
 * @param theme           palette to render in, or null to leave the scene's own choice alone.
 *                        Scenes pick light or dark themselves, so without this every capture
 *                        shows one of those two and no other built-in palette can be looked at
 */
record DemoOptions(Path screenshotFile, String scene, float scale, long exitAfterMillis,
                   limn.scene.ControlSize controlSize, java.util.Locale locale, int videoFrame,
                   int videoSource, limn.scene.LayoutDirection direction,
                   limn.components.Theme theme) {

    static DemoOptions parse(String[] args) {
        Path screenshotFile = null;
        String scene = "default";
        float scale = 0f;
        long exitAfterMillis = 0;
        limn.scene.ControlSize controlSize = null;
        java.util.Locale locale = null;
        int videoFrame = -1;
        int videoSource = 0;
        limn.scene.LayoutDirection direction = null;
        limn.components.Theme theme = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--video-source" -> videoSource = parseIndex(valueOf(args, ++i),
                        "--video-source");
                case "--screenshot" -> screenshotFile = Path.of(valueOf(args, ++i));
                case "--scene" -> scene = valueOf(args, ++i);
                case "--scale" -> scale = parseScale(valueOf(args, ++i));
                case "--exit-after" -> exitAfterMillis = Long.parseLong(valueOf(args, ++i));
                case "--control-size" -> controlSize = parseControlSize(valueOf(args, ++i));
                case "--locale" -> locale = parseLocale(valueOf(args, ++i));
                case "--video-frame" -> videoFrame = parseVideoFrame(valueOf(args, ++i));
                case "--direction" -> direction = parseDirection(valueOf(args, ++i));
                case "--theme" -> theme = parseTheme(valueOf(args, ++i));
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }
        return new DemoOptions(screenshotFile, scene, scale, exitAfterMillis, controlSize,
                locale, videoFrame, videoSource, direction, theme);
    }

    /**
     * Parses the layout direction. Deliberately its own option and not derived from
     * {@code --locale}: direction and language are different axes, and a capture of an Arabic
     * translation in a left-to-right layout is a picture worth being able to take.
     */
    private static limn.scene.LayoutDirection parseDirection(String raw) {
        try {
            return limn.scene.LayoutDirection.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            System.err.println("invalid --direction: " + raw + " (expected ltr or rtl)");
            System.exit(2);
            return null; // unreachable
        }
    }

    /** Matches {@code Theme.name} ignoring case and spaces, so {@code archdark} finds Arch Dark. */
    private static limn.components.Theme parseTheme(String raw) {
        String wanted = raw.replace(" ", "").replace("-", "");
        for (limn.components.Theme candidate : limn.components.Theme.builtins()) {
            if (candidate.name.replace(" ", "").equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        System.err.println("invalid --theme: " + raw + " (expected one of "
                + limn.components.Theme.builtins().stream().map(t -> t.name).toList() + ")");
        System.exit(2);
        return null; // unreachable
    }

    private static int parseIndex(String raw, String option) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 0) {
                throw new NumberFormatException("< 0");
            }
            return parsed;
        } catch (NumberFormatException error) {
            System.err.println("invalid " + option + ": " + raw + " (expected an index, from 0)");
            System.exit(2);
            return 0; // unreachable
        }
    }

    private static java.util.Locale parseLocale(String raw) {
        java.util.Locale parsed = Languages.parse(raw);
        if (parsed == null) {
            System.err.println("invalid --locale: " + raw + " (expected a language tag "
                    + "such as pt-BR, ja or zh-Hans)");
            System.exit(2);
        }
        return parsed;
    }

    private static int parseVideoFrame(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 0) {
                throw new NumberFormatException("< 0");
            }
            return parsed;
        } catch (NumberFormatException error) {
            System.err.println("invalid --video-frame: " + raw + " (expected a picture index, from 0)");
            System.exit(2);
            return -1; // unreachable
        }
    }

    private static String valueOf(String[] args, int index) {
        if (index >= args.length) {
            System.err.println("missing value for " + args[index - 1]);
            printUsage();
            System.exit(2);
        }
        return args[index];
    }

    private static limn.scene.ControlSize parseControlSize(String raw) {
        try {
            return limn.scene.ControlSize.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            System.err.println("invalid --control-size: " + raw + " (expected one of "
                    + java.util.Arrays.toString(limn.scene.ControlSize.values()) + ")");
            System.exit(2);
            return null; // unreachable
        }
    }

    private static float parseScale(String raw) {
        try {
            float parsed = Float.parseFloat(raw);
            if (parsed <= 0) {
                throw new NumberFormatException("<= 0");
            }
            return parsed;
        } catch (NumberFormatException error) {
            System.err.println("invalid --scale: " + raw + " (expected e.g. 1.0, 1.25, 1.5, 2.0)");
            System.exit(2);
            return 0f; // unreachable
        }
    }

    private static void printUsage() {
        System.err.println("""
                usage: limn-demo [--screenshot <file.png>] [--scene <name>] [--scale <factor>]
                                 [--exit-after <ms>] [--control-size <step>] [--locale <tag>]
                                 [--video-frame <n>] [--video-source <n>] [--direction <dir>]
                                 [--theme <name>]
                       limn-demo --gl-info
                  --gl-info       prints the graphics stack this machine offers, then exits
                                  (non-zero where there is no context); takes no other argument
                  --screenshot    renders with an invisible window and saves a PNG (visual verification)
                  --scene         scene: components (default) | components-light | widgets | testcard | text
                  --scale         forces the content scale in screenshot scenes (e.g. 1.25)
                  --exit-after    closes the window by itself after N ms (interaction-free verification)
                  --control-size  process default step: xsmall | small | medium | large | xlarge
                  --locale        UI language tag: pt-BR | ja | fr | zh-Hans | …
                  --video-frame   freezes the video scenes on picture n (deterministic capture)
                  --video-source  starts the video tab on source n of its picker (0 = the first)
                  --direction     process default layout direction: ltr | rtl
                  --theme         palette: Limn | Dark | Light | Draculite | Nordic | … (see --help output)""");
    }
}
