package limn.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Videos} registry: the probe order decoders are asked in, the two failures opening can
 * raise, and the cleanup a process-wide list obliges every caller to do.
 */
class VideosTest {

    private static final Path CLIP = Path.of("clip.mkv");

    @BeforeEach
    void startFromEmpty() {
        Videos.uninstallAllDecoders();
    }

    @AfterEach
    void leaveEmpty() {
        Videos.uninstallAllDecoders();
    }

    @Test
    void installOrderIsProbeOrder() {
        FakeDecoder first = new FakeDecoder("a", true);
        FakeDecoder second = new FakeDecoder("b", true);
        Videos.installDecoder(first);
        Videos.installDecoder(second);

        assertEquals(List.of(first, second), Videos.installedDecoders(),
                "the first installed decoder is asked first");
        assertSame(first.source, Videos.open(CLIP));
        assertEquals(0, second.supportsCalls, "the decoder behind the one that claimed was never asked");
    }

    @Test
    void duplicateInstallDoesNotReorder() {
        FakeDecoder first = new FakeDecoder("a", true);
        FakeDecoder second = new FakeDecoder("b", true);
        Videos.installDecoder(first);
        Videos.installDecoder(second);
        Videos.installDecoder(first);

        assertEquals(List.of(first, second), Videos.installedDecoders(),
                "running backend startup twice cannot reshuffle priorities");
    }

    @Test
    void uninstallOfANeverInstalledDecoderIsNoOp() {
        FakeDecoder installed = new FakeDecoder("a", true);
        Videos.installDecoder(installed);

        Videos.uninstallDecoder(new FakeDecoder("stranger", true));

        assertEquals(List.of(installed), Videos.installedDecoders());
    }

    @Test
    void uninstallAllClearsTheList() {
        Videos.installDecoder(new FakeDecoder("a", true));
        Videos.installDecoder(new FakeDecoder("b", true));
        assertTrue(Videos.isDecoderInstalled());

        Videos.uninstallAllDecoders();

        assertFalse(Videos.isDecoderInstalled());
        assertEquals(List.of(), Videos.installedDecoders());
    }

    @Test
    void openWithNoDecodersThrowsIllegalState() {
        assertFalse(Videos.isDecoderInstalled());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Videos.open(CLIP));
        assertTrue(failure.getMessage().contains("No VideoDecoder installed"), failure.getMessage());
    }

    @Test
    void openWithNoneAcceptingThrowsUnsupportedOperation() {
        Videos.installDecoder(new FakeDecoder("a", false));
        Videos.installDecoder(new FakeDecoder("b", false));

        UnsupportedOperationException failure =
                assertThrows(UnsupportedOperationException.class, () -> Videos.open(CLIP));

        assertTrue(failure.getMessage().contains("(tried, in order: a, b)"), failure.getMessage());
        assertTrue(failure.getMessage().contains(CLIP.toString()), failure.getMessage());
    }

    @Test
    void openPropagatesFromTheAcceptingDecoder() {
        FakeDecoder accepting = new FakeDecoder("a", true);
        accepting.failure = new IllegalArgumentException("frame 0 is not a keyframe");
        FakeDecoder behind = new FakeDecoder("b", true);
        Videos.installDecoder(accepting);
        Videos.installDecoder(behind);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Videos.open(CLIP));

        assertEquals("frame 0 is not a keyframe", failure.getMessage(),
                "the decoder that accepted the input is the one that knows what is wrong with it");
        assertEquals(0, behind.supportsCalls, "no later decoder is tried after one accepts");
    }

    @Test
    void canOpenDoesNotThrowAndDoesNotOpen() {
        FakeDecoder declining = new FakeDecoder("a", false);
        Videos.installDecoder(declining);
        assertFalse(Videos.canOpen(CLIP));

        FakeDecoder claiming = new FakeDecoder("b", true);
        Videos.installDecoder(claiming);
        assertTrue(Videos.canOpen(CLIP));

        assertEquals(0, claiming.openCalls, "asking is not opening");
        assertEquals(0, declining.openCalls);
    }

    @Test
    void installedDecodersIsImmutable() {
        Videos.installDecoder(new FakeDecoder("a", true));
        List<VideoDecoder> snapshot = Videos.installedDecoders();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new FakeDecoder("b", true)));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void nullArgumentsThrow() {
        assertThrows(NullPointerException.class, () -> Videos.installDecoder(null));
        assertThrows(NullPointerException.class, () -> Videos.open(null));
        assertThrows(NullPointerException.class, () -> Videos.canOpen(null));
    }

    /** A decoder that claims whatever it was built to claim, and counts what it was asked. */
    private static final class FakeDecoder implements VideoDecoder {

        private final String name;
        private final boolean claims;
        private final VideoStreamSource source = new StubSource();

        private RuntimeException failure;
        private int supportsCalls;
        private int openCalls;

        FakeDecoder(String name, boolean claims) {
            this.name = name;
            this.claims = claims;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(Path file) {
            supportsCalls++;
            return claims;
        }

        @Override
        public VideoStreamSource openStream(Path file) {
            openCalls++;
            if (failure != null) {
                throw failure;
            }
            return source;
        }
    }

    /** Enough of a source to be returned and closed; it decodes nothing. */
    private static final class StubSource implements VideoStreamSource {

        @Override
        public int width() {
            return 16;
        }

        @Override
        public int height() {
            return 16;
        }

        @Override
        public PixelFormat pixelFormat() {
            return PixelFormat.I420;
        }

        @Override
        public VideoColor color() {
            return VideoColor.unspecified();
        }

        @Override
        public int frameRateNum() {
            return 0;
        }

        @Override
        public int frameRateDen() {
            return 1;
        }

        @Override
        public Read readFrame() {
            return Read.END;
        }

        @Override
        public VideoFrame frame() {
            return null;
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
        }
    }
}
