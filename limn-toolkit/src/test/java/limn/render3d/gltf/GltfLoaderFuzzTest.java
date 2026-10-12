package limn.render3d.gltf;

import limn.io.MalformedContentException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A malformed model is refused with a {@link MalformedContentException} and with nothing else,
 * whatever is wrong with it: {@link GltfLoader#load(byte[])} says so, and an application that
 * loads a user's file catches that one exception to tell the user the file is broken. Anything
 * else escaping is a crash that application did not know to expect.
 *
 * <p>A deterministic mutation fuzz over two seed documents, the demo's small model (three meshes,
 * three materials, an embedded texture and its buffers) and the one-node file the resource tests
 * load. Each input is one of four mutations of a seed: cut short at a random byte, one JSON value
 * swapped for a value of another type (the most productive, so it is drawn twice as often), a few
 * random bytes overwritten, or the document wrapped in a GLB container that is itself sometimes
 * cut short. The seed is fixed, so a failure is the same failure on every machine and every run.
 *
 * <p>What is asserted is the exception's <b>class</b>, exactly: not a subclass, so a
 * {@code NumberFormatException} saying only "For input string" does not pass for a refusal that
 * says where. And its causes, each of which must be one too, or an {@code IllegalArgumentException},
 * so that a blanket catch wrapping a {@code NullPointerException} or an
 * {@code IndexOutOfBoundsException} cannot hide a loader bug behind the right outer class. A
 * failure names each escaping class with the first frame inside the loader that threw it, which is
 * the site to fix, and one input that reaches it.
 */
class GltfLoaderFuzzTest {

    /** The mutation stream. Changing it changes every input, which is a new test, not this one. */
    private static final long SEED = 42;

    /** Enough to reach every refusal the loader has, and to take a second or two. */
    private static final int ITERATIONS = 10000;

    private static final String[] REPLACEMENTS = {
            "\"x\"", "[1]", "{}", "null", "-1", "99999", "1e30", "true", "[]", "0.5"};

    /** A JSON value, or the opening of a container. */
    private static final Pattern VALUE = Pattern.compile(
            "-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?|\"[^\"]*\"|\\[|\\{|true|false|null");

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;

    @Test
    void onlyAMalformedContentExceptionEverEscapes() {
        Map<String, String> escapes = fuzz(SEED, ITERATIONS);
        assertEquals(Map.of(), escapes, "a malformed model escaped the loader as something other "
                + "than a MalformedContentException, by class and the first loader frame that "
                + "threw it, with one input that reaches it");
    }

    /**
     * The fuzz itself, apart from its assertion, so that a longer run is a change of one number.
     *
     * @return every escape, keyed by class and site, each with the first input that reached it
     */
    static Map<String, String> fuzz(long seed, int iterations) {
        List<String> seeds = List.of(resource("fuzz-seed-toy.gltf"), resource("test-node.gltf"));
        Random random = new Random(seed);
        Map<String, String> escapes = new TreeMap<>();
        int loaded = 0;
        int refused = 0;
        for (int it = 0; it < iterations; it++) {
            byte[] data = mutate(seeds.get(random.nextInt(seeds.size())), random);
            try {
                GltfLoader.load(data);
                loaded++;
            } catch (MalformedContentException refusal) {
                String hidden = hiddenCause(refusal);
                if (hidden != null) {
                    escapes.putIfAbsent(name(refusal) + (hidden == null ? "" : " hiding " + hidden)
                            + " at " + site(refusal), sample(data, refusal));
                } else {
                    refused++;
                }
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError escape) {
                escapes.putIfAbsent(name(escape) + " at " + site(escape), sample(data, escape));
            }
        }
        assertTrue(loaded > 0 && refused > 0, "the mutations are too weak or too strong to test "
                + "anything: " + loaded + " loaded, " + refused + " refused");
        return escapes;
    }

    private static byte[] mutate(String seed, Random random) {
        byte[] bytes = seed.getBytes(StandardCharsets.UTF_8);
        switch (random.nextInt(5)) {
            case 0 -> {
                return Arrays.copyOf(bytes, random.nextInt(bytes.length));
            }
            case 1, 2 -> {
                Matcher m = VALUE.matcher(seed);
                List<int[]> spans = new ArrayList<>();
                while (m.find()) {
                    spans.add(new int[] {m.start(), m.end()});
                }
                int[] span = spans.get(random.nextInt(spans.size()));
                String replacement = REPLACEMENTS[random.nextInt(REPLACEMENTS.length)];
                char first = seed.charAt(span[0]);
                // An opening bracket or brace is not a whole value: it is replaced by the new value
                // and a comma, so what the container held follows as further elements and its
                // closing is left unmatched.
                String text = first == '[' || first == '{'
                        ? seed.substring(0, span[0]) + replacement + " , " + seed.substring(span[1])
                        : seed.substring(0, span[0]) + replacement + seed.substring(span[1]);
                return text.getBytes(StandardCharsets.UTF_8);
            }
            case 3 -> {
                int flips = 1 + random.nextInt(4);
                for (int k = 0; k < flips; k++) {
                    bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
                }
                return bytes;
            }
            default -> {
                ByteBuffer glb = ByteBuffer.allocate(20 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
                glb.putInt(GLB_MAGIC).putInt(2).putInt(20 + bytes.length)
                        .putInt(bytes.length).putInt(CHUNK_JSON).put(bytes);
                return Arrays.copyOf(glb.array(), 4 + random.nextInt(glb.capacity() - 3));
            }
        }
    }

    /** The first cause in the chain that is neither a refusal nor an IllegalArgumentException, or null. */
    private static String hiddenCause(Throwable refusal) {
        for (Throwable cause = refusal.getCause(); cause != null; cause = cause.getCause()) {
            if (!(cause instanceof IllegalArgumentException) && !(cause instanceof MalformedContentException)) {
                return name(cause) + " at " + site(cause);
            }
        }
        return null;
    }

    private static String name(Throwable t) {
        return t.getClass().getSimpleName();
    }

    /** The first frame inside the loader's package, as {@code Class.method:line}. */
    private static String site(Throwable t) {
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith("limn.render3d.gltf.")
                    && !frame.getClassName().endsWith("FuzzTest")) {
                String type = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1);
                return type + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "?";
    }

    private static String sample(byte[] data, Throwable t) {
        String text = new String(data, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        return t.getMessage() + " <- " + (text.length() > 160 ? text.substring(0, 160) + "..." : text);
    }

    private static String resource(String name) {
        try (InputStream in = GltfLoaderFuzzTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("no test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
