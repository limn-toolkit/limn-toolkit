package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error for LWJGL's natives not loading. On the class path, where these tests run, the backend
 * is not a named module and the module-path advice would be wrong, so it is not given; the named
 * case, which names the --add-modules line, was proved by running a consumer module with the
 * natives jars on the module path and then with exactly the line it printed.
 */
class NativesMissingTest {

    @Test
    void onTheClassPathItSaysWhatFailedAndNothingAboutModules() {
        UnsatisfiedLinkError missing = new UnsatisfiedLinkError("Failed to locate library: liblwjgl.dylib");
        RuntimeException error = LwjglBackend.nativesMissing(missing);
        assertTrue(error.getMessage().contains("liblwjgl.dylib"), error.getMessage());
        assertFalse(error.getMessage().contains("--add-modules"), error.getMessage());
        assertSame(missing, error.getCause());
    }
}
