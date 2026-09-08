package limn.scene;

import limn.accessibility.Accessible;
import limn.components.ButtonGroup;
import limn.components.RadioButton;
import limn.graphics.Canvas;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a publish costs, and what a frame that changed nothing costs.
 *
 * <p>The second half is the one that will catch a regression, and it has two known ways to break.
 * The first facet built inside a describe hook rather than written into the buffer's own fields;
 * and the first field whose <em>comparison</em> allocates — which is why the scene under test
 * carries a text node with a caret in it, which fails the moment someone compares a produced string
 * instead of a counter, and a value with a display form, which fails the moment someone formats one
 * in the walk instead of taking the widget's cached one with its witness.
 */
class AccessiblePublishCostTest extends AccessibleTestBase {

    /** A scene shaped like the two ways the promise breaks. */
    private Probe caretHolder;

    private void bindCostlyScene() {
        Group root = new Group();
        caretHolder = new Probe(Accessible.Role.TEXT_FIELD, "Name");
        caretHolder.setFocusable(true);
        caretHolder.text = "a name that is already in the field";
        caretHolder.textWitness = 7;
        caretHolder.caret = 4;
        Probe spinner = new Probe(Accessible.Role.SPIN_BUTTON, "Minutes");
        spinner.setFocusable(true);
        spinner.value = 450.0;
        spinner.valueText = "07:30";
        spinner.valueWitness = 3;
        for (int i = 0; i < 20; i++) {
            root.add(new Probe(Accessible.Role.BUTTON, "button " + i));
        }
        root.add(caretHolder);
        root.add(spinner);
        // A real radio group, because its members read their position and set size from the
        // group on every frame that damages them: the first hook to reach for the public
        // members() copy, or to resolve the caption instead of handing the source over, fails the
        // allocation case below. Their paint is stubbed out, and only their paint: a headless
        // frame repaints every widget, a real ring costs a blended colour and a shaped label per
        // paint, and that is the widget's drawing cost and not the walk's, which the widget's own
        // test measures by difference. The describe hook underneath is the shipped one.
        ButtonGroup group = new ButtonGroup();
        for (String choice : new String[] {"Small", "Medium", "Large"}) {
            RadioButton radio = new RadioButton(choice) {
                @Override
                protected void onPaint(Canvas canvas) {
                }
            };
            root.add(radio);
            group.add(radio);
        }
        group.setSelectedIndex(1);
        bind(root);
        frame();
    }

    /**
     * A caret blinking on a bound scene with a reader attached: the frame damages a widget, the
     * walk runs, every field is compared, and nothing at all is produced.
     */
    @Test
    void aFrameThatDamagesSomethingAndChangesNothingPublishesNothing() {
        bindCostlyScene();
        int before = bridge.published.size();

        for (int i = 0; i < 10; i++) {
            caretHolder.invalidate();     // the caret blinking, which changes no accessible fact
            frame();
        }

        assertEquals(before, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
    }

    @Test
    void aFrameThatDamagesSomethingAndChangesNothingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindCostlyScene();

        long least = AllocationProbe.leastAllocatedBy(() -> {
            caretHolder.invalidate();
            frame();
        }, 60);

        assertEquals(0, least,
                "a repaint that changed no accessible fact must cost no memory: the walk compares "
                        + "a name by its source and a text by its counter, and never produces "
                        + "either in order to find out that it did not move");
    }

    /** A re-present frame does not even walk. */
    @Test
    void aRePresentFrameDoesNotWalkAtAll() {
        bindCostlyScene();
        caretHolder.name = limn.i18n.I18nString.literal("Changed");
        caretHolder.invalidate();
        int before = bridge.published.size();

        rePresentFrame();

        assertEquals(before, bridge.published.size(),
                "the same pixels into the other buffer cannot have changed the tree");
    }

    /** And the control: a change really does publish, so none of the above passes by being dead. */
    @Test
    void aRealChangeStillPublishes() {
        bindCostlyScene();
        int before = bridge.published.size();

        caretHolder.text = "a name that is already in the field!";
        caretHolder.textWitness = 8;
        caretHolder.invalidate();
        frame();

        assertEquals(before + 1, bridge.published.size());
    }

    /** A publish walks each node once, and the tree it produces is sized to the node count. */
    @Test
    void aPublishWalksEachNodeOnceAndProducesOneNodePerDescription() {
        bindCostlyScene();
        assertEquals(26, tree().nodeCount(),
                "a window, twenty buttons, a text field, a spinner and three radios: "
                        + describe(tree()));
    }
}
