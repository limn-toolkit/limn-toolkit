package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.backend.lwjgl.a11y.ProbeWindow;
import limn.components.Button;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a real scene's events reach AppKit: in the frame that emitted them (MACOS-NEW-8).
 *
 * <p>The bridge's own cases can only say what a publish or a frame end does when it is called; the
 * defect was in when the scene called it. The queue used to be drained at the top of the next
 * publish, and a scene publishes only when its tree changed, so every notification waited for the
 * next change: the last change before a pause was never told, and a reader driven one step every
 * three seconds was told each step at the next one. So these drive a real {@link Scene} over the
 * platform's bridge with the platform left out, and read what reached the post.
 */
@ExtendWith(PlatformFreeBridges.class)
class AxSceneTimingTest {

    private HeadlessUi ui;
    private final AtomicLong nanos = new AtomicLong();
    private Scene scene;
    private AxBridge bridge;
    private Button first;
    private Button second;
    private final List<String> trace = new ArrayList<>();

    @BeforeEach
    void bind() {
        ui = new HeadlessUi(nanos::get);
        Column root = new Column();
        first = new Button("First");
        second = new Button("Second");
        root.add(first);
        root.add(second);
        scene = new Scene(root, nanos::get);
        ProbeWindow window = new ProbeWindow();
        bridge = PlatformFreeBridges.make();
        bridge.trace(trace::add);
        window.accessibility = bridge;
        scene.bind(window);
        frame();                // the priming publish
        bridge.entered();       // a client has asked, so the scene is live from here on
        scene.requestFocus(first);
        frame();
        trace.clear();
    }

    @AfterEach
    void unbind() {
        ui.close();
    }

    private void frame() {
        scene.renderFrame(new NoopCanvas(400, 300));
    }

    private List<String> posted() {
        return trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
    }

    /**
     * What was posted after the scene emitted an event of this type, in order. The distinction the
     * defect needs: a drain at the top of the publish posts the previous frame's events before this
     * frame's are emitted, which a plain list of posts cannot tell from the right answer.
     */
    private List<String> postedAfterEmitting(String type) {
        int emitted = -1;
        for (int i = 0; i < trace.size(); i++) {
            if (trace.get(i).startsWith("emitted " + type + "#")) emitted = i;
        }
        assertTrue(emitted >= 0, "the scene emitted no " + type + ": " + trace);
        return trace.subList(emitted + 1, trace.size()).stream()
                .filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
    }

    @Test
    void aFocusMoveIsPostedInTheFrameThatPublishedIt() {
        scene.requestFocus(second);
        frame();
        assertTrue(postedAfterEmitting("FOCUS_CHANGED")
                        .contains("NSAccessibilityFocusedUIElementChangedNotification"),
                "one frame, one focus move: told now, not when something else next changes: " + trace);
        assertEquals(0, bridge.queuedEvents());
        int told = posted().size();
        frame();
        assertEquals(told, posted().size(), "and a quiet frame after it tells nothing again");
    }

    @Test
    void anAnnouncementOnAStillWindowLeavesTheQueueInItsOwnFrame() {
        scene.announce("Saved", Accessible.Politeness.POLITE);
        frame();
        assertTrue(trace.contains("emitted ANNOUNCEMENT#0"), String.valueOf(trace));
        assertEquals(0, bridge.queuedEvents(),
                "a frame that changed nothing in the tree still ends, and what it said is drained");
    }

    @Test
    void aReentrantPublishPostsNothingAndTheFrameItAskedForPostsItThoughNothingIsLeftToWalk() {
        scene.requestFocus(second);
        // What an AX callback does when it cannot wait for a frame: the walk happens here, with the
        // platform on the stack, and so the event it raises must wait.
        bridge.host().republishNow();
        assertTrue(trace.stream().anyMatch(line -> line.startsWith("emitted FOCUS_CHANGED#")),
                "the reentrant walk found the focus move: " + trace);
        assertTrue(posted().isEmpty(), "a post from inside an AX callback re-enters the platform (§3.2)");
        assertTrue(bridge.obligationsDeferred());

        int pushes = bridge.pushes();
        frame();   // the tree is clean: this frame walks nothing and publishes nothing
        assertEquals(pushes, bridge.pushes(), "nothing was walked or re-pushed: the root is unchanged");
        assertTrue(posted().contains("NSAccessibilityFocusedUIElementChangedNotification"),
                "the frame the reentrant publish asked for pays its debts whether or not it publishes: "
                        + trace);
        assertFalse(bridge.obligationsDeferred(), "the re-push and the boxes were owed there too");
        assertEquals(0, bridge.queuedEvents());
    }
}
