package limn.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@link Sounds} facade over a fake engine/decoder: verifies install/
 * uninstall, the silent no-op when no engine is present, and decode routing,
 * all without an audio device.
 */
class SoundsTest {

    @Test
    void playIsNoOpWhenNoEngineInstalled() {
        Sounds.uninstallEngine(null); // ensure clean (no-op if none)
        assertFalse(Sounds.isAvailable());
        AudioClip clip = AudioClip.tone(440f, 0.01f, 0.3f);
        assertSame(Playback.NONE, Sounds.play(clip), "no engine → silent no-op handle");
    }

    @Test
    void playRoutesToTheInstalledEngine() {
        RecordingEngine engine = new RecordingEngine();
        Sounds.installEngine(engine);
        try {
            AudioClip clip = AudioClip.tone(440f, 0.01f, 0.3f);
            Playback playback = Sounds.play(clip, 0.5f, true);
            assertSame(clip, engine.lastClip);
            assertEquals(0.5f, engine.lastGain);
            assertEquals(true, engine.lastLoop);
            assertSame(engine.handle, playback);
        } finally {
            Sounds.uninstallEngine(engine);
        }
        assertFalse(Sounds.isAvailable());
    }

    @Test
    void decodeRoutesToTheInstalledDecoder() {
        AudioClip decoded = AudioClip.tone(100f, 0.01f, 0.1f);
        AudioDecoder decoder = bytes -> decoded;
        Sounds.installDecoder(decoder);
        try {
            assertSame(decoded, Sounds.decode(new byte[] {0, 1, 2}));
        } finally {
            Sounds.uninstallDecoder(decoder);
        }
    }

    @Test
    void decodeWithoutDecoderThrows() {
        Sounds.uninstallDecoder(null);
        assertThrows(IllegalStateException.class, () -> Sounds.decode(new byte[] {0}));
    }

    @Test
    void fromResourceMissingThrows() {
        assertThrows(IllegalStateException.class, () -> Sounds.fromResource("/does/not/exist.wav"));
    }

    @Test
    void optionsPlayRoutesTheFullOptionsToTheEngine() {
        RecordingEngine engine = new RecordingEngine();
        Sounds.installEngine(engine);
        try {
            AudioClip clip = AudioClip.tone(440f, 0.01f, 0.3f);
            PlayOptions options = PlayOptions.DEFAULTS
                    .withPitch(1.5f).withBus(AudioBus.MUSIC)
                    .withPriority(PlayOptions.Priority.HIGH);
            Sounds.play(clip, options);
            assertSame(clip, engine.lastClip);
            assertSame(options, engine.lastOptions);
        } finally {
            Sounds.uninstallEngine(engine);
        }
    }

    @Test
    void aLegacyEngineStillHonorsGainAndLoopFromOptions() {
        // An engine overriding only the 3-arg play (a test fake, a minimal
        // port) receives gain/loop through the interface default.
        LegacyEngine engine = new LegacyEngine();
        Sounds.installEngine(engine);
        try {
            Sounds.play(AudioClip.tone(440f, 0.01f, 0.3f),
                    PlayOptions.DEFAULTS.withGain(0.25f).withLoop(true));
            assertEquals(0.25f, engine.lastGain);
            assertEquals(true, engine.lastLoop);
        } finally {
            Sounds.uninstallEngine(engine);
        }
    }

    @Test
    void mixerSettersRouteToTheEngine() {
        RecordingEngine engine = new RecordingEngine();
        Sounds.installEngine(engine);
        try {
            Sounds.setMasterGain(0.7f);
            Sounds.setBusGain(AudioBus.MUSIC, 0.4f);
            assertEquals(0.7f, engine.masterGain);
            assertSame(AudioBus.MUSIC, engine.lastBus);
            assertEquals(0.4f, engine.lastBusGain);
        } finally {
            Sounds.uninstallEngine(engine);
        }
    }

    @Test
    void streamWithoutEngineIsANoOp() {
        Sounds.uninstallEngine(null);
        assertSame(Playback.NONE, Sounds.stream(
                java.nio.file.Path.of("missing.ogg"), PlayOptions.DEFAULTS));
    }

    private static final class RecordingEngine implements AudioEngine {
        final Playback handle = Playback.NONE;
        AudioClip lastClip;
        float lastGain;
        boolean lastLoop;
        PlayOptions lastOptions;
        float masterGain = 1;
        AudioBus lastBus;
        float lastBusGain;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            lastClip = clip;
            lastGain = gain;
            lastLoop = loop;
            return handle;
        }

        @Override
        public Playback play(AudioClip clip, PlayOptions options) {
            lastClip = clip;
            lastOptions = options;
            return handle;
        }

        @Override
        public void setMasterGain(float gain) {
            masterGain = gain;
        }

        @Override
        public void setBusGain(AudioBus bus, float gain) {
            lastBus = bus;
            lastBusGain = gain;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /** Implements ONLY the original 3-arg play: exercises the SPI defaults. */
    private static final class LegacyEngine implements AudioEngine {
        float lastGain;
        boolean lastLoop;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            lastGain = gain;
            lastLoop = loop;
            return Playback.NONE;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
