package limn.graphics;

import limn.graphics.internal.SvgSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large files of the ordinary kind are read: the bounds on what reading an SVG may cost
 * ({@link SvgHostileCorpusTest} holds the files they are for) grow with the file, and a style
 * sheet's rule is tried only on the elements it could name. An export of fifty thousand paths
 * under four hundred class rules was refused once each element was matched against every rule
 * under a fixed count. The files are made here, not kept: they are megabytes.
 */
class SvgLargeFileTest {

    /** Far over what each takes (under a second), and under what the reader took before it matched by index. */
    private static final long SANE_NANOS = 5_000_000_000L;

    private static void readsInTime(byte[] svg, String what) {
        long start = System.nanoTime();
        SvgSource.read(svg, null);
        long took = System.nanoTime() - start;
        assertTrue(took < SANE_NANOS, what + " took " + took / 1_000_000 + " ms");
    }

    /** {@code paths} paths, each with one of {@code rules} classes, each class a rule. */
    @ParameterizedTest
    @CsvSource({"50000, 400", "30000, 800", "100000, 250", "200000, 120"})
    void anExportOfManyPathsUnderManyClassRulesIsRead(int paths, int rules) {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1000 1000'><defs><style>");
        for (int i = 0; i < rules; i++) {
            svg.append(".cls-").append(i).append("{fill:#").append(String.format("%06x", i * 997 & 0xffffff)).append(";}");
        }
        svg.append("</style></defs><g>");
        for (int i = 0; i < paths; i++) {
            svg.append("<path class='cls-").append(i % rules).append("' d='M").append(i % 1000).append(" 0h1v1z'/>");
        }
        svg.append("</g></svg>");
        readsInTime(svg.toString().getBytes(StandardCharsets.UTF_8), paths + " paths under " + rules + " rules");
    }

    /** Thousands of animations that begin at their own times, beside a chain of three: only the chain is worked out round by round. */
    @ParameterizedTest
    @CsvSource({"6000, 100"})
    void manyAnimationsBesideAShortChainAreRead(int animations, int linkMillis) {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>");
        for (int link = 1; link <= 3; link++) {
            String begin = link == 1 ? "0s;d3.end" : "d" + (link - 1) + ".end";
            svg.append("<circle cx='").append(link * 10).append("' cy='10' r='3'><animate id='d").append(link)
                    .append("' attributeName='opacity' values='1;0' begin='").append(begin).append("' dur='")
                    .append(linkMillis).append("ms'/></circle>");
        }
        for (int i = 0; i < animations; i++) {
            svg.append("<rect x='").append(i % 100).append("' y='50' width='1' height='1'><animate attributeName='opacity' "
                    + "values='1;0;1' begin='0s' dur='2s' repeatCount='indefinite'/></rect>");
        }
        svg.append("</svg>");
        readsInTime(svg.toString().getBytes(StandardCharsets.UTF_8), animations + " animations beside a chain");
    }
}
