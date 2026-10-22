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

    /**
     * The two properties ADR 003 §1.3 says every shader must keep for the ES header swap to be all
     * the port needs: the desktop version line first, which {@link ShaderProgram#inProfile} replaces,
     * and nothing ES 3.00 lacks. {@code video_convert_rect.frag} is the one exception, and it is
     * compiled only where an IOSurface is bound to a desktop context, on macOS.
     */
    @Test
    void everyShaderIsWrittenInTheSharedSubset() throws IOException {
        Path shaders = RepositoryRoot.find().resolve("limn-backend-lwjgl/src/main/resources/limn/backend/lwjgl/shaders");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(shaders)) {
            for (Path shader : files.sorted().toList()) {
                String name = shader.getFileName().toString();
                String text = Files.readString(shader);
                if (!text.startsWith(limn.render3d.shader.TargetProfile.GLSL_330.header)) {
                    problems.add(name + ": does not start with #version 330 core");
                }
                if (text.matches("(?s).*layout\\s*\\(\\s*binding.*")) {
                    problems.add(name + ": layout(binding = …), which GLSL ES 3.00 lacks");
                }
                if (text.contains("sampler2DRect") && !name.equals("video_convert_rect.frag")) {
                    problems.add(name + ": sampler2DRect, which GLSL ES 3.00 lacks");
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void theEsHeaderReplacesTheDesktopLineAndNothingElse() {
        String body = "in vec2 uv;\nout vec4 color;\nvoid main() { color = vec4(uv, 0.0, 1.0); }\n";
        String desktop = "#version 330 core\n" + body;
        assertEquals(desktop, ShaderProgram.inProfile(desktop, limn.render3d.shader.TargetProfile.GLSL_330));
        assertEquals(limn.render3d.shader.TargetProfile.GLSL_ES_300.header + body,
                ShaderProgram.inProfile(desktop, limn.render3d.shader.TargetProfile.GLSL_ES_300));
        // A text block's leading newline and indentation are not part of the version line.
        assertEquals(limn.render3d.shader.TargetProfile.GLSL_ES_300.header + body,
                ShaderProgram.inProfile("\n" + desktop, limn.render3d.shader.TargetProfile.GLSL_ES_300));
        String alreadyEs = "#version 300 es\nprecision highp float;\n" + body;
        assertEquals(alreadyEs, ShaderProgram.inProfile(alreadyEs, limn.render3d.shader.TargetProfile.GLSL_ES_300));
    }

    private static List<Path> sources() throws IOException {
        Path root = RepositoryRoot.find().resolve("limn-backend-lwjgl/src/main/java/limn/backend/lwjgl");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}
