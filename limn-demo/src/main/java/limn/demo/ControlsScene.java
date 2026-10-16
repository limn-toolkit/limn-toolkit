package limn.demo;

import limn.components.Label;
import limn.components.RangeSlider;
import limn.components.Slider;
import limn.components.Spinner;
import limn.components.Theme;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.i18n.PluralString;
import limn.scene.Change;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.util.Currency;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/**
 * Sliders and spinners, as a travel booking and an order line use them: a volume, the filters of
 * a hotel search, and the numbers of one line of an order, with a disabled one of each kind.
 */
final class ControlsScene {

    private static final Currency USD = Currency.getInstance("USD");
    /** Room for the widest value read out beside a slider: two amounts of money. */
    private static final float READOUT = 200;

    private ControlsScene() {
    }

    /** Standalone {@code --scene controls}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** Reusable subtree (a page of the showcase). */
    static Widget<?> content() {
        Column page = Page.column();

        page.add(Page.firstSection(Text.CALLS));
        Slider volume = new Slider(0, 100).setValue(30);
        page.add(slider(Text.VOLUME, volume, () -> percent(volume.value())));
        Slider microphone = new Slider(0, 100).setValue(40);
        microphone.setEnabled(false); // no microphone connected
        page.add(slider(Text.MICROPHONE, microphone, () -> percent(microphone.value())));

        page.add(Page.section(Text.HOTEL));
        RangeSlider price = new RangeSlider(0, 1000).setStep(10).setValues(200, 600);
        DoubleFunction<String> money = NumberFormats.currency(USD);
        page.add(range(Text.PRICE, price,
                () -> Text.BETWEEN.format(money.apply(price.lower()), money.apply(price.upper()))));
        RangeSlider nights = new RangeSlider(1, 30).setStep(1).setMinimumGap(2).setValues(3, 7);
        page.add(range(Text.NIGHTS, nights,
                () -> Text.NIGHTS_SPAN.format(Math.round(nights.upper()), Math.round(nights.lower()))));
        Slider rating = new Slider(1, 5).setStep(1).setValue(4);
        page.add(slider(Text.RATING, rating, () -> Text.STARS.format(Math.round(rating.value()))));
        RangeSlider distance = new RangeSlider(0, 50).setStep(1).setValues(0, 10);
        distance.setEnabled(false); // needs the office's address
        page.add(range(Text.DISTANCE, distance,
                () -> Text.KILOMETRES.format(Math.round(distance.lower()), Math.round(distance.upper()))));

        page.add(Page.section(Text.ORDER_LINE));
        Spinner pallets = new Spinner(0, 40, 1).setValue(3);
        pallets.setEnabled(false); // set by the warehouse
        page.add(Page.columns(
                spinner(Text.QUANTITY, new Spinner(1, 999, 1).setValue(12)),
                spinner(Text.DISCOUNT, new Spinner(0, 50, 0.5).setValue(7.5)),
                spinner(Text.START, Spinner.time(0, 24 * 60 - 1, 15).setValue(8 * 60 + 30))));
        page.add(Page.columns(
                spinner(Text.COPIES, new Spinner(1, 20, 1).setValue(2).setEditable(false)),
                // A price read from the supplier's list: it stays 4.99 rather than snapping to 5.00.
                spinner(Text.UNIT_PRICE, new Spinner(0, 999, 0.25).setSnapToStep(false).setValue(4.99)),
                spinner(Text.PALLETS, pallets)));

        return Page.narrow(680, page); // the page scrolls it
    }

    private static String percent(float value) {
        return NumberFormats.unit("%").apply(Math.round(value));
    }

    /** A slider under its caption, its value read out at its end and following every move. */
    private static Widget<?> slider(I18nString caption, Slider slider, Supplier<String> value) {
        Label readout = new Label("");
        Page.followLanguage(() -> readout.setText(value.get()));
        slider.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                readout.setText(value.get());
            }
        });
        return Page.field(caption, slider,
                Page.row(16, Expanded.of(slider, 1), new SizedBox(READOUT, SizedBox.UNSET, readout)));
    }

    /** The same for a range slider, whose readout follows both of its values. */
    private static Widget<?> range(I18nString caption, RangeSlider slider, Supplier<String> value) {
        Label readout = new Label("");
        Page.followLanguage(() -> readout.setText(value.get()));
        slider.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                readout.setText(value.get());
            }
        });
        return Page.field(caption, slider,
                Page.row(16, Expanded.of(slider, 1), new SizedBox(READOUT, SizedBox.UNSET, readout)));
    }

    /** A spinner at its own width under its caption. */
    private static Widget<?> spinner(I18nString caption, Spinner spinner) {
        return Page.field(caption, spinner, Page.row(0, spinner));
    }

    /** What this page says, ready for translation. */
    static final class Text {
        private Text() {
        }

        static final I18nString CALLS = new I18nString("showcase.sliders.calls", "Calls");
        static final I18nString VOLUME = new I18nString("showcase.sliders.volume", "Volume");
        static final I18nString MICROPHONE = new I18nString("showcase.sliders.microphone", "Microphone");

        static final I18nString HOTEL = new I18nString("showcase.sliders.hotel", "Hotel search");
        static final I18nString PRICE = new I18nString("showcase.sliders.price", "Price per night");
        static final I18nString BETWEEN = new I18nString("showcase.sliders.between", "{0} – {1}");
        static final I18nString NIGHTS = new I18nString("showcase.sliders.nights", "Nights");
        static final PluralString NIGHTS_SPAN = PluralString.of("showcase.sliders.nightsSpan",
                "{0}–{1} night", "{0}–{1} nights");
        static final I18nString RATING = new I18nString("showcase.sliders.rating", "Rating");
        static final PluralString STARS = PluralString.of("showcase.sliders.stars",
                "{0} star or more", "{0} stars or more");
        static final I18nString DISTANCE = new I18nString("showcase.sliders.distance", "Distance from the office");
        static final I18nString KILOMETRES = new I18nString("showcase.sliders.kilometres", "{0}–{1} km");

        static final I18nString ORDER_LINE = new I18nString("showcase.sliders.orderLine", "Order line");
        static final I18nString QUANTITY = new I18nString("showcase.sliders.quantity", "Quantity");
        static final I18nString DISCOUNT = new I18nString("showcase.sliders.discount", "Discount (%)");
        static final I18nString START = new I18nString("showcase.sliders.start", "Start time");
        static final I18nString COPIES = new I18nString("showcase.sliders.copies", "Printed copies");
        static final I18nString UNIT_PRICE = new I18nString("showcase.sliders.unitPrice", "Unit price");
        static final I18nString PALLETS = new I18nString("showcase.sliders.pallets", "Pallets");
    }
}
