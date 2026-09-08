package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Built;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.HeadlessBackend;
import limn.demo.a11y.HeadlessWindow;
import limn.demo.a11y.Transcript;
import limn.demo.site.FormExample;
import limn.demo.site.LayoutExample;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.testing.RepositoryRoot;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR 039 §12.1's gallery-wide invariants, run over {@link AccessibilityGallery}: every entry,
 * rendered headlessly in both palettes, publishes a tree in which no node has role
 * {@code UNKNOWN}, every focusable node has a name, no two nodes share an identifier, and every
 * node that is showing is where its ancestors are.
 *
 * <p>The per-component tests in {@code limn-toolkit} prove each widget's row against its own
 * source; this is the other direction — the widgets composed as an application composes them,
 * labelled the way the toolkit intends, read as one tree. An invariant that fails here on a scene
 * built correctly is a toolkit defect, not a gallery one.
 *
 * <p><b>The clipping invariant, stated as the model lets it be stated.</b> The row says "every
 * node's bounds lie inside its nearest clipping ancestor's". The published node carries no mark
 * of which ancestors clip: {@code Widget#clipsChildren()} is consulted by the walk to decide
 * {@code SHOWING} and is not published. And "inside" cannot mean wholly inside, because a row
 * scrolled half off the top of a list is published where it is, with {@code SHOWING} saying how
 * much of it is on screen (§7.2). So the invariant asserted is the one the model supports: a
 * <em>showing</em> node has a non-empty box that overlaps the scene's and overlaps every showing
 * ancestor's, and therefore a node lying wholly outside the window, or wholly outside the
 * container it is published under, is never announced as on screen.
 *
 * <p>The gallery is also held complete: every class in the toolkit's sources that overrides an
 * accessibility hook is covered by some entry, and every class an entry claims to cover really
 * does override one. A component cannot gain an accessible surface without a scene here that
 * shows it used right, and the cover lists cannot rot.
 */
class AccessibleGalleryTest {

    /** The window every entry is shown in, in logical points. */
    private static final int WIDTH = 800;
    private static final int HEIGHT = 640;

    /** The transcript test's warm-up: enough frames for the theme's longest transition. */
    private static final int SETTLE_FRAMES = 24;
    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    /**
     * Entries whose failure is a defect another step already owns, so that the test stays green
     * while it is fixed elsewhere and red the moment it is fixed and this set is not emptied.
     * Keyed by entry name; the value says which defect and where it is owed. <b>Empty</b>: the
     * one entry it held, the nameless picker a colour well raises, was fixed in the widget.
     */
    private static final Map<String, String> KNOWN_UNTIL_FIXED = Map.of();

    /** The two palettes, each a fresh theme so a build never sees the other's tokens. */
    enum Palette {
        LIGHT(Theme::light),
        DARK(Theme::dark);

        final Supplier<Theme> theme;

        Palette(Supplier<Theme> theme) {
            this.theme = theme;
        }
    }

    /** Matches a hook override: the annotation, then the declaration, nothing else between. */
    private static final Pattern HOOK_OVERRIDE = Pattern.compile(
            "@Override\\s+protected\\s+(?:void|boolean)\\s+onAccessibility(?:Child|Action)?\\s*\\(");

    // ------------------------------------------------------------------------ the invariants

    @TestFactory
    Stream<DynamicTest> everyEntryHoldsTheInvariantsInBothPalettes() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            for (Palette palette : Palette.values()) {
                tests.add(DynamicTest.dynamicTest(
                        entry.name() + ", " + palette.name().toLowerCase(Locale.ROOT),
                        () -> check(entry, palette)));
            }
        }
        return tests.stream();
    }

    /**
     * The guide's own worked examples, held to the same four rules. They are what the
     * documentation shows an application doing, and the form is the screen the home page's
     * reader panel and the accessibility guide's transcript are taken from: a nameless field
     * there is a nameless field on every page that teaches the idiom.
     */
    @TestFactory
    Stream<DynamicTest> theGuidesOwnExamplesHoldTheInvariants() {
        Map<String, Supplier<Scene>> examples = new LinkedHashMap<>();
        examples.put("A form", FormExample::scene);
        examples.put("A window laid out", LayoutExample::scene);
        List<DynamicTest> tests = new ArrayList<>();
        examples.forEach((name, example) -> {
            for (Palette palette : Palette.values()) {
                tests.add(DynamicTest.dynamicTest(
                        name + ", " + palette.name().toLowerCase(Locale.ROOT),
                        () -> checkExample(name, example, palette)));
            }
        });
        return tests.stream();
    }

    private static void checkExample(String name, Supplier<Scene> example, Palette palette) {
        try (Harness harness = new Harness(palette)) {
            List<HeadlessWindow> windows = harness.show(name, example.get());
            List<String> violations = new ArrayList<>();
            StringBuilder transcript = new StringBuilder();
            for (HeadlessWindow window : windows) {
                AccessibleTree tree = window.bridge().tree();
                violations.addAll(violations(window.title(), tree));
                transcript.append("== window \"").append(window.title()).append("\" ==\n")
                        .append(Transcript.of(tree));
            }
            if (!violations.isEmpty()) {
                fail("the guide's example \"" + name + "\" in the "
                        + palette.name().toLowerCase(Locale.ROOT) + " palette breaks "
                        + violations.size() + " invariant(s):\n  "
                        + String.join("\n  ", violations)
                        + "\nthe tree it published:\n" + transcript);
            }
        }
    }

    private static void check(Entry entry, Palette palette) {
        try (Harness harness = new Harness(palette)) {
            List<HeadlessWindow> windows = harness.show(entry);
            List<String> violations = new ArrayList<>();
            StringBuilder transcript = new StringBuilder();
            Set<Accessible.Role> published = new TreeSet<>();
            for (HeadlessWindow window : windows) {
                AccessibleTree tree = window.bridge().tree();
                violations.addAll(violations(window.title(), tree));
                for (int i = 0; i < tree.nodeCount(); i++) {
                    published.add(tree.node(i).role());
                }
                transcript.append("== window \"").append(window.title()).append("\" ==\n")
                        .append(Transcript.of(tree));
            }
            for (Accessible.Role promised : entry.publishes()) {
                if (!published.contains(promised)) {
                    violations.add("the entry promises a " + promised + " node and published "
                            + "none: the scene did not show what it says it covers");
                }
            }
            String excuse = KNOWN_UNTIL_FIXED.get(entry.name());
            if (excuse != null) {
                assertTrue(!violations.isEmpty(), "\"" + entry.name() + "\" is listed as known "
                        + "until fixed (" + excuse + ") and no longer fails: strike it off");
                return;
            }
            if (!violations.isEmpty()) {
                fail("gallery entry \"" + entry.name() + "\" in the "
                        + palette.name().toLowerCase(Locale.ROOT) + " palette breaks "
                        + violations.size() + " invariant(s):\n  "
                        + String.join("\n  ", violations)
                        + "\nthe tree it published:\n" + transcript);
            }
        }
    }

    /**
     * The four invariants over one published tree; each violation names the node and the rule.
     *
     * @param window the window the tree belongs to, for the message
     * @param tree   what the scene published
     * @return the violations, empty when the tree holds
     */
    static List<String> violations(String window, AccessibleTree tree) {
        List<String> out = new ArrayList<>();
        if (tree.nodeCount() == 0) {
            out.add("window \"" + window + "\" published no tree at all");
            return out;
        }
        Map<Long, Integer> byId = new HashMap<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            String where = "window \"" + window + "\", node " + describe(node);
            if (node.role() == Accessible.Role.UNKNOWN) {
                out.add(where + ": has role UNKNOWN");
            }
            if (node.has(Accessible.State.FOCUSABLE) && node.name().isBlank()) {
                out.add(where + ": is focusable and has no name");
            }
            Integer earlier = byId.put(node.id(), i);
            if (earlier != null) {
                out.add(where + ": shares its id with node " + describe(tree.node(earlier)));
            }
            if (node.has(Accessible.State.SHOWING)) {
                if (node.width() <= 0 || node.height() <= 0) {
                    out.add(where + ": is showing with an empty box");
                } else if (!overlaps(node.x(), node.y(), node.width(), node.height(),
                        0, 0, tree.sceneWidth(), tree.sceneHeight())) {
                    out.add(where + ": is showing and lies wholly outside the scene ("
                            + tree.sceneWidth() + "x" + tree.sceneHeight() + ")");
                } else {
                    for (int up = node.parent(); up != AccessibleNode.NONE;
                         up = tree.node(up).parent()) {
                        AccessibleNode ancestor = tree.node(up);
                        if (ancestor.has(Accessible.State.SHOWING)
                                && ancestor.width() > 0 && ancestor.height() > 0
                                && !overlaps(node.x(), node.y(), node.width(), node.height(),
                                ancestor.x(), ancestor.y(), ancestor.width(),
                                ancestor.height())) {
                            out.add(where + ": is showing and lies wholly outside its showing "
                                    + "ancestor " + describe(ancestor));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean overlaps(float x, float y, float w, float h,
                                    float ox, float oy, float ow, float oh) {
        return x < ox + ow && ox < x + w && y < oy + oh && oy < y + h;
    }

    private static String describe(AccessibleNode node) {
        return "#" + node.id() + " " + node.role() + " \"" + node.name() + "\" box=("
                + node.x() + ", " + node.y() + ", " + node.width() + "x" + node.height() + ")";
    }

    // ------------------------------------------------------------------------ completeness

    @Test
    void everyComponentThatOverridesAHookHasAGalleryEntry() throws IOException {
        Set<String> described = classesOverridingAHook();
        Set<String> covered = coveredClasses();
        Set<String> missing = new TreeSet<>(described);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(), "these toolkit classes override an accessibility hook and "
                + "no gallery entry covers them; add an entry to AccessibilityGallery that shows "
                + "each used right: " + missing);
    }

    @Test
    void theGalleryClaimsOnlyComponentsThatOverrideAHook() throws IOException {
        Set<String> described = classesOverridingAHook();
        Set<String> stale = new TreeSet<>(coveredClasses());
        stale.removeAll(described);
        assertTrue(stale.isEmpty(), "these classes are listed as covered by a gallery entry and "
                + "override no accessibility hook; a cover list names only what the scene's "
                + "tree exercises: " + stale);
    }

    private static Set<String> coveredClasses() {
        Set<String> covered = new TreeSet<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            for (Class<?> type : entry.covers()) {
                covered.add(type.getName());
            }
        }
        return covered;
    }

    /**
     * Every top-level class in {@code limn-toolkit}'s main sources whose file overrides one of
     * the hooks, by name. A hook in a nested class — a tab header, a divider, a rail — counts
     * for the class that declares the file, because that is the component an application uses.
     */
    private static Set<String> classesOverridingAHook() throws IOException {
        Path sources = RepositoryRoot.find().resolve("limn-toolkit/src/main/java");
        assertTrue(Files.isDirectory(sources), "no toolkit sources at " + sources);
        Set<String> found = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(sources)) {
            for (Path source : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                if (!HOOK_OVERRIDE.matcher(text).find()) {
                    continue;
                }
                String relative = sources.relativize(source).toString();
                found.add(relative.substring(0, relative.length() - ".java".length())
                        .replace(java.io.File.separatorChar, '.'));
            }
        }
        assertTrue(found.size() > 20, "found almost no hook overrides under " + sources
                + "; has the hook been renamed?");
        return found;
    }


    // ------------------------------------------------------------------------ the harness

    /**
     * One UI runtime, one palette, one headless backend: what a dynamic test stands up and tears
     * down, since a test factory runs its lifecycle once for every test it makes.
     */
    private static final class Harness implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(1);
        private final UiRuntime runtime;
        private final HeadlessBackend backend;
        private long nanos;

        Harness(Palette palette) {
            runtime = new UiRuntime(() -> nanos, () -> { }, workers);
            runtime.bindToCurrentThread();
            Ui.install(runtime);
            I18n.setLocale(Locale.ENGLISH);
            Theme.setCurrent(palette.theme.get());
            ControlSize.setProcessDefault(ControlSize.MEDIUM);
            TextRulers.install(HeadlessWindow.RULER);
            backend = new HeadlessBackend(runtime);
        }

        /**
         * Builds the entry under the palette, binds it, opens whatever it opens, and settles
         * every window that exists by then.
         *
         * @param entry what to show
         * @return every window the entry ended up with, the entry's own first
         */
        List<HeadlessWindow> show(Entry entry) {
            Built built = entry.build();
            HeadlessWindow window = backend.open(entry.name(), WIDTH, HEIGHT);
            Scene scene = new Scene(built.root(), () -> nanos);
            scene.bind(window);
            window.frame();
            window.desktopFocus(true);
            settle();
            built.afterFirstFrame().run();
            // A dialog that opened as a window of its own asked for the keyboard, and the
            // desktop would have moved it: the host loses the focus and the modal gains it.
            for (HeadlessWindow other : backend.windows()) {
                if (other != window && other.isModal()) {
                    window.desktopFocus(false);
                    other.desktopFocus(true);
                }
            }
            settle();
            return backend.windows();
        }

        /**
         * Binds a scene an example built for itself — the guide's worked examples come as a
         * {@link Scene}, not a root — and settles it the same way.
         *
         * @param title the window's title, for the messages
         * @param scene the example's own scene
         * @return every window that exists once it settled
         */
        List<HeadlessWindow> show(String title, Scene scene) {
            HeadlessWindow window = backend.open(title, WIDTH, HEIGHT);
            scene.bind(window);
            window.frame();
            window.desktopFocus(true);
            settle();
            return backend.windows();
        }

        /** Renders every window in turn, one frame of scene time apart, until all settled. */
        private void settle() {
            for (int i = 0; i < SETTLE_FRAMES; i++) {
                nanos += FRAME_NANOS;
                runtime.drain();
                for (HeadlessWindow window : backend.windows()) {
                    window.frame();
                }
            }
        }

        @Override
        public void close() {
            TextRulers.uninstall(HeadlessWindow.RULER);
            Theme.setCurrent(Theme.dark());
            Ui.uninstall(runtime);
            workers.shutdownNow();
        }
    }
}
