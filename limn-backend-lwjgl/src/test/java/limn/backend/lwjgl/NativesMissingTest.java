package limn.backend.lwjgl;

import limn.backend.Platform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error for LWJGL's natives not loading. On the class path, where these tests run, the backend
 * is not a named module and the module-path advice would be wrong, so it is not given; the named
 * case was proved by running a consumer module with every platform's natives jars on the module
 * path, which is what the backend's default dependencies bring, and then with only that machine's
 * and the --add-modules line the advice names. Its two answers are tested here as text.
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

    /** EGL ships as classes alone; advising an org.lwjgl.egl.natives module would be a second error. */
    @Test
    void theModulePathAdviceNamesNoNativesForAModuleThatHasNone() {
        String line = LwjglBackend.nativesModules(java.util.List.of("limn.toolkit", "org.lwjgl.glfw",
                "org.lwjgl.egl", "org.lwjgl"));
        assertTrue(line.equals("org.lwjgl.natives,org.lwjgl.glfw.natives"), line);
    }

    /**
     * Unresolved natives: --add-modules alone resolves whichever platform's jar comes first, so the
     * advice has to say to keep only this machine's, and which classifier that is.
     */
    @Test
    void unresolvedNativesAreToldToKeepOnlyThisMachinesJarsAndWhichTheyAre() {
        String advice = LwjglBackend.modulePathAdvice("Failed to locate library: liblwjgl.dylib",
                "natives-macos-arm64", null, "org.lwjgl.natives,org.lwjgl.glfw.natives");
        assertTrue(advice.contains("Keep only the natives-macos-arm64 jars"), advice);
        assertTrue(advice.contains("--add-modules org.lwjgl.natives,org.lwjgl.glfw.natives"), advice);
        assertTrue(advice.contains("class path"), advice);
        assertTrue(advice.contains("/docs/packaging/"), advice);
    }

    /**
     * Resolved natives that still did not load are another platform's jar that won the module name:
     * the advice names the jar it came from and the one this machine needs, and repeats no
     * --add-modules, which is evidently already there.
     */
    @Test
    void anotherPlatformsJarThatWonTheModuleNameIsNamed() {
        String advice = LwjglBackend.modulePathAdvice("Failed to locate library: liblwjgl.dylib",
                "natives-macos-arm64", "lwjgl-3.4.3-natives-macos.jar", "org.lwjgl.natives");
        assertTrue(advice.contains("org.lwjgl.natives came from lwjgl-3.4.3-natives-macos.jar"), advice);
        assertTrue(advice.contains("Keep only the natives-macos-arm64 jars"), advice);
        assertFalse(advice.contains("--add-modules"), advice);
    }

    /** LWJGL's spelling: Intel has no architecture, ARM is arm64, and anything else has no answer. */
    @Test
    void theClassifierIsLwjglsSpellingOfThePlatform() {
        assertEquals("natives-macos-arm64", LwjglBackend.nativesClassifier(Platform.of("Mac OS X", "aarch64")));
        assertEquals("natives-macos", LwjglBackend.nativesClassifier(Platform.of("Mac OS X", "x86_64")));
        assertEquals("natives-linux", LwjglBackend.nativesClassifier(Platform.of("Linux", "amd64")));
        assertEquals("natives-linux-arm64", LwjglBackend.nativesClassifier(Platform.of("Linux", "aarch64")));
        assertEquals("natives-windows", LwjglBackend.nativesClassifier(Platform.of("Windows 11", "amd64")));
        assertEquals("natives-windows-arm64", LwjglBackend.nativesClassifier(Platform.of("Windows 11", "aarch64")));
        assertNull(LwjglBackend.nativesClassifier(Platform.of("FreeBSD", "amd64")));
        assertNull(LwjglBackend.nativesClassifier(Platform.of("Linux", "riscv64")));
    }

    /**
     * The name of the jar the natives module came from is read from the address itself: on
     * Windows a file address's path is {@code /C:/...}, which a Windows path refuses, and an
     * opaque address has no path at all; either way the advice must still be given.
     */
    @Test
    void theJarIsNamedFromAnyAddressAModuleCanHave() {
        assertEquals("lwjgl-3.4.3-natives-macos.jar", LwjglBackend.jarName(
                java.net.URI.create("file:///C:/Users/me/app/lib/lwjgl-3.4.3-natives-macos.jar")));
        assertEquals("lwjgl-3.4.3-natives-linux.jar", LwjglBackend.jarName(
                java.net.URI.create("file:/home/me/app/lib/lwjgl-3.4.3-natives-linux.jar")));
        assertEquals("app.jar", LwjglBackend.jarName(
                java.net.URI.create("jar:file:/home/me/app.jar!/")));
        assertEquals("my natives+1.jar", LwjglBackend.jarName(
                java.net.URI.create("file:/home/me/my%20natives+1.jar")));
        assertNull(LwjglBackend.jarName(java.net.URI.create("jrt:/")));
    }
}
