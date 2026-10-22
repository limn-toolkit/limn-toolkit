package limn.backend.lwjgl;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaging guide's recipes for one platform's natives still name what this module requires.
 *
 * <p>The Gradle recipe works by classifier and needs no list, but two others are lists a reader
 * pastes: the {@code --add-modules} line for the module path, and the Maven recipe, which has to
 * exclude the backend's LWJGL and declare every module of it again because Maven cannot drop one
 * classifier of a dependency. Both were checked by resolving and running a consumer of the 1.0
 * rehearsal; what keeps them true is that a module added to this module's requirements, a ninth
 * LWJGL binding or a second native payload, fails here until the guide names it too.
 */
class PackagingRecipeTest {

    private static final Pattern REQUIRES = Pattern.compile("^\\s*requires\\s+(?:transitive\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    @Test
    void theModulePathLineIsTheOneTheBackendPrints() throws IOException {
        String guide = guide();
        Matcher line = Pattern.compile("listOf\\(\"--add-modules\", ((?:\"[^\"]*\"\\s*\\+?\\s*)+)\\)").matcher(guide);
        assertTrue(line.find(), "the packaging guide has no --add-modules line");
        String modules = line.group(1).replaceAll("[\"+\\s]", "");
        assertEquals(LwjglBackend.nativesModules(requires()), modules);
    }

    @Test
    void theMavenRecipeDeclaresEveryLwjglModuleAgainWithItsNatives() throws IOException {
        String maven = mavenRecipe();
        for (String module : requires()) {
            if (!module.startsWith("org.lwjgl")) {
                continue;
            }
            String artifact = module.replace("org.lwjgl", "lwjgl").replace('.', '-');
            String plain = "<artifactId>" + artifact + "</artifactId><version>${lwjgl.version}</version></dependency>";
            assertTrue(maven.contains(plain), "the Maven recipe does not declare " + artifact + " again");
            String natives = "<artifactId>" + artifact + "</artifactId><version>${lwjgl.version}</version>"
                    + "<classifier>${lwjgl.natives}</classifier>";
            boolean hasNatives = LwjglBackend.nativesModules(List.of(module)).length() > 0;
            assertEquals(hasNatives, maven.contains(natives),
                    "the Maven recipe " + (hasNatives ? "omits" : "names") + " natives of " + artifact);
        }
    }

    @Test
    void theMavenRecipeExcludesAndDeclaresTheSvgRendererAgain() throws IOException {
        assertTrue(requires().contains("limn.lunasvg.natives"));
        String maven = mavenRecipe();
        assertTrue(maven.contains("<exclusion><groupId>org.lwjgl</groupId><artifactId>*</artifactId></exclusion>"));
        assertTrue(maven.contains("<exclusion><groupId>io.github.limn-toolkit</groupId>"
                + "<artifactId>limn-lunasvg-natives</artifactId></exclusion>"));
        assertTrue(maven.matches("(?s).*<artifactId>limn-lunasvg-natives</artifactId><version>[^<]+</version></dependency>.*"));
        assertTrue(maven.matches("(?s).*<artifactId>limn-lunasvg-natives</artifactId><version>[^<]+</version>"
                + "<classifier>\\$\\{limn.natives}</classifier>.*"));
    }

    /**
     * ANGLE's payload has classifiers for Windows and macOS only (ADR 078), so the recipe cannot
     * name it with the {@code ${limn.natives}} every profile sets: a Linux build would ask for a jar
     * that does not exist. It is excluded, its main jar declared again, and each classifier named in
     * the profile of its own platform and in no other.
     */
    @Test
    void theMavenRecipeNamesAnglesClassifiersInTheirOwnProfilesOnly() throws IOException {
        assertTrue(requires().contains("limn.angle.natives"));
        String maven = mavenRecipe();
        assertTrue(maven.contains("<exclusion><groupId>io.github.limn-toolkit</groupId>"
                + "<artifactId>limn-angle-natives</artifactId></exclusion>"));
        assertTrue(maven.matches("(?s).*<artifactId>limn-angle-natives</artifactId><version>[^<]+</version></dependency>.*"));
        for (String platform : List.of("macos-aarch64", "macos-x86_64", "windows-aarch64", "windows-x86_64",
                "linux-aarch64", "linux-x86_64")) {
            int start = maven.indexOf("<profile><id>" + platform + "</id>");
            assertTrue(start >= 0, "no profile " + platform);
            String profile = maven.substring(start, maven.indexOf("</profile>", start));
            boolean named = profile.contains("<artifactId>limn-angle-natives</artifactId>")
                    && profile.contains("<classifier>natives-" + platform + "</classifier>");
            assertEquals(!platform.startsWith("linux"), named,
                    "the " + platform + " profile " + (named ? "names" : "does not name") + " ANGLE's classifier");
        }
        assertFalse(maven.matches("(?s).*limn-angle-natives</artifactId><version>[^<]+</version>"
                + "<classifier>\\$\\{limn.natives}.*"), "ANGLE's classifier through ${limn.natives}");
    }

    private static List<String> requires() throws IOException {
        String source = Files.readString(RepositoryRoot.find()
                .resolve("limn-backend-lwjgl/src/main/java/module-info.java"));
        List<String> names = new ArrayList<>();
        Matcher matcher = REQUIRES.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String guide() throws IOException {
        Path guide = RepositoryRoot.find().resolve("site/src/guides/packaging.md");
        return Files.readString(guide);
    }

    private static String mavenRecipe() throws IOException {
        String guide = guide();
        int section = guide.indexOf("## Natives for one platform");
        assertTrue(section >= 0, "the packaging guide has no section for one platform's natives");
        int start = guide.indexOf("```xml title=\"pom.xml\"", section);
        int end = guide.indexOf("```", start + 3);
        return guide.substring(start, end);
    }
}
