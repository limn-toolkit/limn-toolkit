import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.Pointer.POINTER_SIZE;

/**
 * ADR 039 phase 7, the probe run the record demands BEFORE the bridge (§14, §13.20-§13.23, §13.27).
 *
 * <p>The macOS spike published <b>one static element under one window</b> and this design publishes
 * a nested tree that changes every frame. Five questions ride on that difference, and none of them
 * can be answered by a unit test, because every one is about what AppKit does with what it is
 * handed:
 *
 * <ol>
 *   <li><b>§13.21</b> a tree deeper than one element, and a tree that mutates. Does
 *       {@code accessibilityChildren} on our OWN class answer the levels below the pushed array,
 *       and does {@code AXUIElementCopyElementAtPosition} still hit-test through the nesting with
 *       no {@code accessibilityHitTest:} of ours? And in whose space is a nested element's
 *       {@code setAccessibilityFrameInParentSpace:} measured -- its parent element's, or the view's?</li>
 *   <li><b>§13.20</b> an element destroyed while a client holds it, and the reentrant publish.</li>
 *   <li><b>§13.22</b> {@code accessibilityFocusedUIElement}: does AppKit ever ask US?</li>
 *   <li><b>§13.23</b> a non-ASCII name across the boundary, astral and right-to-left.</li>
 *   <li><b>§13.27</b> a relation whose target is AppKit's own window object.</li>
 * </ol>
 *
 * <p>This is a lab artifact, not a module. It builds its elements the way the bridge will --
 * LWJGL's {@code ObjCRuntime} plus libffi closures, no native code of our own -- so that what it
 * measures is transferable, but it hard-codes its tree instead of walking a scene.
 *
 * <p>Java 17 API only. MUST run with {@code -XstartOnFirstThread}.
 *
 * <p>System properties: {@code probe.seconds} (default 300), {@code probe.commands} (a file the
 * driver appends commands to; the probe truncates it after each read).
 */
public final class AxProbe {

    private static final Thread MAIN_THREAD = Thread.currentThread();
    private static final long T0 = System.nanoTime();

    private static long objcMsgSend;
    private static long pthreadMainNp;
    private static long contentView;
    private static long nsWindow;

    /** Every AX callback is supposed to be the UI thread (Finding 4). This is where that is checked. */
    private static int callbacksOffMainThread;

    // ---- ObjC helpers ---------------------------------------------------------------------------

    static long sel(String name) { return ObjCRuntime.sel_getUid(name); }
    static long cls(String name) { return ObjCRuntime.objc_getClass(name); }
    static long msg(long self, String sel) { return JNI.invokePPP(self, sel(sel), objcMsgSend); }
    static long msg(long self, String sel, long a) { return JNI.invokePPPP(self, sel(sel), a, objcMsgSend); }
    static void msgV(long self, String sel, long a) { JNI.invokePPPV(self, sel(sel), a, objcMsgSend); }
    static boolean msgZ(long self, String sel) { return JNI.invokePPZ(self, sel(sel), objcMsgSend); }
    static long msgIdx(long self, String sel, long i) { return JNI.invokePPPP(self, sel(sel), i, objcMsgSend); }

