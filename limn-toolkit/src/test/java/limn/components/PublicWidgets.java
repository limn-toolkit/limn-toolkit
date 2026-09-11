package limn.components;

import limn.scene.Widget;
import limn.testing.RepositoryRoot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every concrete public widget the component sources declare, subpackages included: the floor a
 * contract test's table is checked against, so a widget added without a row fails the table
 * rather than being quietly absent from it.
 *
 * <p>Read from the source tree rather than from a list kept by hand, because a list kept by hand
 * is exactly the thing a new widget forgets to join. Shared by the contract tests that each hold a
 * row per widget ({@code NotificationContractTest}, {@code DamageContractTest}), so that two of
 * them cannot disagree about what "every widget" means.
 */
final class PublicWidgets {

    private PublicWidgets() {
    }

    /** @return every public, concrete, top-level {@link Widget} under {@code limn.components} */
    static List<Class<?>> concrete() {
        List<Class<?>> found = new ArrayList<>();
        for (Class<?> declared : declaredClasses()) {
            int modifiers = declared.getModifiers();
            if (Widget.class.isAssignableFrom(declared)
                    && declared != Widget.class
                    && Modifier.isPublic(modifiers)
                    && !Modifier.isAbstract(modifiers)
                    && declared.getEnclosingClass() == null) {
                found.add(declared);
            }
        }
        found.sort(Comparator.comparing(Class::getName));
        return found;
    }

    private static List<Class<?>> declaredClasses() {
        Path sources = RepositoryRoot.find().resolve("limn-toolkit/src/main/java");
        Path directory = sources.resolve("limn/components");
        assertTrue(Files.isDirectory(directory), "no such source directory: " + directory);
        List<Class<?>> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(file -> file.getFileName().toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("package-info.java"))
                    .map(file -> sources.relativize(file).toString())
                    .map(name -> name.substring(0, name.length() - ".java".length())
                            .replace('/', '.').replace('\\', '.'))
                    .sorted()
                    .forEach(name -> all.add(load(name)));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return all;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, PublicWidgets.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            throw new AssertionError("a source file declares " + name + " and no class answers to it",
                    absent);
        }
    }
}
