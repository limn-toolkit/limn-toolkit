package limn.print.internal;

import limn.backend.spi.GlyphOutlines;
import limn.graphics.Font;
import limn.graphics.Path2D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bank warns that a face cannot be outlined only where that is true of what is written: once,
 * naming the face, when the backend was asked and nothing is loading. The scratch frames that let a
 * document's animations end ask no backend, and a recording made while a face loads is made again,
 * so the Showcase's order logged the warning nine times for a PDF that kept every character.
 */
class GlyphBankTest {

    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getLogger(GlyphBank.class.getName());
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listen() {
        logger.addHandler(handler);
    }

    @AfterEach
    void stop() {
        logger.removeHandler(handler);
    }

    /** A backend that knows no face 3, with a load done or still going. */
    private static GlyphOutlines unknownFaceWhile(CompletionStage<Void> settled) {
        return new GlyphOutlines() {
            @Override
            public int unitsPerEm(int faceId) {
                return 0;
            }

            @Override
            public String faceName(int faceId) {
                return "Unknown";
            }

            @Override
            public boolean isSyntheticBold(int faceId) {
                return false;
            }

            @Override
            public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
                return false;
            }

            @Override
            public List<Piece> cluster(String characters, Font font) {
                return List.of();
            }

            @Override
            public CompletionStage<Void> settled() {
                return settled;
            }
        };
    }

    @Test
    void aBankThatAsksNoBackendSaysNothing() {
        GlyphBank scratch = new GlyphBank(GlyphOutlines.NONE);
        assertNull(scratch.face(0));
        assertNull(scratch.face(2));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFaceStillLoadingIsNotSaidToBeLost() {
        GlyphBank bank = new GlyphBank(unknownFaceWhile(new CompletableFuture<>()));
        assertNull(bank.face(3));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFaceTheSettledBackendCannotOutlineIsSaidOnceAndNamed() {
        GlyphBank bank = new GlyphBank(unknownFaceWhile(CompletableFuture.completedFuture(null)));
        assertNull(bank.face(3));
        assertNull(bank.face(4));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("face 3, Unknown"), warnings.get(0));
    }
}
