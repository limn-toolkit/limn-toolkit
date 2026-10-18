package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.LeafActionContract;
import limn.testing.a11y.LeafActionSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/**
 * An activated {@link Card}'s area under the leaf contract (ADR 045 §4, ADR 073 §2.7), with no
 * footer, where the leaf is the card's whole node, and with one, where it is the first child of the
 * card's group. The footer holds a check box there, a toggle, so that the leaf is the one the
 * contract finds.
 */
class CardLeafActionContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theLeafContractWithNoFooter() {
        return ContractTests.of(LeafActionContract.cases(new Subject(false), runtime));
    }

    @TestFactory
    Stream<DynamicTest> theLeafContractWithAFooter() {
        return ContractTests.of(LeafActionContract.cases(new Subject(true), runtime));
    }

    private static final class Subject implements LeafActionSubject {
        private final boolean footer;
        private Card card;
        private int presses;

        Subject(boolean footer) {
            this.footer = footer;
        }

        @Override
        public Widget<?> build() {
            card = new Card(new Label("Three items, due on Friday.")).setTitle("Invoice 1042")
                    .setSubtitle("Harbor Lane Supplies");
            if (footer) {
                card.setFooter(new Checkbox("Pin to the top"));
            }
            presses = 0;
            card.onActivate(() -> presses++);
            Column root = new Column();
            root.add(new SizedBox(300, SizedBox.UNSET, card));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return card.area();
        }

        @Override
        public int presses() {
            return presses;
        }
    }
}
