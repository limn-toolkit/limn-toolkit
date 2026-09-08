package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.TextFacet;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link PasswordField} becomes in the accessible tree: one {@code PASSWORD_FIELD} node
 * carrying {@code PASSWORD} and, in place of its contents, <em>the mask</em> — one bullet per
 * painted dot, with the caret and the selection in the mask's own offsets — and one
 * {@code TEXT_FIELD} node carrying the contents once the reveal toggle is on.
 *
 * <p>Six things ADR 039 §7's row is wrong or silent about, each pinned by a case below.
 * <b>The role and the state are not unconditional</b>: the row states {@code PASSWORD_FIELD} and
 * {@code PASSWORD} flat and conditions only the facet on {@code isRevealed()}, which publishes
 * "this text is masked and must never be spoken" beside the cleartext its own facet clause asks
 * for. <b>It is silent on the inverse mapping, which is where the bug is</b>: it maps a model
 * offset forward to a mask ordinal and says nothing about the caret verbs arriving in mask
 * ordinals at an inherited handler that consumes model {@code char} offsets. <b>It is silent on
 * the witness</b>, and the naive reading — let the base publish the revealed branch on its own
 * counter — collides on the first build and raises no text change on either toggle. <b>Its
 * "synthetic children: —" is wrong in the case that matters</b>: this class inherits the trailing
 * button, which is the obvious place to hang a reveal control. <b>It is silent on
 * {@code acceptsTextInput()}</b>, which is false unconditionally here, so the base's whole
 * composing branch is unreachable on this widget. And <b>it is silent on the affinity</b> of the
 * facet it specifies, which is a field the difference compares.
 *
 * <p>The fixture is {@code PasswordFieldTest}'s own secret, {@code "a" + U+1D11E + "b"}: three
 * grapheme clusters in four {@code char}s, and the one shape of string where a mask offset and a
 * model offset disagree. Every case drives the field's public API on a bound scene, or calls the
 * scene from where a bridge stands, and reads back what the scene published. Nothing constructs a
 * node.
 */
class PasswordFieldAccessibilityTest extends AccessibleComponentTestBase {

    /** MUSICAL SYMBOL G CLEF, U+1D11E: one code point, two chars, one grapheme cluster. */
    private static final String CLEF = "𝄞";

    /** Mixed BMP and astral: 3 clusters in 4 chars, so the two offset spaces cannot agree. */
    private static final String SECRET = "a" + CLEF + "b";

    /** What a reader is told one masked cluster is; the painted mark is a drawn circle. */
    private static final String DOT = "•";

    /** The padding between the column and the field, so scene and local coordinates differ. */
    private static final float INSET = 20;

