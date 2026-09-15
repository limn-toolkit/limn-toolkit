package limn.backend.lwjgl.a11y.macos;

import limn.backend.lwjgl.ObjC;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * The whole of this module's contact with the Objective-C runtime: sending messages, making
 * strings, passing a {@code CGRect} by value, and reading a constant out of AppKit.
 *
 * <p>There is no native code of ours anywhere here. A message send is
 * {@code objc_msgSend} reached through LWJGL's JNI trampoline ({@link ObjC}); a struct argument is libffi; a class
 * and a selector are the runtime's own functions (§10.2). The alternative was a shim with a build,
 * a signature and a platform matrix of its own, for a handful of calls.
 *
 * <p><b>Every constant is read, never remembered</b> (§12.3). A role is an {@code NSString} global
 * on this platform, so {@link #constant} resolves the symbol and fails loudly when it is absent
 * rather than answering a null that becomes an attribute a reader silently never hears.
 */
final class AxObjC {

    private final SharedLibrary appKit;
    private final long postNotification;
    private final long postNotificationWithUserInfo;
    private final FFICIF rectSetterCif;
    private final FFICIF rectGetterCif;
    private final Map<String, Long> constants = new LinkedHashMap<>();

    private AxObjC(SharedLibrary appKit) {
        this.appKit = appKit;
        this.postNotification = appKit.getFunctionAddress("NSAccessibilityPostNotification");
        this.postNotificationWithUserInfo =
                appKit.getFunctionAddress("NSAccessibilityPostNotificationWithUserInfo");
        this.rectSetterCif = APIUtil.apiCreateCIF(
                LibFFI.ffi_type_void, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, doubles(4));
        this.rectGetterCif = APIUtil.apiCreateCIF(
                doubles(4), LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer);
    }

    /**
     * @return a runtime bound to the running AppKit, or {@code null} where there is none — which is
     *         every machine that is not a Mac, and is the answer that keeps this module's own tests
     *         runnable everywhere
     */
    static AxObjC openOrNull() {
        if (!ObjC.isAvailable()) return null;
        try {
            SharedLibrary appKit = APIUtil.apiCreateLibrary(
                    "/System/Library/Frameworks/AppKit.framework/AppKit");
            return new AxObjC(appKit);
        } catch (Throwable notAMac) {
            return null;
        }
    }

    /** An FFI struct of n unsigned 64-bit integers: an {@code NSRange}, returned in x0 and x1. */
    static FFIType uint64s(int n) {
        PointerBuffer elements = org.lwjgl.system.MemoryUtil.memAllocPointer(n + 1);
        for (int i = 0; i < n; i++) elements.put(i, LibFFI.ffi_type_uint64.address());
        elements.put(n, NULL);
        FFIType type = FFIType.calloc();
        type.type(LibFFI.FFI_TYPE_STRUCT);
        type.elements(elements);
        return type;
    }

    /** An FFI struct of n doubles. arm64 passes a CGPoint and a CGRect as HFAs in d0..d3. */
    static FFIType doubles(int n) {
        PointerBuffer elements = org.lwjgl.system.MemoryUtil.memAllocPointer(n + 1);
        for (int i = 0; i < n; i++) elements.put(i, LibFFI.ffi_type_double.address());
        elements.put(n, NULL);
        FFIType type = FFIType.calloc();
        type.type(LibFFI.FFI_TYPE_STRUCT);
        type.elements(elements);   // ffi_prep_cif fills in size and alignment from these
        return type;
    }

    /**
     * An <b>autoreleased</b> {@code NSString} from UTF-8.
     *
     * <p>Autoreleased and not retained, because every one of these is returned straight out of an
     * accessibility callback, where the convention is that the caller does not own what a getter
     * hands back and AppKit's own pool is on the stack. The bytes are explicitly UTF-8: the phase 7
     * probe run measured that {@code stringWithUTF8String:} answers <b>nil</b> for invalid UTF-8, so
     * a bridge that took the platform default encoding would produce nameless nodes rather than
     * mangled ones (§13.23).
     *
     * @param value the text
     * @return the string, or zero when {@code value} is null
     */
    long string(String value) {
        if (value == null) return NULL;
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(utf8.length + 1);
            buffer.put(utf8).put((byte) 0).flip();
            return ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:", memAddress(buffer));
        }
    }

    /**
     * @param nsString an NSString
     * @return its text, for logging and for the probe's own assertions
     */
    String javaString(long nsString) {
        if (nsString == NULL) return null;
        long utf8 = ObjC.msg(nsString, "UTF8String");
        return utf8 == NULL ? null : memUTF8(utf8);
    }

    /** An autoreleased {@code NSNumber} carrying a C {@code NSInteger}. */
    long number(long value) {
        return ObjC.msg(ObjC.cls("NSNumber"), "numberWithInteger:", value);
    }

    /** An autoreleased, empty {@code NSMutableArray}. */
    long mutableArray() {
        return ObjC.msg(ObjC.cls("NSMutableArray"), "array");
    }

    void addObject(long array, long object) {
        ObjC.msgVoid(array, "addObject:", object);
    }

    /**
     * {@code void objc_msgSend(id, SEL, NSRect)} through libffi: no {@code JNI.invoke*} overload
     * takes four doubles after two pointers, and on arm64 they travel in d0..d3 rather than on the
     * stack.
     *
     * @param self     the receiver
     * @param selector a selector taking one CGRect
     * @param rect     x, y, width, height
     */
    void msgRect(long self, String selector, double[] rect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer storage = stack.malloc(8 + 8 + 32);
            storage.putLong(0, self).putLong(8, ObjC.sel(selector))
                   .putDouble(16, rect[0]).putDouble(24, rect[1])
                   .putDouble(32, rect[2]).putDouble(40, rect[3]);
            long base = memAddress(storage);
            PointerBuffer values = stack.mallocPointer(3);
            values.put(0, base).put(1, base + 8).put(2, base + 16);
            LibFFI.ffi_call(rectSetterCif, ObjC.msgSend(), stack.malloc(8), values);
        }
    }

    /**
     * {@code NSRect objc_msgSend(id, SEL)} through libffi; the HFA comes back in d0..d3 and libffi
     * lands it in the result buffer.
     *
     * <p>This is how the hit test asks AppKit where a child actually is, rather than recomputing it
     * from the snapshot: the frames AppKit answers with are the ones it hit-tested the top level
     * against, and deriving a second set of numbers to compare against them is how the two drift.
     *
     * @param self     the receiver
     * @param selector a selector returning a CGRect
     * @return x, y, width, height in the space that selector answers in
     */
    double[] msgGetRect(long self, String selector) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer storage = stack.malloc(16);
            storage.putLong(0, self).putLong(8, ObjC.sel(selector));
            long base = memAddress(storage);
            PointerBuffer values = stack.mallocPointer(2);
            values.put(0, base).put(1, base + 8);
            ByteBuffer rect = stack.malloc(32);
            LibFFI.ffi_call(rectGetterCif, ObjC.msgSend(), rect, values);
            return new double[] { rect.getDouble(0), rect.getDouble(8),
                                  rect.getDouble(16), rect.getDouble(24) };
        }
    }

    /**
     * An {@code NSString} global exported by AppKit, resolved once and remembered by symbol.
     *
     * @param symbol e.g. {@code NSAccessibilityButtonRole}
     * @return the string object
     * @throws IllegalStateException when AppKit does not export it, which is a name this repository
     *                               invented rather than a name the platform has since dropped —
     *                               and on this platform an unresolved role is silence, not an error
     */
    long constant(String symbol) {
        Long cached = constants.get(symbol);
        if (cached != null) return cached;
        long address = appKit.getFunctionAddress(symbol);
        if (address == NULL) {
            throw new IllegalStateException("AppKit exports no " + symbol
                    + "; run scripts/a11y/macos/dump-appkit-constants.swift and fix the table");
        }
        long value = org.lwjgl.system.MemoryUtil.memGetAddress(address);
        constants.put(symbol, value);
        return value;
    }

    /**
     * The type encoding AppKit itself declares for a selector, searched across the classes that
     * might declare it.
     *
     * <p>Searched and not asked of one class, because the phase 7 probe run found that
     * {@code NSAccessibilityElement} does not declare every selector this design implements —
     * {@code accessibilityFocusedUIElement} is {@code NSView}'s. An encoding is a fact about the
     * runtime rather than about the class it was read from, so the first class that declares it
     * gives the answer.
     *
     * @param selector the selector
     * @return its encoding, or {@code null} when no class declares it — which the caller answers by
     *         not installing the selector, because the alternative is guessing (MACOS-NEW-6)
     */
    String encodingOrNull(String selector) {
        for (String className : new String[] {
                "NSAccessibilityElement", "NSView", "NSWindow", "NSResponder", "NSApplication" }) {
            long c = ObjC.cls(className);
            if (c == NULL) continue;
            long method = ObjCRuntime.class_getInstanceMethod(c, ObjC.sel(selector));
            if (method != NULL) return ObjCRuntime.method_getTypeEncoding(method);
        }
        return null;
    }

    /**
     * @param element      what the notification is about
     * @param notification the notification name, already resolved
     */
    void post(long element, long notification) {
        if (postNotification == NULL) return;
        JNI.invokePPV(element, notification, postNotification);
    }

    /**
     * @param element      what the notification is about
     * @param notification the notification name, already resolved
     * @param userInfo     an NSDictionary, for the announcement's text and priority
     */
    void post(long element, long notification, long userInfo) {
        if (postNotificationWithUserInfo == NULL) return;
        JNI.invokePPPV(element, notification, userInfo, postNotification == NULL
                ? postNotificationWithUserInfo : postNotificationWithUserInfo);
    }
}
