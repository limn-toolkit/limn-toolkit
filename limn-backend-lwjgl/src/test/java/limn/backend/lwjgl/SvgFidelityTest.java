package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.SvgRenderer;
import limn.graphics.internal.SvgSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How faithfully SVG is drawn, held where it was measured (ADR 066 section 7): 30 tests of each
 * kind from the resvg test suite, each against the picture the suite expects, and 18 files of the
 * kind an application ships, illustrations, emoji and icons, each against the picture a browser
 * drew. A picture is right when no more than 5% of the pixels either picture paints differ by more
 * than 40 levels, both laid on white. A change that draws worse than this fails: a lower renderer,
 * or a step of the toolkit's own reading that loses something.
 */
class SvgFidelityTest {

    private static final String ROOT = "/limn/backend/lwjgl/svg-fidelity/";

    /** Right of 30, per kind of the suite, as measured on 2026-10-14 with lunasvg 3.5.0 and the toolkit's reading. */
    private static final Map<String, Integer> SUITE_FLOOR = Map.of(
            "shapes", 29, "painting", 20, "paint-servers", 27, "masking", 22, "structure", 16, "filters", 6);

    private static final LunaSvgRenderer RENDERER = new LunaSvgRenderer();
    private static final StbImageDecoder PNG = new StbImageDecoder();

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = SvgFidelityTest.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IOException("no " + name);
            }
            return in.readAllBytes();
        }
    }

    private static List<String> list(String name) throws IOException {
        return List.of(new String(resource(name), StandardCharsets.UTF_8).trim().split("\n"));
    }

    /** Whether the toolkit draws {@code svg} as {@code expected} shows it, at its size. */
    static boolean right(byte[] svg, Image expected) {
        Image drawn;
        try (SvgRenderer.Document document = RENDERER.open(SvgSource.read(svg, null).text())) {
            drawn = document.render(expected.width(), expected.height());
        } catch (RuntimeException refused) {
            drawn = new Image(expected.width(), expected.height(), new byte[expected.width() * expected.height() * 4]);
        }
        int painted = 0;
        int off = 0;
        byte[] a = drawn.pixels();
        byte[] b = expected.pixels();
        for (int i = 0; i < a.length; i += 4) {
            int alphaA = a[i + 3] & 0xFF;
            int alphaB = b[i + 3] & 0xFF;
            if (alphaA == 0 && alphaB == 0) {
                continue;
            }
            painted++;
            for (int c = 0; c < 3; c++) {
                int x = ((a[i + c] & 0xFF) * alphaA + 255 * (255 - alphaA)) / 255;
                int y = ((b[i + c] & 0xFF) * alphaB + 255 * (255 - alphaB)) / 255;
                if (Math.abs(x - y) > 40) {
                    off++;
                    break;
                }
            }
        }
        return painted == 0 || off <= painted * 0.05;
    }

    @Test
    void theSuiteIsDrawnAtLeastAsWellAsWhenItWasMeasured() throws IOException {
        Map<String, Integer> right = new TreeMap<>();
        Map<String, List<String>> wrong = new TreeMap<>();
        for (String test : list("suite.txt")) {
            String kind = test.substring(0, test.indexOf("--"));
            boolean ok = right(resource("suite/" + test + ".svg"), PNG.decode(resource("suite/" + test + ".png")));
            right.merge(kind, ok ? 1 : 0, Integer::sum);
            if (!ok) {
                wrong.computeIfAbsent(kind, k -> new ArrayList<>()).add(test);
            }
        }
        System.out.println("SVG suite, right of 30: " + right);
        for (Map.Entry<String, Integer> floor : SUITE_FLOOR.entrySet()) {
            assertTrue(right.getOrDefault(floor.getKey(), 0) >= floor.getValue(), floor.getKey() + ": "
                    + right.get(floor.getKey()) + " right of 30, where " + floor.getValue() + " were: now wrong among "
                    + wrong.get(floor.getKey()));
        }
    }

    @Test
    void filesOfTheKindAnApplicationShipsAreDrawnAsABrowserDrawsThem() throws IOException {
        List<String> wrong = new ArrayList<>();
        List<String> files = list("real.txt");
        for (String file : files) {
            String name = file.substring(0, file.length() - 4);
            if (!right(resource("real/" + file), PNG.decode(resource("real/" + name + ".png")))) {
                wrong.add(name);
            }
        }
        System.out.println("SVG real files wrong: " + wrong);
        assertEquals(18, files.size());
        assertEquals(List.of(), wrong);
    }
}
