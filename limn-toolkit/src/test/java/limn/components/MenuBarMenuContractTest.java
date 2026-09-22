package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.a11y.MenuContract;
import limn.testing.a11y.MenuSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link MenuBar}'s titles under the menu contract (ADR 045 §4; decision 98): the variant whose
 * rows open dropdowns and choose nothing. Three titles over menus with rows and one over an
 * empty menu, which the arrows land on and which opens nothing.
 */
class MenuBarMenuContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theMenuContract() {
        return ContractTests.of(MenuContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements MenuSubject {
        private static final List<String> NAMES = List.of("File", "Edit", "View", "Nothing");
        private final List<String> chosen = new ArrayList<>();
        private MenuBar bar;

        @Override
        public Widget build() {
            chosen.clear();
            bar = new MenuBar();
            for (String title : NAMES) {
                Menu menu = new Menu();
                if (!title.equals("Nothing")) {
                    menu.addItem(title + " one", () -> chosen.add(title + " one"));
                    menu.addItem(title + " two", () -> chosen.add(title + " two"));
                }
                bar.addMenu(title, menu);
            }
            Column root = new Column();
            root.add(bar);
            return root;
        }

        @Override
        public void open() {
            // A bar shows its titles as built.
        }

        @Override
        public Widget widget() {
            return bar;
        }

        @Override
        public List<String> rowNames() {
            return NAMES;
        }

        @Override
        public Row kindOf(int row) {
            return row == 3 ? Row.EMPTY : Row.TITLE;
        }

        @Override
        public List<String> chosen() {
            return chosen;
        }

        @Override
        public List<Boolean> toggled() {
            return List.of();
        }
    }
}
