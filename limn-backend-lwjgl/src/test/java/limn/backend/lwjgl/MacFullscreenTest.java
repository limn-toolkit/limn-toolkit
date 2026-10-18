package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.lwjgl.internal.ObjC;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The AppKit side of native fullscreen, as far as it can be exercised without taking the display
 * fullscreen, which a test must never do: the observer delivers a notification to the window it
 * names and to no other, AppKit's four names resolve, and a window GLFW made unable to go
 * fullscreen is lent the behaviour and given its own back. The transitions themselves are read
 * only by a live run.
 */
class MacFullscreenTest {

    @Test
    void theCollectionBehaviourLentForFullscreenIsThePrimaryOneAndOnlyThat() {
        long managed = 1L << 2;
        assertEquals(MacFullscreen.BEHAVIOR_FULL_SCREEN_PRIMARY,
                MacFullscreen.withFullscreenPrimary(MacFullscreen.BEHAVIOR_FULL_SCREEN_NONE),
                "GLFW's window that is not resizable");
        long glfwResizable = MacFullscreen.BEHAVIOR_FULL_SCREEN_PRIMARY | managed;
        assertEquals(glfwResizable, MacFullscreen.withFullscreenPrimary(glfwResizable),
                "GLFW's resizable window needs nothing");
        assertEquals(MacFullscreen.BEHAVIOR_FULL_SCREEN_PRIMARY | managed,
                MacFullscreen.withFullscreenPrimary(MacFullscreen.BEHAVIOR_FULL_SCREEN_AUXILIARY | managed),
                "the auxiliary one, which AppKit refuses beside it, goes");
    }

    @Test
    void aNotificationReachesTheWindowItNamesAndNoOther() {
        assumeTrue(Platform.current().isMacOs(), "AppKit's notification centre");
        HeadlessGl.assumeWindow();
        long object = ObjC.msg(ObjC.msg(ObjC.cls("NSObject"), "alloc"), "init");
        long other = ObjC.msg(ObjC.msg(ObjC.cls("NSObject"), "alloc"), "init");
        long name;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // A name nothing in AppKit listens for: posting its own for an object that is no window
            // in transition would reach AppKit's observers as well as this one.
            name = ObjC.msg(ObjC.msg(ObjC.cls("NSString"), "alloc"), "initWithUTF8String:",
                    org.lwjgl.system.MemoryUtil.memAddress(stack.UTF8("LimnFullscreenTestNotification")));
        }
        List<MacFullscreen.Event> heard = new ArrayList<>();
        long center = ObjC.msg(ObjC.cls("NSNotificationCenter"), "defaultCenter");
        try {
            assertTrue(MacFullscreen.observeAs(object, name, MacFullscreen.Event.DID_ENTER, heard::add),
                    "the observer class is made and AppKit's four names resolve");
            ObjC.msg(center, "postNotificationName:object:", name, other);
            assertEquals(List.of(), heard, "another object's notification is not this window's");
            ObjC.msg(center, "postNotificationName:object:", name, object);
            assertEquals(List.of(MacFullscreen.Event.DID_ENTER), heard);

            MacFullscreen.forget(object);
            ObjC.msg(center, "postNotificationName:object:", name, object);
            assertEquals(1, heard.size(), "a window forgotten hears nothing more");
        } finally {
            MacFullscreen.forget(object);
            ObjC.msgVoid(name, "release");
            ObjC.msgVoid(other, "release");
            ObjC.msgVoid(object, "release");
        }
    }

    @Test
    void aWindowIsLentTheBehaviourAndGivenItsOwnBackWithoutGoingFullscreen() {
        assumeTrue(Platform.current().isMacOs(), "an NSWindow");
        HeadlessGl.assumeWindow();
        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(HeadlessGl.window());
        long own = ObjC.msg(nsWindow, "collectionBehavior");
        assertFalse(MacFullscreen.isFullscreen(nsWindow), "the hidden test window is windowed");
        try {
            ObjC.msgVoid(nsWindow, "setCollectionBehavior:", MacFullscreen.BEHAVIOR_FULL_SCREEN_NONE);
            long lent = MacFullscreen.allowFullscreen(nsWindow);
            assertEquals(MacFullscreen.BEHAVIOR_FULL_SCREEN_NONE, lent, "what it had is kept");
            assertEquals(MacFullscreen.BEHAVIOR_FULL_SCREEN_PRIMARY, ObjC.msg(nsWindow, "collectionBehavior"));
            assertEquals(-1, MacFullscreen.allowFullscreen(nsWindow), "lent once, not twice");
            MacFullscreen.restoreCollectionBehavior(nsWindow, lent);
            assertEquals(MacFullscreen.BEHAVIOR_FULL_SCREEN_NONE, ObjC.msg(nsWindow, "collectionBehavior"));
            assertFalse(MacFullscreen.isFullscreen(nsWindow), "and nothing went fullscreen");
        } finally {
            ObjC.msgVoid(nsWindow, "setCollectionBehavior:", own); // the shared test window, as it was
        }
    }
}
