package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Expander;
import limn.components.Label;
import limn.components.RadioButton;
import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Showcase page is built once and kept for the life of the window, so a language chosen after
 * it was built has to reach every line it shows: a line formatted once when the page was built
 * ("3–7 nights", the tab strip's shortcuts hint, "2 on") stayed in the language it was built in
 * while the rest of the page changed around it. Each page is built in English and switched to
 * Brazilian Portuguese, and must then read as the same page built in Brazilian Portuguese.
 */
class ShowcaseLanguageSwitchTest {

    /**
     * A blank picture the size a PNG's header gives, or 16 by 16 for anything else: an animated
     * PNG's frames are checked against the size their control says.
     */
    private static final ImageDecoder DECODER = bytes -> {
        boolean png = bytes.length >= 24 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        int width = png ? java.nio.ByteBuffer.wrap(bytes, 16, 4).getInt() : 16;
        int height = png ? java.nio.ByteBuffer.wrap(bytes, 20, 4).getInt() : 16;
        return new Image(width, height, new byte[width * height * 4]);
    };
    private static final limn.graphics.SvgRenderer RENDERER =
            limn.testfixtures.FakeSvg.renderer((svg, px) -> new Image(1, 1, new byte[4]));
    private static final Locale PORTUGUESE = Locale.forLanguageTag("pt-BR");

    private HeadlessUi ui;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> 0L);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        Images.installDecoder(DECODER);
        limn.graphics.SvgIcon.installRenderer(RENDERER);
        backend = new HeadlessBackend(ui.runtime());
        // The Video page opens its clip while it is built, as a capture's does, and not on a worker
        // whose answer lands on whichever frame it finishes before: the two pages compared would
        // otherwise differ by whether the clip's soundtrack line had arrived yet, and that line is
        // one this test exists for ("Audio 1 of 1: eng" stayed English on a switched page).
        VideoScene.setOpenWhileBuilding(true);
    }

    @AfterEach
    void uninstallRuntime() {
        VideoScene.setOpenWhileBuilding(false);
        I18n.setLocale(Locale.ENGLISH);
        limn.graphics.SvgIcon.uninstallRenderer(RENDERER);
        Images.uninstallDecoder(DECODER);
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void everyPageFollowsALanguageChosenAfterItWasBuilt() {
        List<String> stale = new ArrayList<>();
        for (AppScene.Place place : pages()) {
            I18n.setLocale(Locale.ENGLISH);
            Widget<?> switched = shown(place);
            I18n.setLocale(PORTUGUESE);
            List<String> after = texts(switched);
            List<String> fresh = texts(shown(place));
            List<String> missing = new ArrayList<>(fresh);
            after.forEach(missing::remove);
            List<String> extra = new ArrayList<>(after);
            fresh.forEach(extra::remove);
            if (!extra.isEmpty()) {
                stale.add(place.id() + ": shows " + extra + " where built in Portuguese it shows " + missing);
            }
        }
        assertEquals(List.of(), stale);
    }

    /** Every page of the Showcase but the orders, which a test of its own covers. */
    private static List<AppScene.Place> pages() {
        List<AppScene.Place> pages = new ArrayList<>();
        for (AppScene.Place top : AppScene.places(new Label(""), () -> new Label(""))) {
            pages.addAll(top.under);
        }
        return pages;
    }

    /** {@code place}'s page built now, in a window of its own, after its first frame. */
    private Widget<?> shown(AppScene.Place place) {
        Widget<?> page = place.page.get();
        HeadlessWindow window = backend.open(place.id(), 1100, 760);
        Scene scene = new Scene(page, () -> 0L);
        scene.bind(window);
        window.frame();
        window.frame();
        return page;
    }

    /** The words the page shows, read where they are drawn, in the order of the tree. */
    private static List<String> texts(Widget<?> root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Widget<?> at, List<String> out) {
        if (at instanceof Label label) {
            out.add(label.text());
        } else if (at instanceof Button button) {
            out.add(button.text());
        } else if (at instanceof Checkbox box) {
            out.add(box.text());
        } else if (at instanceof RadioButton radio) {
            out.add(radio.text());
        } else if (at instanceof Expander expander && expander.summary() != null) {
            out.add(expander.summary());
        }
        for (Widget<?> child : at.children()) {
            collect(child, out);
        }
    }
}
