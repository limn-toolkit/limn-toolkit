package limn.backend.lwjgl.a11y.macos;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The platform-free bridges a test makes, detached when it ends, and a test that leaves a bridge in
 * the process's set of open windows failed for it.
 *
 * <p>The set is process-wide ({@code AxBridge#openBridges}): a bridge entered it on its first publish
 * and left it only on a detach, so a test that published and never detached kept its bridge and its
 * tree for the life of the test JVM, and every later focus ask, focus check and post-sweep check in
 * any test walked it (macos-B review). Every test class that makes a platform-free bridge makes it
 * here and is extended with this.
 */
final class PlatformFreeBridges implements AfterEachCallback {

    private static final List<AxBridge> MADE = new ArrayList<>();

    /** @return a platform-free bridge this extension detaches when the test ends */
    static AxBridge make() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        MADE.add(bridge);
        return bridge;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        for (AxBridge bridge : MADE) bridge.detach();
        MADE.clear();
        assertEquals(0, AxBridge.openBridgeCount(),
                "a bridge was left in the process's open windows by a test that did not make it here");
        // The last-announced focus is process-wide too (semantics 4), and a detach forgets what its
        // own bridge announced; a memory surviving here would make the next test's first focus post
        // depend on the one before it.
        assertEquals(0, AxBridge.announcedFocusNode(),
                "a test left the process's last-announced focus pointing at a node of its own");
    }
}
