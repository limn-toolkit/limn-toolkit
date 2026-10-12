package limn.components;

import limn.io.MalformedContentException;
import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A palette that is broken is refused with a {@link MalformedContentException} and with nothing
 * else: a palette is a file people edit by hand and send each other.
 */
class ThemeFormatFuzzTest {

    @Test
    void onlyAMalformedContentExceptionEverEscapes() {
        List<byte[]> seeds = List.of(
                ThemeFormat.write(Theme.dark()).getBytes(StandardCharsets.UTF_8),
                ThemeFormat.write(Theme.light()).getBytes(StandardCharsets.UTF_8));
        MutationFuzz.Result result = MutationFuzz.run(42, 20000, seeds,
                data -> ThemeFormat.parse(new String(data, StandardCharsets.UTF_8)),
                thrown -> thrown.getClass() == MalformedContentException.class, "limn.components.");
        assertEquals(Map.of(), result.escapes(), "a broken palette escaped the reader as something other "
                + "than a MalformedContentException");
        assertTrue(result.read() > 0 && result.refused() > 0, "the mutations test nothing: "
                + result.read() + " read, " + result.refused() + " refused");
    }
}
