package limn.backend.lwjgl;

import limn.backend.lwjgl.transfer.Ffi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Native regression checks; also runnable from the probe jar on the ARM64 guest. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "os.arch", matches = "aarch64")
class WindowsFileDialogNativeTest {
    private static final class Native {
        static final SharedLibrary USER = APIUtil.apiCreateLibrary("user32");
        static final SharedLibrary CONTROLS = APIUtil.apiCreateLibrary("comctl32");
        static final SharedLibrary OLE = APIUtil.apiCreateLibrary("ole32");
    }

    public static void main(String[] args) throws Exception {
        assertTrue(WindowsFileDialogThread.PERSISTENT);
        WindowsFileDialogNativeTest test = new WindowsFileDialogNativeTest();
        test.repairsRealTreeMessagesAndDetachesBeforeFreeingCallbacks();
        System.out.println("PASS native tree: truncation, sentinels, deletion, detach");
        test.reusesPumpingApartmentsAndAllowsConcurrentRequests();
        System.out.println("PASS native workers: reuse, concurrent requests, COM apartment, message pump");
    }

    @Test
    @SuppressWarnings("try")
    void repairsRealTreeMessagesAndDetachesBeforeFreeingCallbacks() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long init = stack.ncalloc(4, 1, 8);
            MemoryUtil.memPutInt(init, 8);
            MemoryUtil.memPutInt(init + 4, 2); // ICC_TREEVIEW_CLASSES
            assertNotEquals(0, Ffi.call(Native.CONTROLS.getFunctionAddress("InitCommonControlsEx"), "p>i", init));
            long parent = create(stack, "STATIC", 0, 0);
            assertNotEquals(0, parent);
            long tree = 0;
            try {
                try (WindowsTreeViewGuard guard = WindowsTreeViewGuard.install()) {
                    tree = create(stack, "SysTreeView32", 0x40000000, parent);
                    assertNotEquals(0, tree);
                    long insertion = stack.ncalloc(8, 1, 96);
                    MemoryUtil.memPutAddress(insertion, -0x10000L);
                    MemoryUtil.memPutAddress(insertion + 8, -0xfffeL);
                    MemoryUtil.memPutInt(insertion + 16, 9); // TVIF_TEXT | TVIF_STATE
                    MemoryUtil.memPutInt(insertion + 32, 0x1000);
                    MemoryUtil.memPutInt(insertion + 36, 0xf000);
                    MemoryUtil.memPutAddress(insertion + 40, MemoryUtil.memAddress(stack.UTF16("guard test")));
                    long item = send(tree, 0x1132, 0, insertion);
                    assertNotEquals(0, item);
                    assertNotEquals(0, item >>> 32, "test requires a handle above 4 GB");
                    long low = item & 0xffffffffL;
                    long signed = (int) item;
                    assertEquals(item, send(tree, 0x110a, 4, -0x10000L)); // TVGN_CHILD of TVI_ROOT
                    long accessible = send(tree, 0x112b, item, 0);
                    assertNotEquals(0, accessible);
                    assertEquals(accessible, send(tree, 0x112b, low, 0));
                    assertEquals(accessible, send(tree, 0x112b, signed, 0));
                    assertEquals(0x1000, send(tree, 0x1127, low, 0xf000));
                    assertEquals(0x1000, send(tree, 0x1127, signed, 0xf000));
                    assertEquals(0, send(tree, 0x110a, 1, low));
                    assertEquals(0, send(tree, 0x110a, 1, signed));
                    assertNotEquals(0, send(tree, 0x1101, 0, item)); // Delete it.
                    assertEquals(0, send(tree, 0x112b, low, 0));
                    assertEquals(0, send(tree, 0x1127, signed, 0xf000));
                }
                // The tree still exists after close(): no callback may remain in its subclass chain.
                assertEquals(0, send(tree, 0x1105, 0, 0)); // TVM_GETCOUNT
            } finally {
                Ffi.call(Native.USER.getFunctionAddress("DestroyWindow"), "p>i", parent);
            }
        }
    }

    @Test
    void reusesPumpingApartmentsAndAllowsConcurrentRequests() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        Set<Thread> concurrent = java.util.Collections.synchronizedSet(new HashSet<>());
        try {
            for (int i = 0; i < 2; i++) {
                WindowsFileDialogThread.execute(() -> {
                    concurrent.add(Thread.currentThread());
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(2, concurrent.size());
        } finally {
            release.countDown();
        }
        // Let both tasks publish their idle state before checking reuse.
        Thread.sleep(100);
        for (int i = 0; i < 30; i++) {
            CompletableFuture<Thread> answer = new CompletableFuture<>();
            WindowsFileDialogThread.execute(() -> {
                try {
                    WindowsFileDialogThread.initialize(Native.OLE.getFunctionAddress("CoInitializeEx"));
                    WindowsFileDialogThread.initialize(Native.OLE.getFunctionAddress("CoInitializeEx"));
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        long type = stack.ncalloc(4, 1, 4);
                        long qualifier = stack.ncalloc(4, 1, 4);
                        assertEquals(0, Ffi.call(Native.OLE.getFunctionAddress("CoGetApartmentType"),
                                "pp>i", type, qualifier));
                        assertTrue(MemoryUtil.memGetInt(type) == 0 || MemoryUtil.memGetInt(type) == 3);
                    }
                    answer.complete(Thread.currentThread());
                } catch (Throwable failure) {
                    answer.completeExceptionally(failure);
                }
            });
            Thread worker = answer.get(5, TimeUnit.SECONDS);
            assertTrue(concurrent.contains(worker), "an idle thread must be reused");
            assertTrue(worker.isDaemon());
            Thread.sleep(10);
        }
    }

    private static long create(MemoryStack stack, String name, int style, long parent) {
        return Ffi.call(Native.USER.getFunctionAddress("CreateWindowExW"), "ippiiiiipppp>p", 0,
                MemoryUtil.memAddress(stack.UTF16(name)), 0, style, 0, 0, 100, 100, parent, 0, 0, 0);
    }

    private static long send(long tree, int message, long w, long l) {
        return Ffi.call(Native.USER.getFunctionAddress("SendMessageW"), "pipp>p", tree, message, w, l);
    }
}
