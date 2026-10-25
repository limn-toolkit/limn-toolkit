package limn.graphics;

import limn.concurrent.Subscription;
import limn.concurrent.UiRuntime;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Fonts#preload} and {@link Fonts#setDefaultFamilyWhenResident}, the font switch that keeps
 * the read of a family's faces out of the frame that shows them: the stage is answered on a later
 * turn and never inside the call, the switch waits for the backend to say the faces are resident,
 * and a font picker moving through a list ends on its last choice whatever order the reads land
 * in.
 *
 * <p>The backend is a loader that holds each request until the test says its read landed, which
 * is what a backend does on the UI thread once a worker has read the faces; the backend module
 * proves its real store answers the same way.
 */
class FontsPreloadTest {

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

    private HeadlessUi ui;
    private UiRuntime runtime;
    private final HeldLoader loader = new HeldLoader();
    private final AtomicInteger changes = new AtomicInteger();
    private Subscription listening;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
        Fonts.setDefaultFamily(null);
        listening = Fonts.observeChanges(changes::incrementAndGet);
    }

    @AfterEach
    void uninstallRuntime() {
        listening.cancel();
        Fonts.uninstallLoader(loader);
        Fonts.setDefaultFamily(null);
        runtime.drain(); // whatever the reset abandoned is answered on this runtime, not the next
        ui.close();
    }

    @Test
    void withNoBackendAPreloadIsAnsweredOnALaterTurnAndNeverInsideTheCall() {
        AtomicReference<Thread> answeredOn = new AtomicReference<>();
        Fonts.preload("Menlo").thenRun(() -> answeredOn.set(Thread.currentThread()));
        assertNull(answeredOn.get(), "never inside the call, so a caller may change its state there");

        runtime.drain();
        assertSame(Thread.currentThread(), answeredOn.get(),
                "nothing can be read with no backend, and that is still answered, on the UI thread");
    }

    @Test
    void aPreloadReachesTheBackendAndIsAnsweredOnlyWhenTheFacesAreResident() {
        Fonts.installLoader(loader);
        AtomicInteger answers = new AtomicInteger();
        Fonts.preload("Menlo").thenRun(answers::incrementAndGet);
        assertEquals(List.of("Menlo"), loader.asked);

        runtime.drain();
        assertEquals(0, answers.get(), "the read has not landed");
        loader.land("Menlo");
        assertEquals(1, answers.get());
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(), "a preload switches nothing");
        assertEquals(0, changes.get(), "and tells no listener anything");
    }

    @Test
    void theNamesThatNeedNoReadNeverReachTheBackendAndAreStillAnsweredLater() {
        Fonts.installLoader(loader);
        AtomicInteger answers = new AtomicInteger();
        Fonts.preload(Font.DEFAULT_FAMILY).thenRun(answers::incrementAndGet);
        Fonts.preload("   ").thenRun(answers::incrementAndGet);
        assertEquals(0, answers.get());

        runtime.drain();
        assertEquals(2, answers.get(), "the embedded face is resident, and a blank name names nothing");
        assertEquals(List.of(), loader.asked, "the alias would only send the backend looking for it");
        assertThrows(NullPointerException.class, () -> Fonts.preload(null));
    }

    @Test
    void theSwitchWaitsForTheFacesAndThenNotifiesOnce() {
        Fonts.installLoader(loader);
        List<Boolean> answers = new ArrayList<>();
        Fonts.setDefaultFamilyWhenResident("Menlo").thenAccept(answers::add);
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(), "nothing changes inside the call");

        runtime.drain();
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                "and nothing changes on a frame before the faces are resident");
        assertEquals(0, changes.get());
        assertEquals(List.of(), answers);

        loader.land("Menlo");
        assertEquals("Menlo", Fonts.defaultFamily());
        assertEquals(1, changes.get(), "one switch, one notification");
        assertEquals(List.of(true), answers);
    }

    @Test
    void aPickerMovingThroughTheListEndsOnItsLastChoiceWhicheverReadLandsLast() {
        Fonts.installLoader(loader);
        List<Boolean> first = new ArrayList<>();
        List<Boolean> second = new ArrayList<>();
        Fonts.setDefaultFamilyWhenResident("Menlo").thenAccept(first::add);
        Fonts.setDefaultFamilyWhenResident("Arial").thenAccept(second::add);

        loader.land("Arial");
        loader.land("Menlo"); // the overtaken read lands after the one that overtook it
        runtime.drain();

        assertEquals("Arial", Fonts.defaultFamily(), "an overtaken read must not switch afterwards");
        assertEquals(List.of(false), first, "and says it switched nothing");
        assertEquals(List.of(true), second);
        assertEquals(1, changes.get(), "the overtaken request notified nobody");
    }

    @Test
    void anOvertakenReadThatLandsFirstSwitchesNothing() {
        Fonts.installLoader(loader);
        List<Boolean> first = new ArrayList<>();
        Fonts.setDefaultFamilyWhenResident("Menlo").thenAccept(first::add);
        Fonts.setDefaultFamilyWhenResident("Arial");
        runtime.drain();
        assertEquals(List.of(false), first, "answered on a later turn than the call that overtook it");

        loader.land("Menlo");
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                "the first family's faces are resident, and it is not the choice any more");
        assertEquals(0, changes.get());

        loader.land("Arial");
        assertEquals("Arial", Fonts.defaultFamily());
        assertEquals(1, changes.get());
    }

    @Test
    void anImmediateSwitchOvertakesAWaitingOneEvenToTheFamilyInForce() {
        Fonts.installLoader(loader);
        List<Boolean> waiting = new ArrayList<>();
        Fonts.setDefaultFamilyWhenResident("Menlo").thenAccept(waiting::add);
        Fonts.setDefaultFamily(Font.DEFAULT_FAMILY); // unchanged, and still the later choice

        loader.land("Menlo");
        runtime.drain();
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily());
        assertEquals(List.of(false), waiting);
        assertEquals(0, changes.get());
    }

    @Test
    void askingForTheFamilyInForceAnswersTrueAndNotifiesNobody() {
        Fonts.installLoader(loader);
        List<Boolean> answers = new ArrayList<>();
        Fonts.setDefaultFamilyWhenResident(null).thenAccept(answers::add);
        assertEquals(List.of(), answers);

        runtime.drain();
        assertEquals(List.of(true), answers, "the embedded face needs no read, and is the default");
        assertEquals(0, changes.get());
        assertEquals(List.of(), loader.asked);
    }

    @Test
    void aBackendThatShutsDownFirstSwitchesNothingAndLeavesTheNextRequestWhole() {
        Fonts.installLoader(loader);
        CompletionStage<Boolean> stranded = Fonts.setDefaultFamilyWhenResident("Menlo");
        Fonts.uninstallLoader(loader);
        loader.land("Menlo"); // a delivery that got through all the same
        runtime.drain();
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily());
        assertFalse(stranded.toCompletableFuture().isDone(), "no UI thread is left to answer it on");

        List<Boolean> next = new ArrayList<>();
        Fonts.setDefaultFamilyWhenResident("Arial").thenAccept(next::add);
        runtime.drain();
        assertEquals("Arial", Fonts.defaultFamily(), "with no backend, a switch needs no read");
        assertEquals(List.of(true), next);
    }

    @Test
    void bothAreUiThreadCalls() throws Exception {
        CompletableFuture<Throwable> preload = new CompletableFuture<>();
        CompletableFuture<Throwable> change = new CompletableFuture<>();
        Thread other = new Thread(() -> {
            preload.complete(thrown(() -> Fonts.preload("Menlo")));
            change.complete(thrown(() -> Fonts.setDefaultFamilyWhenResident("Menlo")));
        });
        other.start();
        other.join();
        assertInstanceOf(IllegalStateException.class, preload.get());
        assertInstanceOf(IllegalStateException.class, change.get());
        assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily());
    }

    private static Throwable thrown(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }
}
