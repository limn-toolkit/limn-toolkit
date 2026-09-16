import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.macosx.ObjCRuntime;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Whether the objects the macOS bridge autoreleases at the end of a frame are ever freed, on the thread
 * and under the launcher the bridge really runs on (the macos-C review's autorelease finding, 2026-09-15).
 *
 * <p>{@code AxBridge} sends {@code +[NSMutableDictionary dictionary]}, {@code +[NSString
 * stringWithUTF8String:]} and {@code +[NSNumber numberWithInteger:]} for an announcement's user info, and
 * {@code +[NSMutableArray array]} for a re-push, from the frame's end on the user-interface thread: the
 * main thread under {@code -XstartOnFirstThread}, between two {@code glfwPollEvents} calls, where no
 * accessibility callback puts AppKit's pool on the stack. Each of those answers an autoreleased object. The
 * question is what drains it: GLFW's poll wraps only its own work in a pool, and nothing else in a Limn
 * application runs an AppKit run loop.
 *
 * <p>Printed:
 * <ol>
 *   <li>whether this is the main thread, and what the runtime answers for the four functions used;</li>
 *   <li><b>one frame without a pool of ours</b>: an announcement's three objects and a re-push's array,
 *       built exactly as the bridge builds them, each held by a weak reference; then 120 frames of
 *       {@code glfwPollEvents} and a 16 ms sleep; then whether each is still alive;</li>
 *   <li><b>the same frame inside {@code objc_autoreleasePoolPush}/{@code objc_autoreleasePoolPop}</b>;</li>
 *   <li><b>the growth</b>: the process's {@code malloc_zone_statistics} bytes and blocks in use across
 *       20 000 such frames, twice without a pool and twice with one (two identical runs each, so the JVM's
 *       own allocation on other threads is the noise floor the difference is read against).</li>
 * </ol>
 *
 * <p>Nothing is posted: whether AppKit keeps the user info past a post does not change whether the
 * dictionary built for it is freed, and a posted announcement could be spoken by a screen reader already
 * running. No window is opened. Java 17 API only; MUST run with {@code -XstartOnFirstThread}, in the
 * console session, against a classpath carrying LWJGL (the demo's fat jar):
 * {@code java -XstartOnFirstThread -cp limn-demo-all.jar AutoreleaseProbe.java}.
 */
public final class AutoreleaseProbe {

    private static long msgSend;
    private static long storeWeak;
    private static long loadWeakRetained;
    private static long release;
    private static long zoneStatistics;
    private static long announcementKey;
    private static long priorityKey;

    public static void main(String[] args) {
        System.out.println("AutoreleaseProbe pid=" + ProcessHandle.current().pid()
                + " java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.version")
                + " arch=" + System.getProperty("os.arch"));
        SharedLibrary objc = ObjCRuntime.getLibrary();
        SharedLibrary system = APIUtil.apiCreateLibrary("libSystem.B.dylib");
        SharedLibrary appKit = APIUtil.apiCreateLibrary("/System/Library/Frameworks/AppKit.framework/AppKit");
        msgSend = objc.getFunctionAddress("objc_msgSend");
        long poolPush = objc.getFunctionAddress("objc_autoreleasePoolPush");
        long poolPop = objc.getFunctionAddress("objc_autoreleasePoolPop");
        storeWeak = objc.getFunctionAddress("objc_storeWeak");
        loadWeakRetained = objc.getFunctionAddress("objc_loadWeakRetained");
        release = objc.getFunctionAddress("objc_release");
        zoneStatistics = system.getFunctionAddress("malloc_zone_statistics");
        long mainNp = system.getFunctionAddress("pthread_main_np");
        announcementKey = memGetAddress(appKit.getFunctionAddress("NSAccessibilityAnnouncementKey"));
        priorityKey = memGetAddress(appKit.getFunctionAddress("NSAccessibilityPriorityKey"));
        System.out.println("pthread_main_np=" + JNI.invokeI(mainNp) + " thread='" + Thread.currentThread().getName() + "'");
        System.out.println("symbols objc_autoreleasePoolPush=" + (poolPush != NULL) + " objc_autoreleasePoolPop="
                + (poolPop != NULL) + " objc_storeWeak=" + (storeWeak != NULL) + " objc_loadWeakRetained="
                + (loadWeakRetained != NULL) + " malloc_zone_statistics=" + (zoneStatistics != NULL)
                + " NSAccessibilityAnnouncementKey='" + javaString(announcementKey) + "' NSAccessibilityPriorityKey='"
                + javaString(priorityKey) + "'");

        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        long app = msg(cls("NSApplication"), "sharedApplication");
        System.out.println("glfwInit ok; NSApp=" + (app != NULL));

        // (2) and (3): one frame's objects, held weakly, across 120 frames of polling.
        for (boolean pooled : new boolean[] { false, true }) {
            long slots = nmemCallocChecked(4, 8);
            long pool = pooled ? JNI.invokeP(poolPush) : NULL;
            long[] objects = frame("Saved the report to the shared library", 90);
            for (int i = 0; i < 4; i++) JNI.invokePPP(slots + 8L * i, objects[i], storeWeak);
            if (pooled) JNI.invokePV(pool, poolPop);
            for (int f = 0; f < 120; f++) {
                GLFW.glfwPollEvents();
                sleep(16);
            }
            String[] names = { "dictionary", "string", "number", "array" };
            StringBuilder line = new StringBuilder(pooled ? "one frame, pooled:" : "one frame, no pool of ours:");
            for (int i = 0; i < 4; i++) {
                boolean tagged = objects[i] < 0;   // arm64: the top bit marks a tagged pointer, never freed
                long alive = JNI.invokePP(slots + 8L * i, loadWeakRetained);
                if (alive != NULL) JNI.invokePV(alive, release);
                line.append(' ').append(names[i]).append(tagged ? "=tagged" : alive != NULL ? "=ALIVE" : "=freed");
            }
            System.out.println(line + " (after 120 polled frames)");
            for (int i = 0; i < 4; i++) JNI.invokePPP(slots + 8L * i, NULL, storeWeak);
            nmemFree(slots);
        }

        // (4) the growth, two identical runs each.
        for (boolean pooled : new boolean[] { false, false, true, true }) {
            System.gc();
            sleep(500);
            long[] before = zone();
            for (int f = 0; f < 20_000; f++) {
                long pool = pooled ? JNI.invokeP(poolPush) : NULL;
                frame("Saved the report to the shared library", f % 2 == 0 ? 10 : 90);
                if (pooled) JNI.invokePV(pool, poolPop);
                if (f % 500 == 0) GLFW.glfwPollEvents();
            }
            GLFW.glfwPollEvents();
            sleep(500);
            long[] after = zone();
            System.out.println("20000 frames, " + (pooled ? "pooled" : "no pool of ours") + ": bytes in use +"
                    + (after[1] - before[1]) + " blocks in use +" + (after[0] - before[0])
                    + " (" + ((after[1] - before[1]) / 20_000) + " bytes a frame)");
        }
        GLFW.glfwTerminate();
        System.out.println("done");
    }

    /** One frame's autoreleased objects, built with the messages AxBridge sends: user info, and a re-push's array. */
    private static long[] frame(String text, int priority) {
        long info = msg(cls("NSMutableDictionary"), "dictionary");
        long string = string(text);
        long number = msg(cls("NSNumber"), "numberWithInteger:", priority);
        msgVoid(info, "setObject:forKey:", string, announcementKey);
        msgVoid(info, "setObject:forKey:", number, priorityKey);
        long array = msg(cls("NSMutableArray"), "array");
        msgVoid(array, "addObject:", info);
        return new long[] { info, string, number, array };
    }

    private static long[] zone() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer stats = stack.calloc(32);   // unsigned blocks_in_use; size_t size_in_use, max_size_in_use, size_allocated
            JNI.invokePPV(NULL, memAddress(stats), zoneStatistics);
            return new long[] { Integer.toUnsignedLong(stats.getInt(0)), stats.getLong(8) };
        }
    }

    private static long nmemCallocChecked(int n, int size) {
        long address = nmemCalloc(n, size);
        if (address == NULL) throw new OutOfMemoryError();
        return address;
    }

    private static long string(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(utf8.length + 1);
            buffer.put(utf8).put((byte) 0).flip();
            return msg(cls("NSString"), "stringWithUTF8String:", memAddress(buffer));
        }
    }

    private static String javaString(long nsString) {
        long utf8 = msg(nsString, "UTF8String");
        return utf8 == NULL ? null : memUTF8(utf8);
    }

    private static long cls(String name) {
        return ObjCRuntime.objc_getClass(name);
    }

    private static long msg(long self, String selector) {
        return JNI.invokePPP(self, ObjCRuntime.sel_getUid(selector), msgSend);
    }

    private static long msg(long self, String selector, long a) {
        return JNI.invokePPPP(self, ObjCRuntime.sel_getUid(selector), a, msgSend);
    }

    private static void msgVoid(long self, String selector, long a) {
        JNI.invokePPPV(self, ObjCRuntime.sel_getUid(selector), a, msgSend);
    }

    private static void msgVoid(long self, String selector, long a, long b) {
        JNI.invokePPPPV(self, ObjCRuntime.sel_getUid(selector), a, b, msgSend);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
