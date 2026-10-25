package limn.demo;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every native library the runnable demo jar carries travels with its licence text.
 *
 * <p>The fat jar is this module's runtime classpath unpacked into one file, so what is on this
 * suite's classpath is what the jar holds. LWJGL's natives jars put GLFW, HarfBuzz, OpenAL Soft,
 * stb, tinyfiledialogs and libffi into it with no licence text at all, and until 2026-10-25 the jar
 * carried LWJGL's own licence and nothing for any of them: not the copyright notices their
 * licences ask a binary distribution to reproduce, nor the LGPL that OpenAL Soft is under.
 * {@code limn-demo/licenses/} holds the texts and the fat jar copies it to
 * {@code META-INF/licenses/}; its README says where each text was taken from, and these cases hold
 * that note to the files, to the LWJGL that ships and to every native on the classpath.
 */
class NativeLicencesTest {

    /** A row of the README's table: the file, the URL it was taken from, its digest. */
    private static final Pattern ROW = Pattern.compile(
            "^\\| `([^`]+)` \\|.*\\((https://[^)]+)\\).*\\| `([0-9a-f]{64})` \\|$");

    /** A native library, by the suffixes the three systems give one, versioned or not. */
    private static final Pattern NATIVE = Pattern.compile(".+\\.(?:so(?:\\.\\d+)*|dylib|dll)$");

    /** Where LWJGL's natives jars put a library: {@code <os>/<arch>/org/lwjgl/[<module>/]}. */
    private static final String LWJGL = "(?:linux|macos|windows)/(?:x64|arm64)/org/lwjgl/";

    /**
     * The texts each native library needs. A bare name is a file of {@code limn-demo/licenses/};
     * a name starting with {@code /} is a resource the library's own jar carries beside it, and
     * {@code $1} in it is the rule's first group.
     */
    private static final Map<Pattern, List<String>> NEEDS = needs();

    private static Map<Pattern, List<String>> needs() {
        Map<Pattern, List<String>> needs = new LinkedHashMap<>();
        String library = "\\.(?:so|dylib|dll)";
        needs.put(Pattern.compile("(?:macos|windows)/(?:x64|arm64)/org/lwjgl/(?:lib)?lwjgl" + library),
                List.of("LWJGL-LICENSE.txt", "libffi-LICENSE.txt"));
        needs.put(Pattern.compile("linux/(?:x64|arm64)/org/lwjgl/liblwjgl\\.so"),
                List.of("LWJGL-LICENSE.txt", "libffi-LICENSE.txt", "liburing-LICENSE.txt"));
        needs.put(Pattern.compile(LWJGL + "glfw/(?:lib)?glfw(?:_async)?" + library),
                List.of("GLFW-LICENSE.txt"));
        needs.put(Pattern.compile(LWJGL + "harfbuzz/(?:lib)?harfbuzz(?:-gpu|-raster|-vector)?" + library),
                List.of("HarfBuzz-LICENSE.txt", "HarfBuzz-USE-LICENSE.txt"));
        needs.put(Pattern.compile("(?:macos|windows)/(?:x64|arm64)/org/lwjgl/openal/(?:libopenal|OpenAL)" + library),
                List.of("OpenAL-Soft-LICENSE.txt", "OpenAL-Soft-PFFFT-LICENSE.txt",
                        "OpenAL-Soft-fmt-LICENSE.txt", "OpenAL-Soft-GSL-LICENSE.txt"));
        // On Linux OpenAL Soft also compiles in its RealtimeKit client, core/rtkit.cpp, whose MIT
        // notice is its own: the library holds RealtimeKit's D-Bus names, so the code is there.
        needs.put(Pattern.compile("linux/(?:x64|arm64)/org/lwjgl/openal/libopenal\\.so"),
                List.of("OpenAL-Soft-LICENSE.txt", "OpenAL-Soft-PFFFT-LICENSE.txt",
                        "OpenAL-Soft-fmt-LICENSE.txt", "OpenAL-Soft-GSL-LICENSE.txt",
                        "OpenAL-Soft-RTKit-LICENSE.txt"));
        needs.put(Pattern.compile(LWJGL + "(?:opengl|opengles)/(?:lib)?lwjgl_(?:opengl|opengles)" + library),
                List.of("LWJGL-LICENSE.txt", "Khronos-LICENSE.txt"));
        needs.put(Pattern.compile(LWJGL + "stb/(?:lib)?lwjgl_stb" + library),
                List.of("LWJGL-LICENSE.txt", "stb-LICENSE.txt"));
        needs.put(Pattern.compile(LWJGL + "tinyfd/(?:lib)?lwjgl_tinyfd" + library),
                List.of("LWJGL-LICENSE.txt", "tinyfiledialogs-LICENSE.txt"));
        // The three payloads this project builds carry their licences in their own jars.
        needs.put(Pattern.compile("limn/svg/native/[^/]+/liblimnsvg" + library),
                List.of("/limn/svg/native/LICENSE-lunasvg.txt", "/limn/svg/native/LICENSE-plutovg.txt"));
        needs.put(Pattern.compile("limn/angle/native/([^/]+)/lib(?:EGL|GLESv2)" + library),
                List.of("/limn/angle/native/LICENSE-angle.txt", "/limn/angle/native/$1/THIRD_PARTY.txt"));
        needs.put(Pattern.compile("limn/video/ffmpeg/native/[^/]+/[^/]+"),
                List.of("/limn/video/ffmpeg/native/LICENSE-ffmpeg.txt"));
        return needs;
    }

