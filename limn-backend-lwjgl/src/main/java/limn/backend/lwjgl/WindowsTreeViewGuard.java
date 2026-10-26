package limn.backend.lwjgl;

import limn.backend.lwjgl.a11y.ClosureArgs;
import limn.backend.lwjgl.transfer.Ffi;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.LibFFI;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.Set;

/**
 * Repairs the navigation tree's item handles truncated by a 32-bit accessibility client.
 * The ARM64 diagnostic of 2026-10-26 reproduced a fatal COMCTL32 access violation with both
 * zero- and sign-extended handles. Only live items obtained from the control itself may replace
 * them; a missing or ambiguous item answers zero. No exception handler or foreign process is changed.
 * The hook and subclasses belong exclusively to the calling dialog thread.
 *
 * <p>Active on every Windows build, not only ARM64: on x64 the truncated handle does not end the
 * process, but the control answers it with nothing and the reader announces each item as one of
 * one, while the repaired handle lets it read the item's place and state as a 64-bit reader does.
 */
final class WindowsTreeViewGuard implements AutoCloseable {
    static final int GET_NEXT = 0x110a;
    static final int GET_STATE = 0x1127;
    static final int TO_ACCESSIBLE_ID = 0x112b;
    private static final int WM_NCDESTROY = 0x82;
    private static final long SUBCLASS_ID = 0xA7E4;
    /** Whether dialogs get the guard: every Windows build (see the class comment). */
    static final boolean ACTIVE = limn.backend.Platform.current().isWindows();

    private static final class Native {
        static final SharedLibrary USER = APIUtil.apiCreateLibrary("user32");
        static final SharedLibrary CONTROLS = APIUtil.apiCreateLibrary("comctl32");
        static final SharedLibrary KERNEL = APIUtil.apiCreateLibrary("kernel32");
        static final long DEFAULT = CONTROLS.getFunctionAddress("DefSubclassProc");
        static final long REMOVE = CONTROLS.getFunctionAddress("RemoveWindowSubclass");
    }

    private final Set<Long> trees = new HashSet<>();
    private long hook;
    private long hookCallback;
    private long subclassCallback;
    private boolean inspecting;

    static WindowsTreeViewGuard install() {
        WindowsTreeViewGuard guard = new WindowsTreeViewGuard();
        if (!ACTIVE) {
            return guard;
        }
        try {
            guard.subclassCallback = ((Subclass) guard::onTree).address();
            guard.hookCallback = ((Hook) guard::onMessage).address();
            int thread = JNI.invokeI(Native.KERNEL.getFunctionAddress("GetCurrentThreadId"));
            guard.hook = Ffi.call(Native.USER.getFunctionAddress("SetWindowsHookExW"), "ippi>p",
                    4, guard.hookCallback, 0, thread);
            if (guard.hook == 0) {
                throw new IllegalStateException("could not install the file dialog's tree guard");
            }
            return guard;
        } catch (RuntimeException | Error failure) {
            guard.close();
            throw failure;
        }
    }

