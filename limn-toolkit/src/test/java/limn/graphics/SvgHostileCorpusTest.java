package limn.graphics;

import limn.graphics.internal.SvgAnimation;
import limn.graphics.internal.SvgSource;
import limn.io.MalformedContentException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hostile SVGs an audit wrote against the toolkit's own reading (ADR 061, ADR 066 section 6),
 * kept so that {@code check} goes on answering each as it was made to: one that reached for a
 * file is read with no path left for the renderer, at any moment of its animation; one that
 * spent the reader's memory or time without bound is refused as damaged content, quickly.
 */
class SvgHostileCorpusTest {

    private static final String CORPUS = "/limn/graphics/svg-hostile/";

    /** Longer than any of these takes to be read once its work is done once; far shorter than any took before. */
    private static final long QUICKLY_NANOS = 2_000_000_000L;
    /** Longer than any of these takes to be refused (half a second at most); far shorter than any took before. */
    private static final long REFUSED_NANOS = 1_000_000_000L;

    private static final Pattern REFERENCE = Pattern.compile("([\\w:-]*href)=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private static byte[] file(String name) throws IOException {
        try (InputStream in = SvgHostileCorpusTest.class.getResourceAsStream(CORPUS + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    /** A document that keeps every attribute it is told to set. */
    private static final class Recording implements SvgRenderer.Document {
        final List<String> set = new ArrayList<>();

        @Override public float width() { return 10; }
        @Override public float height() { return 10; }
        @Override public boolean setAttribute(String id, String name, String value) {
            set.add(name + "=" + value);
            return true;
        }
        @Override public Image render(int width, int height) { return new Image(1, 1, new byte[4]); }
        @Override public void close() { }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sv1-smil-set-href.svg", "sv1-smil-animate-href.svg", "sv1-css-keyframes-href.svg",
            "sv1-image-fragment-path.svg"})
    void aFileThatReachedForAPathLeavesNoneForTheRenderer(String name) throws IOException {
        SvgSource source = SvgSource.read(file(name), null);
        // None of these embeds a picture, so no image may keep a reference, and nothing else any
        // but one to an element of the file.
        String text = new String(source.text(), StandardCharsets.UTF_8);
        assertFalse(text.matches("(?s).*<image[^>]*href.*"), name + " hands the renderer " + text);
        Matcher reference = REFERENCE.matcher(text);
        while (reference.find()) {
            assertTrue(reference.group(2).startsWith("#"), name + " hands the renderer " + reference.group(0));
        }
        SvgAnimation animation = source.animation();
        if (animation != null) {
            Recording document = new Recording();
            SvgAnimation.Applied applied = new SvgAnimation.Applied();
            for (int frame = 0; frame < animation.frameCount(24); frame++) {
                animation.apply(document, applied, animation.timeOf(24, frame));
            }
            for (String set : document.set) {
                assertFalse(set.toLowerCase(Locale.ROOT).matches("[\\w:-]*href=.*"), name + " set " + set);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sv2-var-multiplies.svg", "sv3-smil-chain.svg", "sv3-deep-selector-budget.svg",
            "sv3-long-selector.svg"})
    void aFileThatSpentTheReaderWithoutBoundIsRefusedQuickly(String name) throws IOException {
        refusedQuickly(file(name), name);
    }

    /**
     * Files that took seconds for work that is now done once, and files whose damage escaped as
     * an exception other than {@link MalformedContentException}: they are read, and quickly.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sv3-descendant-selector.svg", "sv3-nth-child.svg", "sv4-animation-count-dot.svg"})
    void aFileThatTookSecondsIsReadQuickly(String name) throws IOException {
        byte[] svg = file(name);
        long start = System.nanoTime();
        SvgSource.read(svg, null);
        long took = System.nanoTime() - start;
        assertTrue(took < QUICKLY_NANOS, name + " took " + took / 1_000_000 + " ms");
    }

    /**
     * Longer than any crafted class or attribute list below takes to be read or refused (a few
     * hundred milliseconds); each took from 6 seconds to minutes when a try was counted as one
     * step whatever it compared.
     */
    private static final long ENDED_NANOS = 3_000_000_000L;

    /**
     * Files of the 2026-10-19 audit whose matching cost more than the tries it was charged, built
     * here since they are megabytes: one element whose class lists {@code n} words, all different
     * (1.5 MB, 20 seconds); a rule {@code .k.aaa…} against a class {@code k aaa…} twice as long,
     * each word end a near miss (600 KB, 10 seconds; 2.4 MB, minutes); one rule of {@code n}
     * classes over forty elements that carry all of them (2.4 MB, 7 seconds); and one rule of
     * {@code n} attribute conditions over two hundred elements of a thousand attributes (2.8 MB,
     * 19 seconds). Each is read or refused, quickly. And the files of that fix's review: a rule
     * {@code #qqq… circle} under a group of that id, a few megabytes long, over two hundred thousand
     * rects, where each rect's walk up to the group compared the whole id again (27 seconds), the
     * same with {@code .qqq… rect}, and {@code #qqq…BB rect} over a group {@code qqq…Aa}, of the same
     * hash and told apart only at its end (as slow before that fix).
     */
    @ParameterizedTest
    @CsvSource({"distinctClassWords, 200000", "classWordNearMisses, 400000", "classWordNearMisses, 1600000",
            "compoundOfManyClasses, 10000", "compoundOfManyAttributes, 200000", "longIdAbove, 200000",
            "longClassAbove, 200000", "longIdAboveThatCollides, 200000"})
    void aCraftedClassOrAttributeListIsReadOrRefusedQuickly(String shape, int n) {
        byte[] svg = crafted(shape, n);
        long start = System.nanoTime();
        try {
            SvgSource.read(svg, null);
        } catch (MalformedContentException refused) {
            // as good an answer as a reading, if it comes quickly
        }
        long took = System.nanoTime() - start;
        assertTrue(took < ENDED_NANOS, shape + " " + n + " (" + svg.length + " bytes) took " + took / 1_000_000 + " ms");
    }

    static byte[] crafted(String shape, int n) {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>");
        switch (shape) {
            case "distinctClassWords" -> {
                svg.append("<style>.zz{fill:red}</style><rect width='1' height='1' class='");
                for (int i = 0; i < n; i++) {
                    svg.append('w').append(i).append(' ');
                }
                svg.append("'/>");
            }
            case "classWordNearMisses" -> svg.append("<style>.k.").append(repeat('a', n / 2))
                    .append("{fill:red}</style><rect class='k ").append(repeat('a', n)).append("' width='1' height='1'/>");
            case "compoundOfManyClasses" -> {
                StringBuilder classes = new StringBuilder();
                StringBuilder selector = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    classes.append('c').append(i).append(' ');
                    selector.append(".c").append(i);
                }
                svg.append("<style>").append(selector).append("{fill:red}</style>");
                for (int e = 0; e < 40; e++) {
                    svg.append("<rect width='1' height='1' class='").append(classes).append("'/>");
                }
            }
            case "compoundOfManyAttributes" -> {
                StringBuilder selector = new StringBuilder();
                StringBuilder attributes = new StringBuilder();
                for (int i = 0; i < 1000; i++) {
                    attributes.append(" d").append(i).append("=''");
                }
                for (int i = 0; i < n; i++) {
                    selector.append("[d").append(i % 1000).append(']');
                }
                svg.append("<style>").append(selector).append("{fill:red}</style>");
                for (int e = 0; e < 200; e++) {
                    svg.append("<rect").append(attributes).append("/>");
                }
            }
            case "longIdAbove", "longClassAbove", "longIdAboveThatCollides" -> {
                String key = repeat('q', 4 << 20);
                boolean id = !shape.equals("longClassAbove");
                // "Aa" and "BB" have one hash: the sheet's key and the group's are never equal, and
                // tell themselves apart only at their end.
                boolean collides = shape.equals("longIdAboveThatCollides");
                // The id's rule names a type no element has; the class's and the colliding id's name the
                // rects, so that each rect walks up to the group.
                String type = shape.equals("longIdAbove") ? " circle" : " rect";
                svg.append("<style>").append(id ? '#' : '.').append(key).append(collides ? "BB" : "").append(type).append("{fill:red}</style><g ")
                        .append(id ? "id='" : "class='").append(key).append(collides ? "Aa" : "").append("'>");
                for (int i = 0; i < n; i++) {
                    svg.append("<rect/>");
                }
                svg.append("</g>");
            }
            default -> throw new IllegalArgumentException(shape);
        }
        return svg.append("</svg>").toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * What matching worked out about each element dies with the reading: kept on the elements, it
     * stayed with the picture for its life at some thirty times the file, and a 16 MB file of
     * class words ran a 512 MB heap out. Here a file of 16 MB, every element carrying the same
     * thousand class words (none of which the sheet names), is read, and what the source keeps of
     * a 4 MB one is held to a few times its size.
     */
    @org.junit.jupiter.api.Test
    void whatMatchingWorkedOutAboutElementsDiesWithTheReading() throws InterruptedException {
        SvgSource.read(classWordsOnEveryElement(4100), null);
        byte[] svg = classWordsOnEveryElement(1000);
        Runtime runtime = Runtime.getRuntime();
        long before = usedAfterCollection(runtime);
        SvgSource source = SvgSource.read(svg, null);
        long kept = usedAfterCollection(runtime) - before;
        assertNotNull(source.root());
        assertTrue(kept < 8L * svg.length, "a file of " + svg.length + " bytes keeps " + kept + " bytes");
    }

    private static long usedAfterCollection(Runtime runtime) throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** {@code elements} groups, each with every two-character word of letters and digits as a class: 3.9 KB each. */
    private static byte[] classWordsOnEveryElement(int elements) {
        StringBuilder words = new StringBuilder();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (char a : alphabet.toCharArray()) {
            for (char b : alphabet.toCharArray()) {
                words.append(a).append(b).append(' ');
            }
        }
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'><style>.zz9{fill:red}</style>");
        for (int i = 0; i < elements; i++) {
            svg.append("<g class='").append(words).append("'/>");
        }
        return svg.append("</svg>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    static void refusedQuickly(byte[] svg, String name) {
        long start = System.nanoTime();
        try {
            SvgSource.read(svg, null);
            throw new AssertionError(name + " was read");
        } catch (MalformedContentException expected) {
            long took = System.nanoTime() - start;
            assertTrue(took < REFUSED_NANOS, name + " took " + took / 1_000_000 + " ms to refuse");
        }
    }
}
