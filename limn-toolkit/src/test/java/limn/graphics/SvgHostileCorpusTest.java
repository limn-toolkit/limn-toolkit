package limn.graphics;

import limn.graphics.internal.SvgAnimation;
import limn.graphics.internal.SvgSource;
import limn.io.MalformedContentException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /** Longer than any of these takes once it is refused; far shorter than any took before. */
    private static final long QUICKLY_NANOS = 2_000_000_000L;

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

    static void refusedQuickly(byte[] svg, String name) {
        long start = System.nanoTime();
        try {
            SvgSource.read(svg, null);
            throw new AssertionError(name + " was read");
        } catch (MalformedContentException expected) {
            long took = System.nanoTime() - start;
            assertTrue(took < QUICKLY_NANOS, name + " took " + took / 1_000_000 + " ms to refuse");
        }
    }
}
