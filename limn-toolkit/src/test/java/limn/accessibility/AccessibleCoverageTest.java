package limn.accessibility;

import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every widget the toolkit ships, against what has been said about it.
 *
 * <p>This is the test that makes the build refuse a widget nobody described, and it is the one that
 * matters in five years: a screen reader's coverage decays silently, because a control that says
 * nothing about itself looks exactly like a control nobody has used yet. Nothing else in the suite
 * would notice a new widget arriving with no accessible role.
 *
 * <p><b>It is a ratchet, not a wall.</b> Describing forty components is a per-component pipeline
 * with its own step for each, so a test that simply failed until all of them were done would leave
 * the build red for the length of the work and stop being a signal. Instead it carries the list of
 * what is left, and asserts the list is <em>exact</em> in both directions: nothing outside it may be
 * undescribed, so a new widget cannot slip in; and nothing inside it may already be described, so
 * the list cannot rot as the work lands. Describing a component therefore fails this test until its
 * name is struck off, which is the bookkeeping being enforced rather than remembered.
 *
 * <p>Discovery is by reflection over the classes the component sources declare, not by grepping for
 * {@code extends Widget}: a widget that extends {@code Painted}, which extends {@code Widget}, is
 * every bit a widget, and half the hard ones in this toolkit are exactly that shape. What it cannot
 * see is an anonymous or method-local subclass, which has no declared name to strike off; there are
 * none today and one would be a poor way to ship a control in any case.
 */
class AccessibleCoverageTest {

    /**
     * Widgets that have not yet been given a description, and the whole of phase 4's remaining
     * work.
     *
     * <p>Strike a name off in the same commit that describes it. The order is the order the
     * classes were discovered in, which is alphabetical by source file, and is not a running order:
     * the pipeline takes them in whatever order makes each one's own step cheapest to verify.
     */
    private static final Set<String> UNDESCRIBED = new TreeSet<>(Set.of(
            "limn.components.BackdropPanel",
            "limn.components.Button",
            "limn.components.Checkbox",
            "limn.components.ColorPicker",
            "limn.components.ColorPicker$AlphaRail",
            "limn.components.ColorPicker$ChannelGroup$ChannelTrack",
            "limn.components.ColorPicker$HueRamp",
            "limn.components.ColorPicker$Preview",
            "limn.components.ColorPicker$SaturationValueField",
            "limn.components.ColorPickerButton",
            "limn.components.ContextMenus$ContextRegion",
            "limn.components.Dialog$ActionRow",
            "limn.components.Dialog$CardColumn",
            "limn.components.Dialog$DialogPanel",
            "limn.components.Dialog$SceneOverlay",
            "limn.components.ImageView",
            "limn.components.Label",
            "limn.components.ListView",
            "limn.components.MediaControls",
            "limn.components.MediaControls$MuteButton",
            "limn.components.MediaControls$PlayPause",
            "limn.components.MenuBar",
            "limn.components.PasswordField",
            "limn.components.PopupMenu$MenuSurface",
            "limn.components.ProgressBar",
            "limn.components.RadioButton",
            "limn.components.ScrollBar",
            "limn.components.ScrollView",
            "limn.components.SearchField",
            "limn.components.SegmentedControl",
            "limn.components.Separator",
            "limn.components.Slider",
            "limn.components.Spinner",
            "limn.components.TabbedPane",
            "limn.components.TabbedPane$StripButton",
            "limn.components.TabbedPane$TabHeader",
            "limn.components.TabbedPane$TabStrip",
            "limn.components.TextArea",
            "limn.components.TextField",
            "limn.components.TokenBox",
            "limn.components.TokenColumn",
            "limn.components.TokenPadding",
            "limn.components.TokenRow",
            "limn.components.ToolBar",
            "limn.components.VideoView",
            "limn.components.Viewport3D",
            "limn.components.chart.BarChart",
            "limn.components.chart.DonutChart",
            "limn.components.chart.LineChart",
            "limn.scene.layout.Column",
            "limn.scene.layout.Expanded",
            "limn.scene.layout.Padding",
            "limn.scene.layout.Row",
            "limn.scene.layout.SizedBox",
            "limn.scene.layout.Stack"
    ));

