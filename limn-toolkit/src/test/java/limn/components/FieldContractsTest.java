package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.PopupOwnerContract;
import limn.testing.a11y.PopupOwnerSubject;
import limn.testing.a11y.TextContract;
import limn.testing.a11y.TextSubject;
import limn.testing.a11y.ToggleContract;
import limn.testing.a11y.ToggleSubject;
import limn.testing.a11y.ValueContract;
import limn.testing.a11y.ValueSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * The per-shape contracts still hold over a control a field holds, required and with a message on
 * its line: a text, a value, a popup owner and a toggle, each named by the field's caption rather
 * than by a name of its own. What a field adds (a description, two states, a name from a caption)
 * must not take anything a contract asks of the shape.
 */
class FieldContractsTest extends ComponentTestBase {

    /**
     * A ruler that measures, so the caption and the line have boxes: under the headless default
     * every text is zero by zero, and the contract's invariants rightly refuse a label showing
     * with an empty box.
     */
    @org.junit.jupiter.api.BeforeEach
    void measuringRuler() {
        limn.graphics.TextRulers.install(SCALED_RULER);
    }

    @org.junit.jupiter.api.AfterEach
    void defaultRuler() {
        limn.graphics.TextRulers.uninstall(SCALED_RULER);
    }

    private static Field around(Widget<?> control, Widget<?> placed, String caption) {
        Field field = new Field(limn.i18n.I18nString.literal(caption), control, placed).setRequired(true);
        field.setValidation(Validation.WARNING, "Check this one");
        return field;
    }

    @TestFactory
    Stream<DynamicTest> aTextField() {
        return ContractTests.of(TextContract.cases(new TextSubject() {
            private TextField text;

            @Override
            public Widget<?> build() {
                text = new TextField();
                text.setText("invoices");
                Column root = new Column();
                root.add(around(text, new SizedBox(200, 32, text), "Search"));
                return root;
            }

            @Override
            public Widget<?> widget() {
                return text;
            }

            @Override
            public String initialText() {
                return "invoices";
            }

            @Override
            public String text() {
                return text.text();
            }
        }, runtime));
    }

    @TestFactory
    Stream<DynamicTest> aSlider() {
        return ContractTests.of(ValueContract.cases(new ValueSubject() {
            private Slider slider;
            private int changes;

            @Override
            public Widget<?> build() {
                slider = new Slider(0, 100).setStep(5).setValue(40);
                changes = 0;
                slider.onChange(value -> changes++);
                Column root = new Column();
                root.add(around(slider, new SizedBox(300, 30, slider), "Volume"));
                return root;
            }

            @Override
            public Widget<?> widget() {
                return slider;
            }

            @Override
            public double value() {
                return slider.value();
            }

            @Override
            public double min() {
                return slider.min();
            }

            @Override
            public double max() {
                return slider.max();
            }

            @Override
            public double step() {
                return 5;
            }

            @Override
            public boolean readOnly() {
                return false;
            }

            @Override
            public void set(double value) {
                slider.setValue((float) value);
            }

            @Override
            public int changes() {
                return changes;
            }
        }, runtime));
    }

    @TestFactory
    Stream<DynamicTest> aComboBox() {
        return ContractTests.of(PopupOwnerContract.cases(new PopupOwnerSubject() {
            private ComboBox<String> combo;

            @Override
            public Widget<?> build() {
                combo = new ComboBox<>(List.of("English", "Portuguese", "French"));
                Column root = new Column();
                root.add(around(combo, new SizedBox(200, 32, combo), "Language"));
                root.add(new SizedBox(200, 160));
                return root;
            }

            @Override
            public Widget<?> widget() {
                return combo;
            }

            @Override
            public boolean isOpen() {
                return combo.isOpen();
            }

            @Override
            public void close() {
                combo.close();
            }
        }, runtime));
    }

    @TestFactory
    Stream<DynamicTest> aCheckBox() {
        return ContractTests.of(ToggleContract.cases(new ToggleSubject() {
            private Checkbox box;
            private int changes;

            @Override
            public Widget<?> build() {
                box = new Checkbox("Opt in");
                changes = 0;
                box.onChange(on -> changes++);
                Column root = new Column();
                root.add(around(box, box, "Newsletter"));
                return root;
            }

            @Override
            public Widget<?> widget() {
                return box;
            }

            @Override
            public boolean isOn() {
                return box.isChecked();
            }

            @Override
            public boolean operable() {
                return true;
            }

            @Override
            public int changes() {
                return changes;
            }
        }, runtime));
    }
}