    /**
     * The upstream repositories LWJGL builds three of its libraries from, by the module whose
     * natives jar records the commit.
     */
    private static final Map<String, String> UPSTREAM = Map.of(
            "glfw/glfw", "glfw", "harfbuzz/harfbuzz", "harfbuzz", "kcat/openal-soft", "openal");

    @Test
    void everyTextIsTheOneItsRowNamesAndEveryTextHasARow() throws Exception {
        Map<String, String[]> rows = rows();
        Set<String> files = new TreeSet<>();
        try (Stream<Path> listed = Files.list(licences())) {
            listed.map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("README.md"))
                    .forEach(files::add);
        }
        assertEquals(files, rows.keySet(), "every text has one row in the README, and every row a text");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String[]> row : rows.entrySet()) {
            String digest = sha256(Files.readString(licences().resolve(row.getKey()), StandardCharsets.UTF_8));
            if (!digest.equals(row.getValue()[1])) {
                wrong.add(row.getKey() + " is " + digest + ", the README says " + row.getValue()[1]);
            }
        }
        assertEquals(List.of(), wrong, "a text is not what its row says it is");
    }

    @Test
    void theTextsAreTakenAtTheLwjglThatShips() throws Exception {
        String catalog = Files.readString(RepositoryRoot.find().resolve("gradle/libs.versions.toml"));
        Matcher pinned = Pattern.compile("(?m)^lwjgl = \"([^\"]+)\"").matcher(catalog);
        assertTrue(pinned.find(), "the catalog pins no LWJGL");
        String version = pinned.group(1);

        // LWJGL's core library records LWJGL's own commit, which is the one its tag points at.
        Map<String, String> recorded = recordedCommits();
        String readme = Files.readString(licences().resolve("README.md"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        assertNotNull(recorded.get("core"), "LWJGL's core natives record no commit: " + recorded);
        assertTrue(readme.contains("tag `" + version + "` (commit " + recorded.get("core") + ")"),
                "the README does not name LWJGL " + version + " at " + recorded.get("core"));

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String[]> row : rows().entrySet()) {
            String url = row.getValue()[0];
            Matcher github = Pattern.compile("https://raw\\.githubusercontent\\.com/([^/]+/[^/]+)/([^/]+)/").matcher(url);
            if (!github.lookingAt()) {
                continue;
            }
            String repository = github.group(1);
            String ref = github.group(2);
            String expected = repository.equals("LWJGL/lwjgl3") ? version : recorded.get(UPSTREAM.get(repository));
            if (expected == null) {
                wrong.add(row.getKey() + " comes from " + repository + ", which no natives jar records");
            } else if (!expected.equals(ref)) {
                wrong.add(row.getKey() + " was taken at " + ref + ", and what ships was built at " + expected);
            }
        }
        for (Map.Entry<String, String> upstream : UPSTREAM.entrySet()) {
            if (!readme.contains(upstream.getKey() + "/" + recorded.get(upstream.getValue()) + "/")) {
                wrong.add("no text is taken from " + upstream.getKey() + " at " + recorded.get(upstream.getValue()));
            }
        }
        assertEquals(List.of(), wrong, "take the texts again at the LWJGL that ships");
    }

    @Test
    void everyNativeLibraryOnTheClasspathHasItsLicence() throws IOException {
        Set<String> natives = new TreeSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            natives.addAll(nativesIn(Path.of(entry)));
        }
        // The classpath is the jar's: an empty list would be a test that checks nothing.
        assertTrue(natives.stream().anyMatch(name -> name.contains("/org/lwjgl/openal/")),
                "no LWJGL natives on the classpath: " + natives.size());

        Map<String, List<String>> missing = new TreeMap<>();
        for (String library : natives) {
            List<String> texts = null;
            Matcher matched = null;
            for (Map.Entry<Pattern, List<String>> need : NEEDS.entrySet()) {
                Matcher matcher = need.getKey().matcher(library);
                if (matcher.matches()) {
                    texts = need.getValue();
                    matched = matcher;
                    break;
                }
            }
            if (texts == null) {
                missing.put(library, List.of("no rule here says which licence it is under"));
                continue;
            }
            for (String text : texts) {
                String absent = null;
                if (text.startsWith("/")) {
                    String resource = matched.groupCount() > 0 ? text.replace("$1", matched.group(1)) : text;
                    URL found = NativeLicencesTest.class.getResource(resource);
                    absent = found == null ? resource + " is not beside it" : null;
                } else if (!Files.isRegularFile(licences().resolve(text))) {
                    absent = "limn-demo/licenses/" + text + " does not exist";
                }
                if (absent != null) {
                    missing.computeIfAbsent(library, key -> new ArrayList<>()).add(absent);
                }
            }
        }
        assertEquals(Map.of(), missing, "native libraries the fat jar would carry without their licence");
    }

    /** {@code limn-demo/licenses/}, which the fat jar copies to {@code META-INF/licenses/}. */
    private static Path licences() {
        return RepositoryRoot.find().resolve("limn-demo/licenses");
    }

    /** The README's rows, by file: the URL the file was taken from and its digest. */
    private static Map<String, String[]> rows() throws IOException {
        Map<String, String[]> rows = new TreeMap<>();
        for (String line : Files.readAllLines(licences().resolve("README.md"), StandardCharsets.UTF_8)) {
            Matcher row = ROW.matcher(line);
            if (row.matches() && rows.put(row.group(1), new String[] {row.group(2), row.group(3)}) != null) {
                throw new AssertionError(row.group(1) + " has two rows");
            }
        }
        return rows;
    }

    /**
     * The commit each LWJGL natives jar records beside a library ({@code <library>.git}), by
     * module: {@code core} for LWJGL's own library, else the directory under {@code org/lwjgl/}.
     * Every platform's jar records the same one, and a disagreement fails here.
     */
    private static Map<String, String> recordedCommits() throws IOException {
        Pattern marker = Pattern.compile("META-INF/" + LWJGL + "(?:([a-z]+)/)?[^/]+\\.git");
        Map<String, String> commits = new TreeMap<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.endsWith(".jar") || !Files.isRegularFile(Path.of(entry))) {
                continue;
            }
            try (ZipFile jar = new ZipFile(entry)) {
                for (ZipEntry file : jar.stream().toList()) {
                    Matcher matcher = marker.matcher(file.getName());
                    if (!matcher.matches()) {
                        continue;
                    }
                    String module = matcher.group(1) == null ? "core" : matcher.group(1);
                    String commit;
                    try (InputStream in = jar.getInputStream(file)) {
                        commit = new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
                    }
                    String earlier = commits.putIfAbsent(module, commit);
                    assertTrue(earlier == null || earlier.equals(commit),
                            module + " records " + earlier + " and " + commit);
                }
            }
        }
        return commits;
    }

    private static List<String> nativesIn(Path entry) throws IOException {
        List<String> natives = new ArrayList<>();
        if (Files.isDirectory(entry)) {
            try (Stream<Path> files = Files.walk(entry)) {
                files.filter(Files::isRegularFile)
                        .map(file -> entry.relativize(file).toString().replace(File.separatorChar, '/'))
                        .filter(name -> NATIVE.matcher(name).matches())
                        .forEach(natives::add);
            }
        } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
            try (ZipFile jar = new ZipFile(entry.toFile())) {
                jar.stream().map(ZipEntry::getName)
                        .filter(name -> !name.endsWith("/") && NATIVE.matcher(name).matches())
                        .forEach(natives::add);
            }
        }
        return natives;
    }

    /** The digest of a text with LF line ends, as the projects publish them, whatever the checkout did. */
    private static String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
    }
}
