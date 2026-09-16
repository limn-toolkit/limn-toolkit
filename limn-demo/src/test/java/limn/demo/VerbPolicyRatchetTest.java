package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.HeadlessWindow;
import limn.demo.a11y.Transcript;
import limn.components.DisplayMode;
import limn.concurrent.Subscription;
import limn.scene.Change;
import limn.scene.Scene;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR 039 §1.5's verb policy, ratcheted over the whole gallery (decision 2; semantics 5): <b>a
 * node accepts exactly the parameterless verbs it publishes.</b> The published snapshot is the
 * only synchronous authority a bridge has — {@code Host#perform} answers from it and posts, and
 * a later refusal on the UI thread reaches no platform — so a widget that performs a verb it did
 * not publish is a control a reader cannot see and one platform will invoke by accident, and a
 * widget that publishes a verb it refuses is a promise every platform breaks. This test performs,
 * on every node of every gallery entry, every parameterless verb the node does <em>not</em>
 * publish, and asserts that nothing moved: the transcript of every window is the same afterwards,
 * no window opened or closed, and no scene bound to any of the entry's windows announced a change.
 * A verb the host refuses from the snapshot is not "nothing moved" and not "accepted" either: it is
 * reported on its own ({@link Outcome}).
 *
 * <p>The published verbs themselves are each widget's own test's business; this is the
 * complement, and it is what stops a synonym being accepted in silence.
 *
 * <p>The ratchet runs twice over the gallery: as each entry is built, and again with every
 * surface that can float above the page asked to open as an overlay of the scene, and a menu bar's
 * first menu opened (ADR 039 §1.13, amended 2026-09-15). In both, before any verb is sent, it holds
 * the central rule on the entry's window: a node outside the layer that owns input — outside the
 * in-scene overlay's subtree, or anywhere in a window a native modal blocks — publishes no verb and
 * accepts no setter ({@code AccessibleNode#accepts}: a writable facet implies one only on an
 * {@code ENABLED} node, fix round 2e), because the scene refuses every one there.
 * {@link #IN_SCENE_OVERLAYS} names the entries whose second run has an overlay open, so the rule
 * cannot pass by an overlay failing to open. The rule is held on every window the entry ended up
 * with, not only its own.
 *
 * <p>Two more checks keep the rule honest in both directions (fix round 2d, 2026-09-15). In the
 * run with its surfaces in the scene, a node inside the open layer still publishes a verb and
 * performing one moves something, so a walk that withdrew every verb everywhere cannot pass. And
 * for the entries in {@link #FADE_OUT_ENTRIES} the surface is closed through the verb it
 * publishes and one frame of its fade-out is sampled: what is still drawn of the closing surface
 * publishes no verb but the two the scene performs itself on a focusable widget, because every
 * other verb those surfaces carry (an option's choice, a dialog button's answer, the dismissal)
 * resolves a surface that is already on its way out and is refused.
 *
 * <p>{@link #ALLOWLIST} names the nodes that accept an unpublished verb today, each keyed by the
 * item that owns the fix. An entry there is held to the opposite promise: the moment its node
 * refuses everything it did not publish, the entry is stale and the test says so, so the list
 * can only shrink.
 *
 * <p>A third pass, in both runs, holds the setters (phase 3 addendum, 2026-09-15): every node is
 * sent {@code SET_VALUE} and {@code SET_TEXT}, and {@code SET_CARET} and {@code SET_SELECTION}
 * where it has a text facet, each with an argument that differs from what it publishes, and
 * something moves exactly when {@link AccessibleNode#accepts} says the node takes it.
 * {@link #SETTER_ALLOWLIST} is empty since decision 66 struck its three lines.
 *
 * <p>And a fourth, which gives the parameterless half the setter pass's second direction
 * (decision 66, 2026-09-15): <b>a verb a node publishes moves something when it is performed</b>,
 * over the whole gallery rather than over one open layer. That is where a published verb the
 * scene's gate refuses in silence shows up, which is what the showing axis was
 * ({@code INCREMENT} on a slider in an unselected tab, {@code PRESS} on a button scrolled out of
 * view), and neither of the two passes above can see it.
 * {@link #movesNothingByDefinition} says which verbs are not asked and why, and
 * {@link #PERFORMED_UNSEEN}, which carried its one finding under the same shrink-only rule, is
 * empty since that finding was fixed.
 */
class VerbPolicyRatchetTest {

    /** Frames rendered after a verb is posted: one to run it, one to publish what it changed. */
    private static final int FRAMES_AFTER_A_VERB = 2;

    /**
     * Frames rendered after a verb that may dismiss a surface, for the published-verb pass: long
     * enough for a fade-out to finish and the window it was in to go.
     *
     * <p><b>Read, not guessed (2026-09-15, the phase-3 fix review).</b> Three numbers, in the order
     * they were taken.
     *
     * <ol>
     *   <li><b>What a surface actually takes.</b> Every surface in a window of its own leaves
     *       through {@code Scene#fadeWindowOut(Theme.current().animWindow, destroy)} —
     *       {@code ComboBox}, {@code PopupMenu}, {@code Dialog}, {@code DatePicker} — and
     *       {@code Theme.animWindow} is {@code 0.16} s. {@code Harness#settle} steps scene time by
     *       a fixed 20&nbsp;ms a frame, so the fade is <b>8 frames</b> and the destroy callback
     *       runs on the ninth. 24 frames is 480&nbsp;ms: three times that, and longer than the
     *       longest transition anywhere in the toolkit that a dismissal could still be waiting on
     *       ({@code Theme.animTab} 0.22 s, {@code ScrollBar}'s 0.28 s fade-out).</li>
     *   <li><b>It is the number the sibling suites already settle with</b>, for the same reason:
     *       {@code AccessibleGalleryTest.SETTLE_FRAMES} and
     *       {@code AccessibleTranscriptTest.SETTLE_FRAMES} are both 24 at the same 20&nbsp;ms step.
     *       A second settle width in the same harness would be a second answer to one question.</li>
     *   <li><b>It became load-bearing on 2026-09-15, and the floor was measured rather than
     *       assumed.</b> Re-run at five widths once a popup's scene came to run on its opener's
     *       clock ({@code Scene#clock}) and its window therefore really went: 248 tests, 0 failures
     *       at 9, 24 and 400 frames, and <b>2 failures at 2 and at 8</b> — "Popup menu, open" in
     *       both runs, four published verbs that "moved nothing": the cascade's {@code CANCEL} and
     *       {@code PRESS} on each of Cut, Copy and Paste. That is exactly the fade: what a
     *       dismissal in a window of its own changes is the window's opacity, which no tree
     *       publishes, so nothing a harness can read moves until the destroy — the ninth frame, the
     *       fade's eight plus the one that runs the callback. Before that fix the same four were
     *       still unseen at 400 frames, and were carried as {@link #PERFORMED_UNSEEN}.
     *       24 is nearly three times that floor — room for every other transition a dismissal could
     *       be waiting on — and is green at the top of the range too, so nothing depends on the
     *       upper end.</li>
     * </ol>
     */
    private static final int FRAMES_FOR_A_SURFACE_TO_GO = 24;

    /**
     * A node that accepts a verb it does not publish, owed to a named item. Matched by the entry
     * it appears in, the node's role and the node's name; {@code verbs} says which unpublished
     * verbs it accepts, so an entry cannot cover more than what was found.
     *
     * @param item   the item id that owns the fix, and the lane it is in
     * @param entry  the gallery entry's name
     * @param role   the node's role
     * @param name   the node's name, or {@code null} for every node of that role in the entry
     * @param verbs  the unpublished verbs the node accepts today
     */
    record Exemption(String item, String entry, Accessible.Role role, String name,
                     Set<Accessible.Action> verbs) {
        boolean covers(String inEntry, AccessibleNode node, Accessible.Action verb) {
            return entry.equals(inEntry) && role == node.role()
                    && (name == null || name.equals(node.name())) && verbs.contains(verb);
        }
    }

    /**
     * What accepts an unpublished verb today, and who empties each line: the widget lanes of the
     * 2026-09-13 pass. Empty when the policy holds everywhere. Found by this test's first run
     * (2026-09-14) over the gallery as it then stood; the DatePicker group (decision 18) and the
     * calendar title (WINDOWS-NEW-10) the pass expected here are not: the open picker's group
     * sits under a modal overlay and refuses everything, and the title refuses {@code EXPAND}
     * rather than accepting it — that defect is a bridge vending a pattern, not a widget
     * accepting a verb, and semantics 5 closes it on the bridge.
     */
    static final List<Exemption> ALLOWLIST = List.of(
            // The three CRIT-1 lines (menu titles and a submenu row accepting EXPAND and PRESS
            // unpublished) were struck on 2026-09-14: the titles and the rows publish EXPAND
            // while closed and COLLAPSE while open, and refuse PRESS.
            // The two TABLE-NEW-13 lines (a cell and a column header accepting SELECT, their keys
            // read as row indices) were struck the same day: cells are keyed by row and column
            // together and header cells distinctly, and each publishes the verbs it accepts.
    );

    /**
     * The entries whose run with its surfaces in the scene has an in-scene overlay open at rest.
     * Exactly these: an entry that opens one and is not listed, or is listed and opens none,
     * fails, so the central rule is never held over a scene that has nothing covering it. The
     * popup menu and the dialog in its own window are built open inside the entry, where no
     * presentation can be asked of them first, and stay windows of their own.
     */
    static final Set<String> IN_SCENE_OVERLAYS = Set.of(
            "Combo box, open", "Date picker, open", "Menu bar", "Dialog, in the scene",
            "Colour picker button, open");

    /**
     * The entries whose open surface is closed and sampled one frame into its fade-out, in both
     * runs: a combo's list (a window of its own, then an overlay of the scene) and a dialog (a
     * window, and an overlay) and a dialog (a window, and an overlay). Where the fade-out defects
     * lived (fdd9533 and fix round 2d). Only surfaces whose every verb resolves them belong here:
     * a surface holding controls of its own keeps them operable through its fade, and they
     * rightly publish their verbs; those are {@link #FADE_OUT_DISMISSAL_ENTRIES}. The sample
     * recognises the closing surface by where it was at rest — the subtree of the {@code MODAL}
     * node, or the windows beyond the first — and not by a role, which until the 2d review left
     * the date picker's calendar out of reach.
     */
    static final Set<String> FADE_OUT_ENTRIES = Set.of(
            "Combo box, open", "Dialog, in its own window", "Dialog, in the scene");

    /**
     * The entries sampled one frame into the fade-out as {@link #FADE_OUT_ENTRIES} are, where
     * only the dismissal is held: the closing surface publishes no {@code CANCEL} and no
     * {@code COLLAPSE}, because the close those name has already happened and the hook's own
     * guard drops a second one. The controls inside — a date picker's calendar days and paging
     * buttons, a colour picker's rails — still perform their verbs through the fade, as the
     * pointer does, and keep publishing them (2d review, 2026-09-15: a day's SELECT one frame
     * after the close picked the date and notified the application).
     */
    static final Set<String> FADE_OUT_DISMISSAL_ENTRIES = Set.of(
            "Date picker, open", "Colour picker button, open");

    // ------------------------------------------------------------------------- the ratchet

    @TestFactory
    Stream<DynamicTest> everyNodeRefusesEveryVerbItDoesNotPublish() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> check(entry, false)));
        }
        return tests.stream();
    }

    /**
     * The same ratchet with every surface that can be an overlay of the scene presented as one
     * and opened where the entry opens it, and a menu bar's first menu down: the layer beneath
     * such an overlay is where a published verb the scene refuses was found (fix round 2c,
     * 2026-09-15), so the rule is ratcheted there too.
     */
    @TestFactory
    Stream<DynamicTest> everyNodeRefusesEveryVerbItDoesNotPublishWithItsSurfacesInTheScene() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> check(entry, true)));
        }
        return tests.stream();
    }

    /**
     * The setters' half of semantics 5 (fix round 2e review; gallery brief, phase 3 addendum a):
     * on every node of every entry, {@code SET_VALUE} and {@code SET_TEXT} are sent with an
     * argument that differs from what the node publishes, and {@code SET_CARET} and
     * {@code SET_SELECTION} too wherever a text facet exists, and something moves exactly when
     * {@link AccessibleNode#accepts} says the node takes that setter. {@code accepts} is the
     * toolkit's one reading of "does this node accept this verb now" and every bridge refuses
     * through it, so a widget that performs a setter its node does not accept is one a platform
     * invokes against the snapshot's word, and one that accepts a setter and does nothing is a
     * promise every platform breaks.
     */
    @TestFactory
    Stream<DynamicTest> everySetterMovesSomethingExactlyWhenTheNodeAcceptsIt() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> checkSetters(entry, false)));
        }
        return tests.stream();
    }

    /**
     * The parameterless half's other direction, the one the setter pass has had since fix round 2e
     * and this one did not (decision 66, 2026-09-15): <b>a verb a node publishes moves something
     * when it is performed.</b> The pass above proves a node refuses what it does not publish,
     * which a walk that withdrew every verb from everything would satisfy; this is what refuses
     * that walk, over the whole gallery rather than over one open layer.
     *
     * <p>It is what makes the showing axis a ratchet. GALLERY-NEW-1's parameterless half was
     * exactly this shape — {@code INCREMENT} on a colour picker's sliders in a tab nobody selected,
     * {@code PRESS} on a button scrolled out of a scroll view — a verb published, answered yes by
     * {@code Host#perform} from the snapshot, and dropped by the scene's gate in silence. Neither
     * the unpublished-verb pass nor the setter pass can see it.
     *
     * <p>The two free verbs are not asked, for the reason
     * {@link #checkTheOpenLayerIsStillOperable} does not ask them: focusing a node that has the
     * focus, and revealing one already in view, rightly move nothing. {@link #IDEMPOTENT} names
     * the remaining verbs that mean "be in this state" rather than "do this thing", each on the
     * nodes already in that state, and holds them to the same staleness rule as the two allowlists.
     */
    @TestFactory
    Stream<DynamicTest> everyPublishedVerbMovesSomething() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> checkPublished(entry, false)));
        }
        return tests.stream();
    }

    /** The published-verb pass with every surface that can be an overlay of the scene as one. */
    @TestFactory
    Stream<DynamicTest> everyPublishedVerbMovesSomethingWithItsSurfacesInTheScene() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> checkPublished(entry, true)));
        }
        return tests.stream();
    }

    /** The setter pass with every surface that can be an overlay of the scene presented as one. */
    @TestFactory
    Stream<DynamicTest> everySetterMovesSomethingExactlyWhenTheNodeAcceptsItWithItsSurfacesInTheScene() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> checkSetters(entry, true)));
        }
        return tests.stream();
    }

    /**
     * A setter that a node accepts by {@link AccessibleNode#accepts} and the scene refuses
     * anyway, owed to a named finding. Covers a node only while it is published without
     * {@code SHOWING}, the one axis the found defect is on, so it cannot hide a showing node that
     * accepts and does nothing.
     *
     * @param item   the finding that owns the fix
     * @param entry  the gallery entry's name
     * @param roles  the roles of the nodes it covers
     * @param names  their names
     * @param setter the setter those nodes accept and the scene refuses
     */
    record SetterExemption(String item, String entry, Set<Accessible.Role> roles, Set<String> names,
                           Accessible.Action setter) {
        boolean covers(String inEntry, AccessibleNode node, Accessible.Action sent) {
            return entry.equals(inEntry) && setter == sent && roles.contains(node.role())
                    && names.contains(node.name()) && !node.has(Accessible.State.SHOWING);
        }
    }

    /**
     * GALLERY-NEW-1, found by the setter pass's first run (2026-09-15) and not this lane's to
     * settle: a widget that is not showing — a colour picker's sliders in a tab that is not
     * selected, a media bar's volume slider while the bar is hidden — is published
     * {@code ENABLED} with a writable value, so it accepts {@code SET_VALUE}, and
     * {@code Scene#performAccessibleAction} refuses every verb but the two free ones on an owner
     * that is not showing. The same gate refuses the parameterless verbs such nodes publish
     * ({@code INCREMENT} on those sliders, {@code PRESS} on a button scrolled out of a scroll
     * view), which the unpublished-verb pass above cannot see. Which side moves (the walk
     * withholding, or the gate performing) is the orchestrator's call; the list can only shrink.
     */
    static final List<SetterExemption> SETTER_ALLOWLIST = List.of(
            // The three GALLERY-NEW-1 lines (a colour picker's H/S/V/C/M/Y/K sliders and spin
            // buttons in a tab nobody selected, twice, and a hidden media bar's Volume slider)
            // were struck on 2026-09-15 by decision 66: none of those nodes is VISIBLE, so none
            // is published with a verb and none accepts a setter any more.
    );

    /**
     * A verb that means "be in this state" rather than "do this thing", and so moves nothing when
     * the node is already in it. Not an exemption for a defect: it is the same reason the two free
     * verbs are never asked. It covers a node only while the state it names is already set, so it
     * cannot hide a verb that does nothing on a node the state is clear on.
     *
     * @param verb  the verb
     * @param state the state whose presence makes it a no-op
     */
    record Idempotent(Accessible.Action verb, Accessible.State state) {
        boolean covers(AccessibleNode node, Accessible.Action sent) {
            return verb == sent && node.has(state);
        }
    }

    /**
     * The verbs that are a state and not an act. {@code SELECT} and {@code ADD_TO_SELECTION} on a
     * node already {@code SELECTED}: each is what the reader asked for already being true, and a
     * widget that made a change to say so would be announcing a selection that did not move.
     * {@code EXPAND} and {@code COLLAPSE} are not here: a node publishes {@code EXPAND} only while
     * collapsed and {@code COLLAPSE} only while open (decision 2), so neither is ever asked of a
     * node already there.
     */
    static final List<Idempotent> IDEMPOTENT = List.of(
            new Idempotent(Accessible.Action.SELECT, Accessible.State.SELECTED),
            new Idempotent(Accessible.Action.ADD_TO_SELECTION, Accessible.State.SELECTED));

    /**
     * Whether this verb on this node moves nothing <b>by definition</b> rather than because
     * anything is wrong, so the published-verb pass does not ask it. Three shapes, and each one is
     * a property of the node the pass can read rather than a name on a list:
     *
     * <ul>
     *   <li>the two free verbs, for the reason {@link #checkTheOpenLayerIsStillOperable} does not
     *       ask them: focusing what has the focus and revealing what is in view;</li>
     *   <li>{@link #IDEMPOTENT}, a verb that names a state the node is already in;</li>
     *   <li><b>a step past the end of a range.</b> {@code INCREMENT} on a value already at its
     *       maximum and {@code DECREMENT} on one at its minimum are a no-op wherever the value
     *       lives — a scroll bar parked at the top, a colour picker's alpha at 100. The range may
     *       be the parent's: a spin button's {@code Increase} and {@code Decrease} chevrons are
     *       children of the spinner and carry no value of their own, and pressing the dead one of
     *       the pair does nothing. That last case is the loosest test here, because a chevron's
     *       direction is not in the model: at a limit neither chevron is asked, so a broken live
     *       one would go unseen. It is the shape decision 30 would rather close on the widget, by
     *       narrowing the dead chevron with {@code Accessibility#disabled()} as a scroll chevron
     *       with nothing left to scroll already is; until a widget lane does that, the pass does
     *       not report it.</li>
     * </ul>
     */
    private static boolean movesNothingByDefinition(AccessibleTree tree, AccessibleNode node,
                                                    Accessible.Action verb) {
        if (verb == Accessible.Action.FOCUS || verb == Accessible.Action.SCROLL_INTO_VIEW) {
            return true;
        }
        for (Idempotent entry : IDEMPOTENT) {
            if (entry.covers(node, verb)) {
                return true;
            }
        }
        limn.accessibility.ValueFacet own = node.value();
        if (own != null && !own.empty()) {
            return verb == Accessible.Action.INCREMENT && own.value() >= own.max()
                    || verb == Accessible.Action.DECREMENT && own.value() <= own.min();
        }
        if (verb != Accessible.Action.PRESS || node.parent() == AccessibleNode.NONE) {
            return false;
        }
        AccessibleNode parent = tree.node(node.parent());
        limn.accessibility.ValueFacet range = parent.value();
        return parent.role() == Accessible.Role.SPIN_BUTTON && range != null && !range.empty()
                && (range.value() >= range.max() || range.value() <= range.min());
    }

    /**
     * One entry, every node, every parameterless verb the node publishes. A verb that moved
     * something restarts the entry, as an accepted verb does in the main pass, so the next one is
     * asked of the tree the entry publishes at rest.
     */
    private static void checkPublished(Entry entry, boolean inScene) {
        List<String> violations = new ArrayList<>();
        Set<Exemption> used = new LinkedHashSet<>();
        Run run = new Run(entry, inScene);
        try {
            for (int w = 0; w < run.windows.size(); w++) {
                for (int i = 0; i < run.windows.get(w).bridge().tree().nodeCount(); i++) {
                    for (Accessible.Action verb : Accessible.Action.values()) {
                        AccessibleTree tree = run.windows.get(w).bridge().tree();
                        AccessibleNode node = tree.node(i);
                        if (!verb.isParameterless() || node.actions() == null
                                || !node.actions().has(verb)
                                || movesNothingByDefinition(tree, node, verb)) {
                            continue;
                        }
                        Outcome outcome = run.perform(w, node.id(), verb,
                                Accessible.Argument.NONE, FRAMES_FOR_A_SURFACE_TO_GO);
                        if (outcome.refused()) {
                            violations.add(describe(node) + " was refused " + verb + " by the "
                                    + "host from the snapshot it was read from; the harness "
                                    + "read a stale tree");
                        } else if (!outcome.movedSomething()) {
                            Exemption exemption = unseenFor(entry.name(), node, verb);
                            if (exemption != null) {
                                used.add(exemption);
                            } else {
                                violations.add(describe(node) + " publishes " + verb
                                        + " and performing it moved nothing"
                                        + (node.has(Accessible.State.SHOWING) ? ""
                                        : " (it is published without SHOWING, which is decision "
                                        + "66's axis: the scene must reveal it and perform)"));
                            }
                        }
                        run.close();
                        run = new Run(entry, inScene);
                    }
                }
            }
        } finally {
            run.close();
        }
        if (!violations.isEmpty()) {
            fail("gallery entry \"" + entry.name() + "\"" + (inScene ? " with its surfaces in the "
                    + "scene" : "") + ": " + violations.size() + " published verb(s) the scene "
                    + "does not perform (ADR 039 §1.5 and §1.9, amended 2026-09-15; decision 66): "
                    + "a verb a node publishes is a promise every platform makes for it:\n  "
                    + String.join("\n  ", violations));
        }
        for (Exemption exemption : PERFORMED_UNSEEN) {
            if (exemption.entry().equals(entry.name()) && !used.contains(exemption)) {
                fail("the performed-but-unseen entry " + exemption + " is stale: every node it "
                        + "names in \"" + entry.name() + "\"" + (inScene ? " with its surfaces in "
                        + "the scene" : "") + " now moves something the harness can see; strike "
                        + "it off");
            }
        }
    }

    /**
     * What the scene performs and this harness cannot see, each keyed by the item that owns the
     * fix, under the same shrink-only rule as {@link #ALLOWLIST}: the moment the harness sees it,
     * the entry is stale and the test says so.
     *
     * <p><b>Empty since 2026-09-15.</b> Its one finding — in "Popup menu, open", a popup that is a
     * window of its own, pressing a row and cancelling the cascade both reached the widget, both
     * returned done, and the window was still open with the same tree twenty-four frames later, and
     * four hundred — was the harness defect it was diagnosed as, and it was in the toolkit rather
     * than in the backend: the cascade's own scene was built with no clock, so the real-time fade
     * whose last frame destroys the window advanced by the microseconds the frames actually took
     * while its opener's clock was told that seconds had passed. A surface opened in a window of its
     * own now takes its opener's clock ({@code Scene#clock}), the two exemptions went stale, and
     * this list holds the promise the other way round: the moment the harness sees what the scene
     * performed, an entry here is stale and the test says so.
     */
    static final List<Exemption> PERFORMED_UNSEEN = List.of(
            // The two "headless native-popup teardown" lines (a cascade's GROUP accepting CANCEL
            // and its MENU_ITEM rows accepting PRESS, each moving nothing a harness could see)
            // were struck on 2026-09-15: the popup's scene runs on its opener's clock, its
            // fade-out ends, and the window it destroys is gone by the ninth frame
            // (NativePopupTeardownTest).
    );

    private static Exemption unseenFor(String entry, AccessibleNode node, Accessible.Action verb) {
        for (Exemption exemption : PERFORMED_UNSEEN) {
            if (exemption.covers(entry, node, verb)) {
                return exemption;
            }
        }
        return null;
    }

    /** The four setters, in the order they are sent to each node. */
    private static final List<Accessible.Action> SETTERS = List.of(Accessible.Action.SET_VALUE,
            Accessible.Action.SET_TEXT, Accessible.Action.SET_CARET, Accessible.Action.SET_SELECTION);

    /**
     * One entry, every node, every setter with a changed argument. A setter that moved something
     * restarts the entry, as an accepted verb does in the main pass.
     */
    private static void checkSetters(Entry entry, boolean inScene) {
        List<String> violations = new ArrayList<>();
        Set<SetterExemption> used = new LinkedHashSet<>();
        Run run = new Run(entry, inScene);
        try {
            for (int w = 0; w < run.windows.size(); w++) {
                for (int i = 0; i < run.windows.get(w).bridge().tree().nodeCount(); i++) {
                    for (Accessible.Action setter : SETTERS) {
                        AccessibleNode node = run.windows.get(w).bridge().tree().node(i);
                        Accessible.Argument changed = changedArgument(node, setter);
                        if (changed == null) {
                            continue;
                        }
                        boolean accepts = node.accepts(setter);
                        Outcome outcome = run.perform(w, node.id(), setter, changed);
                        if (outcome.refused()) {
                            violations.add(describe(node) + " was refused " + setter + " " + changed
                                    + " by the host from the snapshot it was read from; the "
                                    + "harness read a stale tree");
                        } else if (accepts && !outcome.movedSomething()) {
                            SetterExemption exemption = setterExemptionFor(entry.name(), node,
                                    setter);
                            if (exemption != null) {
                                used.add(exemption);
                            } else {
                                violations.add(describe(node) + " accepts " + setter + " and "
                                        + changed + " moved nothing: " + setterFacts(node));
                            }
                        } else if (!accepts && outcome.movedSomething()) {
                            violations.add(describe(node) + " does not accept " + setter + " ("
                                    + setterFacts(node) + ") and " + changed + " moved something: "
                                    + outcome.moved());
                        }
                        if (outcome.refused() || outcome.movedSomething()) {
                            run.close();
                            run = new Run(entry, inScene);
                        }
                    }
                }
            }
        } finally {
            run.close();
        }
        if (!violations.isEmpty()) {
            fail("gallery entry \"" + entry.name() + "\"" + (inScene ? " with its surfaces in the "
                    + "scene" : "") + ": " + violations.size() + " setter(s) whose effect "
                    + "disagrees with AccessibleNode#accepts (semantics 5, amended 2026-09-15):\n  "
                    + String.join("\n  ", violations));
        }
        for (SetterExemption exemption : SETTER_ALLOWLIST) {
            if (exemption.entry().equals(entry.name()) && !used.contains(exemption)) {
                fail("the setter allowlist entry " + exemption + " is stale: no node it names in \""
                        + entry.name() + "\"" + (inScene ? " with its surfaces in the scene" : "")
                        + " accepts that setter and moves nothing any more; strike it off");
            }
        }
    }

    private static SetterExemption setterExemptionFor(String entry, AccessibleNode node,
                                                      Accessible.Action setter) {
        for (SetterExemption exemption : SETTER_ALLOWLIST) {
            if (exemption.covers(entry, node, setter)) {
                return exemption;
            }
        }
        return null;
    }

    /**
     * An argument that differs from what the node publishes for {@code setter}, or {@code null}
     * where none can: a caret or a selection in an empty text has nowhere else to go. A node with
     * no facet for the setter is still sent one, which it must refuse.
     */
    static Accessible.Argument changedArgument(AccessibleNode node, Accessible.Action setter) {
        return switch (setter) {
            case SET_VALUE -> {
                limn.accessibility.ValueFacet value = node.value();
                if (value == null) {
                    yield new Accessible.Argument.OfValue(1);
                }
                if (value.empty()) {
                    yield new Accessible.Argument.OfValue(value.min());
                }
                if (value.max() > value.min()) {
                    yield new Accessible.Argument.OfValue(
                            value.value() == value.min() ? value.max() : value.min());
                }
                yield new Accessible.Argument.OfValue(value.value() + 1);
            }
            case SET_TEXT -> new Accessible.Argument.OfText(
                    node.text() == null ? "changed" : node.text().text() + " changed");
            case SET_CARET -> {
                limn.accessibility.TextFacet text = node.text();
                if (text == null) {
                    yield new Accessible.Argument.OfRange(0, 0);
                }
                int length = text.text().length();
                if (length == 0) {
                    yield null;
                }
                int to = text.caretOffset() != 0 ? 0 : length;
                yield new Accessible.Argument.OfRange(to, to);
            }
            case SET_SELECTION -> {
                limn.accessibility.TextFacet text = node.text();
                if (text == null) {
                    yield new Accessible.Argument.OfRange(0, 1);
                }
                int length = text.text().length();
                if (length == 0) {
                    yield null;
                }
                boolean whole = Math.min(text.selectionStart(), text.selectionEnd()) == 0
                        && Math.max(text.selectionStart(), text.selectionEnd()) == length;
                yield whole ? new Accessible.Argument.OfRange(0, 1)
                        : new Accessible.Argument.OfRange(0, length);
            }
            default -> throw new IllegalArgumentException(setter + " is not a setter");
        };
    }

    /** What {@code accepts} reads for a setter, for a message. */
    private static String setterFacts(AccessibleNode node) {
        return "value=" + node.value() + ", text=" + (node.text() == null ? "none"
                : "\"" + node.text().text() + "\"") + ", enabled="
                + node.has(Accessible.State.ENABLED) + ", readOnly="
                + node.has(Accessible.State.READ_ONLY);
    }

    /** An exemption names an entry that exists, so a renamed entry cannot orphan one in silence. */
    @Test
    void everyExemptionNamesAGalleryEntry() {
        for (Exemption exemption : ALLOWLIST) {
            AccessibilityGallery.entry(exemption.entry());
        }
        for (SetterExemption exemption : SETTER_ALLOWLIST) {
            AccessibilityGallery.entry(exemption.entry());
        }
        for (Exemption exemption : PERFORMED_UNSEEN) {
            AccessibilityGallery.entry(exemption.entry());
        }
    }

    /**
     * One entry: every node, every unpublished parameterless verb. The tree is read by index
     * rather than by identifier, because an accepted verb rebuilds the entry from scratch to
     * continue from a clean state, and a fresh build mints fresh identifiers over the same shape.
     */
    private static void check(Entry entry, boolean inScene) {
        List<String> violations = new ArrayList<>();
        Set<Exemption> used = new LinkedHashSet<>();
        Run run = new Run(entry, inScene);
        try {
            checkNothingOutsideTheInputLayerIsOperable(entry, inScene, run.windows);
            if (inScene && IN_SCENE_OVERLAYS.contains(entry.name())) {
                checkTheOpenLayerIsStillOperable(entry, run);
                run.close();
                run = new Run(entry, inScene);
            }
            if (FADE_OUT_ENTRIES.contains(entry.name())
                    || FADE_OUT_DISMISSAL_ENTRIES.contains(entry.name())) {
                run.close();
                checkAFadeOutFrame(entry, inScene, FADE_OUT_ENTRIES.contains(entry.name()));
                run = new Run(entry, inScene);
            }
            for (int w = 0; w < run.windows.size(); w++) {
                for (int i = 0; i < run.windows.get(w).bridge().tree().nodeCount(); i++) {
                    for (Accessible.Action verb : Accessible.Action.values()) {
                        if (!verb.isParameterless()) {
                            continue;
                        }
                        AccessibleNode node = run.windows.get(w).bridge().tree().node(i);
                        if (node.actions() != null && node.actions().has(verb)) {
                            continue;
                        }
                        Outcome outcome = run.perform(w, node.id(), verb);
                        if (outcome.refused()) {
                            // Not a node accepting anything, and not nothing either: the node
                            // was read off the tree this very run published.
                            violations.add(describe(node) + " was refused " + verb + " by the "
                                    + "host from the snapshot it was read from; the harness "
                                    + "read a stale tree"
                                    + (outcome.movedSomething() ? ", and " + outcome.moved() : ""));
                        } else if (!outcome.movedSomething()) {
                            continue;
                        } else {
                            Exemption exemption = exemptionFor(entry.name(), node, verb);
                            if (exemption != null) {
                                used.add(exemption);
                            } else {
                                violations.add(describe(node) + " accepted " + verb
                                        + ", which it does not publish (" + published(node)
                                        + "): " + outcome.moved());
                            }
                        }
                        // Whatever it did, it did: start the entry over so the next verb is
                        // asked of the tree the entry publishes at rest.
                        run.close();
                        run = new Run(entry, inScene);
                    }
                }
            }
        } finally {
            run.close();
        }
        if (!violations.isEmpty()) {
            fail("gallery entry \"" + entry.name() + "\": " + violations.size()
                    + " unpublished verb(s) accepted, or refused by the host from a snapshot the "
                    + "node was read from (ADR 039 §1.5, amended 2026-09-14: a node accepts "
                    + "exactly the parameterless verbs it publishes):\n  "
                    + String.join("\n  ", violations));
        }
        for (Exemption exemption : ALLOWLIST) {
            if (exemption.entry().equals(entry.name()) && !used.contains(exemption)) {
                fail("the allowlist entry " + exemption + " is stale: no node of that role and "
                        + "name in \"" + entry.name() + "\" accepts any of those unpublished verbs "
                        + "any more; strike it off");
            }
        }
    }

    /**
     * The central rule on every window of the entry at rest (ADR 039 §1.9 and §1.13, amended
     * 2026-09-15; semantics 5): a node outside the layer that owns input publishes no verb and
     * accepts no setter. A setter is read through {@link AccessibleNode#accepts}, not off the
     * facet's writability: such a node keeps the writable value or the editable text it really
     * has and is published without {@code ENABLED}, which is what withdraws the setter (fix round
     * 2e, 2026-09-15). Outside is outside the subtree of the node published
     * {@code MODAL} (the top in-scene overlay of that window), or anywhere in a window whose own
     * node is not {@code ENABLED} (a native modal blocks it). With its surfaces in the scene, an
     * overlay is open in some window exactly when {@link #IN_SCENE_OVERLAYS} names the entry.
     */
    private static void checkNothingOutsideTheInputLayerIsOperable(Entry entry, boolean inScene,
                                                                  List<HeadlessWindow> windows) {
        boolean anyModal = false;
        StringBuilder all = new StringBuilder();
        for (HeadlessWindow window : windows) {
            AccessibleTree tree = window.bridge().tree();
            anyModal |= modalOf(tree) >= 0;
            all.append(Transcript.of(tree));
            checkNothingOutsideTheInputLayerIsOperable(entry, inScene, tree);
        }
        if (inScene) {
            boolean expected = IN_SCENE_OVERLAYS.contains(entry.name());
            if (expected != anyModal) {
                fail("gallery entry \"" + entry.name() + "\" with its surfaces in the scene "
                        + (expected ? "opened no in-scene overlay, which IN_SCENE_OVERLAYS says "
                        + "it does" : "opened an in-scene overlay IN_SCENE_OVERLAYS does not "
                        + "name; add it") + ":\n" + all);
            }
        }
    }

    /** @return the index of the last node published {@code MODAL}, or {@code -1} */
    private static int modalOf(AccessibleTree tree) {
        int modal = -1;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).has(Accessible.State.MODAL)) {
                modal = i;
            }
        }
        return modal;
    }

    /** {@link #checkNothingOutsideTheInputLayerIsOperable(Entry, boolean, List)} on one tree. */
    private static void checkNothingOutsideTheInputLayerIsOperable(Entry entry, boolean inScene,
                                                                  AccessibleTree tree) {
        int modal = modalOf(tree);
        boolean blocked = !tree.node(0).has(Accessible.State.ENABLED);
        if (modal < 0 && !blocked) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (!blocked && isWithin(tree, i, modal)) {
                continue;
            }
            AccessibleNode node = tree.node(i);
            if (node.actions() != null) {
                violations.add(describe(node) + " " + published(node));
            }
            if (node.accepts(Accessible.Action.SET_VALUE)) {
                violations.add(describe(node) + " accepts SET_VALUE");
            }
            if (node.accepts(Accessible.Action.SET_TEXT)) {
                violations.add(describe(node) + " accepts SET_TEXT");
            }
        }
        if (!violations.isEmpty()) {
            fail("gallery entry \"" + entry.name() + "\"" + (inScene ? " with its surfaces in the "
                    + "scene" : "") + ": " + violations.size() + " node(s) outside the layer that "
                    + "owns input publish an operation the scene refuses (ADR 039 §1.13, amended "
                    + "2026-09-15):\n  " + String.join("\n  ", violations) + "\n"
                    + Transcript.of(tree));
        }
    }

    /**
     * The other direction, with the surfaces in the scene: inside the open layer a node still
     * publishes a verb that moves something when performed. The central rule only ever withdraws,
     * so a walk that withdrew every verb from every node would satisfy it and the complement at
     * once; this is what refuses that walk. The two free verbs are not asked, because focusing a
     * node that has the focus, or revealing one already in view, rightly moves nothing.
     */
    private static void checkTheOpenLayerIsStillOperable(Entry entry, Run run) {
        for (int w = 0; w < run.windows.size(); w++) {
            AccessibleTree tree = run.windows.get(w).bridge().tree();
            int modal = modalOf(tree);
            if (modal < 0) {
                continue;
            }
            for (int i = modal; i < tree.nodeCount(); i++) {
                AccessibleNode node = run.windows.get(w).bridge().tree().node(i);
                if (!isWithin(run.windows.get(w).bridge().tree(), i, modal)
                        || node.actions() == null) {
                    continue;
                }
                for (Accessible.Action verb : node.actions().actions()) {
                    if (verb == Accessible.Action.FOCUS
                            || verb == Accessible.Action.SCROLL_INTO_VIEW) {
                        continue;
                    }
                    // A refusal from the snapshot moved nothing in the scene, whatever else it
                    // is, so it cannot stand for an operable layer.
                    Outcome outcome = run.perform(w, node.id(), verb);
                    if (!outcome.refused() && outcome.movedSomething()) {
                        return;
                    }
                }
            }
        }
        StringBuilder all = new StringBuilder();
        for (HeadlessWindow window : run.windows) {
            all.append(Transcript.of(window.bridge().tree()));
        }
        fail("gallery entry \"" + entry.name() + "\" with its surfaces in the scene: no node "
                + "inside the open layer publishes a verb that moves anything when performed, so "
                + "the layer that owns input offers a reader nothing to do (ADR 039 §1.13):\n"
                + all);
    }

    /**
     * Closes the entry's open surface through the verb it publishes for that — {@code CANCEL} on
     * a dialog or an in-scene list's layer, else {@code COLLAPSE} on the combo field whose list
     * is a window of its own — renders one frame, and holds the fading surface to the verb
     * policy: nothing still drawn of it publishes a verb other than the free pair on a focusable
     * widget, and the central rule holds on every window. The frame is always one of the fade:
     * the entry settled first, and a fade registered on a scene with no ticker running starts at
     * {@code dt == 0} ({@code Scene.tickAnimations}), wall clock or not. A sample that finds the
     * surface gone anyway is a failure and not a pass.
     *
     * @param whole whether every verb of the surface is held, or only its dismissal
     */
    private static void checkAFadeOutFrame(Entry entry, boolean inScene, boolean whole) {
        try (Run run = new Run(entry, inScene)) {
            if (!sampleAFadeOutFrame(entry, inScene, run, whole)) {
                StringBuilder all = new StringBuilder();
                for (HeadlessWindow window : run.harness.windows()) {
                    all.append(Transcript.of(window.bridge().tree()));
                }
                fail("gallery entry \"" + entry.name() + "\"" + (inScene ? " with its surfaces in "
                        + "the scene" : "") + ": one frame after closing its surface nothing of it "
                        + "was still published, so no fade-out frame was sampled:\n" + all);
            }
        }
    }

    /** @return whether a frame of the fade-out was sampled; {@code false} when it was missed */
    private static boolean sampleAFadeOutFrame(Entry entry, boolean inScene, Run run,
                                               boolean whole) {
        int windowsAtRest = run.harness.windows().size();
        boolean hadModal = modalOf(run.windows.get(0).bridge().tree()) >= 0;
        if (!run.closeTheOpenSurface()) {
            fail("gallery entry \"" + entry.name() + "\"" + (inScene ? " with its surfaces in "
                    + "the scene" : "") + ": no node publishes CANCEL or COLLAPSE to close "
                    + "its surface with:\n" + Transcript.of(run.windows.get(0).bridge().tree()));
        }
        run.harness.settle(1);
        List<HeadlessWindow> now = run.harness.windows();
        List<AccessibleNode> surface = new ArrayList<>();
        List<AccessibleTree> trees = new ArrayList<>();
        if (hadModal) {
            AccessibleTree tree = now.get(0).bridge().tree();
            int modal = modalOf(tree);
            for (int i = 0; modal >= 0 && i < tree.nodeCount(); i++) {
                if (isWithin(tree, i, modal)) {
                    surface.add(tree.node(i));
                }
            }
        } else if (now.size() == windowsAtRest) {
            for (int w = 1; w < now.size(); w++) {
                AccessibleTree tree = now.get(w).bridge().tree();
                for (int i = 0; i < tree.nodeCount(); i++) {
                    surface.add(tree.node(i));
                }
            }
        }
        // Still drawn when anything of it is still published: the layer's own MODAL node, or a
        // node other than a window's own in a window that was there at rest.
        boolean drawn = false;
        for (AccessibleNode node : surface) {
            drawn |= node.role() != Accessible.Role.WINDOW;
        }
        if (!drawn) {
            return false;
        }
        for (HeadlessWindow window : now) {
            trees.add(window.bridge().tree());
        }
        List<String> violations = new ArrayList<>();
        for (AccessibleNode node : surface) {
            if (node.actions() == null) {
                continue;
            }
            for (Accessible.Action verb : node.actions().actions()) {
                boolean free = (verb == Accessible.Action.FOCUS
                        || verb == Accessible.Action.SCROLL_INTO_VIEW)
                        && node.has(Accessible.State.FOCUSABLE);
                boolean dismissal = verb == Accessible.Action.CANCEL
                        || verb == Accessible.Action.COLLAPSE;
                if (whole ? !free : dismissal) {
                    violations.add(describe(node) + " publishes " + verb);
                }
            }
        }
        if (!violations.isEmpty()) {
            StringBuilder all = new StringBuilder();
            for (AccessibleTree tree : trees) {
                all.append(Transcript.of(tree));
            }
            fail("gallery entry \"" + entry.name() + "\"" + (inScene ? " with its surfaces in "
                    + "the scene" : "") + ", one frame into the fade-out of its closed surface: "
                    + violations.size() + " node(s) of the closing surface publish a verb its "
                    + "hook refuses once the surface is on its way out (semantics 5):\n  "
                    + String.join("\n  ", violations) + "\n" + all);
        }
        for (AccessibleTree tree : trees) {
            checkNothingOutsideTheInputLayerIsOperable(entry, inScene, tree);
        }
        return true;
    }

    private static boolean isWithin(AccessibleTree tree, int index, int ancestor) {
        for (int at = index; at != AccessibleNode.NONE; at = tree.node(at).parent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static Exemption exemptionFor(String entry, AccessibleNode node,
                                          Accessible.Action verb) {
        for (Exemption exemption : ALLOWLIST) {
            if (exemption.covers(entry, node, verb)) {
                return exemption;
            }
        }
        return null;
    }

    private static String describe(AccessibleNode node) {
        return node.role() + " \"" + node.name() + "\"";
    }

    private static String published(AccessibleNode node) {
        return node.actions() == null ? "no verb published" : "publishes " + node.actions().actions();
    }

    // -------------------------------------------------------------------------- one build

    /**
     * What one verb sent through a bridge's host did, kept in two halves that mean different
     * things (gallery brief item 5, 2026-09-15). A {@code refused} verb never reached the scene:
     * the host answered no from the snapshot, which it does only for a node the snapshot no longer
     * holds, and which is a harness that read a stale tree, not a node accepting anything. A
     * {@code moved} description is a side effect: a change some scene announced, a window opened
     * or closed, a transcript that differs.
     *
     * @param refused whether {@code Host#perform} refused the verb synchronously
     * @param moved   what moved, or {@code null} when nothing did
     */
    record Outcome(boolean refused, String moved) {
        boolean movedSomething() {
            return moved != null;
        }
    }

    /** One build of an entry, settled, with every window's scene watched while a verb runs. */
    private static final class Run implements AutoCloseable {
        final Harness harness = new Harness(Palette.LIGHT);
        final List<HeadlessWindow> windows;
        final List<String> changes = new ArrayList<>();

        Run(Entry entry, boolean inScene) {
            windows = new ArrayList<>(harness.show(inScene ? inTheScene(entry) : entry));
            if (inScene) {
                openAMenuBar(windows.get(0));
            }
            // Whatever the settle left in flight is not this test's: a verb is asked of a scene
            // that has gone quiet, and only what it does after that counts.
            harness.settle(FRAMES_AFTER_A_VERB);
        }

        /**
         * Watches every scene bound to a window the backend holds now: the entry's, and a native
         * popup's, a dialog's or a menu's that the entry opened as a window of its own. Until the
         * phase-1 critic's reading only the entry's first scene was watched, so a verb accepted
         * inside a second window that announced a change and left the transcripts equal passed.
         */
        private List<Subscription> watchEveryScene() {
            List<Subscription> watching = new ArrayList<>();
            for (HeadlessWindow window : harness.windows()) {
                Scene scene = window.scene();
                if (scene == null) {
                    continue;
                }
                String where = "window \"" + window.title() + "\"";
                watching.add(scene.observeChanges((source, change) -> {
                    if (change.aspect() != Change.Aspect.LAYOUT) {
                        changes.add(change.aspect() + "/" + change.origin() + " on "
                                + source.getClass().getSimpleName() + " in " + where);
                    }
                }));
            }
            return watching;
        }

        /**
         * The entry with every surface that can float above the page asked to open as an overlay
         * of the scene: a combo's list, a date picker's calendar, a menu bar's cascade and a
         * colour button's dialog. Set on the built tree before the harness binds it, so whatever
         * the entry opens after its first frame opens in the scene.
         */
        private static Entry inTheScene(Entry entry) {
            return new Entry(entry.name(), entry.covers(), entry.publishes(), () -> {
                AccessibilityGallery.Built built = entry.build();
                AccessibilityGallery.present(built.root(), DisplayMode.IN_SCENE);
                return built;
            }, entry.reader());
        }

        /**
         * Opens the first menu of a menu bar in the window, through the verb its title publishes,
         * because no entry opens one and beneath its cascade is where the defect this run holds
         * was found.
         */
        private void openAMenuBar(HeadlessWindow window) {
            AccessibleTree tree = window.bridge().tree();
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.role() == Accessible.Role.MENU_ITEM && node.parent() != AccessibleNode.NONE
                        && tree.node(node.parent()).role() == Accessible.Role.MENU_BAR
                        && node.actions() != null
                        && node.actions().has(Accessible.Action.SHOW_MENU)) {
                    assertTrue(window.bridge().host.perform(node.id(), Accessible.Action.SHOW_MENU,
                            Accessible.Argument.NONE));
                    harness.settle();
                    return;
                }
            }
        }

        /**
         * Sends the verb that closes the entry's open surface: {@code CANCEL} wherever it is
         * published first (a dialog's card, an in-scene list's layer), else {@code COLLAPSE} on a
         * combo field. Posted through the host as a bridge does, and not yet run.
         *
         * @return whether a node offered either verb
         */
        boolean closeTheOpenSurface() {
            for (Accessible.Action verb : List.of(Accessible.Action.CANCEL,
                    Accessible.Action.COLLAPSE)) {
                for (HeadlessWindow window : windows) {
                    AccessibleTree tree = window.bridge().tree();
                    for (int i = 0; i < tree.nodeCount(); i++) {
                        AccessibleNode node = tree.node(i);
                        if (node.actions() != null && node.actions().has(verb)) {
                            assertTrue(window.bridge().host.perform(node.id(), verb,
                                    Accessible.Argument.NONE));
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Performs one verb on one node the way a bridge does, and reads what moved in every
         * window.
         *
         * @return whether the host refused it, and what moved
         */
        Outcome perform(int window, long nodeId, Accessible.Action verb) {
            return perform(window, nodeId, verb, Accessible.Argument.NONE);
        }

        /** {@link #perform(int, long, Accessible.Action)} with an argument, for a setter. */
        Outcome perform(int window, long nodeId, Accessible.Action verb,
                        Accessible.Argument argument) {
            return perform(window, nodeId, verb, argument, FRAMES_AFTER_A_VERB);
        }

        /**
         * {@link #perform(int, long, Accessible.Action, Accessible.Argument)} with the settle
         * spelled out, for the published-verb pass: a verb that dismisses a surface in a window of
         * its own — a popup menu's row, its {@code CANCEL} — moves nothing anybody can see in two
         * frames, because what it started is a fade-out and the window is still there with the
         * same tree. It has moved by the time the fade is over.
         */
        Outcome perform(int window, long nodeId, Accessible.Action verb,
                        Accessible.Argument argument, int frames) {
            List<String> before = transcripts();
            int windowsBefore = harness.windows().size();
            changes.clear();
            List<Subscription> watching = watchEveryScene();
            boolean accepted;
            try {
                accepted = windows.get(window).bridge().host.perform(nodeId, verb, argument);
                harness.settle(frames);
            } finally {
                for (Subscription subscription : watching) {
                    subscription.cancel();
                }
            }
            List<String> after = transcripts();
            StringBuilder moved = new StringBuilder();
            if (!changes.isEmpty()) {
                moved.append("the scene announced ").append(changes).append("; ");
            }
            if (harness.windows().size() != windowsBefore) {
                moved.append("the window count went from ").append(windowsBefore).append(" to ")
                        .append(harness.windows().size()).append("; ");
            }
            for (int w = 0; w < Math.min(before.size(), after.size()); w++) {
                if (!before.get(w).equals(after.get(w))) {
                    moved.append("window ").append(w).append("'s transcript changed: ")
                            .append(firstDifference(before.get(w), after.get(w))).append("; ");
                }
            }
            return new Outcome(!accepted, moved.isEmpty() ? null : moved.toString());
        }

        private List<String> transcripts() {
            List<String> out = new ArrayList<>();
            for (HeadlessWindow window : harness.windows()) {
                AccessibleTree tree = window.bridge().tree();
                out.add(Transcript.of(tree));
            }
            return out;
        }

        private static String firstDifference(String before, String after) {
            String[] a = before.split("\n");
            String[] b = after.split("\n");
            StringBuilder out = new StringBuilder();
            if (a.length != b.length) {
                out.append(a.length).append(" lines became ").append(b.length).append("; ");
            }
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                String x = i < a.length ? a[i] : "(no line)";
                String y = i < b.length ? b[i] : "(no line)";
                if (!x.strip().equals(y.strip())) {
                    return out.append("[").append(x.strip()).append("] became [")
                            .append(y.strip()).append("]").toString();
                }
            }
            return out.append("(only the numbering moved)").toString();
        }

        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            harness.close();
        }
    }

    /** The allowlist's shape is documented above; this keeps the class from being empty-tested. */
    @Test
    void theAllowlistIsKeyedByAnItemId() {
        for (Exemption exemption : ALLOWLIST) {
            assertTrue(exemption.item().matches("[A-Z]+(-[A-Z]+)*-?[0-9]+.*|decision [0-9]+.*"),
                    "an allowlist entry is keyed by the item or decision that owns the fix: "
                            + exemption);
        }
        for (SetterExemption exemption : SETTER_ALLOWLIST) {
            assertTrue(exemption.item().matches("[A-Z]+(-[A-Z]+)*-?[0-9]+.*"),
                    "a setter allowlist entry is keyed by the finding that owns the fix: "
                            + exemption);
        }
    }
}
