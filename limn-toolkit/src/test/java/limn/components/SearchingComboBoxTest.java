package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An editable combo whose items come from a search (ADR 051 decision 5): asked 200 ms after the
 * last keystroke, loading meanwhile, an answer for a text typed past dropped, the count told to a
 * reader, nothing found and a failed search each a line of the list, and a pick held by itself.
 */
class SearchingComboBoxTest extends AccessibleComponentTestBase {

    record Customer(String code, String name) { }

    private final long[] nanos = {0};
    private final List<String> asked = new ArrayList<>();
    private final List<CompletableFuture<List<Customer>>> answers = new ArrayList<>();
    private final List<Customer> picked = new ArrayList<>();
    private ComboBox<Customer> combo;

    private void build() {
        runtime.setDelayClock(() -> nanos[0]);
        combo = new ComboBox<>(Customer::name).setSearch(text -> {
            asked.add(text);
            CompletableFuture<List<Customer>> answer = new CompletableFuture<>();
            answers.add(answer);
            return answer;
        });
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        combo.onSelect(() -> picked.add(combo.selectedItem()));
        bind(combo);
        scene.setTextRuler(RULER);
        frame();
        scene.requestFocus(combo.children().get(0));
        frame();
    }

    private void type(String text) {
        text.codePoints().forEach(cp -> drive(scene).charTyped(cp));
        drive(scene).inputBatchEnded();
        frame();
    }

    private void press(int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
    }

    private void pause(long millis) {
        nanos[0] += millis * 1_000_000L;
        runtime.drain();
        frame();
    }

    private void answer(int which, List<Customer> found) {
        answers.get(which).complete(found);
        runtime.drain();
        frame();
    }

    private AccessibleNode labelLine() {
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.LABEL) {
                return node;
            }
        }
        return null;
    }

    private List<String> said() {
        List<String> out = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.ANNOUNCEMENT) {
                out.add(String.valueOf(event.newValue()));
            }
        }
        return out;
    }

    @Test
    void aWordTypedQuicklyIsOneSearchAndLoadingIsALineOfTheList() {
        build();
        type("sa");
        pause(100);
        type("o");
        assertTrue(asked.isEmpty(), "nothing is asked while the typing goes on");
        assertTrue(combo.isOpen());
        assertEquals(ComponentStrings.COMBO_LOADING.get(), labelLine().name());
        pause(SEARCH_DELAY);
        assertEquals(List.of("sao"), asked, "one search, for the whole word");
    }

    private static final long SEARCH_DELAY = ComboBox.SEARCH_DELAY_MILLIS;

    @Test
    void anAnswerForATextTypedPastIsDropped() {
        build();
        type("sao");
        pause(SEARCH_DELAY);
        type(" p");
        pause(SEARCH_DELAY);
        assertEquals(List.of("sao", "sao p"), asked);
        answer(0, List.of(new Customer("1", "Padaria São João"), new Customer("2", "São Paulo Alimentos")));
        assertEquals(0, combo.rowsForTest().length, "the answer for \"sao\" came after \"sao p\" was typed");
        answer(1, List.of(new Customer("2", "São Paulo Alimentos")));
        assertEquals(1, combo.rowsForTest().length);
        assertNull(labelLine(), "items, not a line");
    }

    @Test
    void theCountIsToldAndEnterPicksTheFirstWhichIsHeldByItself() {
        build();
        type("sao");
        pause(SEARCH_DELAY);
        bridge.events.clear();
        Customer paulo = new Customer("2", "São Paulo Alimentos");
        answer(0, List.of(paulo, new Customer("1", "Padaria São João")));
        assertEquals(List.of(ComponentStrings.COMBO_RESULTS.format(2)), said());
        press(limn.input.Keys.ENTER);
        assertFalse(combo.isOpen());
        assertEquals(paulo, combo.selectedItem());
        assertEquals(-1, combo.selectedIndex(), "no index names an item of an answer that is gone");
        assertEquals("São Paulo Alimentos", combo.text());
        assertEquals(List.of(paulo), picked);
    }

    @Test
    void nothingFoundAndAFailedSearchAreLinesAndNothingCanBeChosen() {
        build();
        type("zzz");
        pause(SEARCH_DELAY);
        bridge.events.clear();
        answer(0, List.of());
        assertEquals(ComponentStrings.COMBO_NO_RESULTS.get(), labelLine().name());
        assertEquals(List.of(ComponentStrings.COMBO_NO_RESULTS.get()), said());
        press(limn.input.Keys.ENTER);
        assertTrue(picked.isEmpty(), "Enter on a line picks nothing, and the refused text goes back");

        type("y");
        pause(SEARCH_DELAY);
        answers.get(1).completeExceptionally(new IllegalStateException("the server is down"));
        runtime.drain();
        frame();
        assertEquals(ComponentStrings.COMBO_SEARCH_FAILED.get(), labelLine().name());
        assertEquals("y", combo.text(), "the field stays as typed; Enter on nothing found had put it back");
    }

    @Test
    void theListIsBusyWhileTheSearchRuns() {
        build();
        type("sao");
        AccessibleTree tree = tree();
        boolean busy = false;
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.LIST && node.has(Accessible.State.BUSY)) {
                busy = true;
            }
        }
        assertTrue(busy, "BUSY on the list, as a loading branch of a tree");
    }
}
