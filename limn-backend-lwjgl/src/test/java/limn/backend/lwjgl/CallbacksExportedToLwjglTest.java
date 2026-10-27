package limn.backend.lwjgl;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.CallbackI;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every package of this module that declares an LWJGL callback is exported to {@code org.lwjgl}.
 *
 * <p>On Java 25 LWJGL builds each callback through the foreign-function linker: it defines a hidden
 * class with the {@code Lookup} the callback's {@code Callback.Descriptor} was given, so in the
 * package that built the descriptor, which is the package of the callback's interface, and then
 * instantiates that class reflectively from its own module. On the class path every package is
 * open to everyone; on the module path the instantiation is refused with an
 * {@code IllegalAccessException} unless the package is exported to {@code org.lwjgl}, and the 1.0
 * rehearsal found the macOS clipboard text-only and its accessibility bridge off for that reason.
 * Tests here run on the class path and could not see it, so this one reads the compiled module
 * descriptor beside the classes instead.
 */
class CallbacksExportedToLwjglTest {

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);

    @Test
    void everyPackageDeclaringACallbackIsExportedToLwjgl() throws IOException, URISyntaxException {
        Set<String> declaring = new TreeSet<>(callbackInterfacePackages());
        declaring.addAll(descriptorPackages());
        assertTrue(declaring.contains("limn.backend.lwjgl.transfer"), "found no callback in transfer: " + declaring);

        ModuleDescriptor module = descriptor();
        Set<String> exportedToLwjgl = module.exports().stream()
                .filter(e -> !e.isQualified() || e.targets().contains("org.lwjgl"))
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> missing = new TreeSet<>(declaring);
        missing.removeAll(exportedToLwjgl);
        assertEquals(Set.of(), missing, "packages declaring LWJGL callbacks that module-info.java does not export "
                + "to org.lwjgl: on Java 25 on the module path their callbacks cannot be made");
    }

    @Test
    void theQualifiedExportsGoToLwjglAlone() throws IOException, URISyntaxException {
        for (ModuleDescriptor.Exports export : descriptor().exports()) {
            if (export.isQualified()) {
                assertEquals(Set.of("org.lwjgl"), export.targets(), export.source());
            } else {
                assertEquals("limn.backend.lwjgl", export.source(), "the one package applications see");
            }
        }
    }

    /** The packages of every interface this module's classes declare that is a LWJGL callback. */
    private static Set<String> callbackInterfacePackages() throws IOException, URISyntaxException {
        Path classes = classesRoot();
        Set<String> packages = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(), ".");
                name = name.substring(0, name.length() - ".class".length());
                if (name.equals("module-info") || name.endsWith("package-info")) {
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(name, false, CallbacksExportedToLwjglTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError absent) {
                    continue; // jlayer's users when the class path lacks it; they declare no callback
                }
                if (type.isInterface() && CallbackI.class.isAssignableFrom(type)) {
                    packages.add(type.getPackageName());
                }
            }
        }
        assertFalse(packages.isEmpty(), "no callback interface found under " + classes);
        return packages;
    }

    /**
     * The packages whose sources build a {@code Callback.Descriptor}: its {@code Lookup} is the
     * caller's, and the hidden class lands in the caller's package.
     */
    private static Set<String> descriptorPackages() throws IOException {
        Path root = RepositoryRoot.find().resolve("limn-backend-lwjgl/src/main/java");
        Set<String> packages = new TreeSet<>();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources = files.filter(f -> f.toString().endsWith(".java")).toList();
        }
        for (Path file : sources) {
            String text = Files.readString(file);
            if (text.contains("new Callback.Descriptor(")) {
                Matcher pkg = PACKAGE.matcher(text);
                assertTrue(pkg.find(), file + " has no package");
                packages.add(pkg.group(1));
            }
        }
        return packages;
    }

    private static ModuleDescriptor descriptor() throws IOException, URISyntaxException {
        try (InputStream in = Files.newInputStream(classesRoot().resolve("module-info.class"))) {
            return ModuleDescriptor.read(in);
        }
    }

    /** The directory the backend's classes were compiled into, where module-info.class is too. */
    private static Path classesRoot() throws URISyntaxException {
        Path root = Path.of(LwjglBackend.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(root), "the backend's classes are not a directory: " + root);
        return root;
    }
}
