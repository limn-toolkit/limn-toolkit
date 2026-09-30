package limn.components;

import limn.accessibility.AccessibleNode;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chips of a combo holding many (ADR 052 §1, §3): one per selected item in the list's order,
 * as many as fit whole on the field's line and a "+N" for the rest, drawn and never operated, out
 * of the accessible tree, hidden while an editable combo's text filters, and replaceable.
 */
class ComboBoxChipsTest extends AccessibleComponentTestBase {

    private static final List<String> STATUSES =
            List.of("Aberto", "Faturado", "Enviado", "Cancelado", "Devolvido");

    private ComboBox<String> combo;

    private void build(float width, boolean editable, LayoutDirection direction) {
        combo = new ComboBox<>(STATUSES).setDisplayMode(DisplayMode.IN_SCENE)
                .setSelectionMode(SelectionMode.MULTI).setEditable(editable);
        Column root = new Column();
        root.setLayoutDirection(direction);
        root.add(new SizedBox(width, SizedBox.UNSET, combo));
        bind(root);
        scene.setTextRuler(RULER);
        frame();
    }

    private Widget<?> strip() {
        List<Widget<?>> children = combo.children();
        return children.get(children.size() - 1);
    }

    /** The chips shown, then whether the badge shows: the badge is the strip's last child. */
    private List<Widget<?>> shownChips() {
        List<Widget<?>> out = new ArrayList<>();
        List<Widget<?>> all = strip().children();
        for (int i = 0; i < all.size() - 1; i++) {
            if (all.get(i).isVisible()) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    private Widget<?> badge() {
        List<Widget<?>> all = strip().children();
        return all.get(all.size() - 1);
    }

    @Test
    void everyChipShowsWhenTheyFitAndNoBadge() {
        build(400, false, LayoutDirection.LTR);
        combo.setSelectedIndices(0, 2);
        frame();
        assertEquals(2, shownChips().size());
        assertFalse(badge().isVisible());
        assertTrue(shownChips().get(0).x() < shownChips().get(1).x(), "in the list's order");
    }

    @Test
    void whatDoesNotFitBecomesACount() {
        build(200, false, LayoutDirection.LTR);
        combo.selectAll();
        frame();
        int shown = shownChips().size();
        assertTrue(shown >= 1 && shown < STATUSES.size(), "some, not all: " + shown);
        assertTrue(badge().isVisible());
        Widget<?> last = shownChips().get(shown - 1);
        assertTrue(badge().x() >= last.x() + last.width(), "the badge after the chips");
        assertTrue(badge().x() + badge().width() <= strip().width() + 0.001f, "inside the field");
    }

    @Test
    void aFirstChipTooWideIsGivenWhatTheBadgeLeaves() {
        build(120, false, LayoutDirection.LTR);
        combo.setSelectedIndices(3, 4);
        frame();
        assertEquals(1, shownChips().size());
        assertTrue(badge().isVisible());
        Widget<?> first = shownChips().get(0);
        assertTrue(first.x() + first.width() <= badge().x() + 0.001f);
    }

    @Test
    void rightToLeftTheChipsStartFromTheRight() {
        build(400, false, LayoutDirection.RTL);
        combo.setSelectedIndices(0, 2);
        frame();
        assertTrue(shownChips().get(0).x() > shownChips().get(1).x());
    }

    @Test
    void theChipsTakeNoPointerAndAreNotInTheTree() {
        build(400, false, LayoutDirection.LTR);
        combo.setSelectedIndices(0);
        frame();
        Widget<?> chip = shownChips().get(0);
        float x = chip.localToSceneX() + chip.width() / 2;
        float y = chip.localToSceneY() + chip.height() / 2;
        assertSame(combo, scene.root().hitTest(x - scene.root().x(), y - scene.root().y()),
                "a press on a chip is the field's");
        limn.accessibility.AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertFalse("Aberto".equals(node.name())
                            && node.role() != limn.accessibility.Accessible.Role.COMBO_BOX,
                    "no chip node: " + node);
        }
    }

    @Test
    void theRendererIsAskedOncePerItemAndMayNotTakeTheKeyboard() {
        build(400, false, LayoutDirection.LTR);
        List<String> asked = new ArrayList<>();
        combo.setChipRenderer(item -> {
            asked.add(item);
            return new Label(item);
        });
        combo.setSelectedIndices(0, 1);
        frame();
        combo.setSelectedIndices(0);
        combo.setSelectedIndices(0, 1);
        frame();
        assertEquals(List.of("Aberto", "Faturado"), asked);

        combo.setChipRenderer(item -> new Button(item));
        assertThrows(IllegalArgumentException.class, () -> combo.setSelectedIndices(2));
    }

    @Test
    void typingHidesTheChipsAndAnEmptyTextShowsThemAgain() {
        build(400, true, LayoutDirection.LTR);
        combo.setSelectedIndices(0);
        frame();
        TextField editor = (TextField) combo.children().get(0);
        scene.requestFocus(editor);
        frame();
        assertTrue(strip().isVisible());
        assertFalse(editor.paintsEmptyContent(), "no hint and no caret under the chips");
        drive(scene).charTyped('f');
        drive(scene).inputBatchEnded();
        frame();
        assertFalse(strip().isVisible(), "the text stands instead");
        assertTrue(editor.paintsEmptyContent());
        drive(scene).keyEvent(limn.input.Keys.ESCAPE, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertTrue(strip().isVisible(), "the list gone, the filter goes and the chips come back");
    }
}
