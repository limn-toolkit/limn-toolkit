package limn.demo.a11y;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.DisplayMode;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.demo.DocumentationDay;
import limn.demo.a11y.AccessibilityGallery.Built;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one reader driver (decision 24): opens a single accessibility-gallery entry in a window of
 * its own, puts the keyboard in the widget the entry names, sends the entry's reader script
 * through the scene's key path on a timer, prints a line for every step, and exits.
 *
 * <pre>
 * limn-demo --reader &lt;id&gt; [--exit-after ms] [--presentation in-scene|native] [--locale tag]
 * </pre>
 *
 * <p>What it pins, so every guest on every day hears the same run: the documentation day
 * (2026-09-09, {@link DocumentationDay}; the settled reader-scene-clock) on every date widget, and
 * the language, pt-BR unless {@code --locale} names another, because that is the guests' reader
 * language (decision 65) — test goldens and site captures stay English, and are not taken here.
 * {@code --presentation} asks every surface that can float above the page to open in the scene or
 * as a window of its own; without it the entry's own presentation stands, which for the closed
 * date picker is the native window (decision 5).
 *
 * <p>What it prints: a header line naming the script, the entry, the language, the day and the
 * presentation; {@code --- focus <widget>} once the keyboard is placed; and
 * {@code --- step N KEYS - label focus=<widget>} after each step is sent. A recipe waits for the
 * {@code --- step N } prefix, which is what {@code --scene tree-reader} printed.
 *
 * <p>What it does not do: walk the tree it publishes. A client pass is a separate run with a
 * restarted demo (decision 42), so nothing in this process asks the platform what it was told.
 * The first step waits {@value #FIRST_STEP_MILLIS} ms, time for a reader to settle on the new
 * window, and the others follow {@value #STEP_MILLIS} ms apart so each announcement is finished
 * before the next event; the window is brought forward before every step, because a reader speaks
 * the window in front and whatever launched this keeps taking the foreground back.
 */
public final class ReaderDriver {

    /** The language a reader run speaks unless told otherwise: the guests' (decision 65). */
    public static final Locale READER_LOCALE = Locale.forLanguageTag("pt-BR");

    /** When the first step is sent, after the window is shown. */
    public static final long FIRST_STEP_MILLIS = 5000;

    /** The time between two steps. */
    public static final long STEP_MILLIS = 3000;

    /** How long the window stays after the last step when {@code --exit-after} is not given. */
    public static final long TAIL_MILLIS = 5000;

    /** When the entry's own opening and the focus run, once the scene has been laid out. */
    private static final long FOCUS_MILLIS = 500;

    /** The window's title, which a client matches and a reader speaks. */
    public static final String WINDOW_TITLE = "Limn accessibility gallery";

    private static final int WIDTH = 800;
    private static final int HEIGHT = 640;

    private ReaderDriver() {
    }

    /**
     * A run's options.
     *
     * @param id              the reader script's id
     * @param exitAfterMillis when to close, or {@code 0} for the last step plus {@link #TAIL_MILLIS}
     * @param presentation    where floating surfaces open, or {@code null} for the entry's own
     * @param locale          the language the entry is built in
     */
    public record Options(String id, long exitAfterMillis, DisplayMode presentation, Locale locale) {

        /**
         * @param args the command line, {@code --reader <id>} somewhere in it
         * @return the options
         * @throws IllegalArgumentException for a missing value, an unknown option or an unknown id
         */
        public static Options parse(String[] args) {
            String id = null;
            long exitAfter = 0;
            DisplayMode presentation = null;
            Locale locale = READER_LOCALE;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--reader" -> id = valueAt(args, ++i);
                    case "--exit-after" -> exitAfter = Long.parseLong(valueAt(args, ++i));
                    case "--presentation" -> presentation = switch (valueAt(args, ++i)) {
                        case "in-scene" -> DisplayMode.IN_SCENE;
                        case "native" -> DisplayMode.NATIVE_WINDOW;
                        default -> throw new IllegalArgumentException(
                                "--presentation takes in-scene or native, not " + args[i]);
                    };
                    case "--locale" -> locale = Locale.forLanguageTag(valueAt(args, ++i));
                    default -> throw new IllegalArgumentException("unknown option for a reader "
                            + "run: " + args[i] + "\n" + usage());
                }
            }
            if (id == null) {
                throw new IllegalArgumentException("no --reader id\n" + usage());
            }
            try {
                AccessibilityGallery.readerEntry(id); // refused before a window opens
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(unknown.getMessage() + "\n" + usage(), unknown);
            }
            return new Options(id, exitAfter, presentation, locale);
        }

        private static String valueAt(String[] args, int i) {
            if (i >= args.length) {
                throw new IllegalArgumentException(args[i - 1] + " needs a value\n" + usage());
            }
            return args[i];
        }
    }

    /** @return the command line a reader run takes, with the scripts there are */
    public static String usage() {
        List<String> ids = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            ids.add(entry.reader().id());
        }
        return "usage: --reader <" + String.join("|", ids) + "> [--exit-after ms] "
                + "[--presentation in-scene|native] [--locale tag]";
    }

    /**
     * The command line of an older spelling, as a reader run's: {@code --scene tree-reader}, the
     * scene ADR 044 §4's runs of 2026-09-13 used, is {@code --reader tree-loading} since
     * 2026-09-15, with every other option kept.
     *
     * @param args a demo command line
     * @return the reader run's command line, or {@code null} when {@code args} names none
     */
    public static String[] readerArguments(String[] args) {
        List<String> out = new ArrayList<>();
        boolean reader = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--reader")) {
                reader = true;
                out.add(args[i]);
            } else if (args[i].equals("--scene") && i + 1 < args.length
                    && args[i + 1].equals("tree-reader")) {
                reader = true;
                out.add("--reader");
                out.add(AccessibilityGallery.readerEntry("tree-loading").reader().id());
                i++;
            } else {
                out.add(args[i]);
            }
        }
        return reader ? out.toArray(String[]::new) : null;
    }

    /**
     * The line printed after a step is sent.
     *
     * @param number  the step's one-based number
     * @param step    the step
     * @param focused the widget holding the keyboard afterwards, or {@code null}
     * @return {@code --- step N KEYS - label focus=Widget}
     */
    public static String stepLine(int number, Step step, Widget focused) {
        return "--- step " + number + " " + step.keys() + " - " + step.label() + " focus="
                + (focused == null ? "none" : focused.getClass().getSimpleName());
    }

    /**
     * Runs a reader script in a window, until the run closes it.
     *
     * @param args {@code --reader <id>} and the options above
     */
    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException refused) {
            System.err.println(refused.getMessage());
            System.exit(2);
            return;
        }
        run(options);
    }

    private static void run(Options options) {
        // Before anything is built, for the reason the demo's --locale is: a widget names itself
        // in the language it was built under.
        limn.i18n.I18n.setLocale(options.locale());
        Entry entry = AccessibilityGallery.readerEntry(options.id());
        List<Step> steps = entry.reader().steps();
        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(WindowConfig.of(WINDOW_TITLE, WIDTH, HEIGHT));
            Built built = entry.build();
            if (options.presentation() != null) {
                AccessibilityGallery.present(built.root(), options.presentation());
            }
            DocumentationDay.pin(built.root());
            Scene scene = new Scene(new Padding(Insets.all(16), built.root()));
            scene.setBackground(Theme.current().background);
            scene.bind(window);
            window.show();
            System.out.println("--- reader " + options.id() + " \"" + entry.name() + "\" steps="
                    + steps.size() + " locale=" + options.locale().toLanguageTag() + " today="
                    + DocumentationDay.CLOCK.instant() + " presentation="
                    + (options.presentation() == null ? "as built" : options.presentation()));
            Ui.postDelayed(() -> {
                built.afterFirstFrame().run();
                if (built.focus() != null) {
                    built.focus().requestFocus();
                }
                System.out.println("--- focus " + describe(scene.focusedWidget()));
            }, FOCUS_MILLIS);
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                int number = i + 1;
                Ui.postDelayed(() -> {
                    window.focus();
                    step.sendTo(scene);
                    System.out.println(stepLine(number, step, scene.focusedWidget()));
                }, FIRST_STEP_MILLIS + i * STEP_MILLIS);
            }
            long exitAfter = options.exitAfterMillis() > 0 ? options.exitAfterMillis()
                    : FIRST_STEP_MILLIS + (steps.size() - 1) * STEP_MILLIS + TAIL_MILLIS;
            Ui.postDelayed(() -> {
                System.out.println("--- exit after " + exitAfter + " ms");
                window.requestClose();
            }, exitAfter);
            backend.runEventLoop();
        }
    }

    private static String describe(Widget widget) {
        return widget == null ? "none" : widget.getClass().getSimpleName();
    }
}
