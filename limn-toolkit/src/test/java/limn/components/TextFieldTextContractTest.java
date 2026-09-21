package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.TextContract;
import limn.testing.a11y.TextSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** {@link TextField} and {@link TextArea} under the text contract (ADR 045 §4). */
class TextFieldTextContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theField() {
        return ContractTests.of(TextContract.cases(new FieldSubject(), runtime));
    }

    @TestFactory
    Stream<DynamicTest> theArea() {
        return ContractTests.of(TextContract.cases(new AreaSubject(), runtime));
    }

    private static final class FieldSubject implements TextSubject {
        private TextField field;

        @Override
        public Widget build() {
            field = new TextField();
            field.setText("invoices");
            field.setAccessibleName("Search");
            Column root = new Column();
            root.add(new SizedBox(200, 32, field));
            return root;
        }

        @Override
        public Widget widget() {
            return field;
        }

        @Override
        public String initialText() {
            return "invoices";
        }

        @Override
        public String text() {
            return field.text();
        }
    }

    private static final class AreaSubject implements TextSubject {
        private TextArea area;

        @Override
        public Widget build() {
            area = new TextArea();
            area.setText("notes here");
            area.setAccessibleName("Notes");
            Column root = new Column();
            root.add(new SizedBox(200, 80, area));
            return root;
        }

        @Override
        public Widget widget() {
            return area;
        }

        @Override
        public String initialText() {
            return "notes here";
        }

        @Override
        public String text() {
            return area.text();
        }
    }
}
