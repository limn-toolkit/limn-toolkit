package limn.backend.lwjgl.transfer;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.Callback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The backend's callbacks keep the shape LWJGL needs on every Java the toolkit runs on: each
 * signature passed to {@link Ffi#callback} or {@code Wl.on} has its interface in {@link FfiShapes},
 * every interface of a typed callback is a {@code @FunctionalInterface}, and each shape, called
 * from C, receives its arguments and answers its result. On Java 25 LWJGL refused a callback that
 * broke either of the first two, and the window could not be made on macOS.
 */
class FfiShapesTest {

    private static final Pattern CALLBACK = Pattern.compile("Ffi\\.callback\\(\"([a-z>]+)\"");
    private static final Pattern LISTENER = Pattern.compile("wl\\.(?:on|ignore)\\(\"([a-z]+)\"");
    private static final Pattern TYPED = Pattern.compile("interface (\\w+) extends CallbackI \\{");

    @Test
    void everySignatureTheSourcesPassHasAShape() throws IOException {
        TreeSet<String> missing = new TreeSet<>();
        for (Path file : sources()) {
            String text = Files.readString(file);
            Matcher callback = CALLBACK.matcher(text);
            while (callback.find()) {
                if (!FfiShapes.SHAPES.containsKey(callback.group(1))) {
                    missing.add(callback.group(1) + " in " + file.getFileName());
                }
            }
            Matcher listener = LISTENER.matcher(text);
            while (listener.find()) {
                if (!FfiShapes.SHAPES.containsKey(listener.group(1) + ">v")) {
                    missing.add(listener.group(1) + ">v in " + file.getFileName());
                }
            }
        }
        assertEquals(new TreeSet<>(), missing, "signatures with no shape in FfiShapes: run scripts/generate-ffi-shapes.py");
    }

    @Test
    void everyTypedCallbackInterfaceIsFunctional() throws IOException {
        List<String> bare = new ArrayList<>();
        for (Path file : sources()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher typed = TYPED.matcher(lines.get(i));
                if (typed.find() && !(file.getFileName().toString().equals("FfiShapes.java")
                        && typed.group(1).equals("Shape"))
                        && (i == 0 || !lines.get(i - 1).strip().equals("@FunctionalInterface"))) {
                    bare.add(file.getFileName() + ":" + (i + 1) + " " + typed.group(1));
                }
            }
        }
        assertEquals(List.of(), bare, "callback interfaces LWJGL refuses on Java 25");
    }

    @Test
    void everyShapeCalledFromCReceivesItsArgumentsAndAnswers() {
        assertEquals(List.of(), roundTrips());
    }

    /**
     * Calls each shape's closure through libffi with distinct arguments and checks what the
     * handler received and what the caller got back. Also run by hand under Java 25.
     *
     * @return one line for each shape that went wrong
     */
    static List<String> roundTrips() {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Class<? extends FfiShapes.Shape>> shape : FfiShapes.SHAPES.entrySet()) {
            String signature = shape.getKey();
            int arrow = signature.indexOf('>');
            long[] sent = new long[arrow];
            for (int i = 0; i < arrow; i++) {
                sent[i] = signature.charAt(i) == 'p' ? 0x1122_3344_5566_7700L + i : -1000L * (i + 1);
            }
            char result = signature.charAt(arrow + 1);
            long answer = result == 'p' ? 0x0123_4567_89AB_CDEFL : result == 'i' ? -12_345 : 0;
            long[][] received = new long[1][];
            Callback closure = Ffi.callback(signature, args -> {
                received[0] = args.clone();
                return answer;
            });
            try {
                long got = Ffi.call(closure.address(), signature, sent);
                if (received[0] == null || !java.util.Arrays.equals(sent, received[0])) {
                    wrong.add(signature + " received " + java.util.Arrays.toString(received[0]));
                }
                if (got != answer) {
                    wrong.add(signature + " answered " + got + " instead of " + answer);
                }
            } finally {
                closure.free();
            }
        }
        return wrong;
    }

    private static List<Path> sources() throws IOException {
        Path root = RepositoryRoot.find().resolve("limn-backend-lwjgl/src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
