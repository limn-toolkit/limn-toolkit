package limn.components;

import limn.concurrent.Subscription;
import limn.graphics.Font;
import limn.graphics.FontLoader;
import limn.graphics.Fonts;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Theme#apply()} does with the palette's font: the palette switches at once, the font
 * only once the family's faces are resident, so a theme picker reads no font file inside a frame;
 * a later choice overtakes a font still waiting, so the font never lands after the user moved on;
 * and a palette whose family is already in force switches nothing and lays nothing out again.
 * Until 2026-10-25 {@code apply} switched the font through {@code Fonts.setDefaultFamily}, inside
 * the call, and the next frame read the family's faces.
 *
 * <p>The backend is a loader that holds each read until the test says it landed, as
 * {@code FontsPreloadTest}'s does.
 */
class ThemeApplyFontTest extends ComponentTestBase {

    private static final String SERIF = "Menlo";
    private static final String OTHER = "Arial";

    /** A backend's loader whose reads land when the test says so. */
    private static final class HeldLoader implements FontLoader {

        final List<String> asked = new ArrayList<>();
        private final Map<String, List<Runnable>> waiting = new LinkedHashMap<>();

        @Override
        public String load(Path file) {
            throw new UnsupportedOperationException("not a file test");
        }

        @Override
        public void preload(String family, Runnable whenResident) {
            asked.add(family);
            waiting.computeIfAbsent(family, k -> new ArrayList<>()).add(whenResident);
        }

        /** {@code family}'s faces are resident: every request for it is answered, on this thread. */
        void land(String family) {
            List<Runnable> answers = waiting.remove(family);
            assertTrue(answers != null, "nothing asked for " + family);
            for (Runnable answer : answers) {
                answer.run();
            }
        }
    }

    /** The root of a scene, counting the measures a relayout costs it. */
    private static final class Probe extends limn.scene.layout.Container<Probe> {
        int measures;

        @Override
        protected Size onMeasure(Constraints constraints) {
            measures++;
            return constraints.constrain(10, 10);
        }
    }

    private final HeldLoader loader = new HeldLoader();
    private final AtomicInteger fontChanges = new AtomicInteger();
    private final AtomicInteger paletteChanges = new AtomicInteger();
    private Subscription fontListening;
    private Subscription paletteListening;

    @BeforeEach
    void installLoader() {
        Fonts.setDefaultFamily(null);
        Fonts.installLoader(loader);
        fontListening = Fonts.observeChanges(fontChanges::incrementAndGet);
        paletteListening = Theme.observeChanges(paletteChanges::incrementAndGet);
    }

    @AfterEach
    void uninstallLoader() {
        fontListening.cancel();
        paletteListening.cancel();
        Fonts.uninstallLoader(loader);
        Fonts.setDefaultFamily(null);
        runtime.drain(); // whatever the reset abandoned is answered on this runtime, not the next
    }

    private static Theme naming(String family) {
        return Theme.light().toBuilder().name("Light in " + family).fontFamily(family).build();
    }

    @Test
    void thePaletteSwitchesAtOnceAndTheFontOnlyWhenItsFacesAreResident() {
        Theme serif = naming(SERIF);
        List<Boolean> answers = new ArrayList<>();

        serif.apply().thenAccept(answers::add);

        assertSame(serif, Theme.current(), "the colours are not held back for the font");
        assertEquals(1, paletteChanges.get());
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(), "the font does not change inside the call");
        assertEquals(List.of(SERIF), loader.asked, "the faces are asked of the backend's worker");

