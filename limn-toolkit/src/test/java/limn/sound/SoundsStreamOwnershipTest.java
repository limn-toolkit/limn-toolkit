package limn.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming a source that is already open, and the one thing that has to be true on every path
 * through it: the source is closed exactly once and never by the caller. A track closed twice is a
 * decoder torn down under a streaming thread still reading it, and one closed never is a file
 * handle that outlives the window.
 */
class SoundsStreamOwnershipTest {

    /** Counts its own closes, which is the whole assertion. */
    private static final class CountingSource implements AudioStreamSource {
        int closes;
        int channels = 2;

        @Override
        public int channels() {
            return channels;
        }

        @Override
        public int sampleRate() {
            return 44_100;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            return maxFrames;
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
            closes++;
        }
    }

    /** An engine that takes the source and keeps it, the way a real one does. */
    private static final class KeepingEngine implements AudioEngine {
        final Playback handle = new Playback() {
            @Override
            public void stop() {
            }

            @Override
            public boolean isPlaying() {
                return true;
            }

            @Override
            public void setGain(float gain) {
            }
        };
        AudioStreamSource received;
        PlayOptions receivedOptions;
        boolean available = true;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public Playback playStream(AudioStreamSource source, PlayOptions options) {
            received = source;
            receivedOptions = options;
            return handle;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    @Test
    void anAvailableEngineReceivesTheSourceAndTheFacadeDoesNotCloseIt() {
        KeepingEngine engine = new KeepingEngine();
        Sounds.installEngine(engine);
        try {
            CountingSource source = new CountingSource();
            PlayOptions options = PlayOptions.DEFAULTS.withBus(AudioBus.MUSIC).withGain(0.4f);

            Playback playback = Sounds.stream(source, options);

            assertSame(engine.handle, playback);
            assertSame(source, engine.received, "the open source reaches the engine unwrapped");
            assertSame(options, engine.receivedOptions);
            assertEquals(0, source.closes,
                    "the engine owns it now; a facade that also closed it would close it twice");
        } finally {
            Sounds.uninstallEngine(engine);
        }
    }

    @Test
    void withNoEngineInstalledTheSourceIsClosedByTheFacade() {
        Sounds.uninstallEngine(null); // nothing installed
        CountingSource source = new CountingSource();

        assertSame(Playback.NONE, Sounds.stream(source, PlayOptions.DEFAULTS));

        assertEquals(1, source.closes,
                "ownership transferred at the call, so the no-engine path owes the close");
    }

    @Test
    void withNoAudioDeviceTheSourceIsClosedByTheFacade() {
        KeepingEngine engine = new KeepingEngine();
        engine.available = false;
        Sounds.installEngine(engine);
        try {
            CountingSource source = new CountingSource();

            assertSame(Playback.NONE, Sounds.stream(source, PlayOptions.DEFAULTS));

            assertEquals(1, source.closes, "no device is still a transfer of ownership");
            assertSame(null, engine.received, "an unavailable engine is not asked to stream");
        } finally {
            Sounds.uninstallEngine(engine);
        }
    }

    @Test
    void anEngineWithoutStreamingSupportClosesTheSourceItself() {
        // The SPI default, which a player depends on: handing a source to any engine at all
        // transfers it, including to one that cannot stream.
        AudioEngine noStreaming = new AudioEngine() {
            @Override
            public Playback play(AudioClip clip, float gain, boolean loop) {
                return Playback.NONE;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        Sounds.installEngine(noStreaming);
        try {
            CountingSource source = new CountingSource();
            assertSame(Playback.NONE, Sounds.stream(source, PlayOptions.DEFAULTS));
            assertEquals(1, source.closes);
        } finally {
            Sounds.uninstallEngine(noStreaming);
        }
    }

    @Test
    void aRefusedArgumentIsNotAnOwnershipTransfer() {
        CountingSource source = new CountingSource();
        assertThrows(NullPointerException.class, () -> Sounds.stream(source, null));
        assertEquals(0, source.closes, "nothing was accepted, so nothing was taken over");
        assertThrows(NullPointerException.class,
                () -> Sounds.stream((AudioStreamSource) null, PlayOptions.DEFAULTS));
    }

    @Test
    void theFileOverloadOpensNothingWhenThereIsNoEngineToPlayIt() {
        // The decoder is consulted only after the engine is known to be able to play: a machine
        // with no device must not read a file it is about to throw away.
        Sounds.uninstallEngine(null);
        Sounds.uninstallDecoder(null);
        assertSame(Playback.NONE,
                Sounds.stream(java.nio.file.Path.of("nothing.ogg"), PlayOptions.DEFAULTS),
                "no engine short-circuits before requireDecoder would have thrown");
    }

    @Test
    void theFileOverloadHandsTheOpenedSourceToTheSameOwnershipRule() {
        KeepingEngine engine = new KeepingEngine();
        CountingSource opened = new CountingSource();
        AudioDecoder decoder = new AudioDecoder() {
            @Override
            public AudioClip decode(byte[] fileBytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AudioStreamSource openStream(java.nio.file.Path file) {
                return opened;
            }
        };
        Sounds.installEngine(engine);
        Sounds.installDecoder(decoder);
        try {
            Sounds.stream(java.nio.file.Path.of("music.ogg"), PlayOptions.DEFAULTS);
            assertSame(opened, engine.received, "one code path, one ownership rule");
            assertEquals(0, opened.closes);
        } finally {
            Sounds.uninstallEngine(engine);
            Sounds.uninstallDecoder(decoder);
        }
    }

    @Test
    void aSourceRefusedAtAdmissionIsStillClosedByWhoeverTookIt() {
        // Channel counts other than mono and stereo, and a full admission queue, are the engine's
        // to refuse, and the shipped one closes on those paths too. The facade's contract is that
        // the caller never has to know which of them happened.
        AudioEngine refusing = new AudioEngine() {
            @Override
            public Playback play(AudioClip clip, float gain, boolean loop) {
                return Playback.NONE;
            }

            @Override
            public Playback playStream(AudioStreamSource source, PlayOptions options) {
                source.close();
                return Playback.NONE;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        Sounds.installEngine(refusing);
        try {
            CountingSource source = new CountingSource();
            source.channels = 6;
            assertSame(Playback.NONE, Sounds.stream(source, PlayOptions.DEFAULTS));
            assertTrue(source.closes == 1, "closed exactly once, by the engine that refused it");
        } finally {
            Sounds.uninstallEngine(refusing);
        }
    }
}
