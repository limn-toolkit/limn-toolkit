package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.MenuContract;
import limn.testing.a11y.MenuSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link PopupMenu}'s root column under the menu contract (ADR 045 §4; decision 98): the
 * variant whose rows choose, flip and open submenus. Every kind of row in one column: a
 * command, a check, a submenu with rows, a submenu with none, a disabled command, and a
 * command with a chord; opened in the scene at its anchor, because the harness's window
 * cannot host a window of its own.
 */
class PopupMenuMenuContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theMenuContract() {
        return ContractTests.of(MenuContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements MenuSubject {
        private static final List<String> NAMES = List.of("New", "Wrap", "Export", "Empty",
                "Paste", "Quit");
        private static final List<Row> KINDS = List.of(Row.COMMAND, Row.CHECK, Row.SUBMENU,
                Row.EMPTY, Row.DISABLED, Row.COMMAND);
        private final List<String> chosen = new ArrayList<>();
        private final List<Boolean> toggled = new ArrayList<>();
        private Label anchor;
        private PopupMenu popup;

        @Override
        public Widget<?> build() {
            chosen.clear();
            toggled.clear();
            Menu menu = new Menu()
                    .addItem("New", () -> chosen.add("New"))
                    .addCheck("Wrap", false, toggled::add)
                    .addSubmenu("Export", new Menu()
                            .addItem("PNG", () -> chosen.add("PNG"))
                            .addItem("SVG", () -> chosen.add("SVG")))
                    .addSubmenu("Empty", new Menu())
                    .add(MenuItem.of("Paste", () -> chosen.add("Paste")).setEnabled(false))
                    .addItem("Quit", () -> chosen.add("Quit"));
            popup = new PopupMenu(menu).setDisplayMode(DisplayMode.IN_SCENE);
            anchor = new Label("anchor");
            Column root = new Column();
            root.add(new SizedBox(80, 20, anchor)); // a box of its own: the invariants refuse an empty one
            return root;
        }

        @Override
        public void open() {
            popup.showAt(anchor, 20, 20);
        }

        @Override
        public Widget<?> widget() {
            // The capture layer, focusable from its constructor and focused for the whole life
            // of the cascade: what the scene says holds the keyboard once the popup is open.
            return anchor.scene().focusedWidget();
        }

        @Override
        public List<String> rowNames() {
            return NAMES;
        }

        @Override
        public Row kindOf(int row) {
            return KINDS.get(row);
        }

        @Override
        public List<String> chosen() {
            return chosen;
        }

        @Override
        public List<Boolean> toggled() {
            return toggled;
        }
    }
}
