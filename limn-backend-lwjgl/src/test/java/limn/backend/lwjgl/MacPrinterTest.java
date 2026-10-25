package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.PrintResult;
import limn.backend.lwjgl.internal.ObjC;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A macOS print request answers once and lets go of the objects it held for its panel on every
 * path. No panel is shown: the panel is handed in, and three plain {@code NSObject}s stand for the
 * operation, the document and the print info, so their retain counts say what was released.
 */
class MacPrinterTest {

    /** A plain object at a retain count of 1, which the request owns, and 1 more, the test's. */
    private static long held() {
        long object = ObjC.msg(ObjC.msg(ObjC.cls("NSObject"), "alloc"), "init");
        ObjC.msg(object, "retain");
        return object;
    }

    private static long retainCount(long object) {
        return ObjC.msg(object, "retainCount");
    }

    /**
     * A panel run as a window of its own whose run throws an Error answers FAILED and lets go of
     * its objects. The task caught exceptions only, so an Error left it before the answer: the
     * window kept believing its dialog open and answered every later print CANCELLED.
     */
    @Test
    void aPanelOfItsOwnThatThrowsAnErrorAnswersFailedAndLetsGoOfItsObjects() {
        assumeTrue(Platform.current().isMacOs(), "AppKit's objects");
        try (HeadlessUi ui = new HeadlessUi()) {
            long[] objects = {held(), held(), held()};
            int waiting = MacPrinter.pendingCount();
            AtomicReference<PrintResult> answer = new AtomicReference<>();
            MacPrinter.start(objects[0], objects[1], objects[2], MacFileDialogs.NO_OWNER, 0L, answer::set,
                    new MacPrinter.Panel() {
                        @Override
                        public void sheet(long operation, long nsWindow, long context) {
                            fail("no sheet without a window on screen");
                        }

                        @Override
                        public boolean alone(long operation) {
                            throw new Error("while the panel ran");
                        }
                    });
            ui.pumpUntil(() -> answer.get() != null);
            assertEquals(PrintResult.FAILED, answer.get());
            assertEquals(waiting, MacPrinter.pendingCount(), "no request left waiting");
            for (long object : objects) {
                assertEquals(1, retainCount(object), "released once by the request");
                ObjC.msgVoid(object, "release");
            }
        }
    }

    /**
     * A sheet whose start throws lets go of its entry and its three objects, and hands the throw
     * on for the window's printer to answer FAILED. They waited for a delegate call that could
     * never come, for the life of the process. The window's pointer is never messaged: no sheet
     * took the keyboard from it.
     */
    @Test
    void aSheetThatCannotBeStartedLetsGoOfItsEntryAndItsObjects() {
        assumeTrue(Platform.current().isMacOs(), "AppKit's objects");
        try (HeadlessUi ui = new HeadlessUi()) {
            long[] objects = {held(), held(), held()};
            long window = 0x7F00_1234_5000L; // a pointer nothing may message
            int waiting = MacPrinter.pendingCount();
            AtomicReference<PrintResult> answer = new AtomicReference<>();
            Error thrown = assertThrows(Error.class, () -> MacPrinter.start(objects[0], objects[1], objects[2],
                    () -> window, window, answer::set, new MacPrinter.Panel() {
                        @Override
                        public void sheet(long operation, long nsWindow, long context) {
                            throw new Error("runOperationModalForWindow: threw");
                        }

                        @Override
                        public boolean alone(long operation) {
                            return fail("a window on screen carries a sheet");
                        }
                    }));
            assertEquals("runOperationModalForWindow: threw", thrown.getMessage());
            assertEquals(waiting, MacPrinter.pendingCount(), "no entry left waiting for the delegate");
            for (long object : objects) {
                assertEquals(1, retainCount(object), "released once by the request");
                ObjC.msgVoid(object, "release");
            }
            ui.runtime().drain();
            assertNull(answer.get(), "the window's printer answers it, from the throw");
        }
    }
}
