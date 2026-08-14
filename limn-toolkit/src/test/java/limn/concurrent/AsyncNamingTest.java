package limn.concurrent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The naming rule from {@code docs/design/background-work.md}, asserted over the whole toolkit:
 * a member whose name ends in {@code Async} returns an unstarted {@link Work}, and a member that
 * returns an already-running future is not called {@code Async}.
 *
 * <p>It exists because the rule is worth exactly as much as its exceptions are rare, and because
 * the two shapes are indistinguishable at the call site: forgetting {@code start()} on a {@code
 * Work} compiles, runs and does nothing. A facade that quietly picked the other shape would be
 * found by whoever wrote the call site, one silent bug at a time.
 *
 * <p>The class list is walked off the compiled output rather than kept by hand here: a list that
 * had to be maintained would go stale in exactly the release that adds a facade, which is the
 * release this test exists for.
 */
class AsyncNamingTest {

    @Test
    void everyMemberNamedAsyncHandsBackAnUnstartedWork() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Method method : publicToolkitMethods()) {
            if (method.getName().endsWith("Async") && method.getReturnType() != Work.class) {
                wrong.add(signature(method) + " returns " + method.getReturnType().getSimpleName());
            }
        }
        assertEquals(List.of(), wrong,
                "a name ending in Async promises an unstarted Work the caller must start()");
    }

    @Test
    void noAlreadyRunningFutureIsNamedAsync() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Method method : publicToolkitMethods()) {
            Class<?> returned = method.getReturnType();
            boolean isFuture = CompletionStage.class.isAssignableFrom(returned)
                    || CompletableFuture.class.isAssignableFrom(returned);
            if (isFuture && method.getName().endsWith("Async")) {
                wrong.add(signature(method));
            }
        }
        assertEquals(List.of(), wrong,
                "a future is running before the caller sees it, so it must not wear the suffix "
                        + "that means 'not started yet': name it Shared");
    }

    /**
     * The guard the two tests above cannot give themselves: a sweep that reached nothing passes
     * both of them, and a broken walk (a moved output directory, a classloader change) looks
     * exactly like a clean bill of health.
     */
    @Test
    void theSweepReachesBothFamilies() throws Exception {
        List<Method> methods = publicToolkitMethods();
        long unstarted = methods.stream().filter(m -> m.getReturnType() == Work.class).count();
        long futures = methods.stream()
                .filter(m -> CompletionStage.class.isAssignableFrom(m.getReturnType()))
                .count();
        assertNotEquals(0, unstarted, "the sweep found no Work-returning member at all");
        assertNotEquals(0, futures, "the sweep found no future-returning member at all");
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /** Every public method of every public class in this module, loaded but not initialized. */
    private static List<Method> publicToolkitMethods() throws Exception {
        // Anchored on a CLASS file so this finds the compiled tree rather than the resource tree,
        // which Gradle keeps in a separate output directory.
        Path anchor = Path.of(Ui.class.getResource("Ui.class").toURI());
        Path root = anchor.getParent().getParent().getParent(); // .../limn/concurrent/Ui.class
        List<Method> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = root.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                if (name.contains("$") || name.equals("module-info")) {
                    continue;
                }
                Class<?> type = Class.forName(name, false, AsyncNamingTest.class.getClassLoader());
                if (!Modifier.isPublic(type.getModifiers())) {
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                        found.add(method);
                    }
                }
            }
        }
        return found;
    }
}