    /**
     * A retained NSString built from UTF-8. The astral and right-to-left names of §13.23 go through
     * exactly this call, so if {@code stringWithUTF8String:} is the wrong door for them this is
     * where it shows.
     *
     * <p>{@code probe.mangle} makes it the wrong door on purpose: the Java string is encoded as
     * ISO-8859-1 and handed to a call that reads UTF-8. That is not a strawman, it is the mistake a
     * bridge makes by writing {@code getBytes()} and taking the platform default. A check that
     * cannot fail is not a check, and §13.23 is a check -- so the run that says the names survive
     * is only worth reading beside the run that says they do not.
     */
    static long nsString(String s) {
        byte[] bytes = (MANGLE ? s.getBytes(StandardCharsets.ISO_8859_1) : s.getBytes(StandardCharsets.UTF_8));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(bytes.length + 1);
            buffer.put(bytes).put((byte) 0).flip();
            long str = msg(cls("NSString"), "stringWithUTF8String:", memAddress(buffer));
            return str == NULL ? NULL : msg(str, "retain");
        }
    }

    private static final boolean MANGLE = Boolean.getBoolean("probe.mangle");

    /**
     * Whether every node also gets {@code setAccessibilityChildren:}, not just the root's children.
     *
     * <p>The first run of this probe found that a point inside a grandchild hit-tests to its
     * grandparent: AppKit stops at the array that was PUSHED and does not descend through the
     * children our own {@code -accessibilityChildren} answers, even though a client walking the
     * tree reaches them perfectly. The two are different code paths in AppKit, and this switch is
     * what tells them apart -- if pushing at every level fixes the hit test, then the default hit
     * test reads a stored array rather than sending the selector, and §2.2's "everything below is a
     * pull" is true for the walk and false for hit-testing.
     */
    private static final boolean PUSH_EVERY_LEVEL = Boolean.getBoolean("probe.pushall");

    static String javaString(long nsString) {
        if (nsString == NULL) return null;
        long utf8 = msg(nsString, "UTF8String");
        return utf8 == NULL ? null : memUTF8(utf8);
    }

    /** An FFI struct of n doubles: CGPoint (2), CGRect (4). arm64 passes both as HFAs in d0..d3. */
    static FFIType doubles(int n) {
        PointerBuffer elements = memAllocPointer(n + 1);
        for (int i = 0; i < n; i++) elements.put(i, LibFFI.ffi_type_double.address());
        elements.put(n, NULL);
        FFIType type = FFIType.calloc();
        type.type(LibFFI.FFI_TYPE_STRUCT);
        type.elements(elements);
        return type;
    }

    private static FFICIF rectSetterCif;
    private static FFICIF rectGetterCif;

    static void msgRect(long self, String sel, double x, double y, double w, double h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer storage = stack.malloc(8 + 8 + 32);
            storage.putLong(0, self).putLong(8, sel(sel))
                   .putDouble(16, x).putDouble(24, y).putDouble(32, w).putDouble(40, h);
            long base = memAddress(storage);
            PointerBuffer values = stack.mallocPointer(3);
            values.put(0, base).put(1, base + 8).put(2, base + 16);
            LibFFI.ffi_call(rectSetterCif, objcMsgSend, stack.malloc(8), values);
        }
    }

    static double[] msgGetRect(long self, String sel) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer storage = stack.malloc(16);
            storage.putLong(0, self).putLong(8, sel(sel));
            long base = memAddress(storage);
            PointerBuffer values = stack.mallocPointer(2);
            values.put(0, base).put(1, base + 8);
            ByteBuffer rect = stack.malloc(32);
            LibFFI.ffi_call(rectGetterCif, objcMsgSend, rect, values);
            return new double[] { rect.getDouble(0), rect.getDouble(8), rect.getDouble(16), rect.getDouble(24) };
        }
    }

    static void log(String s) {
        System.out.printf("[%8.3fs] %s%n", (System.nanoTime() - T0) / 1e9, s);
        System.out.flush();
    }

    static boolean onMainThread() {
        return pthreadMainNp != NULL && JNI.invokeI(pthreadMainNp) == 1;
    }

    /** Called at the top of every IMP: the thread claim of Finding 4 is either true or counted. */
    static void enterCallback(String what) {
        if (!onMainThread() || Thread.currentThread() != MAIN_THREAD) {
            callbacksOffMainThread++;
            log("!!! " + what + " ON A FOREIGN THREAD: java='" + Thread.currentThread().getName()
                    + "' pthread_main_np=" + (pthreadMainNp == NULL ? -1 : JNI.invokeI(pthreadMainNp)));
        }
    }

    // ---- Upcalls ---------------------------------------------------------------------------------

    /** IMP returning an id: (id self, SEL _cmd) -> id. */
    public interface IdGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(IdGetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            long self = memGetAddress(memGetAddress(args));
            long cmd = memGetAddress(memGetAddress(args + POINTER_SIZE));
            APIUtil.apiClosureRetP(ret, invoke(self, cmd));
        }
        long invoke(long self, long cmd);
    }
    public abstract static class IdGetter extends Callback implements IdGetterI {
        protected IdGetter() { super(IdGetterI.DESCRIPTOR); }
    }

    /** IMP returning a BOOL: (id self, SEL _cmd) -> BOOL. */
    public interface BoolGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(BoolGetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_uint8, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            long self = memGetAddress(memGetAddress(args));
            long cmd = memGetAddress(memGetAddress(args + POINTER_SIZE));
            APIUtil.apiClosureRet(ret, invoke(self, cmd));
        }
        boolean invoke(long self, long cmd);
    }
    public abstract static class BoolGetter extends Callback implements BoolGetterI {
        protected BoolGetter() { super(BoolGetterI.DESCRIPTOR); }
    }

    /** IMP taking a CGPoint by value: (id self, SEL _cmd, NSPoint) -> id. */
    public interface HitTestI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(HitTestI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, doubles(2)));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            long self = memGetAddress(memGetAddress(args));
            long cmd = memGetAddress(memGetAddress(args + POINTER_SIZE));
            long point = memGetAddress(args + 2L * POINTER_SIZE);
            APIUtil.apiClosureRetP(ret, invoke(self, cmd, memGetDouble(point), memGetDouble(point + 8)));
        }
        long invoke(long self, long cmd, double x, double y);
    }
    public abstract static class HitTest extends Callback implements HitTestI {
        protected HitTest() { super(HitTestI.DESCRIPTOR); }
    }

    private static final List<Callback> CALLBACKS = new ArrayList<>();

    // ---- The encodings, read from the running AppKit and never from memory (§12.3) ---------------

    /**
     * The type encoding AppKit itself declares for a selector, read with
     * {@code class_getInstanceMethod} + {@code method_getTypeEncoding}. This is the platform's own
     * answer, and it is what gets passed to {@code class_addMethod}: a table in a source file is a
     * copy of an answer, and a copy is what goes stale.
     */
    static String encodingOf(String className, String selector) {
        long c = cls(className);
        if (c == NULL) return null;
        long m = ObjCRuntime.class_getInstanceMethod(c, sel(selector));
        return m == NULL ? null : ObjCRuntime.method_getTypeEncoding(m);
    }

    /**
     * The classes worth asking for an encoding, in the order a bridge would trust them.
     * {@code NSAccessibilityElement} is the superclass of everything we vend and so is asked first;
     * the rest are here because the run below found that it does not declare every selector this
     * design needs, and an encoding is still the platform's answer when a different class of the
     * platform's gives it.
     */
    private static final String[] ENCODING_SOURCES = {
        "NSAccessibilityElement", "NSView", "NSWindow", "NSResponder", "NSApplication", "NSObject",
    };

    /**
     * Reads a selector's encoding from the running AppKit and refuses to guess. It searches the
     * classes above rather than one, and says which answered: the encoding of a selector is a fact
     * about the ObjC runtime and not about the class it was read from, so a selector
     * {@code NSAccessibilityElement} does not itself implement still has exactly one true answer as
     * long as some class of AppKit's declares it. Nothing here falls back to a literal.
     */
    static String requireEncoding(String selector, String expected) {
        for (String source : ENCODING_SOURCES) {
            String actual = encodingOf(source, selector);
            if (actual == null) continue;
            log("   encoding -" + selector + " = '" + actual + "'  (from " + source + ")"
                    + (actual.equals(expected) ? "   as expected" : "   !!! EXPECTED '" + expected + "'"));
            return actual;
        }
        throw new IllegalStateException("no class in " + Arrays.toString(ENCODING_SOURCES)
                + " declares -" + selector + "; nothing here may guess its encoding");
    }

    // ---- The probe's own tree ----------------------------------------------------------------------

    /** One node. {@code element} is the ObjC object; {@code frame} is in this node's PARENT's space. */
    static final class Node {
        final String key;
        final String role;      // an AppKit role constant name, e.g. NSAccessibilityButtonRole
        final String name;
        final double[] frame;
        final List<Node> kids = new ArrayList<>();
        long element = NULL;
        long childrenArray = NULL;   // retained NSArray, rebuilt whenever kids change
        boolean released;

        Node(String key, String role, String name, double x, double y, double w, double h) {
            this.key = key;
            this.role = role;
            this.name = name;
            this.frame = new double[] { x, y, w, h };
        }

        Node with(Node... children) {
            kids.addAll(Arrays.asList(children));
            return this;
        }
    }

    /** element pointer -> node, the same recovery the Windows spike used to get a Java object back. */
    private static final Map<Long, Node> BY_ELEMENT = new LinkedHashMap<>();
    private static final Map<String, Node> BY_KEY = new LinkedHashMap<>();

    /**
     * The tree. Three levels below the content view, which is two more than the spike had, and
     * the names carry the two characters §13.23 asks for:
     *
     * <ul>
     *   <li>an astral character, U+1D11E MUSICAL SYMBOL G CLEF, which is a surrogate pair in Java
     *       and four bytes in UTF-8 -- the case a bridge that assumes one char is one code point
     *       mangles;</li>
     *   <li>a right-to-left string, Hebrew, which is a different failure: the bytes survive and the
     *       ORDER is what a broken layer reverses.</li>
     * </ul>
     */
    static Node buildTree() {
        // Every box is strictly inside its parent's. That is not tidiness: a child sticking out of
        // its parent makes a hit test ambiguous, and an ambiguous hit test cannot answer §13.21.
        // Parent space is measured from the PARENT's bottom-left corner, which this run establishes
        // and §2.2 does not say -- it describes the content view's space, which is the parent only
        // for the root's own children.
        Node deep = new Node("deep", "NSAccessibilityButtonRole", "Fundo", 10, 10, 100, 24);
        Node nested = new Node("nested", "NSAccessibilityGroupRole", "Aninhado", 20, 20, 140, 44)
                .with(deep);
        Node save = new Node("save", "NSAccessibilityButtonRole", "Salvar 𝄞", 20, 150, 160, 32);
        Node check = new Node("check", "NSAccessibilityCheckBoxRole", "שלום עולם", 20, 110, 160, 24);
        Node form = new Node("form", "NSAccessibilityGroupRole", "Formulário", 20, 60, 200, 200)
                .with(save, check, nested);
        Node footer = new Node("footer", "NSAccessibilityStaticTextRole", "Rodapé", 10, 20, 120, 20);
        Node second = new Node("second", "NSAccessibilityGroupRole", "Segundo", 250, 60, 140, 60)
                .with(footer);
        Node root = new Node("root", "NSAccessibilityGroupRole", "<root, not vended>", 0, 0, 0, 0);
        root.with(form, second);
        return root;
    }

    // ---- main ------------------------------------------------------------------------------------

    private static SharedLibrary appKit;
    private static long postNotification;
    private static long destroyedNotification;

    /** A role constant resolved by dlsym on AppKit -- never the literal "AXButton". */
    static long roleConstant(String symbol) {
        long address = appKit.getFunctionAddress(symbol);
        if (address == NULL) throw new IllegalStateException("AppKit has no " + symbol);
        return memGetAddress(address);
    }

    public static void main(String[] argv) throws Exception {
        int seconds = Integer.getInteger("probe.seconds", 300);
        Path commands = Path.of(System.getProperty("probe.commands", "/tmp/axprobe.cmd"));
        log("pid=" + ProcessHandle.current().pid() + " java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.version") + " arch=" + System.getProperty("os.arch")
                + " commands=" + commands);

        objcMsgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        long poolPush = ObjCRuntime.getLibrary().getFunctionAddress("objc_autoreleasePoolPush");
        long poolPop = ObjCRuntime.getLibrary().getFunctionAddress("objc_autoreleasePoolPop");
        pthreadMainNp = APIUtil.apiCreateLibrary("libSystem.B.dylib").getFunctionAddress("pthread_main_np");
        appKit = APIUtil.apiCreateLibrary("/System/Library/Frameworks/AppKit.framework/AppKit");
        postNotification = appKit.getFunctionAddress("NSAccessibilityPostNotification");
        destroyedNotification = memGetAddress(appKit.getFunctionAddress(
                "NSAccessibilityUIElementDestroyedNotification"));
        log("AppKit: NSAccessibilityPostNotification=0x" + Long.toHexString(postNotification)
                + " NSAccessibilityUIElementDestroyedNotification='" + javaString(destroyedNotification) + "'");

        FFIType cgRect = doubles(4);
        rectSetterCif = APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, cgRect);
        rectGetterCif = APIUtil.apiCreateCIF(cgRect, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer);

        log("---- encodings as the running AppKit declares them (§12.3: read, never remembered) ----");
        String childrenEncoding = requireEncoding("accessibilityChildren", "@16@0:8");
        String focusedEncoding = requireEncoding("accessibilityFocusedUIElement", "@16@0:8");
        requireEncoding("accessibilityPerformPress", "B16@0:8");
        requireEncoding("setAccessibilityFrameInParentSpace:", "v48@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16");
        String hitTestEncoding = requireEncoding("accessibilityHitTest:", "@32@0:8{CGPoint=dd}16");

        // (a) the window
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        long window = GLFW.glfwCreateWindow(480, 320, "Limn phase 7 probe", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("glfwCreateWindow failed");
        GLFW.glfwSetWindowPos(window, 200, 200);
        GLFW.glfwShowWindow(window);
        GLFW.glfwFocusWindow(window);
        GLFW.glfwPollEvents();

        long pool = JNI.invokeP(poolPush);
        nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(window);
        contentView = msg(nsWindow, "contentView");
        log("nsWindow=0x" + Long.toHexString(nsWindow) + " (" + ObjCRuntime.object_getClassName(nsWindow) + ")"
                + " contentView=0x" + Long.toHexString(contentView)
                + " (" + ObjCRuntime.object_getClassName(contentView) + ")");
        log("contentView accessibilityFrame(screen) = " + Arrays.toString(msgGetRect(contentView, "accessibilityFrame")));

        // (b) our own subclass of NSAccessibilityElement. accessibilityChildren is an IMP and not a
        //     stock setter on purpose: §2.2 says everything below the pushed array is a PULL, and
        //     the pull is what has never been exercised.
        long elementClass = ObjCRuntime.objc_allocateClassPair(cls("NSAccessibilityElement"), "LimnProbeElement", 0);
        if (elementClass == NULL) throw new IllegalStateException("objc_allocateClassPair failed");

        IdGetter children = new IdGetter() {
            @Override public long invoke(long self, long cmd) {
                enterCallback("-accessibilityChildren");
                Node node = BY_ELEMENT.get(self);
                if (node == null) {
                    log("!!! -accessibilityChildren on an element we do not know: 0x" + Long.toHexString(self));
                    return NULL;
                }
                childReads++;
                return node.childrenArray;
            }
        };
        CALLBACKS.add(children);
        boolean added = ObjCRuntime.class_addMethod(elementClass, sel("accessibilityChildren"),
                children.address(), childrenEncoding);

        // Runs A and B measured that AppKit's own hit test stops at the pushed array: three points
        // inside three different grandchildren all resolved to their grandparent, whether the
        // intervening children were stored with setAccessibilityChildren: or answered by our getter.
        // This is the fallback §13.21 keeps open, and it costs nothing that §2.2 has not already
        // priced: it goes on OUR class, not on the content view GLFW owns, so item 16 stays closed.
        if (Boolean.getBoolean("probe.hittest")) {
            HitTest hitTest = new HitTest() {
                @Override public long invoke(long self, long cmd, double x, double y) {
                    enterCallback("-accessibilityHitTest:");
                    hitTests++;
                    Node node = BY_ELEMENT.get(self);
                    if (node == null) return self;
                    long deepest = descend(node, x, y);
                    if (hitTests <= 8) {
                        log("-accessibilityHitTest:(" + x + "," + y + ") on " + node.key + " -> "
                                + (deepest == self ? node.key + " (self)" : BY_ELEMENT.get(deepest).key));
                    }
                    return deepest;
                }
            };
            CALLBACKS.add(hitTest);
            boolean addedHit = ObjCRuntime.class_addMethod(elementClass, sel("accessibilityHitTest:"),
                    hitTest.address(), hitTestEncoding);
            log("class_addMethod(accessibilityHitTest:, '" + hitTestEncoding + "') = " + addedHit);
        }
        ObjCRuntime.objc_registerClassPair(elementClass);
        log("LimnProbeElement class_addMethod(accessibilityChildren, '" + childrenEncoding + "') = " + added
                + "  respondsToSelector=" + ObjCRuntime.class_respondsToSelector(elementClass, sel("accessibilityChildren")));

        // (c) the tree
        Node root = buildTree();
        for (Node child : root.kids) materialise(child, contentView, elementClass);
        rebuildChildren(root, contentView);
        pushRoot(root);

        log("---- what the provider itself sees, before any client asks ----");
        dump(root, 0);

        if (Boolean.getBoolean("probe.selftest")) selfTest(root);

        JNI.invokePV(pool, poolPop);
        Files.writeString(commands, "");
        log("READY -- pid=" + ProcessHandle.current().pid() + ", serving AX for " + seconds + " s");

        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        long lastBeat = System.nanoTime();
        while (!GLFW.glfwWindowShouldClose(window) && System.nanoTime() < deadline) {
            long iterPool = JNI.invokeP(poolPush);
            GLFW.glfwPollEvents();
            JNI.invokePV(iterPool, poolPop);
            if (drainCommands(commands, root)) break;
            Thread.sleep(16);
            if (System.nanoTime() - lastBeat > 15_000_000_000L) {
                lastBeat = System.nanoTime();
                log("heartbeat: childReads=" + childReads + " callbacksOffMainThread=" + callbacksOffMainThread);
            }
        }
        log("exiting: childReads=" + childReads + " hitTests=" + hitTests + " callbacksOffMainThread=" + callbacksOffMainThread);
        CALLBACKS.forEach(Callback::free);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.exit(0);
    }

    /**
     * The deepest of a node's descendants whose SCREEN frame contains the point, or the node's own
     * element. Screen space is what AppKit passes to {@code accessibilityHitTest:}, and it is
     * bottom-left, which is the same space {@code accessibilityFrame} answers in -- so the
     * comparison needs no flip of ours (§1.8: AppKit owns the flip).
     */
    static long descend(Node node, double x, double y) {
        for (Node kid : node.kids) {
            if (kid.released) continue;
            double[] f = msgGetRect(kid.element, "accessibilityFrame");
            if (x >= f[0] && x < f[0] + f[2] && y >= f[1] && y < f[1] + f[3]) {
                return descend(kid, x, y);
            }
        }
        return node.element;
    }

    /** True while an AX callback of ours is on the stack. §3.2's reentrancy flag, in miniature. */
    private static boolean REENTRANT_GUARD;
    private static final List<String> pendingDestroy = new ArrayList<>();

    private static int hitTests;
    private static int childReads;
    private static long elementClassGlobal;

    /**
     * Builds the ObjC element for a node and its whole subtree. The frame goes in through
     * {@code setAccessibilityFrameInParentSpace:} with the node's PARENT as its accessibility
     * parent -- which is exactly the question §13.21 leaves open for a nested element, since the
     * spike only ever had children of the content view.
     */
    static void materialise(Node node, long parent, long elementClass) {
        elementClassGlobal = elementClass;
        node.element = msg(msg(elementClass, "alloc"), "init");
        BY_ELEMENT.put(node.element, node);
        BY_KEY.put(node.key, node);
        msgV(node.element, "setAccessibilityRole:", roleConstant(node.role));
        // Finding 5: CONTENT-sourced names are titles, never both. Every name here is the widget's
        // own text, so every one is a title.
        msgV(node.element, "setAccessibilityTitle:", nsString(node.name));
        msgV(node.element, "setAccessibilityIdentifier:", nsString(node.key));
        msgV(node.element, "setAccessibilityParent:", parent);
        msgV(node.element, "setAccessibilityEnabled:", 1L);
        msgRect(node.element, "setAccessibilityFrameInParentSpace:",
                node.frame[0], node.frame[1], node.frame[2], node.frame[3]);
        for (Node kid : node.kids) materialise(kid, node.element, elementClass);
        rebuildChildren(node, node.element);
        if (PUSH_EVERY_LEVEL) msgV(node.element, "setAccessibilityChildren:", node.childrenArray);
    }

    /** (Re)builds the retained NSArray a node's -accessibilityChildren answers with. */
    static void rebuildChildren(Node node, long owner) {
        long array = msg(cls("NSMutableArray"), "array");
        for (Node kid : node.kids) {
            if (!kid.released) msgV(array, "addObject:", kid.element);
        }
        long retained = msg(array, "retain");
        if (node.childrenArray != NULL) msg(node.childrenArray, "release");
        node.childrenArray = retained;
    }

    /** §2.2's push: the root's children onto the content view, and nothing deeper. */
    static void pushRoot(Node root) {
        msgV(contentView, "setAccessibilityChildren:", root.childrenArray);
        long readBack = msg(contentView, "accessibilityChildren");
        long count = readBack == NULL ? -1 : msg(readBack, "count");
        log("pushed root's children onto contentView: setAccessibilityChildren: -> count=" + count);
    }

    static void dump(Node node, int depth) {
        String indent = "  ".repeat(depth);
        if (node.element == NULL) {
            log(indent + node.key + " (root, elided on macOS -- AppKit vends the window)");
        } else {
            double[] parentSpace = msgGetRect(node.element, "accessibilityFrameInParentSpace");
            double[] screen = msgGetRect(node.element, "accessibilityFrame");
            log(indent + node.key + " role='" + javaString(msg(node.element, "accessibilityRole"))
                    + "' title='" + javaString(msg(node.element, "accessibilityTitle")) + "'"
                    + " codePoints=" + codePoints(javaString(msg(node.element, "accessibilityTitle")))
                    + " parentSpace=" + Arrays.toString(parentSpace)
                    + " screen=" + Arrays.toString(screen));
        }
        for (Node kid : node.kids) if (!kid.released) dump(kid, depth + 1);
    }

    /** The name printed as scalars, so a mangled astral pair is visible rather than merely ugly. */
    static String codePoints(String s) {
        if (s == null) return "<null>";
        StringBuilder b = new StringBuilder("[");
        s.codePoints().forEach(cp -> b.append(b.length() > 1 ? " " : "").append("U+")
                .append(String.format("%04X", cp)));
        return b.append("]").toString();
    }

    /**
     * Proves the ObjC half without the AX server: sends the selectors straight to the elements. It
     * survives a locked console, which is where a run that cannot vend windows still has to say
     * something true.
     */
    static void selfTest(Node root) {
        log("---- self-test: the pull, invoked directly through objc_msgSend ----");
        for (Node node : BY_ELEMENT.values()) {
            long array = msg(node.element, "accessibilityChildren");
            long count = array == NULL ? -1 : msg(array, "count");
            log("   " + node.key + " -accessibilityChildren count=" + count + " (expected " + node.kids.size() + ")"
                    + (count == node.kids.size() ? "   PASS" : "   FAIL"));
        }
        log("---- self-test: names survive the NSString round trip (mangle=" + MANGLE + ") ----");
        int intact = 0;
        for (Node node : BY_ELEMENT.values()) {
            String back = javaString(msg(node.element, "accessibilityTitle"));
            boolean same = node.name.equals(back);
            if (same) intact++;
            log("   " + node.key + " '" + node.name + "' -> '" + back + "'  " + (same ? "PASS" : "FAIL")
                    + "  " + codePoints(node.name) + " -> " + codePoints(back));
        }
        log("   " + intact + " of " + BY_ELEMENT.size() + " names intact"
                + (MANGLE ? "   (probe.mangle=true: fewer than all is the EXPECTED result)" : ""));
        log("---- self-test done ----");
    }

    /**
     * Adds a child under {@code parentKey}. The frame is stacked below the parent's existing
     * children so a new node is somewhere a hit test can find it.
     */
    static void add(Node root, String parentKey, String key) {
        Node parent = parentKey.equals("root") ? root : BY_KEY.get(parentKey);
        if (parent == null) { log("!!! no such parent: " + parentKey); return; }
        Node child = new Node(key, "NSAccessibilityButtonRole", "Novo " + key,
                10, 10 + 30L * parent.kids.size(), 120, 24);
        parent.kids.add(child);
        materialise(child, parent.element == NULL ? contentView : parent.element, elementClassGlobal);
        afterStructuralChange(root, parent);
    }

    /**
     * Removes a node from its parent. It does not yet release the element -- that is §13.20 and it
     * has its own increment; this one is only about whether the platform sees the shape change.
     */
    static void remove(Node root, String key) {
        Node parent = parentOf(root, key);
        if (parent == null) { log("!!! no such node, or it is the root: " + key); return; }
        parent.kids.removeIf(kid -> kid.key.equals(key));
        afterStructuralChange(root, parent);
    }

    /**
     * §1.3's lifetime rule, which the spike never exercised because it retained everything and
     * released nothing: <b>unlink, post {@code UIElementDestroyed}, then release</b>.
     *
     * <p>The order is the whole of it. Posting after the release would name an object that is
     * already gone, and releasing without posting leaves a client holding a reference it has no way
     * to learn is stale — which is the crash vector §13.9 calls the obvious one.
     */
    static void destroy(Node root, String key) {
        Node node = BY_KEY.get(key);
        Node parent = parentOf(root, key);
        if (node == null || parent == null) { log("!!! no such node: " + key); return; }
        if (REENTRANT_GUARD) {
            // §3.2: from inside an AX callback this releases nothing at all. The caller is standing
            // on the object.
            log("    destroy('" + key + "') DEFERRED: an AX callback is on the stack");
            pendingDestroy.add(key);
            return;
        }
        parent.kids.removeIf(kid -> kid.key.equals(key));
        afterStructuralChange(root, parent);
        long element = node.element;
        // probe.nopost is the rule broken on purpose: release the object and tell nobody. §1.3 says
        // post first, and a rule with no observed consequence is a preference.
        if (Boolean.getBoolean("probe.nopost")) {
            log("    probe.nopost=true: NOT posting UIElementDestroyed for '" + key + "'");
        } else {
            JNI.invokePPV(element, destroyedNotification, postNotification);
            log("    posted UIElementDestroyed for '" + key + "' (element=0x" + Long.toHexString(element) + ")");
        }
        node.released = true;
        BY_ELEMENT.remove(element);
        if (node.childrenArray != NULL) { msg(node.childrenArray, "release"); node.childrenArray = NULL; }
        msg(element, "release");
        node.element = NULL;
        log("    released '" + key + "'; the client's reference is now stale");
    }

    /**
     * §13.27: hands a node a relation whose target is <b>AppKit's own window object</b>, and lets a
     * client try to resolve it.
     *
     * <p>This is the one case §2.2's elision leaves open. The window root is not vended here,
     * because AppKit already vends the window; §1.11 drops any relation that resolves to a window
     * root in the in-scene mounting; and what is left is a native popup or dialog whose
     * {@code POPUP_FOR} names the owner window's root. The design answers that with the object
     * AppKit vends for the window, and that was reasoning rather than measurement — no relation of
     * any kind had crossed this boundary.
     *
     * <p>{@code accessibilityLinkedUIElements} is the general relation attribute on this platform
     * and the one whose encoding the dump script records, so it is what the question is asked with:
     * if a client cannot follow a link to the window object, no relation can name it and the
     * fallback is to drop the relation and emit no mirror.
     *
     * @param key the node to hang the relation on
     */
    static void relate(String key) {
        Node node = BY_KEY.get(key);
        if (node == null || node.element == NULL) { log("!!! no such node: " + key); return; }
        long array = msg(cls("NSMutableArray"), "array");
        msgV(array, "addObject:", nsWindow);
        msgV(node.element, "setAccessibilityLinkedUIElements:", msg(array, "retain"));
        long readBack = msg(node.element, "accessibilityLinkedUIElements");
        long count = readBack == NULL ? -1 : msg(readBack, "count");
        log("    '" + key + "' AXLinkedUIElements <- [the NSWindow AppKit vends]  count=" + count
                + " first=" + (count > 0
                    ? ObjCRuntime.object_getClassName(msgIdx(readBack, "objectAtIndex:", 0)) : "-"));
    }

    static Node parentOf(Node node, String key) {
        for (Node kid : node.kids) {
            if (kid.key.equals(key)) return node;
            Node found = parentOf(kid, key);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The whole of §2.2's re-push rule in one place: a node's own children array is rebuilt, and
     * <b>if the node whose children changed is the root, the array is pushed onto the content view
     * again</b>. Everything deeper needs nothing, because everything deeper is a pull.
     *
     * <p>{@code probe.norepush} skips the push so the case can be watched failing. That is not
     * decoration: "the pushed array is a snapshot AppKit holds and nothing re-derives it" is a
     * claim, and a claim about a platform is worth exactly one run.
     */
    static void afterStructuralChange(Node root, Node changed) {
        rebuildChildren(changed, changed.element == NULL ? contentView : changed.element);
        if (PUSH_EVERY_LEVEL && changed.element != NULL) {
            msgV(changed.element, "setAccessibilityChildren:", changed.childrenArray);
        }
        if (changed != root) {
            log("    children of '" + changed.key + "' rebuilt; nothing pushed (it is a pull)");
            return;
        }
        if (Boolean.getBoolean("probe.norepush")) {
            log("    root's children changed and probe.norepush=true: NOT re-pushed");
            return;
        }
        pushRoot(root);
    }

    /** The driver appends a line, the probe does it and truncates. Returns true to quit. */
    static boolean drainCommands(Path file, Node root) throws Exception {
        if (!Files.exists(file)) return false;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.isBlank()) return false;
        Files.writeString(file, "");
        for (String line : text.strip().split("\n")) {
            String command = line.strip();
            if (command.isEmpty()) continue;
            log(">>> command: " + command);
            String[] words = command.split("\\s+");
            switch (words[0]) {
                case "quit" -> { return true; }
                case "dump" -> dump(root, 0);
                case "add" -> add(root, words[1], words[2]);
                case "remove" -> remove(root, words[1]);
                case "destroy" -> destroy(root, words[1]);
                case "relation" -> relate(words[1]);
                default -> log("!!! unknown command: " + command);
            }
        }
        return false;
    }
}
