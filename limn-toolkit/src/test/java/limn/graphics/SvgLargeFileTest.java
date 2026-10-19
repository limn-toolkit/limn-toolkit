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

    /**
     * A sprite sheet: {@code symbols} symbols of ten paths each, every symbol's paths styled by a
     * rule that names the symbol, {@code #s12 path} or {@code .icon-12 path}. The rule's last
     * compound is a type, so filed under it alone it was tried on every path of the sheet, and an
     * ordinary 112 KB sheet of four hundred symbols was refused.
     */
    @ParameterizedTest
    @CsvSource({"400, id", "1000, id", "1000, class", "3000, class"})
    void aSpriteSheetStyledThroughItsSymbolsIsRead(int symbols, String key) {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><style>");
        for (int i = 0; i < symbols; i++) {
            svg.append(key.equals("id") ? "#s" : ".icon-").append(i).append(" path{fill:#")
                    .append(String.format("%06x", i * 997 & 0xffffff)).append('}');
        }
        svg.append("</style><defs>");
        for (int i = 0; i < symbols; i++) {
            svg.append(key.equals("id") ? "<symbol id='s" : "<symbol class='icon-").append(i).append("' viewBox='0 0 24 24'>");
            for (int p = 0; p < 10; p++) {
                svg.append("<path d='M").append(p).append(" 0h1v1z'/>");
            }
            svg.append("</symbol>");
        }
        svg.append("</defs><use href='#s0'/></svg>");
        readsInTime(svg.toString().getBytes(StandardCharsets.UTF_8), symbols + " symbols keyed by " + key);
    }

    /** {@code rules} theme rules {@code .tN path} over fifty thousand paths three groups down from the one that names its theme. */
    @ParameterizedTest
    @CsvSource({"100", "1000"})
    void themeRulesOverAnAncestorClassAreRead(int rules) {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><style>");
        for (int i = 0; i < rules; i++) {
            svg.append(".t").append(i).append(" path{fill:#").append(String.format("%06x", i * 997 & 0xffffff)).append('}');
        }
        svg.append("</style><g class='t0'><g><g>");
        for (int p = 0; p < 50000; p++) {
            svg.append("<path d='M").append(p % 100).append(" 0h1v1z'/>");
        }
        svg.append("</g></g></g></svg>");
        readsInTime(svg.toString().getBytes(StandardCharsets.UTF_8), rules + " theme rules over 50000 paths");
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
