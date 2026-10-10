package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The one string a launcher builds for a system to read: the file manager's arguments on Windows. */
class LauncherCommandsTest {

    @Test
    void theFileToSelectIsQuotedWhole() {
        assertEquals("/select,\"reports" + java.io.File.separator + "third quarter, final.pdf\"",
                WindowsLauncher.selectArguments(Path.of("reports", "third quarter, final.pdf")));
    }

    @Test
    void aPathThatWouldEndTheQuotingIsRefused() {
        if (java.io.File.separatorChar == '\\') {
            return; // no Windows path holds a quote, so none can be built to ask about
        }
        assertNull(WindowsLauncher.selectArguments(Path.of("a\" /root,\"b.txt")));
    }
}
