package limn.scene;

import limn.concurrent.Subscription;
import limn.scene.SystemPreferences.ColorScheme;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The system's appearance, reduced motion and contrast as the toolkit keeps them: what a backend
 * reports is what {@code current()} answers, and a listener hears each change once, however many
 * times the platform said it (Ubuntu's portal signals every change twice, and macOS's key-value
 * observation fires for every flag).
 */
class SystemPreferencesTest {

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
    void nothingReportedIsNoPreference() {
        assertEquals(SystemPreferences.NONE, SystemPreferences.current());
        assertEquals(ColorScheme.NO_PREFERENCE, SystemPreferences.NONE.colorScheme());
    }

    @Test
    void aListenerHearsEachChangeOnce() {
        List<SystemPreferences> heard = new ArrayList<>();
        Subscription subscription = SystemPreferences.observeChanges(
                () -> heard.add(SystemPreferences.current()));
        SystemPreferences dark = new SystemPreferences(ColorScheme.DARK, false, false, false);

        SystemPreferences.report(dark);
        SystemPreferences.report(dark); // the portal's second signal for the same flip
        SystemPreferences.report(new SystemPreferences(ColorScheme.DARK, true, false, false));

        assertEquals(List.of(dark, new SystemPreferences(ColorScheme.DARK, true, false, false)), heard);
        assertEquals(new SystemPreferences(ColorScheme.DARK, true, false, false), SystemPreferences.current());

        subscription.cancel();
        SystemPreferences.report(SystemPreferences.NONE);
        assertEquals(2, heard.size(), "a cancelled listener hears nothing more");
    }

    @Test
    void aSchemeIsRequired() {
        assertThrows(NullPointerException.class, () -> new SystemPreferences(null, false, false, false));
        assertThrows(NullPointerException.class, () -> SystemPreferences.report(null));
    }
}
