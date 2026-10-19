package limn.components;

import limn.accessibility.Accessible;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A label cut at its last line is still named by the whole text: a reader hears what the cut left out. */
class LabelMaxLinesAccessibilityTest extends AccessibleComponentTestBase {

    @Test
    void theNameIsTheWholeTextWhateverTheLinesCut() {
        String words = "Harbor Lane Supplies and Office Furniture of the North";
        Label label = new Label(words).setMaxLines(2);
        Column root = new Column();
        root.add(new SizedBox(90, 40, label));
        bind(root);
        scene.setTextRuler(RULER); // ten points a glyph, so ninety points cut this text
        frame();

        assertTrue(label.displayedLines().get(label.displayedLines().size() - 1).endsWith("…"),
                "the fixture is cut, or the assertion below proves nothing: " + label.displayedLines());
        assertEquals(words, node(Accessible.Role.LABEL).name(), describe(tree()));
    }
}
