package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchField}: the Enter-to-submit path and the trailing clear button, neither of
 * which had coverage. It inherits all of {@link TextField}'s geometry, so the size conversion
 * gets it for free, but only if these behaviours survive, since both ride on the leading icon
 * and trailing affordance that the conversion re-measures.
 */
class SearchFieldTest extends ComponentTestBase {

    private SearchField field;
    private Scene scene;

    private void build() {
        field = new SearchField();
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(240, 32);
        scene.requestFocus(field);
    }

    @Test
    void enterSubmitsTheCurrentQuery() {
        build();
        AtomicReference<String> submitted = new AtomicReference<>();
        field.onSubmit(submitted::set);
        field.setText("boots");

        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals("boots", submitted.get());
    }

    @Test
    void enterDoesNotInsertANewline() {
        build();
        field.setText("query");
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals("query", field.text(), "a single-line field never grows a newline");
    }

    @Test
    void submitIsNotFiredOnKeyRepeat() {
        build();
        AtomicReference<Integer> count = new AtomicReference<>(0);
        field.onSubmit(q -> count.updateAndGet(n -> n + 1));
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.keyEvent(Keys.ENTER, true, true, 0); // repeat
        scene.inputBatchEnded();
        assertEquals(1, count.get(), "one physical press, one submit");
    }

    @Test
    void clearEmptiesTheFieldAndNotifies() {
        build();
        AtomicReference<String> changed = new AtomicReference<>();
        field.onChange(changed::set);
        field.setText("shoes");

        field.clear();
        assertEquals("", field.text());
        assertEquals("", changed.get(), "clear notifies, like the trailing button does");
    }

    @Test
    void clearOnAnEmptyFieldIsANoOpAndDoesNotNotify() {
        build();
        AtomicReference<String> changed = new AtomicReference<>();
        field.onChange(changed::set);
        field.clear();
        assertNull(changed.get(), "nothing changed, so nothing fires");
    }

    @Test
    void theLeadingIconShiftsTheTextRightSoTheSameClickHitsAnEarlierCharacter() {
        // The measured width is TextField's fixed preferredWidth either way: the affordances
        // take room out of the INNER area, not out of the box. The observable consequence is
        // the hit mapping, and it is exactly what breaks if the icon ramp and the leading
        // inset ever fall out of step: a click would land on the wrong character.
        build();
        field.setText("abcdefghij");

        TextField plain = new TextField();
        Scene plainScene = new Scene(plain);
        plainScene.setTextRuler(RULER);
        plainScene.layoutPass(240, 32);
        plainScene.requestFocus(plain);
        plain.setText("abcdefghij");

        // RULER is 10pt per code point, so x=60 is 6 characters into a field whose text starts
        // at padH, and fewer than that once a leading icon has pushed the text right.
        plainScene.mouseButton(Keys.MOUSE_LEFT, true, 0, 60, 16);
        plainScene.inputBatchEnded();
        int plainCaret = plain.model().cursor();

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 60, 16);
        scene.inputBatchEnded();
        int searchCaret = field.model().cursor();

        assertTrue(searchCaret < plainCaret,
                "the leading magnifier shifts the text right, so the same x lands earlier: "
                        + searchCaret + " vs " + plainCaret);
    }
}
