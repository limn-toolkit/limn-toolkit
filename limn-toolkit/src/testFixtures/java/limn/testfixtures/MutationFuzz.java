package limn.testfixtures;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A deterministic mutation fuzz for a reader of bytes: seeds that are valid, each damaged in one
 * of a few ways a broken or hostile file is, and handed to the reader, which may answer or may
 * refuse in the way it documents and may do nothing else.
 *
 * <p>The stream of mutations comes from a fixed seed, so a failure is the same failure on every
 * machine and every run, and the report names each escaping class with the first frame of the
 * reader's own that threw it and one input that reaches it.
 */
public final class MutationFuzz {

    /** What reads the bytes. What it throws is judged by the run. */
    @FunctionalInterface
    public interface Reader {
        void read(byte[] data) throws Exception;
    }

    /**
     * What a run found.
     *
     * @param escapes every exception that was no documented refusal, by class and site, each with
     *                one input that reaches it, in hexadecimal
     * @param read    how many inputs were read whole
     * @param refused how many were refused as documented
     */
    public record Result(Map<String, String> escapes, int read, int refused) {
    }

    private static final int[] INTERESTING = {
            0, 1, -1, 2, 16, 255, 256, 65535, 65536, 0x7FFFFFFF, 0x80000000, 0x7FFFFFF0, 0x40000000, 0x10000000};

    private MutationFuzz() {
    }

    /**
     * @param seed       the mutation stream; changing it changes every input
     * @param iterations how many inputs
     * @param seeds      valid inputs to damage
     * @param reader     what reads them
     * @param refusal    whether an exception is the reader's documented refusal
     * @param sitePrefix the package whose first frame names where an escape was thrown
     */
    public static Result run(long seed, int iterations, List<byte[]> seeds, Reader reader,
            Predicate<Throwable> refusal, String sitePrefix) {
        Random random = new Random(seed);
        Map<String, String> escapes = new TreeMap<>();
        int read = 0;
        int refused = 0;
        for (int it = 0; it < iterations; it++) {
            byte[] data = mutate(seeds.get(random.nextInt(seeds.size())), random);
            try {
                reader.read(data);
                read++;
            } catch (Exception | StackOverflowError | OutOfMemoryError thrown) {
                if (refusal.test(thrown)) {
                    refused++;
                } else {
                    escapes.putIfAbsent(thrown.getClass().getSimpleName() + " at " + site(thrown, sitePrefix),
                            sample(data, thrown));
                }
            }
        }
        return new Result(escapes, read, refused);
    }

    /** One of the ways a file is broken: cut short, a few bytes wrong, a number that lies, a part gone or doubled. */
    public static byte[] mutate(byte[] seed, Random random) {
        byte[] bytes = seed.clone();
        if (bytes.length < 2) {
            return bytes;
        }
        switch (random.nextInt(7)) {
            case 0 -> {
                return Arrays.copyOf(bytes, random.nextInt(bytes.length));
            }
            case 1 -> {
                int flips = 1 + random.nextInt(4);
                for (int k = 0; k < flips; k++) {
                    bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
                }
                return bytes;
            }
            case 2, 3 -> {
                // A length, a count or an offset that lies, in either byte order and width.
                int value = INTERESTING[random.nextInt(INTERESTING.length)];
                ByteOrder order = random.nextBoolean() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                ByteBuffer view = ByteBuffer.wrap(bytes).order(order);
                if (random.nextBoolean() && bytes.length >= 4) {
                    view.putInt(random.nextInt(bytes.length - 3), value);
                } else {
                    view.putShort(random.nextInt(bytes.length - 1), (short) value);
                }
                return bytes;
            }
            case 4 -> {
                int from = random.nextInt(bytes.length);
                int length = 1 + random.nextInt(Math.min(64, bytes.length - from));
                byte[] out = new byte[bytes.length - length];
                System.arraycopy(bytes, 0, out, 0, from);
                System.arraycopy(bytes, from + length, out, from, bytes.length - from - length);
                return out;
            }
            case 5 -> {
                int from = random.nextInt(bytes.length);
                int length = 1 + random.nextInt(Math.min(64, bytes.length - from));
                byte[] out = new byte[bytes.length + length];
                System.arraycopy(bytes, 0, out, 0, from + length);
                System.arraycopy(bytes, from, out, from + length, bytes.length - from);
                return out;
            }
            default -> {
                int at = random.nextInt(bytes.length);
                Arrays.fill(bytes, at, Math.min(bytes.length, at + 1 + random.nextInt(16)),
                        (byte) (random.nextBoolean() ? 0 : 0xFF));
                return bytes;
            }
        }
    }

    private static String site(Throwable thrown, String prefix) {
        for (StackTraceElement frame : thrown.getStackTrace()) {
            if (frame.getClassName().startsWith(prefix) && !frame.getClassName().contains("Fuzz")) {
                String simple = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1);
                return simple + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        StackTraceElement[] frames = thrown.getStackTrace();
        return frames.length == 0 ? "no frame" : frames[0].toString();
    }

    private static String sample(byte[] data, Throwable thrown) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 96); i++) {
            hex.append(String.format("%02x", data[i]));
        }
        return thrown.getMessage() + " <- " + data.length + " bytes: " + hex + (data.length > 96 ? "…" : "");
    }
}
