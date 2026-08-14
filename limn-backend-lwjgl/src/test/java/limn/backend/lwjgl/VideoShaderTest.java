package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the two properties of the conversion program that no pixel comparison
 * would catch, onto its source: no GL, so they hold on a machine with no GPU
 * and in a review that never runs the suite.
 *
 * <p>One: every number of the colour matrix arrives as a uniform. A coefficient
 * typed into the shader would create a second table, and the day one of them
 * changes the pictures acquire a tint that looks like a display profile.
 *
 * <p>Two: the program decodes a matrix and does not manage colour. Its output
 * is display-referred, which is what lets the composite treat it as an ordinary
 * picture; a transfer function applied here would be applied twice.
 *
 * <p><b>There are two conversion sources and every check runs over both.</b> That
 * is not tidiness: when the rectangle-sampler sibling arrived for the zero-copy
 * path, a test naming {@code video_convert.frag} as a hardcoded resource did not
 * start failing: it went <em>blind</em>, and would have reported six passes over
 * a file nobody had changed while the new one drifted. So the source is a
 * parameter, and {@link #theseChecksCatchACoefficientTypedIntoTheRectangleSource}
 * is the check on the check: it doctors the rectangle source and asserts that the
 * parameterised form fails on it.
 */
class VideoShaderTest {

    /** The two fragment sources. Anything added here is checked by everything below. */
    private static final String[] FRAGMENTS = {"video_convert.frag", "video_convert_rect.frag"};

    /**
     * The only numbers the conversion may spell: the clamp, and the interleaved flag it compares
     * against. Not the code range: 255 became a uniform when a picture stopped always having
     * eight bits, and a shader that still spelled it would decode a 10-bit picture as if the
     * samples ran out at a quarter of their range.
     */
    private static final Set<String> ALLOWED_LITERALS = Set.of("0.0", "1.0", "0", "1");

    /** Every uniform a conversion declares. The Java side looks up exactly these, for both. */
    private static final Set<String> UNIFORMS = Set.of(
            "u_luma", "u_cb", "u_cr", "u_interleaved", "u_height", "u_chromaShift",
            "u_sampleScale", "u_maxCode", "u_yScale", "u_yOffset", "u_chromaNeutral",
            "u_crToR", "u_cbToG", "u_crToG", "u_cbToB");

    @ParameterizedTest
    @ValueSource(strings = {"video_convert.frag", "video_convert_rect.frag"})
    void everyColourNumberArrivesAsAUniform(String fragment) {
        String source = shader(fragment);
        for (String uniform : new String[] {
            "u_sampleScale", "u_maxCode", "u_yScale", "u_yOffset", "u_chromaNeutral",
            "u_crToR", "u_cbToG", "u_crToG", "u_cbToB",
        }) {
            assertTrue(source.contains("uniform float " + uniform + ";"),
                    fragment + ": " + uniform + " must be a uniform, not a literal: " + source);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"video_convert.frag", "video_convert_rect.frag"})
    void noCoefficientIsTypedIntoTheShader(String fragment) {
        // 255 is the code range and 0/1 are the clamp; everything else about
        // the colour is somebody else's number. A neutral of 0.5 instead of the
        // uniform (128 of 255) tints grey by about a code; a luma offset of
        // 0.0627 instead of 16 does the same to every dark pixel.
        assertNoTypedCoefficients(fragment, shader(fragment));

        // And every uniform it declares is one the backend binds: a number
        // that arrives unbound is as wrong as one typed in.
        Set<String> declared = new LinkedHashSet<>();
        Matcher uniforms = Pattern.compile("uniform\\s+\\w+\\s+(\\w+)\\s*;").matcher(shader(fragment));
        while (uniforms.find()) {
            declared.add(uniforms.group(1));
        }
        assertEquals(UNIFORMS, declared,
                fragment + ": the shader's uniforms and the ones the backend binds");
    }

    @ParameterizedTest
    @ValueSource(strings = {"video_convert.frag", "video_convert_rect.frag"})
    void samplesAreAddressedByIntegerTexel(String fragment) {
        // Chroma for pixel x is sample x >> shift. A normalized coordinate
        // cannot express that at odd widths whatever the filter is set to.
        String source = withoutComments(shader(fragment));
        assertTrue(source.contains("texelFetch("), source);
        assertFalse(source.contains("texture("),
                "a filtered sample would round the chroma coordinate: " + source);
        assertTrue(source.contains(">> u_chromaShift.x") && source.contains(">> u_chromaShift.y"),
                source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"video_convert.frag", "video_convert_rect.frag"})
    void theConversionDoesNotManageColour(String fragment) {
        String source = shader(fragment);
        for (String transform : new String[] {"linearToSrgb", "srgbToLinear", "tonemapACES"}) {
            assertFalse(source.contains(transform),
                    fragment + ": the picture is already display-referred; " + transform
                            + " here would be applied a second time by the composite");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"video_convert.frag", "video_convert_rect.frag"})
    void bothStagesDeclareTheSameGlslVersion(String fragment) {
        assertEquals("#version 330 core", shader("video_convert.vert").lines().findFirst().orElseThrow());
        assertEquals("#version 330 core", shader(fragment).lines().findFirst().orElseThrow());
    }

    @Test
    void theTriangleNeedsNoVertexData() {
        // Keeping this program off the batch is the point; an attribute here
        // would mean a vertex buffer and a format to keep in step with it, for
        // a triangle whose three corners are already known. One vertex stage
        // serves both fragment sources, which is why this is not parameterised.
        String source = withoutComments(shader("video_convert.vert"));
        assertTrue(source.contains("gl_VertexID"), source);
        assertFalse(source.contains(" in "), "no vertex attributes: " + source);
    }

    @Test
    void theTwoSourcesAreTheSameConversion() {
        // The cost of a second file is that the two can drift, and a drift in the arithmetic would
        // show as a hardware-decoded picture whose colour is subtly not a software one's, which
        // reads as an interop bug and is not one. So the difference is bounded to exactly what the
        // sampler forces: the keyword, and texelFetch's missing level argument.
        assertEquals(canonical(withoutComments(shader("video_convert.frag"))),
                canonical(rectangleAsIfItWereSampled(withoutComments(shader("video_convert_rect.frag")))),
                "the rectangle conversion must differ from the uploaded one only by its sampler");
    }

    @Test
    void theseChecksCatchACoefficientTypedIntoTheRectangleSource() {
        // The check on the check. Six tests naming one file kept passing when a second file
        // appeared, so the parameterisation above is only worth something if it actually fires on
        // the source it gained, which is what this asserts, by breaking that source deliberately.
        String rect = shader("video_convert_rect.frag");
        String doctoredRect = rect.replace("cbcr.x - u_chromaNeutral", "cbcr.x - 512.0");
        assertNotEquals(rect, doctoredRect,
                "the doctoring did not take, so the assertion below would pass for the wrong reason");
        assertThrows(AssertionError.class,
                () -> assertNoTypedCoefficients("video_convert_rect.frag (doctored)", doctoredRect),
                "a coefficient typed into the rectangle source must fail these checks");

        // And the same for the uploaded one, so neither parameter is the only one being examined.
        String uploaded = shader("video_convert.frag");
        String doctoredUploaded =
                uploaded.replace("u_yScale * (y - u_yOffset)", "1.1643835616 * (y - u_yOffset)");
        assertNotEquals(uploaded, doctoredUploaded, "the doctoring did not take");
        assertThrows(AssertionError.class,
                () -> assertNoTypedCoefficients("video_convert.frag (doctored)", doctoredUploaded));
    }

    /** The literal check, as a method so the test above can point it at a source that should fail. */
    private static void assertNoTypedCoefficients(String where, String source) {
        Set<String> literals = numericLiterals(withoutComments(source));
        Set<String> unexpected = new LinkedHashSet<>(literals);
        unexpected.removeAll(ALLOWED_LITERALS);
        assertTrue(unexpected.isEmpty(),
                where + ": the conversion may only spell the code range and the clamp; found "
                        + unexpected);
    }


    /**
     * The rectangle source rewritten as the sampled one would spell it: a rectangle sampler is a
     * {@code sampler2D} with no level argument, so putting both back makes the two comparable.
     * Nothing else may differ, which is the point.
     */
    private static String rectangleAsIfItWereSampled(String source) {
        return source.replace("sampler2DRect", "sampler2D")
                .replaceAll("texelFetch\\(([^)]*)\\)", "texelFetch($1, 0)");
    }

    /** Whitespace-insensitive form, so the two files may be laid out and wrapped independently. */
    private static String canonical(String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

    /**
     * Every numeric literal in the source: floats in each spelling GLSL allows
     * ({@code 1.0}, {@code .5}, {@code 128.}, {@code 1.6e0}) and plain integers,
     * because {@code cb - 128} converts implicitly and would otherwise be a
     * retyped coefficient this test cannot see.
     */
    private static Set<String> numericLiterals(String source) {
        Set<String> literals = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "(?<![\\w.])(?:\\d+\\.\\d*(?:[eE][+-]?\\d+)?"
                        + "|\\.\\d+(?:[eE][+-]?\\d+)?"
                        + "|\\d+[eE][+-]?\\d+"
                        + "|\\d+)(?![\\w.])").matcher(source);
        while (matcher.find()) {
            literals.add(matcher.group());
        }
        return literals;
    }

    /**
     * Strips line comments and the version directive: the comments explain the
     * numbers and are allowed to quote them, and {@code #version 330} is a
     * number about the language rather than about the picture.
     */
    private static String withoutComments(String source) {
        return source.replaceAll("(?m)//.*$", "").replaceAll("(?m)^#version.*$", "");
    }

    private static String shader(String name) {
        try (InputStream in = VideoShaderTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/shaders/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing shader resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Both sources, for a reader wondering whether the array above is used anywhere else. */
    @Test
    void everyFragmentSourceInTheListExists() {
        for (String fragment : FRAGMENTS) {
            assertTrue(shader(fragment).startsWith("#version"), fragment);
        }
    }
}
