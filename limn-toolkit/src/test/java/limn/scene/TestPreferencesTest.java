package limn.scene;

import limn.animation.Motion;
import limn.concurrent.Subscription;
import limn.scene.SystemPreferences.ColorScheme;
import limn.testing.HeadlessUi;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application's own test puts the system's preferences in place with {@code limn-test}'s
 * {@link TestPreferences}, since it cannot build a {@link SystemPreferences} itself: what it sets is
 * what {@code current()} answers and what a listener hears, and closing it puts back what was there.
 */
class TestPreferencesTest {

    private HeadlessUi ui;

    @BeforeEach
    void install() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstall() {
        SystemPreferences.report(SystemPreferences.NONE);
        ui.close();
    }

    @Test
    void whatATestSetsIsWhatTheApplicationReadsAndHears() {
        List<SystemPreferences> heard = new ArrayList<>();
        Subscription subscription = SystemPreferences.observeChanges(
                () -> heard.add(SystemPreferences.current()));
        try (TestPreferences system = new TestPreferences()) {
            assertSame(SystemPreferences.NONE, SystemPreferences.current(), "opening changes nothing");

            system.colorScheme(ColorScheme.DARK).reduceMotion(true);
            system.reduceMotion(true); // the same value again is not heard

            assertEquals(ColorScheme.DARK, SystemPreferences.current().colorScheme());
            assertTrue(SystemPreferences.current().reduceMotion());
            assertTrue(Motion.reduced(), "the toolkit follows it as it follows the platform");
            assertFalse(SystemPreferences.current().increaseContrast());
            system.increaseContrast(true).underlineAccessKeys(true);
            assertTrue(SystemPreferences.current().increaseContrast());
            assertTrue(SystemPreferences.current().underlineAccessKeys());
            assertEquals(4, heard.size(), "one change per setter that changed something");
        } finally {
            subscription.cancel();
        }
        assertEquals(SystemPreferences.NONE, SystemPreferences.current(), "closed, what was there is back");
    }

    @Test
    void closingPutsBackWhatWasThereNotNone() {
        try (TestPreferences outer = new TestPreferences()) {
            outer.colorScheme(ColorScheme.LIGHT);
            try (TestPreferences inner = new TestPreferences()) {
                inner.colorScheme(ColorScheme.DARK);
                assertTrue(SystemPreferences.current().colorScheme() == ColorScheme.DARK);
                assertFalse(SystemPreferences.current().reduceMotion(), "it starts from what was there");
            }
            assertEquals(ColorScheme.LIGHT, SystemPreferences.current().colorScheme());
        }
        assertEquals(SystemPreferences.NONE, SystemPreferences.current());
    }
}
