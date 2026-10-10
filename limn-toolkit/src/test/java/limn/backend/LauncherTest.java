package limn.backend;

import limn.backend.spi.LauncherPeer;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link Launcher} lets through to a backend, and what it answers by itself. */
class LauncherTest {

    /** A backend that takes everything and remembers what it was handed. */
    private static final class Recording implements LauncherPeer {
        final List<String> handed = new ArrayList<>();

        @Override
        public CompletionStage<Boolean> openAddress(URI address) {
            handed.add("address " + address);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> openFile(Path file) {
            handed.add("file " + file);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> showInFolder(Path file) {
            handed.add("show " + file);
            return CompletableFuture.completedFuture(true);
        }
    }

    private HeadlessUi ui;
    private Recording backend;
    private Launcher launcher;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        backend = new Recording();
        launcher = new Launcher(backend);
    }

    @AfterEach
    void closeRuntime() {
        ui.close();
    }

    private static boolean now(CompletionStage<Boolean> answer) {
        return answer.toCompletableFuture().getNow(null);
    }

    @Test
    void aWebAddressAndAMailAddressAreHandedOver() {
        assertTrue(now(launcher.openAddress(URI.create("https://example.org/docs#tables"))));
        assertTrue(now(launcher.openAddress(URI.create("HTTP://example.org"))), "a scheme has no case");
        assertTrue(now(launcher.openAddress(URI.create("mailto:someone@example.org"))));
        assertEquals(List.of("address https://example.org/docs#tables", "address HTTP://example.org",
                "address mailto:someone@example.org"), backend.handed);
    }

    @Test
    void anAddressOfAnotherKindNeverReachesTheBackend() {
        for (String refused : List.of("file:///bin/sh", "FILE:///bin/sh", "javascript:alert(1)",
                "ms-settings:display", "tel:+15550100", "docs/tables", "//example.org/x", "https:///nohost",
                "http:relative")) {
            URI address = URI.create(refused);
            assertFalse(Launcher.isAllowed(address), refused);
            assertFalse(now(launcher.openAddress(address)), refused);
        }
        assertEquals(List.of(), backend.handed);
    }

    @Test
    void anApplicationAllowsItsOwnSchemeOnceAndNeverTheFileOne() {
        URI address = URI.create("limn-test-scheme:order/1089");
        assertFalse(Launcher.isAllowed(address));
        Launcher.allowScheme("Limn-Test-Scheme");
        assertTrue(Launcher.isAllowed(address));
        assertTrue(now(launcher.openAddress(address)));
        assertThrows(IllegalArgumentException.class, () -> Launcher.allowScheme("file"));
        assertThrows(IllegalArgumentException.class, () -> Launcher.allowScheme("FILE"));
        assertThrows(IllegalArgumentException.class, () -> Launcher.allowScheme("not a scheme"));
        assertThrows(IllegalArgumentException.class, () -> Launcher.allowScheme("tel:"));
    }

    @Test
    void aFileIsHandedOverAbsoluteAndAMissingOneIsNot(@TempDir Path folder) throws IOException {
        Path report = Files.writeString(folder.resolve("report.txt"), "x");
        Path relative = Path.of("").toAbsolutePath().relativize(report);
        assertTrue(now(launcher.openFile(relative)));
        assertTrue(now(launcher.showInFolder(report)));
        assertFalse(now(launcher.openFile(folder.resolve("gone.txt"))));
        assertFalse(now(launcher.showInFolder(folder.resolve("gone.txt"))));
        Path absolute = report.toAbsolutePath().normalize();
        assertEquals(List.of("file " + absolute, "show " + absolute), backend.handed);
    }

    @Test
    void aWindowWithNoDesktopAnswersNoToEverything(@TempDir Path folder) {
        assertFalse(now(Launcher.NONE.openAddress(URI.create("https://example.org"))));
        assertFalse(now(Launcher.NONE.openFile(folder)));
        assertFalse(now(Launcher.NONE.showInFolder(folder)));
    }

    @Test
    void itIsAskedOnTheUiThreadOnly() throws InterruptedException {
        Throwable[] thrown = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                launcher.openAddress(URI.create("https://example.org"));
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        other.start();
        other.join();
        assertTrue(thrown[0] instanceof IllegalStateException, String.valueOf(thrown[0]));
        assertEquals(List.of(), backend.handed);
    }
}
