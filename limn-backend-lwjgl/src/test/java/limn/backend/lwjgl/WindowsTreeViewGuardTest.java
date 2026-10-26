package limn.backend.lwjgl;

import limn.backend.lwjgl.transfer.Ffi;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.Callback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WindowsTreeViewGuardTest {
    @Test
    void repairsBothWow64ExtensionsIncludingNestedAndLaterRootItems() {
        Tree tree = new Tree();
        long root = 0x1234_01020304L;
        long child = 0x1234_81234567L;
        long secondRoot = 0x1234_12345678L;
        tree.add(0, root);
        tree.add(root, child);
        tree.add(0, secondRoot);
        assertEquals(child, WindowsTreeViewGuard.resolve(child & 0xffffffffL, tree));
        assertEquals(child, WindowsTreeViewGuard.resolve((int) child, tree));
        assertEquals(secondRoot, WindowsTreeViewGuard.resolve(secondRoot & 0xffffffffL, tree));
    }

    @Test
    void refusesDeletedUnknownAndAmbiguousItems() {
        Tree tree = new Tree();
        long first = 0x1234_81234567L;
        tree.add(0, first);
        assertEquals(0, WindowsTreeViewGuard.resolve(42, tree));
        tree.children.get(0L).clear();
        assertEquals(0, WindowsTreeViewGuard.resolve((int) first, tree));
        tree.add(0, first);
        tree.add(0, 0x5678_81234567L);
        assertEquals(0, WindowsTreeViewGuard.resolve((int) first, tree));
    }

    @Test
    void walksPastTheProbesSixHundredItemLimit() {
        Tree tree = new Tree();
        long last = 0;
        for (int i = 1; i <= 800; i++) {
            long item = 0x1234_80000000L + i;
            tree.add(last, item);
            last = item;
        }
        assertEquals(last, WindowsTreeViewGuard.resolve((int) last, tree));
    }

    @Test
    void preservesNullFullHandlesAndAllControlSentinels() {
        assertFalse(WindowsTreeViewGuard.truncated(0));
        assertFalse(WindowsTreeViewGuard.truncated(0x1234_81234567L));
        for (long sentinel = -0x10000L; sentinel <= -0xfffdL; sentinel++) {
            assertFalse(WindowsTreeViewGuard.truncated(sentinel));
        }
        assertTrue(WindowsTreeViewGuard.truncated(0x81234567L));
        assertTrue(WindowsTreeViewGuard.truncated((int) 0x81234567L));
    }

    @Test
    void onlyInterpretsItemRelativeMessagesAsHandles() {
        assertEquals(1, WindowsTreeViewGuard.carrier(0x1127, 0));
        assertEquals(1, WindowsTreeViewGuard.carrier(0x112b, 0));
        for (int flag : new int[]{1, 2, 3, 4, 6, 7, 0xb}) {
            assertEquals(2, WindowsTreeViewGuard.carrier(0x110a, flag));
        }
        for (int flag : new int[]{0, 5, 8, 9, 10}) {
            assertEquals(0, WindowsTreeViewGuard.carrier(0x110a, flag));
        }
        assertEquals(0, WindowsTreeViewGuard.carrier(0x113e, 0));
    }

    @Test
    void nativeCallbackShapesPreservePointerWidthsAndSignedHookCode() {
        WindowsTreeViewGuard.Hook hook = (code, w, l) -> {
            assertEquals(-1, code);
            assertEquals(0x1234_87654321L, w);
            return l;
        };
        long hookAddress = hook.address();
        WindowsTreeViewGuard.Subclass subclass = (hwnd, msg, w, l, id, ref) -> {
            assertEquals(0x1234_12345678L, hwnd);
            assertEquals(0x1127, msg);
            assertEquals(0x5678_12345678L, w);
            assertEquals(-42L, l);
            assertEquals(0x9876_12345678L, id);
            return ref;
        };
        long subclassAddress = subclass.address();
        try {
            assertEquals(-77L, Ffi.call(hookAddress, "ipp>p", -1, 0x1234_87654321L, -77));
            assertEquals(0x4567_87654321L, Ffi.call(subclassAddress, "pipppp>p",
                    0x1234_12345678L, 0x1127, 0x5678_12345678L, -42, 0x9876_12345678L, 0x4567_87654321L));
        } finally {
            Callback.free(hookAddress);
            Callback.free(subclassAddress);
        }
    }

    private static final class Tree implements WindowsTreeViewGuard.Navigation {
        final Map<Long, List<Long>> children = new HashMap<>();
        final Map<Long, Long> parents = new HashMap<>();

        void add(long parent, long item) {
            children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(item);
            parents.put(item, parent);
        }

        @Override
        public long next(long flag, long item) {
            if (flag == 0 || flag == 4) {
                List<Long> items = children.getOrDefault(flag == 0 ? 0 : item, List.of());
                return items.isEmpty() ? 0 : items.get(0);
            }
            if (flag == 3) {
                return parents.get(item);
            }
            List<Long> siblings = children.get(parents.get(item));
            int next = siblings.indexOf(item) + 1;
            return next == siblings.size() ? 0 : siblings.get(next);
        }
    }
}
