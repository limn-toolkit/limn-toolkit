package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warm-up before the system's dialogs: done once, within its bound, and at once after that. Its
 * effect, a dialog that survives an automation client reading it first, was measured on the ARM64
 * guest with the print probe ({@code -Dprobe.saveDialog=true}), since no test here has a client in
 * another process.
 */
class UiaClientWarmUpTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "os.arch", matches = "aarch64")
    void startsOnceWithinItsBoundAndThenReturnsAtOnce() {
        long first = System.nanoTime();
        UiaClientWarmUp.ensure();
        long firstMs = (System.nanoTime() - first) / 1_000_000;
        assertTrue(firstMs <= 2_500, "the first call waits at most its bound: " + firstMs + " ms");
        long second = System.nanoTime();
        UiaClientWarmUp.ensure();
        long secondMs = (System.nanoTime() - second) / 1_000_000;
        assertTrue(secondMs < 50, "the second call returns at once: " + secondMs + " ms");
    }

    @Test
    void doesNothingWhereItIsNotNeeded() {
        if (limn.backend.Platform.current().isWindows() && "aarch64".equals(System.getProperty("os.arch"))) {
            return;
        }
        long start = System.nanoTime();
        UiaClientWarmUp.ensure();
        assertTrue((System.nanoTime() - start) / 1_000_000 < 50, "no thread, no library, no wait");
    }
}