    /**
     * Widgets the survey in ADR 039 §7 does not name, with the reason each is absent.
     *
     * <p>The record calls that table a survey rather than a specification and expects it to be
     * wrong in details; this is where the details are kept, so that a class missing from it is a
     * recorded gap rather than a silent one. A widget added from here on has to be in the table or
     * in this list, and putting it in this list means writing down why.
     */
    private static final Set<String> ABSENT_FROM_THE_SURVEY = new TreeSet<>(Set.of(
            // All five are named in the survey's prose and not as a class: it says "Dialog's card
            // column and action row" among the scaffolding it deletes, and it says of MediaControls
            // that "its icon buttons are real widgets and get BUTTON". The gap is in the naming and
            // not in the thinking, which is why each is recorded here rather than added to the
            // table: a survey that grew a row per private inner class would be a specification,
            // which §7 says in its own words it is not.
            "CardColumn",
            "DialogPanel",
            "MuteButton",
            "PlayPause",
            "SceneOverlay"
    ));

    /**
     * Widgets that deliberately take an ancestor's description instead of writing their own.
     *
     * <p>The list exists so that inheritance cannot describe a widget by accident. A subclass
     * inherits its parent's hook whether or not anyone thought about it, so a rule that accepted an
     * inherited description would strike three names off this test's list the moment somebody
     * described one of them &mdash; and a password field that took a text field's description
     * would publish the secret's own offsets under the role of a plain field, which is precisely
     * the row the survey warns hardest about. So the default is that a concrete widget declares
     * its own, and deferring to a parent is a decision written down here.
     *
     * <p>A name here is still checked: the ancestor it defers to has to actually declare one.
     */
    private static final Set<String> DEFERS_TO_AN_ANCESTOR = new TreeSet<>(Set.of(
            // Nothing yet. A rail family or a chart family is where the first entries belong.
    ));

    /**
     * Widgets whose pipeline step settled that they describe nothing, on purpose.
     *
     * <p>The list above cannot express this, and that is why this one exists. ADR 039 §1.6 makes
     * transparency a predicate rather than an opt-in: a widget that declares no role, no name, no
     * action and no state of its own is deleted from the tree and its children hoisted into its
     * place. A widget settled that way declares no describe hook <em>by definition</em>, so it
     * could only ever be struck off the undescribed list by gaining an empty one &mdash; which
     * would be a lie in the source and indistinguishable, to the check above, from a widget
     * somebody described. Phase 4 would then be unable to end with that list empty.
     *
     * <p>So a name moves here instead, and is asserted in the inverse direction: nothing in this
     * list may describe itself or inherit a description. That is the same guard the two lists
     * above carry, pointed the other way, so this cannot quietly become a hiding place for a
     * widget somebody later gave a hook to.
     *
     * <p>Every transparent widget still on the undescribed list belongs here eventually; each
     * arrives in its own step, once that step has proved the deletion against the predicate and
     * pinned what the deletion does <em>not</em> delete.
     */
    private static final Set<String> TRANSPARENT_BY_THE_PREDICATE = new TreeSet<>(Set.of(
            // Clips its children, declares nothing, is never focusable and can never acquire a
            // tooltip, so §1.6's predicate deletes it and hoists its content into the split's own
            // place. The clip outlives the node: isShowing() walks widgets rather than nodes, so a
            // collapsed pane's controls publish visible and not showing for free. Pinned by
            // limn.components.SplitPaneAccessibilityTest.
            "limn.components.SplitPane$Pane"
    ));

    @Test
    void everyWidgetIsEitherDescribedOrOnTheListOfWhatIsLeft() {
        Set<String> undescribed = new TreeSet<>();
        for (Class<?> widget : widgets()) {
            boolean deferred = DEFERS_TO_AN_ANCESTOR.contains(widget.getName());
            if (deferred) {
                assertTrue(describedAnywhereInItsAncestry(widget),
                        widget.getName() + " defers to an ancestor and no ancestor describes it");
                continue;
            }
            if (TRANSPARENT_BY_THE_PREDICATE.contains(widget.getName())) {
                continue; // settled, deliberately, with no code; asserted in the test below
            }
            if (!declaresItsOwn(widget)) {
                undescribed.add(widget.getName());
            }
        }
        assertEquals(UNDESCRIBED, undescribed,
                "the list of undescribed widgets must be exact in both directions. A name here and "
                        + "not in the field above is a widget nobody has said anything about; a "
                        + "name in the field and not here is a widget that was described and whose "
                        + "entry was left behind.");
    }

    @Test
    void everyWidgetSettledAsTransparentStillSaysNothingAboutItself() {
        Set<String> known = new TreeSet<>();
        for (Class<?> widget : widgets()) {
            known.add(widget.getName());
        }
        for (String name : TRANSPARENT_BY_THE_PREDICATE) {
            assertTrue(known.contains(name),
                    name + " is settled as transparent and is no longer a concrete widget; the "
                            + "entry is stale");
            assertFalse(describedAnywhereInItsAncestry(load(name)),
                    name + " was settled as transparent and now describes itself. Either the "
                            + "description is the mistake, or the entry is: a widget with a hook "
                            + "belongs on the undescribed list until it is struck off for real.");
        }
    }

