package limn.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one reading of {@code os.name} and {@code os.arch}, as a pure function, so that every
 * platform branch in the toolkit can be tested from any machine.
 */
class PlatformTest {

    @Test
    void theFamilyIsReadFromTheNamesTheJvmsActuallyReport() {
        assertEquals(Platform.Os.MACOS, Platform.of("Mac OS X", "aarch64").os());
        assertEquals(Platform.Os.MACOS, Platform.of("Darwin", "x86_64").os());
        assertEquals(Platform.Os.WINDOWS, Platform.of("Windows 11", "amd64").os());
        assertEquals(Platform.Os.WINDOWS, Platform.of("Windows Server 2022", "amd64").os());
        assertEquals(Platform.Os.LINUX, Platform.of("Linux", "amd64").os());
        assertEquals(Platform.Os.OTHER, Platform.of("FreeBSD", "amd64").os());
        assertEquals(Platform.Os.OTHER, Platform.of("", "").os());
    }

    @Test
    void darwinIsMacOsAndNotWindows() {
        // "Darwin" contains "win". The macOS test has to come first, and this is the one place it
        // has to: it used to be right in each of the seven copies only because each happened to
        // spell the two tests on one line in that order.
        Platform darwin = Platform.of("Darwin", "arm64");
        assertTrue(darwin.isMacOs());
        assertFalse(darwin.isWindows());
    }

    @Test
    void theArchitectureFoldsEachPairOfSpellingsIntoOne() {
        assertEquals("x86_64", Platform.of("Linux", "amd64").arch());
        assertEquals("x86_64", Platform.of("Linux", "x86_64").arch());
        assertEquals("x86_64", Platform.of("Windows 11", "x64").arch());
        assertEquals("aarch64", Platform.of("Linux", "arm64").arch());
        assertEquals("aarch64", Platform.of("Mac OS X", "aarch64").arch());
        assertEquals("aarch64", Platform.of("Mac OS X", "AArch64").arch());
    }

    @Test
    void anArchitectureItDoesNotKnowPassesThroughRatherThanBeingGuessedAt() {
        assertEquals("riscv64", Platform.of("Linux", "riscv64").arch());
        assertEquals("ppc64le", Platform.of("Linux", "PPC64LE").arch());
    }

    @Test
    void theRosettaCaseFollowsTheJvmAndNotTheMachine() {
        // An x86_64 JDK under Rosetta on an Apple Silicon Mac: `uname -m` says arm64, os.arch says
        // x86_64, and the library that can be loaded is the one os.arch names.
        assertEquals("x86_64", Platform.of("Mac OS X", "x86_64").arch());
    }

    @Test
    void theCurrentPlatformIsReadOnceAndIsOneOfTheFamilies() {
        Platform current = Platform.current();
        assertNotNull(current.os());
        assertNotNull(current.arch());
        assertSame(current, Platform.current(), "read once, not on every ask");
        assertEquals(Platform.of(System.getProperty("os.name", ""),
                System.getProperty("os.arch", "")), current);
    }

    @Test
    void exactlyOneOfTheThreeConveniencesHoldsForAKnownFamily() {
        for (Platform platform : new Platform[] {
                Platform.of("Mac OS X", "aarch64"),
                Platform.of("Windows 11", "amd64"),
                Platform.of("Linux", "amd64")}) {
            int holding = (platform.isMacOs() ? 1 : 0) + (platform.isWindows() ? 1 : 0)
                    + (platform.isLinux() ? 1 : 0);
            assertEquals(1, holding, platform.toString());
        }
        Platform other = Platform.of("FreeBSD", "amd64");
        assertFalse(other.isMacOs() || other.isWindows() || other.isLinux());
    }
}
