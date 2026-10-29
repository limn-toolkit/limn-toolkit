package limn.backend.lwjgl.internal;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Puts the native libraries of the platform being built for into a GraalVM native image, and
 * those of no other platform. GraalVM runs it while it builds an image, because
 * {@code META-INF/native-image/io.github.limn-toolkit/limn-backend-lwjgl/native-image.properties}
 * names it; on the JVM nothing loads it.
 *
 * <p><b>It is code rather than a list because a list cannot say "this platform".</b> The jars carry
 * every desktop platform's libraries, LWJGL's, the SVG renderer's, ANGLE's and, with
 * {@code limn-video-ffmpeg}, FFmpeg's: about 130 MB, of which one machine uses under 20. The metadata
 * files beside this class are lists of resources, with no way to name the platform, so a pattern
 * there either leaves the libraries out, and the image cannot draw, or takes all six platforms'.
 * An image is built on the system and processor it runs on, so the builder's own {@code os.name}
 * and {@code os.arch} name the one slice it needs.
 *
 * <p>The resources are taken from the class path, which is how an image is built with
 * {@code -jar} or {@code -cp}. The platform-neutral resources (shaders, translations, fonts) and
 * the reflection and JNI the toolkit needs are in the modules' {@code reachability-metadata.json}
 * files, where a list is enough.
 *
 * <p>Compiled against GraalVM's SDK, which is not a dependency of this module at run time: the
 * module declares it {@code requires static}, and its POM does not name it.
 */
public final class NativeImageFeature implements Feature {

    /** Builds the feature; GraalVM calls this, by reflection, once per image. */
    public NativeImageFeature() {
    }

    @Override
    public String getDescription() {
        return "Registers the Limn toolkit's native libraries for the platform being built";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        List<String> prefixes = prefixes(System.getProperty("os.name"), System.getProperty("os.arch"));
        Module module = access.getApplicationClassLoader().getUnnamedModule();
        for (String resource : matching(access.getApplicationClassPath(), prefixes)) {
            RuntimeResourceAccess.addResource(module, resource);
        }
    }

    /**
     * The resource directories holding one platform's native libraries: the toolkit's payloads,
     * named {@code <os>-<arch>} as their loaders name them, and LWJGL's, named {@code <os>/<arch>},
     * with the checksums LWJGL keeps under {@code META-INF} to decide whether a library it extracted
     * before is still the same one.
     *
     * @param osName the {@code os.name} of the machine the image is for
     * @param osArch the {@code os.arch} of the machine the image is for
     * @return the prefixes, each ending in {@code /}
     */
    static List<String> prefixes(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os = name.startsWith("mac") ? "macos" : name.startsWith("windows") ? "windows" : "linux";
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        String limn = os + "-" + (arm ? "aarch64" : "x86_64");
        String lwjgl = os + "/" + (arm ? "arm64" : "x64");
        return List.of(
                "limn/video/ffmpeg/native/" + limn + "/",
                "limn/svg/native/" + limn + "/",
                "limn/angle/native/" + limn + "/",
                lwjgl + "/",
                "META-INF/" + lwjgl + "/");
    }

    /**
     * Every file under {@code prefixes} in the jars and directories of {@code classPath}.
     *
     * @return the resource names, sorted, so an image built twice registers them in one order
     */
    static List<String> matching(List<Path> classPath, List<String> prefixes) {
        TreeSet<String> found = new TreeSet<>();
        for (Path entry : classPath) {
            if (Files.isDirectory(entry)) {
                inDirectory(entry, prefixes, found);
            } else if (Files.isRegularFile(entry)) {
                inJar(entry, prefixes, found);
            }
        }
        return new ArrayList<>(found);
    }

    private static void inJar(Path jar, List<String> prefixes, TreeSet<String> found) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .filter(name -> startsWithAny(name, prefixes))
                    .forEach(found::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + jar + " for its native libraries", unreadable);
        }
    }

    private static void inDirectory(Path root, List<String> prefixes, TreeSet<String> found) {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(name -> startsWithAny(name, prefixes))
                    .forEach(found::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + root + " for its native libraries", unreadable);
        }
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