    private long onMessage(int code, long w, long l) {
        if (code == 0 && !inspecting) {
            inspecting = true;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // CWPSTRUCT: LPARAM, WPARAM, UINT, padding, HWND on the 64-bit Windows ABI.
                long hwnd = MemoryUtil.memGetAddress(l + 24);
                if (MemoryUtil.memGetInt(l + 16) != WM_NCDESTROY && !trees.contains(hwnd)) {
                    var name = stack.calloc(96);
                    int length = JNI.invokePPI(hwnd, MemoryUtil.memAddress(name), 48,
                            Native.USER.getFunctionAddress("GetClassNameW"));
                    if (length > 0 && "SysTreeView32".equals(MemoryUtil.memUTF16(name, length))) {
                        if (JNI.invokePPPPI(hwnd, subclassCallback, SUBCLASS_ID, 0L,
                                Native.CONTROLS.getFunctionAddress("SetWindowSubclass")) == 0) {
                            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                                    "could not guard a file dialog navigation tree");
                        } else {
                            trees.add(hwnd);
                        }
                    }
                }
            } catch (Throwable failure) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "could not guard a file dialog navigation tree", failure);
            } finally {
                inspecting = false;
            }
        }
        return JNI.invokePPPP(0L, code, w, l, Native.USER.getFunctionAddress("CallNextHookEx"));
    }

    private long onTree(long hwnd, int message, long w, long l, long id, long ref) {
        if (message == WM_NCDESTROY) {
            JNI.invokePPPI(hwnd, subclassCallback, SUBCLASS_ID, Native.REMOVE);
            trees.remove(hwnd);
        }
        int carrier = carrier(message, w);
        long value = carrier == 1 ? w : l;
        if (carrier != 0 && truncated(value)) {
            long repaired;
            try {
                // DefSubclassProc visits the rest of this control's chain without reentering
                // this guard, so no broad reentrancy bypass can forward an unvalidated handle.
                repaired = resolve(value, (flag, item) -> JNI.invokePPPP(hwnd, GET_NEXT, flag, item,
                        Native.DEFAULT));
            } catch (Throwable failure) {
                return 0; // Never forward the suspect handle after a failed lookup.
            }
            if (repaired == 0) {
                return 0;
            }
            if (carrier == 1) {
                w = repaired;
            } else {
                l = repaired;
            }
        }
        return JNI.invokePPPP(hwnd, message, w, l, Native.DEFAULT);
    }

    /** 1 for wParam, 2 for lParam, 0 for messages that carry no affected item handle. */
    static int carrier(int message, long w) {
        if (message == GET_STATE || message == TO_ACCESSIBLE_ID) {
            return 1;
        }
        if (message == GET_NEXT) {
            return switch ((int) w) {
                case 1, 2, 3, 4, 6, 7, 0xb -> 2;
                default -> 0;
            };
        }
        return 0;
    }

    static boolean truncated(long value) {
        // TVI_ROOT, TVI_FIRST, TVI_LAST and TVI_SORT are control sentinels, not truncated pointers.
        if (value == 0 || (value >= -0x10000L && value <= -0xfffdL)) {
            return false;
        }
        long high = value >>> 32;
        return high == 0 || high == 0xffffffffL;
    }

    @FunctionalInterface
    interface Navigation {
        long next(long flag, long item);
    }

    /** Walks all live items, without the probe's 600-item limit or a cache of deleted handles. */
    static long resolve(long value, Navigation tree) {
        long match = 0;
        long item = tree.next(0, 0); // TVGN_ROOT
        while (item != 0) {
            if ((item & 0xffffffffL) == (value & 0xffffffffL)) {
                if (match != 0 && match != item) {
                    return 0; // More than one live item has these low bits: do not guess.
                }
                match = item;
            }
            long child = tree.next(4, item);
            if (child != 0) {
                item = child;
                continue;
            }
            long sibling = tree.next(1, item);
            while (sibling == 0) {
                item = tree.next(3, item);
                if (item == 0) {
                    return match;
                }
                sibling = tree.next(1, item);
            }
            item = sibling;
        }
        return match;
    }

    @Override
    public void close() {
        // Only free executable callbacks after every native reference has been removed.
        boolean detached = true;
        if (hook != 0) {
            detached = JNI.invokePI(hook, Native.USER.getFunctionAddress("UnhookWindowsHookEx")) != 0;
            if (detached) {
                hook = 0;
            }
        }
        for (long tree : trees) {
            if (JNI.invokePPPI(tree, subclassCallback, SUBCLASS_ID, Native.REMOVE) == 0) {
                detached = false;
            }
        }
        trees.clear();
        if (detached) {
            if (hookCallback != 0) {
                Callback.free(hookCallback);
                hookCallback = 0;
            }
            if (subclassCallback != 0) {
                Callback.free(subclassCallback);
                subclassCallback = 0;
            }
        }
    }

    @FunctionalInterface
    interface Hook extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(Hook.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_sint32,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRetP(ret, invoke(ClosureArgs.int32(args, 0), ClosureArgs.pointer(args, 1),
                    ClosureArgs.pointer(args, 2)));
        }

        long invoke(int code, long w, long l);
    }

    @FunctionalInterface
    interface Subclass extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(Subclass.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint32,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRetP(ret, invoke(ClosureArgs.pointer(args, 0), ClosureArgs.int32(args, 1),
                    ClosureArgs.pointer(args, 2), ClosureArgs.pointer(args, 3),
                    ClosureArgs.pointer(args, 4), ClosureArgs.pointer(args, 5)));
        }

        long invoke(long hwnd, int message, long w, long l, long id, long ref);
    }
}
