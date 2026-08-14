package limn.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PlayOptions} defaults, withers and validation. */
class PlayOptionsTest {

    @Test
    void defaultsAreNeutral() {
        PlayOptions d = PlayOptions.DEFAULTS;
        assertEquals(1f, d.gain());
        assertEquals(1f, d.pitch());
        assertEquals(0f, d.pan());
        assertFalse(d.loop());
        assertSame(AudioBus.SFX, d.bus());
        assertEquals(PlayOptions.Priority.NORMAL, d.priority());
        assertNull(d.position());
    }

    @Test
    void withersDeriveWithoutMutatingTheRest() {
        PlayOptions options = PlayOptions.DEFAULTS
                .withGain(0.5f)
                .withPitch(1.2f)
                .withPan(-0.3f)
                .withLoop(true)
                .withBus(AudioBus.MUSIC)
                .withPriority(PlayOptions.Priority.HIGH);
        assertEquals(0.5f, options.gain());
        assertEquals(1.2f, options.pitch());
        assertEquals(-0.3f, options.pan());
        assertTrue(options.loop());
        assertSame(AudioBus.MUSIC, options.bus());
        assertEquals(PlayOptions.Priority.HIGH, options.priority());
        // The source instance is untouched (immutable derivation).
        assertEquals(1f, PlayOptions.DEFAULTS.gain());
        assertSame(AudioBus.SFX, PlayOptions.DEFAULTS.bus());
    }

    @Test
    void atSetsAPositionOverridingPan() {
        PlayOptions options = PlayOptions.DEFAULTS.withPan(1f).at(1, 2, 3);
        assertEquals(1f, options.position().x());
        assertEquals(2f, options.position().y());
        assertEquals(3f, options.position().z());
        assertEquals(1f, options.pan(), "pan is kept but documented as overridden");
    }

    @Test
    void validatesRanges() {
        assertThrows(IllegalArgumentException.class, () -> PlayOptions.DEFAULTS.withGain(1.5f));
        assertThrows(IllegalArgumentException.class, () -> PlayOptions.DEFAULTS.withGain(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> PlayOptions.DEFAULTS.withPitch(0.1f));
        assertThrows(IllegalArgumentException.class, () -> PlayOptions.DEFAULTS.withPitch(5f));
        assertThrows(IllegalArgumentException.class, () -> PlayOptions.DEFAULTS.withPan(2f));
        assertThrows(IllegalArgumentException.class, () -> PlayOptions.DEFAULTS.withBus(null));
        assertThrows(IllegalArgumentException.class, () -> PlayOptions.DEFAULTS.withPriority(null));
    }

    @Test
    void customBusesAreDistinctIdentities() {
        AudioBus a = AudioBus.of("voice");
        AudioBus b = AudioBus.of("voice");
        assertEquals("voice", a.name());
        assertFalse(a.equals(b), "buses are identity objects");
    }
}
