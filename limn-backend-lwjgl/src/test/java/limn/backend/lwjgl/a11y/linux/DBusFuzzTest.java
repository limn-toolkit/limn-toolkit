package limn.backend.lwjgl.a11y.linux;

import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message off the bus that is damaged is told apart from one that is whole, and that is all:
 * the reader that takes a frame answers a message or a failure, throws nothing, allocates
 * nothing a length in the frame alone asked for, and ends. The frames are other programs', and
 * one that ended the reader would end every conversation on the connection.
 */
class DBusFuzzTest {

    private static final String[] GOLDEN = {"a11y-hello-reply.hex", "doaction-call.hex", "embed-reply.hex",
            "getall-call.hex", "introspect-call.hex", "introspect-reply.hex", "session-hello-reply.hex"};

    private static List<byte[]> seeds() {
        List<byte[]> seeds = new ArrayList<>();
        for (String file : GOLDEN) {
            try (InputStream in = DBusFuzzTest.class.getResourceAsStream("golden/" + file)) {
                seeds.add(DBus.unhex(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim()));
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        return seeds;
    }

    @Test
    void aDamagedFrameIsAFailureAndNeverAnExceptionNorAnEndlessRead() {
        int[] parsed = {0};
        int[] failed = {0};
        MutationFuzz.Result result = assertTimeoutPreemptively(Duration.ofSeconds(120), () ->
                MutationFuzz.run(42, 40000, seeds(), frame -> {
                    DBus.Conn.Inbound inbound = DBus.Conn.Inbound.of(frame);
                    if (inbound.message() != null) {
                        parsed[0]++;
                    } else {
                        failed[0]++;
                    }
                }, thrown -> false, "limn.backend.lwjgl.a11y.linux."),
                "the reader did not end: an array or a nesting the frame chose kept it going");
        assertEquals(Map.of(), result.escapes(), "a damaged frame escaped the reader");
        assertTrue(parsed[0] > 0 && failed[0] > 0, "the mutations test nothing: " + parsed[0] + " parsed, "
                + failed[0] + " failed");
    }
}
