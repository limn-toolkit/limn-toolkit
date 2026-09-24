package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.lwjgl.internal.ObjC;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A gated content view answers no input context while its input method is off and the inherited
 * one while it is on, hands typed text on to GLFW when it has none, and leaves GLFW's own class as
 * it was. GLFW's switch changed the system's input source instead: measured on a guest with the
 * Japanese input method chosen, every move of the focus off a text field left the whole system on
 * the Latin layout, after the application quit as well. The system's input source itself is read
 * only by a live run; see {@link MacInputContextGate}.
 */
class MacInputContextGateTest {

    @Test
    void aGatedViewHasNoInputContextWhileOffAndGlfwsClassIsLeftAsItWas() {
        assumeTrue(Platform.current().isMacOs(), "an AppKit view");
        HeadlessGl.assumeAvailable();
        long window = HeadlessGl.window();
        long view = GLFWNativeCocoa.glfwGetCocoaView(window);
        long glfwClass = ObjCRuntime.object_getClass(view);
        long inputContext = ObjC.sel("inputContext");
        long glfwsOwn = ObjCRuntime.method_getImplementation(
                ObjCRuntime.class_getInstanceMethod(glfwClass, inputContext));
        List<Integer> typed = new ArrayList<>();
        GLFWCharCallback previous = GLFW.glfwSetCharCallback(window, (w, codepoint) -> typed.add(codepoint));
        try {
            assertTrue(MacInputContextGate.install(view), "the view is gated");
            long gated = ObjCRuntime.object_getClass(view);
            assertNotEquals(glfwClass, gated, "the view moved into a subclass");
            assertEquals(glfwClass, ObjCRuntime.class_getSuperclass(gated), "of GLFW's own class");
            assertEquals(glfwsOwn, ObjCRuntime.method_getImplementation(
                            ObjCRuntime.class_getInstanceMethod(glfwClass, inputContext)),
                    "and GLFW's class answers as it did");

            assertEquals(0L, ObjC.msg(view, "inputContext"), "off until a text widget has the focus");
            MacInputContextGate.setEnabled(view, true);
            assertNotEquals(0L, ObjC.msg(view, "inputContext"), "on: the context AppKit made for it");
            MacInputContextGate.setEnabled(view, false);
            assertEquals(0L, ObjC.msg(view, "inputContext"), "and off again");

            // With no context AppKit's key bindings call the responder's -insertText:, which GLFW's
            // view does not answer: the gate hands it on, or a list's type-ahead hears no letters.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long text = ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:",
                        org.lwjgl.system.MemoryUtil.memAddress(stack.UTF8("ka")));
                ObjC.msgVoid(view, "insertText:", text);
            }
            assertEquals(List.of((int) 'k', (int) 'a'), typed, "the letters reach GLFW's characters");
        } finally {
            MacInputContextGate.forget(view);
            ObjCRuntime.object_setClass(view, glfwClass); // the shared test window, as it was
            GLFW.glfwSetCharCallback(window, previous);
        }
    }
}