    /** A glyph that draws nothing: enough of an icon for the trailing button to exist. */
    private static final Icon BLANK = new Icon() {
        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("never rasterized");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark) {
            // Drawn by the frame the fixture renders, and drawing nothing is the point.
        }
    };

    private static final I18nString REVEAL = I18nString.literal("Show password");

    private PasswordField field;

    /** The column everything is bound in, so the field keeps its own measured box. */
    private Column root;

    // ------------------------------------------------------------------------------ the fixture

    private void bindField() {
        field = new PasswordField();
        root = new Column();
        root.add(Padding.all(INSET, field));
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
    }

    /**
     * @return the field's node, found by the role the toggle is currently supposed to publish, so
     *         that a role which stopped following the flag fails every case rather than one
     */
    private AccessibleNode fieldNode() {
        return node(field.isRevealed()
                ? Accessible.Role.TEXT_FIELD : Accessible.Role.PASSWORD_FIELD);
    }

    /** @return the field's text facet, which this widget publishes in both branches */
    private TextFacet facet() {
        TextFacet published = fieldNode().text();
        assertNotNull(published, "the field publishes a facet in both branches" + describe(tree()));
        return published;
    }

    /** @return every event of {@code type} raised so far, in order */
    private List<AccessibleEvent> eventsOf(AccessibleEvent.Type type) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == type) {
                found.add(event);
            }
        }
        return found;
    }

    /**
     * Sweeps the whole published tree — every name, every description, every facet — for the
     * secret and for every piece of it.
     *
     * <p>Not the field's own node alone: the promise is that the value is nowhere a reader can
     * reach, and the way it would get out is by being handed to {@code a.name} or to {@code a.text}
     * from the display line, whose {@code text()} <em>is</em> the secret because its index space
     * has to be the model's.
     *
     * <p>The pieces are the runs between cluster boundaries, two {@code char}s and longer: a lone
     * {@code "a"} is not evidence of anything — the placeholder a real form carries has letters in
     * it — while {@code "a𝄞"}, {@code "𝄞b"}, the astral character alone and the whole value are
     * every leak a wrong hand-over could produce.
     */
    private void theSecretIsNowhereInTheTree() {
        int[] boundaries = {0, 1, 3, SECRET.length()};
        for (int from : boundaries) {
            for (int to : boundaries) {
                if (to - from < 2) {
                    continue;
                }
                String piece = SECRET.substring(from, to);
                for (int i = 0; i < tree().nodeCount(); i++) {
                    AccessibleNode any = tree().node(i);
                    assertFalse(any.name().contains(piece),
                            "node " + i + " is named with \"" + piece + "\"" + describe(tree()));
                    assertFalse(any.description().contains(piece),
                            "node " + i + " is described by \"" + piece + "\"" + describe(tree()));
                    TextFacet text = any.text();
                    assertFalse(text != null && text.text().contains(piece),
                            "node " + i + " publishes \"" + piece + "\"" + describe(tree()));
                }
            }
        }
    }

    // -------------------------------------------------------------------------- what it publishes

    @Test
    void aMaskedFieldIsAPasswordFieldWhoseTextIsOneBulletPerCluster() {
        bindField();
        field.setText(SECRET);
        frame();

        AccessibleNode node = fieldNode();
        assertEquals(Accessible.Role.PASSWORD_FIELD, node.role(), describe(tree()));
        assertTrue(node.has(Accessible.State.PASSWORD),
                "the state platforms refuse to speak over" + describe(tree()));
        assertEquals(DOT.repeat(3), facet().text(),
                "three clusters in four chars: counting chars would publish a fourth dot the "
                        + "screen does not show, and would leak that a character is astral"
                        + describe(tree()));
        assertEquals(List.of(node), nodesWith(Accessible.State.PASSWORD), describe(tree()));
    }

    @Test
    void theSecretReachesNoNodeOfTheTreeAtAll() {
        bindField();
        field.setPlaceholder("Passphrase");
        field.setTooltip("At least twelve characters");
        field.setTrailingButton(BLANK, REVEAL, () -> { });
        field.setText(SECRET);
        scene.requestFocus(field);
        frame();

        theSecretIsNowhereInTheTree();

        field.model().selectAll();
        field.invalidate();
        frame();
        theSecretIsNowhereInTheTree();
    }

    @Test
    void theCaretAndTheSelectionAreMaskOffsetsAndNotTheModelsOwn() {
        bindField();
        field.setText(SECRET);
        scene.requestFocus(field);
        frame();

        assertEquals(4, field.model().cursor(),
                "the model counts chars, and setText leaves the caret past the last of them");
        TextFacet atTheEnd = facet();
        assertEquals(3, atTheEnd.caretOffset(),
                "and the mask has three stops, so the model's own offset would put the caret one "
                        + "past the end of the string it is a caret into" + describe(tree()));
        assertTrue(atTheEnd.caretOffset() <= atTheEnd.text().length(), describe(tree()));

        field.model().setCursor(1, false);
        field.invalidate();
        frame();
        assertEquals(1, facet().caretOffset(),
                "after the first cluster, in both spaces, which is the offset that agrees"
                        + describe(tree()));

        field.model().setCursor(3, false);
        field.invalidate();
        frame();
        assertEquals(2, facet().caretOffset(),
                "past the whole surrogate pair is two dots, not three" + describe(tree()));

        field.model().selectAll();
        field.invalidate();
        frame();
        TextFacet all = facet();
        assertEquals(0, all.selectionStart(), describe(tree()));
        assertEquals(3, all.selectionEnd(),
                "select-all selects every dot and nothing past the last one" + describe(tree()));
    }

    @Test
    void theAffinityIsPinnedDownstreamWhileMaskedWhateverSideTheModelHolds() {
        bindField();
        field.setText(SECRET);
        scene.requestFocus(field);
        // The side a click, a drag or a visual arrow leaves behind, which on a revealed field is a
        // fact about a shaping of the secret and on a masked one is a fact about nothing.
        field.model().setCaret(new ShapedText.Position(1, ShapedText.Affinity.UPSTREAM), false);
        field.invalidate();
        frame();

        assertEquals(ShapedText.Affinity.UPSTREAM, field.model().caretAffinity(),
                "the model is holding the other side, which is what makes this a test");
        assertEquals(ShapedText.Affinity.DOWNSTREAM, facet().caretAffinity(),
                "and the mask is a simple left-to-right line, so an offset there has exactly one "
                        + "visual position and a side says nothing true about it"
                        + describe(tree()));

        bridge.events.clear();
        field.model().setCaret(new ShapedText.Position(1, ShapedText.Affinity.DOWNSTREAM), false);
        field.invalidate();
        frame();

        assertEquals(ShapedText.Affinity.DOWNSTREAM, facet().caretAffinity(), describe(tree()));
        assertEquals(List.of(), eventsOf(AccessibleEvent.Type.CARET_MOVED),
                "the side is a field the difference compares, so an unpinned one would report a "
                        + "caret move for a caret that did not move" + bridge.events);
    }

    @Test
    void anEmptyMaskedFieldPublishesAnEmptyFacetAndNotANullOne() {
        bindField();

        assertEquals(new TextFacet("", 0, ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null), facet(),
                "no dots, the caret at the start, nothing selected, one line and no caret box "
                        + "because nothing is focused: exactly what an empty plain field publishes"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the toggle

    @Test
    void revealingPublishesTheContentsUnderThePlainRoleAndMaskingGoesBack() {
        bindField();
        field.setText(SECRET);
        frame();
        long id = fieldNode().id();

        field.setRevealed(true);
        frame();

        AccessibleNode revealed = fieldNode();
        assertEquals(id, revealed.id(), "a toggle is not a rebuild" + describe(tree()));
        assertEquals(Accessible.Role.TEXT_FIELD, revealed.role(),
                "a platform's refusal to speak is keyed on the role, so a revealed field left "
                        + "under PASSWORD_FIELD would be the one control on screen a blind user "
                        + "cannot read after deliberately asking to" + describe(tree()));
        assertFalse(revealed.has(Accessible.State.PASSWORD),
                "and the state says the text is masked, which it is not" + describe(tree()));
        assertEquals(SECRET, facet().text(), describe(tree()));
        assertEquals(4, facet().caretOffset(),
                "the model's own offsets, because the mask is gone" + describe(tree()));

        field.setRevealed(false);
        frame();

        AccessibleNode masked = fieldNode();
        assertEquals(Accessible.Role.PASSWORD_FIELD, masked.role(), describe(tree()));
        assertTrue(masked.has(Accessible.State.PASSWORD), describe(tree()));
        assertEquals(DOT.repeat(3), facet().text(), describe(tree()));
        theSecretIsNowhereInTheTree();
    }

    /**
     * The case that catches two counters, which is the defect neither branch shows on its own.
     *
     * <p>The difference between two snapshots compares the witness and nothing else about the
     * string, and raises a text change only when the witness moved <em>and</em> the strings
     * differ. A masked branch counting on its own and a revealed branch riding the base's counter
     * are both 1 after their first build, so the reveal would publish cleartext with an unmoved
     * witness and say nothing: a reader would go on speaking bullets over a field that now reads
     * plainly, and on the way back would go on speaking the value it had cached.
     */
    @Test
    void eachToggleRaisesATextChangeCarryingTheNewValue() {
        bindField();
        field.setText(SECRET);
        frame();
        assertEquals(1, eventsOf(AccessibleEvent.Type.TEXT_CHANGED).size(),
                "the first build: empty to three dots" + bridge.events);

        bridge.events.clear();
        field.setRevealed(true);
        frame();
        assertEquals(1, eventsOf(AccessibleEvent.Type.TEXT_CHANGED).size(),
                "a reader caching three bullets has to be told they became the value"
                        + bridge.events);
        assertEquals(fieldNode().id(), eventsOf(AccessibleEvent.Type.TEXT_CHANGED).get(0).nodeId());
        assertEquals(SECRET, facet().text(), describe(tree()));

        bridge.events.clear();
        field.setRevealed(false);
        frame();
        assertEquals(1, eventsOf(AccessibleEvent.Type.TEXT_CHANGED).size(),
                "and told again on the way back, or it goes on speaking the secret"
                        + bridge.events);
        assertEquals(DOT.repeat(3), facet().text(), describe(tree()));
    }

    @Test
    void aTextChangeThatDoesNotChangeTheMaskIsNotOne() {
        bindField();
        field.setText("abc");
        frame();
        bridge.events.clear();

        field.setText("xyz");
        frame();

        assertEquals(DOT.repeat(3), facet().text(), describe(tree()));
        assertEquals(List.of(), eventsOf(AccessibleEvent.Type.TEXT_CHANGED),
                "three dots became three dots: the counter moves when the published string moves "
                        + "and not when the rebuild runs" + bridge.events);
    }

    // ------------------------------------------------------------------------------ the actions

    @Test
    void aCaretSetFromAReaderIsReadAsMaskOffsets() throws InterruptedException {
        bindField();
        field.setText(SECRET);
        scene.requestFocus(field);
        field.model().setCursor(0, false);
        field.invalidate();
        frame();

        assertTrue(perform(fieldNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(2, 2)));
        frame();
        assertEquals(3, field.model().cursor(),
                "the second dot ends past the whole surrogate pair. Inherited unchanged this "
                        + "would land on char 2, inside the pair, and silently: the shorter "
                        + "buffer's own bounds check passes" + describe(tree()));

        assertTrue(perform(fieldNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(0, 3)));
        frame();
        assertEquals(0, field.model().selectionStart(), describe(tree()));
        assertEquals(4, field.model().selectionEnd(),
                "three dots is four chars, and a selection that stopped at three would cut a "
                        + "surrogate pair in half" + describe(tree()));
    }

    @Test
    void anOrdinalTheMaskHasNoStopForIsRefusedAndNotClamped() throws InterruptedException {
        bindField();
        field.setText(SECRET);
        scene.requestFocus(field);
        field.model().setCursor(1, false);
        field.invalidate();
        frame();

        perform(fieldNode().id(), Accessible.Action.SET_CARET,
                new Accessible.Argument.OfRange(4, 4));
        perform(fieldNode().id(), Accessible.Action.SET_SELECTION,
                new Accessible.Argument.OfRange(0, 9));
        frame();

        assertEquals(1, field.model().cursor(),
                "nothing moved. caretIndex clamps to the last stop, so without the range check a "
                        + "client asking for a dot that is not there would be quietly answered "
                        + "with the end of the value" + describe(tree()));
        assertEquals(1, field.model().selectionEnd(), describe(tree()));
    }

    @Test
    void aWholeValueSetStillWorksWhileMaskedAndTellsTheApplication() throws InterruptedException {
        bindField();
        List<String> changes = new ArrayList<>();
        field.onChange(changes::add);
        field.setText(SECRET);
        frame();

        assertTrue(perform(fieldNode().id(), Accessible.Action.SET_TEXT,
                new Accessible.Argument.OfText("new")));
        frame();

        assertEquals(List.of("new"), changes,
                "a password manager fills the field this way, and the whole-value verb carries no "
                        + "offsets to translate");
        assertEquals(DOT.repeat(3), facet().text(), describe(tree()));
        theSecretIsNowhereInTheTree();
    }

    @Test
    void theInheritedHalfIsStillThereWhileMasked() throws InterruptedException {
        bindField();
        field.setPlaceholder("Passphrase");
        field.setText(SECRET);
        frame();

        AccessibleNode node = fieldNode();
        assertEquals("Passphrase", node.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.PLACEHOLDER, node.nameFrom(), describe(tree()));
        assertTrue(node.has(Accessible.State.EDITABLE),
                "masked is not read-only: the text can be typed into either way" + describe(tree()));
        assertFalse(node.has(Accessible.State.READ_ONLY), describe(tree()));
        assertFalse(node.has(Accessible.State.HAS_POPUP),
                "a context menu is a verb and not a state; see TextFieldAccessibilityTest"
                        + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SHOW_MENU),
                "the only route a reader has to Paste and Select All; Cut and Copy are greyed by "
                        + "the field's own clipboard rule and not by hiding the menu"
                        + describe(tree()));

        field.setValidation(TextField.Validation.SUCCESS);
        frame();
        assertFalse(fieldNode().has(Accessible.State.INVALID), describe(tree()));

        field.setValidation(TextField.Validation.ERROR);
        frame();
        assertTrue(fieldNode().has(Accessible.State.INVALID), describe(tree()));
    }

    @Test
    void aTrailingRevealButtonIsTheBasesChildWithTheBasesBox() {
        bindField();
        field.setTrailingButton(BLANK, REVEAL, () -> field.setRevealed(!field.isRevealed()));
        field.setText(SECRET);
        frame();

        AccessibleNode node = fieldNode();
        List<AccessibleNode> children = childrenOf(node);
        assertEquals(1, children.size(),
                "one trailing button and nothing else: the reveal toggle itself is a public "
                        + "setter this widget draws nothing for, so §7.1 refuses it a node"
                        + describe(tree()));
        AccessibleNode button = children.get(0);
        assertEquals(Accessible.Role.BUTTON, button.role(), describe(tree()));
        assertEquals("Show password", button.name(), describe(tree()));
        assertTrue(button.actions().has(Accessible.Action.PRESS), describe(tree()));
        assertEquals(32f, button.width(),
                "fieldTrailing at MEDIUM, never the centred icon's smaller square"
                        + describe(tree()));
        assertEquals(node.x() + node.width() - 32f, button.x(), describe(tree()));
        assertFalse(button.has(Accessible.State.PASSWORD),
                "the state is the field's own and is not written onto what it draws"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it costs

    @Test
    void aQuietMaskedFieldPublishesNothingAndAllocatesNothing() {
        bindField();
        scene.setTextRuler(new MemoizingRuler());
        field.setText(SECRET);
        scene.requestFocus(field);
        frame();

        int published = bridge.published.size();
        bridge.events.clear();
        for (int i = 0; i < 10; i++) {
            field.invalidate();
            frame();
        }
        assertEquals(published, bridge.published.size(),
                "ten damaged frames that changed no accessible fact, which is the blink"
                        + describe(tree()));
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bridge.listening = true;
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            field.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            field.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;
        bridge.listening = true;

        assertEquals(0, withAReaderAttached - withNobodyListening,
                "describing a masked field whose caret is blinking must cost no memory. The line "
                        + "that would break it is the mask built inside the describe hook rather "
                        + "than behind the cache key, which is one string per damaged frame to "
                        + "conclude that nothing moved");
    }
}
