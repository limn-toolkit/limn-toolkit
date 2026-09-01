package limn.scene;

import limn.i18n.I18n;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code windowClosed} unsubscribes the scene from every process-wide change source it
 * subscribed to in its constructor &mdash; all four of them. Three were always removed; the
 * {@link I18n} listener was the omission the direction axis's symmetry review noticed, and a
 * closed scene kept waking for every locale change for as long as the application held it.
 */
class SceneTeardownTest extends SceneTestBase {

    @Test
    void aClosedSceneStopsHearingLocaleChanges() {
        RecordingWindow window = new RecordingWindow();
        Scene scene = new Scene(new FixedBox(10, 10));
        scene.bind(window);

        // Two locales that differ from each other, with the machine's own kept out of the
        // pair: a change to the locale already in force is a documented no-op and would make
        // the positive control observe nothing.
        Locale before = I18n.locale();
        Locale hebrew = Locale.forLanguageTag("he-IL");
        Locale arabic = Locale.forLanguageTag("ar-EG");
        Locale first = before.equals(hebrew) ? arabic : hebrew;
        Locale second = first.equals(hebrew) ? arabic : hebrew;
        try {
            I18n.setLocale(first);
            assertTrue(window.frameRequests > 0,
                    "a live scene hears a locale change, or this test observes nothing");

            scene.windowClosed();
            int atClose = window.frameRequests;
            I18n.setLocale(second);
            assertEquals(atClose, window.frameRequests,
                    "a closed scene must not wake for a language it will never draw");
        } finally {
            I18n.setLocale(before);
        }
    }
}
