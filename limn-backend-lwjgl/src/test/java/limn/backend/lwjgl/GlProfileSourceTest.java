package limn.backend.lwjgl;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the backend's sources to the two rules that keep the OpenGL ES path honest (ADR 003): every
 * GL call goes through {@link Gl}, and every GL constant means the same in both profiles. Neither
 * needs a context, so both hold on every machine that runs {@code check}, long before a change
 * reaches the Windows job that runs the ES path for real.
 */
class GlProfileSourceTest {

    /**
     * The constants that are not core OpenGL ES 3.0, each with where the ES path gets the same
     * value or why it never needs it. A new desktop-only constant fails {@link
     * #everyConstantHasTheSameValueInEs} until it is answered here.
     */
    private static final Map<String, String> DESKTOP_ONLY = Map.of(
            "GL_BACK_LEFT", "the default framebuffer's colour buffer, GL_BACK on ES (GlApi#defaultColorBuffer)",
            "GL_CONTEXT_PROFILE_MASK", "read by GraphicsProbe on the desktop profile only",
            "GL_CONTEXT_FLAGS", "read by GraphicsProbe on the desktop profile only",
            "GL_TIME_ELAPSED", "org.lwjgl.opengles.EXTDisjointTimerQuery.GL_TIME_ELAPSED_EXT",
            "GL_R16", "org.lwjgl.opengles.EXTTextureNorm16.GL_R16_EXT",
            "GL_RG16", "org.lwjgl.opengles.EXTTextureNorm16.GL_RG16_EXT");

    private static final Pattern CONSTANT = Pattern.compile("\\bGL33C\\.(GL_[A-Z0-9_]+)\\b"
            + "|import static org\\.lwjgl\\.opengl\\.GL33C\\.(GL_[A-Z0-9_]+);");

    private static final Pattern DIRECT_CALL = Pattern.compile(
            "\\bGL\\d+C?\\.n?gl[A-Z]\\w*\\s*\\(|import static org\\.lwjgl\\.opengl\\.GL\\d+C?\\.n?gl[A-Z]"
                    + "|\\borg\\.lwjgl\\.opengl\\.GL\\.|^import org\\.lwjgl\\.opengl\\.GL;"
                    + "|\\bGLES\\d*\\.n?gl[A-Z]\\w*\\s*\\(|\\bGLES\\.(create|set|get)",
            Pattern.MULTILINE);

    /** The two classes that ARE the profiles, and nothing else, call LWJGL's GL classes. */
    private static final List<String> PROFILES = List.of("DesktopGl.java", "GlesGl.java");

    @Test
    void everyGlCallGoesThroughTheFacade() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources()) {
            if (PROFILES.contains(source.getFileName().toString())) {
                continue;
            }
            String text = Files.readString(source);
            Matcher matcher = DIRECT_CALL.matcher(text);
            while (matcher.find()) {
                int line = (int) text.substring(0, matcher.start()).lines().count();
                violations.add(source.getFileName() + ":" + line + " " + matcher.group().strip());
            }
        }
        assertEquals(List.of(), violations,
                "call GL through Gl (or GlApi) so that the OpenGL ES profile receives it too");
    }

    @Test
    void everyConstantHasTheSameValueInEs() throws IOException, ReflectiveOperationException {
        TreeSet<String> names = new TreeSet<>();
        for (Path source : sources()) {
            Matcher matcher = CONSTANT.matcher(Files.readString(source));
            while (matcher.find()) {
                names.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
        }
        assertTrue(names.size() > 50, "the pattern found too few constants to be reading the sources: " + names);
        // Reading a field initialises its class, and LWJGL's ES classes load libGLESv2 when they are
        // initialised unless told not to; the values are what is wanted here, not a library.
        org.lwjgl.system.Configuration.OPENGLES_EXPLICIT_INIT.set(true);
        List<String> problems = new ArrayList<>();
        for (String name : names) {
            int desktop = org.lwjgl.opengl.GL33C.class.getField(name).getInt(null);
            String answer = DESKTOP_ONLY.get(name);
            if (answer != null) {
                if (answer.startsWith("org.lwjgl.")) {
                    int dot = answer.lastIndexOf('.');
                    Field field = Class.forName(answer.substring(0, dot)).getField(answer.substring(dot + 1));
                    if (field.getInt(null) != desktop) {
                        problems.add(name + ": " + answer + " has another value");
                    }
                }
                continue;
            }
            try {
                int es = org.lwjgl.opengles.GLES30.class.getField(name).getInt(null);
                if (es != desktop) {
                    problems.add(name + ": 0x" + Integer.toHexString(desktop) + " on the desktop, 0x"
                            + Integer.toHexString(es) + " on ES");
                }
            } catch (NoSuchFieldException absent) {
                problems.add(name + ": not in OpenGL ES 3.0; answer it in DESKTOP_ONLY");
            }
        }
        assertEquals(List.of(), problems);
    }

    private static List<Path> sources() throws IOException {
        Path root = RepositoryRoot.find().resolve("limn-backend-lwjgl/src/main/java/limn/backend/lwjgl");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}
