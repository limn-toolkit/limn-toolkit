package limn.demo;

import limn.components.BackdropPanel;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.SegmentedControl;
import limn.components.Slider;
import limn.components.Theme;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Showcase's pages set side by side lines up: the controls of a form's two columns, each
 * as far under its caption as its neighbour is under its own and as long as its column. Each case
 * is a misalignment the owner saw, or one the sweep of 2026-10-17 measured on the filmed pages.
 */
class ShowcaseAlignmentTest {

    private HeadlessUi ui;

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi(() -> 0L);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.light());
        TextRulers.install(HeadlessWindow.RULER);
    }

    @AfterEach
    void tearDown() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        I18n.setLocale(Locale.ENGLISH); // a case read in pt-BR leaves the next class in English
        ui.close();
    }

    @Test
    void theOnboardingBarsAreOnOneLineAndAsLongAsTheirColumns() {
        Scene scene = open(BasicsScene::content);
        List<ProgressBar> bars = all(scene.root(), ProgressBar.class);
        assertEquals(2, bars.size(), "the profile and the credit check");
        ProgressBar profile = bars.get(0);
        ProgressBar credit = bars.get(1);
        assertEquals(profile.localToSceneY(), credit.localToSceneY(), 0.5f,
                "both bars as far under their captions, so on one line");
        assertEquals(profile.width(), credit.width(), 0.5f, "both bars as long as their columns");
    }

    @Test
    void theAccountsChoicesStandOnTheLinesOfTheirNeighbours() {
        Scene scene = open(BasicsScene::content);
        RadioButton company = only(all(scene.root(), RadioButton.class),
                each -> each.text().equals(BasicsScene.Text.COMPANY.withoutAccessLetter().get()));
        SegmentedControl cycle = only(all(scene.root(), SegmentedControl.class), each -> each.segmentCount() == 3);
        assertEquals(middle(cycle), middle(company), 0.5f, "the radio buttons on the cycle's line");

        I18nString[][] pairs = {
                {BasicsScene.Text.EMAIL_INVOICES, BasicsScene.Text.ACTIVE},
                {BasicsScene.Text.PAPER_INVOICES, BasicsScene.Text.PORTAL},
                {BasicsScene.Text.TAX_EXEMPT, BasicsScene.Text.CREDIT_HOLD}};
        List<Checkbox> boxes = all(scene.root(), Checkbox.class);
        for (I18nString[] pair : pairs) {
            Checkbox box = only(boxes, each -> each.text().equals(pair[0].withoutAccessLetter().get()));
            Checkbox toggle = only(boxes, each -> each.text().equals(pair[1].withoutAccessLetter().get()));
            assertEquals(middle(toggle), middle(box), 0.5f, pair[0].english() + " beside " + pair[1].english());
        }
    }

    @Test
    void theSoundPagesCaptionsAreWholeAndItsSlidersStartOnOneLine() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR")); // where "Panorâmica" was cut
        Scene scene = open(AudioScene::content);
        List<Slider> sliders = all(scene.root(), Slider.class);
        assertEquals(5, sliders.size(), "pan, three levels and the track's position");
        for (Slider slider : sliders) {
            assertEquals(sliders.get(0).localToSceneX(), slider.localToSceneX(), 0.5f, "where the sliders start");
            Label caption = (Label) slider.parent().parent().children().get(0).children().get(0);
            float whole = caption.measure(new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0,
                    Constraints.UNBOUNDED_LIMIT)).width();
            assertTrue(caption.width() >= whole - 0.5f, "\"" + caption.text() + "\" whole");
        }
    }

    @Test
    void theOutlinesRowsStandOnTheListsRowsBesideIt() {
        Scene scene = open(PickerScene::content);
        List<Label> labels = all(scene.root(), Label.class);
        // The fifth row of the list of columns and of the outline of folders, one beside the other.
        Label listed = only(labels, each -> each.text().equals("Salesperson"));
        Label outlined = only(labels, each -> each.text().equals("Archive"));
        assertEquals(middle(listed), middle(outlined), 0.5f, "the fifth rows");
    }

    @Test
    void noEffectsNameIsCutOnItsPanel() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR")); // where "Vidro, desfoque e véu" was cut
        Scene scene = open(GlassScene::content);
        List<BackdropPanel> panels = all(scene.root(), BackdropPanel.class);
        int names = 0;
        for (BackdropPanel panel : panels) {
            for (Label name : all(panel, Label.class)) {
                names++;
                Constraints free = new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0, Constraints.UNBOUNDED_LIMIT);
                limn.scene.Size oneLine = name.measure(free);
                if (oneLine.width() > name.width() + 0.5f) {
                    float laidOut = name.measure(Constraints.loose(name.width(), Constraints.UNBOUNDED_LIMIT)).height();
                    assertTrue(laidOut >= 2 * oneLine.height() - 0.5f,
                            "\"" + name.text() + "\" wider than its panel and on one line, so cut");
                }
            }
        }
        assertTrue(names >= 7, "the effects' names, found " + names);
    }

    private Scene open(Supplier<Widget<?>> page) {
        Scene scene = new Scene(page.get(), () -> 0L);
        HeadlessWindow window = new HeadlessBackend(ui.runtime()).open("page", 1000, 2400);
        scene.bind(window);
        window.frame();
        return scene;
    }

    /** The y of {@code widget}'s middle, in the scene. */
    private static float middle(Widget<?> widget) {
        return widget.localToSceneY() + widget.height() / 2;
    }

    private static <T> T only(List<T> found, java.util.function.Predicate<T> which) {
        List<T> matching = found.stream().filter(which).toList();
        assertEquals(1, matching.size(), "one match");
        return matching.get(0);
    }

    private static <T> List<T> all(Widget<?> at, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(at, type, found);
        return found;
    }

    private static <T> void collect(Widget<?> at, Class<T> type, List<T> found) {
        if (type.isInstance(at)) {
            found.add(type.cast(at));
        }
        for (Widget<?> child : at.children()) {
            collect(child, type, found);
        }
    }
}