    @Test
    void everyWidgetIsNamedInTheSurveyOrRecordedAsAbsentFromIt() {
        Set<String> named = surveyedNames();
        assertTrue(named.size() > 20,
                "ADR 039 §7's table was not found or could not be read; it named " + named.size()
                        + " things");
        Set<String> missing = new TreeSet<>();
        for (Class<?> widget : widgets()) {
            if (!namedAnywhereInItsAncestry(widget, named)) {
                missing.add(widget.getSimpleName());
            }
        }
        assertEquals(ABSENT_FROM_THE_SURVEY, missing,
                "a widget the survey does not name is a gap to record, not to leave. Add it to §7's "
                        + "table, or to the field above with the reason it is not there.");
    }

    /** Whether this class itself declares the describe hook. */
    private static boolean declaresItsOwn(Class<?> widget) {
        try {
            widget.getDeclaredMethod("onAccessibility", Accessibility.class);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    /** Whether anything between this class and {@link Widget} declares the describe hook. */
    private static boolean describedAnywhereInItsAncestry(Class<?> widget) {
        for (Class<?> at = widget; at != null && at != Widget.class; at = at.getSuperclass()) {
            if (declaresItsOwn(at)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namedAnywhereInItsAncestry(Class<?> widget, Set<String> named) {
        for (Class<?> at = widget; at != null && at != Widget.class; at = at.getSuperclass()) {
            if (named.contains(at.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every concrete widget the component and layout sources declare.
     *
     * <p>Concrete, because an abstract intermediate is never a node in a tree — but the check above
     * walks the ancestry, so describing one covers every concrete class under it at once, which is
     * the right place to describe a family.
     */
    private static List<Class<?>> widgets() {
        List<Class<?>> found = new ArrayList<>();
        for (Class<?> declared : declaredClasses()) {
            if (Widget.class.isAssignableFrom(declared)
                    && declared != Widget.class
                    && !Modifier.isAbstract(declared.getModifiers())) {
                found.add(declared);
            }
        }
        found.sort(java.util.Comparator.comparing(Class::getName));
        return found;
    }

    private static List<Class<?>> declaredClasses() {
        Path sources = repositoryRoot().resolve("limn-toolkit/src/main/java");
        List<Class<?>> all = new ArrayList<>();
        for (String pkg : List.of("limn/components", "limn/scene/layout")) {
            Path directory = sources.resolve(pkg);
            assertTrue(Files.isDirectory(directory), "no such source directory: " + directory);
            try (Stream<Path> files = Files.walk(directory)) {
                files.filter(file -> file.getFileName().toString().endsWith(".java"))
                        .filter(file -> !file.getFileName().toString().equals("package-info.java"))
                        .map(file -> sources.relativize(file).toString())
                        .map(name -> name.substring(0, name.length() - ".java".length())
                                .replace('/', '.').replace('\\', '.'))
                        .sorted()
                        .forEach(name -> collect(load(name), all));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return all;
    }

    /** Loaded without running its static initialisers: this test asks what a class is, not what it does. */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, AccessibleCoverageTest.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            throw new AssertionError("a source file declares " + name + " and no class answers to it",
                    absent);
        }
    }

    private static void collect(Class<?> type, List<Class<?>> into) {
        into.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collect(nested, into);
        }
    }

    /** Every identifier ADR 039 §7's table names, in backticks, between its heading and §7.1's. */
    private static Set<String> surveyedNames() {
        Path record = repositoryRoot().resolve("docs/adr")
                .resolve("039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-"
                        + "own-thread.md");
        List<String> lines;
        try {
            lines = Files.readAllLines(record, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Set<String> named = new LinkedHashSet<>();
        boolean inside = false;
        Pattern quoted = Pattern.compile("`([A-Za-z][A-Za-z0-9_.]*)`");
        for (String line : lines) {
            if (line.startsWith("## 7. ")) {
                inside = true;
                continue;
            }
            if (inside && line.startsWith("### 7.1")) {
                break;
            }
            if (!inside) {
                continue;
            }
            Matcher token = quoted.matcher(line);
            while (token.find()) {
                String name = token.group(1);
                // "ComboBox.PopupPanel" names the nested class; both halves count as named.
                for (String part : name.split("\\.")) {
                    named.add(part);
                }
            }
        }
        return named;
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.exists(directory.resolve("NOTICE"))
                    && Files.exists(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }
}
