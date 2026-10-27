package limn.demo.site;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The program every README shows under its "window on screen" heading is {@link HelloWindow}'s
 * region, imports and all, less its comments. GitHub renders a README raw, with no build between
 * the file and the reader, so the site's snippet machinery cannot reach it, and the eleven copies
 * once showed {@code main} alone, which compiles nowhere without the imports a reader then had to
 * guess.
 */
class HelloWindowReadmeTest {

    private static final Pattern JAVA_BLOCK = Pattern.compile("```java\n(.*?)\n```", Pattern.DOTALL);

    @Test
    void everyReadmeShowsTheCompiledProgramWithItsImports() throws IOException {
        Path root = RepositoryRoot.find();
        String program = program(Files.readString(
                root.resolve("limn-demo/src/main/java/limn/demo/site/HelloWindow.java")));
        assertTrue(program.startsWith("import "), "the region does not start at the imports");

        List<Path> readmes;
        try (Stream<Path> files = Files.list(root)) {
            readmes = files.filter(f -> f.getFileName().toString().matches("README(\\.[\\w-]+)?\\.md"))
                    .sorted().toList();
        }
        assertEquals(11, readmes.size(), "README.md and its ten translations: " + readmes);
        List<String> wrong = new ArrayList<>();
        for (Path readme : readmes) {
            Matcher block = JAVA_BLOCK.matcher(Files.readString(readme));
            boolean found = false;
            while (block.find()) {
                if (block.group(1).contains("new LwjglBackend()")) {
                    found = true;
                    if (!block.group(1).equals(program)) {
                        wrong.add(readme.getFileName().toString());
                    }
                }
            }
            if (!found) {
                wrong.add(readme.getFileName() + " (no program)");
            }
        }
        assertEquals(List.of(), wrong, "READMEs whose program is not HelloWindow's region less its comments");
    }

    /** The region's text, as the site de-indents it, with the comment lines left out. */
    private static String program(String source) {
        String region = source.substring(source.indexOf("// #region hello-window\n") + "// #region hello-window\n".length(),
                source.indexOf("// #endregion"));
        return Arrays.stream(region.strip().split("\n"))
                .filter(line -> !line.strip().startsWith("//") && !line.strip().startsWith("/**"))
                .collect(Collectors.joining("\n"));
    }
}
