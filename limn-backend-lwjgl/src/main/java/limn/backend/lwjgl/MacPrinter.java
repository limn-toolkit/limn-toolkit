package limn.backend.lwjgl;

import limn.backend.PrintResult;
import limn.backend.lwjgl.internal.ObjC;
import limn.backend.lwjgl.transfer.Ffi;
import limn.backend.spi.PrintJob;
import limn.concurrent.Ui;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * macOS's print panel over PDFKit: a {@code PDFDocument} made over the job's bytes,
 * its {@code -printOperationForPrintInfo:scalingMode:autoRotate:}, and that operation run as a sheet
 * on the window with {@code -runOperationModalForWindow:delegate:didRunSelector:contextInfo:}. The
 * call returns at once and the application keeps running; AppKit calls the delegate on the main
 * thread, which is the UI thread, when the panel is confirmed and the job spooled, or cancelled.
 *
 * <p><b>What the panel starts from.</b> A copy of the shared print info, so a panel's choices do not
 * leak into the next one through the shared object, with the job's paper when the job knows it, its
 * margins at zero (the document's margins are already on its pages) and the page centred on the
 * sheet. PDFKit scales a page down to the printer's paper and never up
 * ({@code kPDFPrintPageScaleDownToFit}, the third value of {@code PDFPrintScalingMode} in
 * {@code PDFDocument.h}), and turns a landscape page to the paper's orientation. The panel shows the
 * system's preview of the pages, the paper size and the orientation; its PDF button is the system's
 * "save as PDF".
 *
 * <p><b>The macOS 11 floor.</b> Every call here was read against the SDK's headers on 2026-10-22
 * (SDK 27.0): {@code PDFDocument} is {@code PDFKIT_CLASS_AVAILABLE(10_4, 11_0)},
 * {@code -initWithData:} carries no later mark, {@code -printOperationForPrintInfo:scalingMode:autoRotate:}
 * is {@code PDFKIT_AVAILABLE(10_7, NA)}; {@code NSPrintOperation}'s
 * {@code -runOperationModalForWindow:delegate:didRunSelector:contextInfo:}, {@code -runOperation},
 * {@code printPanel} and {@code showsPrintPanel} carry none, {@code jobTitle} is 10.5;
 * {@code NSPrintInfo}'s {@code sharedPrintInfo}, {@code paperSize}, the four margins and the two
 * centring flags carry none; {@code NSPrintPanel}'s {@code options} is 10.5. Nothing newer than
 * macOS 10.7 is called.
 *
 * <p><b>The delegate.</b> The did-run selector is a method, not a block, so it needs an object of a
 * class that has it: one class, {@code LimnPrintDelegate}, is made for the process with the runtime's
 * {@code objc_allocateClassPair}, and one instance answers every window; the context pointer says
 * which request it answers.
 *
 * <p><b>Objects kept across loop turns.</b> The event loop drains an autorelease pool every turn, so
 * the operation, the document and the print info a sheet is still using are retained here until the
 * delegate is called, and released after AppKit's call has returned, as {@link MacFileDialogs} does
 * with its panel.
 *
 * <p>A window that is not on screen cannot carry a sheet: its panel runs as a window of its own with
 * {@code -runOperation}, which is modal to the application and returns when the panel is done. It is
 * run from a posted task, so the event that asked has finished and drawn its frame first; the loop
 * waits while that panel is up, as the system's own modal panels make every application wait.
 */
final class MacPrinter {

    private static final System.Logger LOG = System.getLogger(MacPrinter.class.getName());

    /** {@code kPDFPrintPageScaleDownToFit} ({@code PDFDocument.h}). */
    static final long SCALE_DOWN_TO_FIT = 2;
    /** {@code NSPrintPanelShowsPaperSize} ({@code NSPrintPanel.h}). */
    static final long SHOWS_PAPER_SIZE = 1 << 2;
    /** {@code NSPrintPanelShowsOrientation} ({@code NSPrintPanel.h}). */
    static final long SHOWS_ORIENTATION = 1 << 3;
    /** {@code NSPrintPanelShowsPreview} ({@code NSPrintPanel.h}). */
    static final long SHOWS_PREVIEW = 1 << 17;

    private static final String DELEGATE_CLASS = "LimnPrintDelegate";
    private static final String DID_RUN = "printOperationDidRun:success:contextInfo:";

    private static final String PDFKIT = "/System/Library/Frameworks/PDFKit.framework/PDFKit";

    /** Whether PDFKit loaded; null until first asked. */
    private static Boolean available;
    /** The one delegate object, its class made on first use; NULL until then. */
    private static long delegate = NULL;
    /** The did-run method's closure, kept for the life of the process. */
    private static Callback didRun;
    /** {@code void objc_msgSend(id, SEL, CGFloat)}. */
    private static FFICIF oneDouble;
    /** {@code void objc_msgSend(id, SEL, NSSize)}: a size is passed as its two doubles. */
    private static FFICIF twoDoubles;

    /** The requests whose panel is up, by the context number AppKit hands back. */
    private static final Map<Long, Pending> PENDING = new HashMap<>();
    private static long nextContext = 1;

    private record Pending(long operation, long document, long printInfo, long owner,
                           Consumer<PrintResult> done) {
    }

    private MacPrinter() {
    }

    /**
     * @return whether PDFKit is here to print through: true on every supported macOS, false where
     *         the framework cannot be loaded (logged once)
     */
    static boolean isAvailable() {
        if (available == null) {
            available = load();
        }
        return available;
    }

    private static boolean load() {
        if (!ObjC.isAvailable()) {
            return false;
        }
        try {
            APIUtil.apiCreateLibrary(PDFKIT);
            if (ObjC.cls("PDFDocument") == NULL) {
                LOG.log(Level.WARNING, "PDFKit loaded with no PDFDocument class; printing stays unavailable");
                return false;
            }
            return true;
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(Level.WARNING, "PDFKit could not be loaded; printing stays unavailable", failed);
            return false;
        }
    }

    /**
     * Shows the print panel for the job, as a sheet on {@code nsWindow} when it is on screen.
     *
     * @param nsWindow the owner's {@code NSWindow}
     * @param onScreen whether the owner is shown, which is what a sheet needs
     * @param job      what to print
     * @param done     the answer, called on the UI thread after the event that answered
     */
    static void print(long nsWindow, boolean onScreen, PrintJob job, Consumer<PrintResult> done) {
        if (!isAvailable() || !ready()) {
            done.accept(PrintResult.UNAVAILABLE);
            return;
        }
        long data;
        ByteBuffer bytes = MemoryUtil.memAlloc(job.pdf().length);
        try {
            bytes.put(job.pdf()).flip();
            // dataWithBytes copies, so the buffer is freed at once and the job's array is left alone.
            data = ObjC.msg(ObjC.cls("NSData"), "dataWithBytes:length:", MemoryUtil.memAddress(bytes),
                    job.pdf().length);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        long document = data == NULL ? NULL
                : ObjC.msg(ObjC.msg(ObjC.cls("PDFDocument"), "alloc"), "initWithData:", data);
        if (document == NULL) {
            LOG.log(Level.WARNING, "not printed, PDFKit cannot read the PDF of {0}", job.title());
            done.accept(PrintResult.FAILED);
            return;
        }
        long printInfo = ObjC.msg(ObjC.msg(ObjC.cls("NSPrintInfo"), "sharedPrintInfo"), "copy");
        if (job.knowsPaper()) {
            sendTwoDoubles(printInfo, "setPaperSize:", job.paperWidth(), job.paperHeight());
        }
        for (String margin : new String[] {"setTopMargin:", "setBottomMargin:", "setLeftMargin:", "setRightMargin:"}) {
            sendDouble(printInfo, margin, 0);
        }
        ObjC.msgVoid(printInfo, "setHorizontallyCentered:", 1L);
        ObjC.msgVoid(printInfo, "setVerticallyCentered:", 1L);

        long operation = ObjC.msg(document, "printOperationForPrintInfo:scalingMode:autoRotate:", printInfo,
                SCALE_DOWN_TO_FIT, 1L);
        if (operation == NULL) {
            LOG.log(Level.WARNING, "not printed, PDFKit made no print operation for {0}", job.title());
            ObjC.msgVoid(printInfo, "release");
            ObjC.msgVoid(document, "release");
            done.accept(PrintResult.FAILED);
            return;
        }
        ObjC.msg(operation, "retain"); // held until the panel answers
        ObjC.msgVoid(operation, "setJobTitle:", MacTray.nsString(job.title()));
        ObjC.msgVoid(operation, "setShowsPrintPanel:", 1L);
        ObjC.msgVoid(operation, "setShowsProgressPanel:", 1L);
        long panel = ObjC.msg(operation, "printPanel");
        if (panel != NULL) {
            long options = ObjC.msg(panel, "options");
            ObjC.msgVoid(panel, "setOptions:", options | SHOWS_PAPER_SIZE | SHOWS_ORIENTATION | SHOWS_PREVIEW);
        }

        long context = nextContext++;
        Pending pending = new Pending(operation, document, printInfo, onScreen ? nsWindow : NULL, done);
        PENDING.put(context, pending);
        if (onScreen && nsWindow != NULL) {
            ObjC.msg(operation, "runOperationModalForWindow:delegate:didRunSelector:contextInfo:", nsWindow,
                    delegate, ObjC.sel(DID_RUN), context);
            return;
        }
        // No sheet without a window on screen: the panel as a window of its own, from a posted task.
        Ui.post(() -> {
            if (PENDING.remove(context) == null) {
                return;
            }
            boolean printed;
            try {
                printed = (ObjC.msg(operation, "runOperation") & 0xFF) != 0;
            } catch (RuntimeException failed) {
                LOG.log(Level.WARNING, "the macOS print panel could not be run", failed);
                finish(pending, PrintResult.FAILED);
                return;
            }
            finish(pending, printed ? PrintResult.PRINTED : PrintResult.CANCELLED);
        });
    }

    /** Makes the delegate class and its one object, and the two call interfaces, once. */
    private static boolean ready() {
        if (delegate != NULL) {
            return true;
        }
        try {
            long made = ObjCRuntime.objc_allocateClassPair(ObjC.cls("NSObject"), DELEGATE_CLASS, 0);
            if (made == NULL) {
                // Made by an earlier backend of this process whose delegate object went with it: use it.
                made = ObjC.cls(DELEGATE_CLASS);
            } else {
                // (id self, SEL _cmd, NSPrintOperation *op, BOOL success, void *contextInfo) -> void
                didRun = Ffi.callback("pppip>v", args -> {
                    answered(args[4], (args[3] & 0xFF) != 0);
                    return 0;
                });
                ObjCRuntime.class_addMethod(made, ObjC.sel(DID_RUN), didRun.address(), "v@:@c^v");
                ObjCRuntime.objc_registerClassPair(made);
            }
            if (made == NULL) {
                LOG.log(Level.WARNING, "could not make " + DELEGATE_CLASS + "; printing stays unavailable");
                return false;
            }
            oneDouble = APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer,
                    LibFFI.ffi_type_pointer, LibFFI.ffi_type_double);
            twoDoubles = APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer,
                    LibFFI.ffi_type_pointer, LibFFI.ffi_type_double, LibFFI.ffi_type_double);
            delegate = ObjC.msg(ObjC.msg(made, "alloc"), "init"); // for the life of the process
            return delegate != NULL;
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(Level.WARNING, "the print panel's delegate could not be made", failed);
            return false;
        }
    }

    /** The did-run selector, on the main thread inside AppKit's call. */
    private static void answered(long context, boolean success) {
        Pending pending = PENDING.remove(context);
        if (pending == null) {
            return;
        }
        // Released after AppKit's call returns, not in it: AppKit is still ending the sheet and
        // holds the operation while it does, and the answer belongs outside the event that carried it.
        Ui.post(() -> finish(pending, success ? PrintResult.PRINTED : PrintResult.CANCELLED));
    }

    private static void finish(Pending pending, PrintResult result) {
        ObjC.msgVoid(pending.operation, "release");
        ObjC.msgVoid(pending.printInfo, "release");
        ObjC.msgVoid(pending.document, "release");
        MacFileDialogs.giveTheKeyboardBack(pending.owner);
        pending.done.accept(result);
    }

    /** A message whose one argument is a float of the system's width, answered with nothing. */
    private static void sendDouble(long self, String selector, double value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long values = stack.ncalloc(8, 3, 8);
            long slots = stack.ncalloc(8, 3, 8);
            MemoryUtil.memPutAddress(values, self);
            MemoryUtil.memPutAddress(values + 8, ObjC.sel(selector));
            MemoryUtil.memPutDouble(values + 16, value);
            for (int i = 0; i < 3; i++) {
                MemoryUtil.memPutAddress(slots + 8L * i, values + 8L * i);
            }
            LibFFI.nffi_call(oneDouble.address(), ObjC.msgSend(), stack.ncalloc(8, 1, 8), slots);
        }
    }

    /**
     * A message whose one argument is a size. An {@code NSSize} is two doubles, which both arm64
     * (a homogeneous aggregate, in two floating-point registers) and x86-64 (two SSE eightbytes)
     * pass exactly as two double arguments.
     */
    private static void sendTwoDoubles(long self, String selector, double a, double b) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long values = stack.ncalloc(8, 4, 8);
            long slots = stack.ncalloc(8, 4, 8);
            MemoryUtil.memPutAddress(values, self);
            MemoryUtil.memPutAddress(values + 8, ObjC.sel(selector));
            MemoryUtil.memPutDouble(values + 16, a);
            MemoryUtil.memPutDouble(values + 24, b);
            for (int i = 0; i < 4; i++) {
                MemoryUtil.memPutAddress(slots + 8L * i, values + 8L * i);
            }
            LibFFI.nffi_call(twoDoubles.address(), ObjC.msgSend(), stack.ncalloc(8, 1, 8), slots);
        }
    }
}
