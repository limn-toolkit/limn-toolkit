package limn.graphics;

import limn.graphics.internal.SvgSource;
import limn.io.MalformedContentException;
import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An SVG that is damaged is refused by the toolkit's own reading with a
 * {@link MalformedContentException} and with nothing else, and quickly (ADR 066 section 6): what
 * reaches the renderer has passed through here first.
 */
class SvgSourceFuzzTest {

    private static final List<String> SEEDS = List.of(
            "<?xml version='1.0' encoding='UTF-8'?><svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>"
                    + "<path d='M12 2 L22 22 H2 Z' fill='#123' stroke-width='2'/><circle cx='12' cy='12' r='4'/></svg>",
            "<!DOCTYPE svg PUBLIC '-//W3C//DTD SVG 1.1//EN' 'http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd' ["
                    + "<!ENTITY ns 'http://ns.example/&#65;'><!ENTITY r \"<rect id='r' width='2' height='2'/>\">]>"
                    + "<svg xmlns='http://www.w3.org/2000/svg' xmlns:x='&ns;' width='10' height='10'><g>&r;</g></svg>",
            "<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='8' height='8'>"
                    + "<style><![CDATA[.s{fill:red} g > rect{opacity:.5}]]></style><defs><g id='a'><rect class='s' width='1' height='1'/></g>"
                    + "<g id='b'><use href='#a'/><use xlink:href='#a' x='2'/></g></defs><use href='#b'/>"
                    + "<image href='data:image/png;base64,iVBORw0KGgo=' width='1' height='1'/>"
                    + "<text x='1' y='5'>caf&#233; &amp; &lt;b&gt;</text><script>x()</script></svg>",
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><circle cx='4' cy='12' r='3'>"
                    + "<animate attributeName='r' values='3;1;3' dur='1s' repeatCount='indefinite' calcMode='spline' "
                    + "keySplines='.5 0 .5 1;.5 0 .5 1'/></circle><rect x='9' y='9' width='6' height='6'>"
                    + "<animateTransform attributeName='transform' type='rotate' from='0 12 12' to='360 12 12' dur='2s'/>"
                    + "</rect></svg>");

    private static List<byte[]> seeds() {
        List<byte[]> bytes = new ArrayList<>();
        for (String seed : SEEDS) {
            bytes.add(seed.getBytes(StandardCharsets.UTF_8));
        }
        return bytes;
    }

    private static void read(byte[] data) {
        SvgSource.read(data, null);
    }

    @Test
    void everySeedIsRead() {
        for (byte[] seed : seeds()) {
            read(seed);
        }
    }

    @Test
    void damagedBytesAreRefusedAsDamagedContentAndNothingElse() {
        MutationFuzz.Result result = MutationFuzz.run(66, 20_000, seeds(), SvgSourceFuzzTest::read,
                thrown -> thrown.getClass() == MalformedContentException.class, "limn.graphics.");
        assertEquals(Map.of(), result.escapes(), "a damaged SVG escaped the reader as something else");
        assertTrue(result.read() > 0 && result.refused() > 0, result.read() + " read, " + result.refused() + " refused");
    }

    /**
     * Damage of the kind that reaches past the first character: a quote, a bracket or an ampersand
     * dropped in, part of a tag cut out, a name doubled. Byte noise alone is mostly refused at once.
     */
    @Test
    void damagedMarkupIsRefusedAsDamagedContentAndNothingElse() {
        Random random = new Random(661);
        Map<String, String> escapes = new TreeMap<>();
        int read = 0;
        int refused = 0;
        long slowest = 0;
        String[] marks = {"\"", "'", "<", ">", "&", ";", "/", "=", "]]>", "<!--", "&#", "<![CDATA[", "<!ENTITY a 'b'>", "&r;"};
        for (int it = 0; it < 20_000; it++) {
            StringBuilder text = new StringBuilder(SEEDS.get(random.nextInt(SEEDS.size())));
            for (int m = 1 + random.nextInt(4); m > 0 && text.length() > 0; m--) {
                int at = random.nextInt(text.length());
                switch (random.nextInt(4)) {
                    case 0 -> text.insert(at, marks[random.nextInt(marks.length)]);
                    case 1 -> text.delete(at, Math.min(text.length(), at + 1 + random.nextInt(12)));
                    case 2 -> text.insert(at, text.substring(at, Math.min(text.length(), at + random.nextInt(40))));
                    default -> text.setCharAt(at, (char) (32 + random.nextInt(95)));
                }
            }
            byte[] data = text.toString().getBytes(StandardCharsets.UTF_8);
            long start = System.nanoTime();
            try {
                read(data);
                read++;
            } catch (MalformedContentException expected) {
                refused++;
            } catch (RuntimeException | StackOverflowError escaped) {
                escapes.putIfAbsent(escaped.getClass().getSimpleName() + ": " + escaped.getMessage(), text.toString());
            }
            slowest = Math.max(slowest, System.nanoTime() - start);
        }
        assertEquals(Map.of(), escapes);
        assertTrue(read > 0 && refused > 0, read + " read, " + refused + " refused");
        assertTrue(slowest < 2_000_000_000L, "one damaged file took " + slowest / 1_000_000 + " ms");
    }
}