        runtime.drain();
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                "nor on a turn before the faces are resident, which is the frame that would read them");
        assertEquals(0, fontChanges.get());
        assertEquals(List.of(), answers);

        loader.land(SERIF);
        assertEquals(SERIF, Fonts.defaultFamily());
        assertEquals(1, fontChanges.get(), "one switch, one relayout");
        assertEquals(List.of(true), answers);
    }

    @Test
    void aLaterApplyOvertakesAFontStillWaitingEvenForTheToolkitsOwnFace() {
        List<Boolean> first = new ArrayList<>();
        List<Boolean> second = new ArrayList<>();

        naming(SERIF).apply().thenAccept(first::add);
        Theme.dark().apply().thenAccept(second::add); // the user moved on before the read landed
        runtime.drain();
        loader.land(SERIF); // and it lands afterwards

        assertSame(Theme.dark(), Theme.current());
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                "the overtaken family must not land after the user moved on");
        assertEquals(List.of(false), first, "and it says it switched nothing");
        assertEquals(List.of(true), second);
        assertEquals(0, fontChanges.get(), "no font moved, so nothing was laid out again");
    }

    @Test
    void steppingThroughPalettesEndsOnTheLastOnesFontWhicheverReadLandsLast() {
        List<Boolean> first = new ArrayList<>();
        List<Boolean> second = new ArrayList<>();

        naming(SERIF).apply().thenAccept(first::add);
        naming(OTHER).apply().thenAccept(second::add);
        loader.land(OTHER);
        loader.land(SERIF);
        runtime.drain();

        assertEquals(OTHER, Fonts.defaultFamily());
        assertEquals(List.of(false), first);
        assertEquals(List.of(true), second);
        assertEquals(1, fontChanges.get());
    }

    @Test
    void aPaletteWhoseFamilyIsInForceAnswersTrueAndLaysNothingOutAgain() {
        Fonts.setDefaultFamily(SERIF);
        fontChanges.set(0);
        Probe root = new Probe();
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.renderFrame(new FakeCanvas(400, 300));
        int measured = root.measures;

        List<Boolean> answers = new ArrayList<>();
        for (Theme palette : List.of(naming(SERIF), Theme.dark().toBuilder().fontFamily(SERIF).build(),
                naming(SERIF))) {
            palette.apply().thenAccept(answers::add);
            runtime.drain();
            loader.land(SERIF); // a backend that has the faces answers on the next turn
            scene.renderFrame(new FakeCanvas(400, 300));
        }

        assertEquals(List.of(true, true, true), answers);
        assertEquals(SERIF, Fonts.defaultFamily());
        assertEquals(0, fontChanges.get(), "the family in force was not switched to again");
        assertEquals(measured, root.measures, "three palette switches are three repaints, no relayout");
    }

    @Test
    void aPaletteWhoseFamilyIsInForceStillOvertakesAFontPickerWaiting() {
        List<Boolean> picked = new ArrayList<>();
        List<Boolean> applied = new ArrayList<>();

        Fonts.setDefaultFamilyWhenResident(SERIF).thenAccept(picked::add);
        Theme.light().apply().thenAccept(applied::add); // the toolkit's own face, already in force
        runtime.drain();
        loader.land(SERIF);
        runtime.drain();

        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(), "the palette was the later choice");
        assertEquals(List.of(false), picked);
        assertEquals(List.of(true), applied);
        assertEquals(0, fontChanges.get());
    }

    @Test
    void withNoBackendTheFontSwitchesOnTheRuntimesNextTurn() {
        Fonts.uninstallLoader(loader);
        List<Boolean> answers = new ArrayList<>();

        naming(SERIF).apply().thenAccept(answers::add);
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(), "still never inside the call");

        runtime.drain();
        assertEquals(SERIF, Fonts.defaultFamily(), "nothing can be read, so nothing is waited for");
        assertEquals(List.of(true), answers);
    }

    @Test
    void applyFontFamilyAloneLeavesThePaletteAndSwitchesTheFontTheSameWay() {
        Theme before = Theme.current();
        List<Boolean> answers = new ArrayList<>();

        naming(SERIF).applyFontFamily().thenAccept(answers::add);
        assertSame(before, Theme.current());
        assertEquals(0, paletteChanges.get());
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily());

        loader.land(SERIF);
        assertEquals(SERIF, Fonts.defaultFamily());
        assertEquals(List.of(true), answers);
    }
}
