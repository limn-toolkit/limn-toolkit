package limn.backend.lwjgl;

import limn.backend.PrintResult;
import limn.backend.lwjgl.transfer.Ffi;
import limn.backend.spi.PrintJob;
import limn.concurrent.Ui;
import limn.concurrent.internal.Threads;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;

import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Windows' print dialog and Direct2D's print control: {@code PrintDlgEx}
 * owned by the window, then the system's own PDF renderer ({@code Windows.Data.Pdf}) drawing each
 * chosen page through {@code IPdfRendererNative::RenderPageToDeviceContext} into a Direct2D command
 * list, and every list handed to an {@code ID2D1PrintControl} made over a package target for the
 * printer and the print ticket the dialog chose.
 *
 * <p><b>Why the system's own PDF renderer, measured.</b> On the Windows 11 ARM64 guest on 2026-10-22 a plain desktop
 * process, not packaged and in no app container, activated {@code Windows.Data.Pdf.PdfDocument}
 * ({@code RoGetActivationFactory} answered {@code S_OK}, from the SSH session and from the desktop
 * alike), {@code PdfCreateRenderer} made a renderer over a D3D11 device, and the pages it drew into
 * command lists reached the system's PDF printer as text: the printer's PDF held an embedded font and
 * no image, and a PDF reader extracted every line of the original from it. A ten-page table of
 * 74,510 bytes came out as 131,460 bytes in 734 ms, the renderer's part 125 ms of it. Through this
 * class, with the dialog confirmed, the same table came out as 130,610 bytes, on the ARM64 and the
 * x64 Java alike.
 *
 * <p><b>What the user sees, measured on Windows 11 (build 26100).</b> {@code PrintDlgEx} shows the
 * system's newer print dialog, hosted by the shell rather than by this process, titled with the
 * process's name; the window that asked is disabled while it is up and enabled again after it. It
 * offers the printer, the orientation and the pages, and no preview (the dialog says the application
 * does not support one). A printer that writes a file, as the system's PDF printer does, asks for the
 * file's name while the package target is made, on this thread, in a prompt owned by the window;
 * closing that prompt answers {@link PrintResult#CANCELLED}.
 *
 * <p><b>The thread.</b> {@code PrintDlgEx} runs its own message loop and returns when the user is
 * done, so the whole request runs on a thread of its own, a single-threaded apartment as the dialog
 * and the runtime classes ask: the owner is disabled while the dialog is up, as a dialog's owner is,
 * and the application's loop goes on. The answer is posted to the UI thread. The PDF is read before
 * the dialog opens, so the dialog knows how many pages there are and a PDF the system cannot read
 * answers {@link PrintResult#FAILED} without a dialog.
 *
 * <p><b>The paper.</b> When the job knows its paper the dialog starts from the default printer's
 * own settings with that paper in them: a standard size by its number when the paper is one within a
 * millimetre, a size of its own in tenths of a millimetre otherwise, and landscape when the paper is
 * wider than tall ({@link #paperFor}). What the user then chooses is the dialog's DEVMODE, which
 * becomes the job's print ticket through the print ticket provider.
 *
 * <p><b>The calls.</b> Every COM call is a function pointer read from the object's vtable, through
 * {@link Ffi}, as {@link WindowsFileDialogs} does: LWJGL binds neither Direct2D nor the print
 * system. The slots were read on the guest from the system's own headers by calling each method
 * through a vtable whose entries record their index (2026-10-22, the same numbers for ARM64 and
 * x64); the Windows Runtime ones were read from the system's metadata (methods in declaration order
 * after {@code IInspectable}'s six). The structure offsets were read the same way.
 */
final class WindowsPrinter {

    private static final System.Logger LOG = System.getLogger(WindowsPrinter.class.getName());

    // IUnknown
    private static final int QUERY_INTERFACE = 0;
    private static final int RELEASE = 2;
    // IInspectable-based: IPdfDocumentStatics, IPdfDocument, IPdfPage, IAsyncOperation, IAsyncInfo
    private static final int LOAD_FROM_STREAM_ASYNC = 8;
    private static final int GET_PAGE = 6;
    private static final int GET_PAGE_COUNT = 7;
    private static final int PAGE_GET_SIZE = 10;
    private static final int ASYNC_GET_RESULTS = 8;
    private static final int ASYNC_INFO_GET_STATUS = 7;
    private static final int ASYNC_INFO_GET_ERROR_CODE = 8;
    // IPdfRendererNative
    private static final int RENDER_PAGE_TO_DEVICE_CONTEXT = 4;
    // ID2D1Factory1, ID2D1Device, ID2D1DeviceContext, ID2D1CommandList, ID2D1PrintControl
    private static final int FACTORY_CREATE_DEVICE = 17;
    private static final int DEVICE_CREATE_DEVICE_CONTEXT = 4;
    private static final int DEVICE_CREATE_PRINT_CONTROL = 5;
    private static final int CONTEXT_BEGIN_DRAW = 48;
    private static final int CONTEXT_END_DRAW = 49;
    private static final int CONTEXT_CREATE_COMMAND_LIST = 67;
    private static final int CONTEXT_SET_TARGET = 74;
    private static final int COMMAND_LIST_CLOSE = 5;
    private static final int PRINT_CONTROL_ADD_PAGE = 3;
    private static final int PRINT_CONTROL_CLOSE = 4;
    // IPrintDocumentPackageTargetFactory, IStream
    private static final int CREATE_TARGET_FOR_PRINT_JOB = 3;
    private static final int STREAM_SEEK = 5;

    // PRINTDLGEXW (commdlg.h), 136 bytes on a 64-bit Windows.
    private static final int PRINTDLGEX_SIZE = 136;
    private static final int PD_OWNER = 8;
    private static final int PD_DEV_MODE = 16;
    private static final int PD_DEV_NAMES = 24;
    private static final int PD_FLAGS = 40;
    private static final int PD_PAGE_RANGES = 52;
    private static final int PD_MAX_PAGE_RANGES = 56;
    private static final int PD_PAGE_RANGES_POINTER = 64;
    private static final int PD_MIN_PAGE = 72;
    private static final int PD_MAX_PAGE = 76;
    private static final int PD_COPIES = 80;
    private static final int PD_START_PAGE = 128;
    private static final int PD_RESULT_ACTION = 132;
    // Flags
    private static final int PD_PAGENUMS = 0x2;
    private static final int PD_NOSELECTION = 0x4;
    private static final int PD_USEDEVMODECOPIESANDCOLLATE = 0x40000;
    private static final int PD_HIDEPRINTTOFILE = 0x100000;
    private static final int PD_NOCURRENTPAGE = 0x800000;
    private static final int START_PAGE_GENERAL = 0xFFFFFFFF;
    private static final int PD_RESULT_PRINT = 1;
    private static final int MAX_PAGE_RANGES = 32;

    // DEVMODEW (wingdi.h)
    private static final int DM_SIZE = 68;
    private static final int DM_DRIVER_EXTRA = 70;
    private static final int DM_FIELDS = 72;
    private static final int DM_ORIENTATION_AT = 76;
    private static final int DM_PAPER_SIZE_AT = 78;
    private static final int DM_PAPER_LENGTH_AT = 80;
    private static final int DM_PAPER_WIDTH_AT = 82;
    private static final int DM_ORIENTATION = 0x1;
    private static final int DM_PAPERSIZE = 0x2;
    private static final int DM_PAPERLENGTH = 0x4;
    private static final int DM_PAPERWIDTH = 0x8;
    private static final int DM_OUT_BUFFER = 2;
    static final short DMORIENT_PORTRAIT = 1;
    static final short DMORIENT_LANDSCAPE = 2;
    static final short DMPAPER_USER = 256;

    private static final int GMEM_MOVEABLE_ZEROINIT = 0x42;
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int D3D_DRIVER_TYPE_HARDWARE = 1;
    private static final int D3D_DRIVER_TYPE_WARP = 5;
    private static final int D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20;
    private static final int D3D11_SDK_VERSION = 7;
    private static final int D2D1_FACTORY_TYPE_SINGLE_THREADED = 0;
    private static final int D2D1_COLOR_SPACE_SRGB = 1;
    /** The resolution Direct2D rasterizes what it cannot keep as vectors at: a gradient, an effect. */
    private static final float RASTER_DPI = 150;
    private static final int K_PT_JOB_SCOPE = 2;
    private static final int CLSCTX_INPROC_SERVER = 1;
    /** {@code AsyncStatus}: Started, then Completed, Canceled or Error. */
    private static final int ASYNC_STARTED = 0;
    private static final int ASYNC_COMPLETED = 1;
    /** {@code HRESULT_FROM_WIN32(ERROR_PRINT_CANCELLED)}: a printer's file prompt closed. */
    private static final int PRINT_CANCELLED = 0x8007003F;
    /** {@code HRESULT_FROM_WIN32(ERROR_CANCELLED)}. */
    private static final int USER_CANCELLED = 0x800704C7;
    /** How long the runtime may take to read a PDF before the print is given up. */
    private static final long LOAD_TIMEOUT_MS = 30_000;

    private static final String IID_PDF_DOCUMENT_STATICS = "433A0B5F-C007-4788-90F2-08143D922599";
    private static final String IID_RANDOM_ACCESS_STREAM = "905A0FE1-BC53-11DF-8C49-001E4FC686DA";
    private static final String IID_ASYNC_INFO = "00000036-0000-0000-C000-000000000046";
    private static final String IID_DXGI_DEVICE = "54EC77FA-1377-44E6-8C32-88FD5F44C84C";
    private static final String IID_D2D1_FACTORY1 = "BB12D362-DAEE-4B9A-AA1D-14BA401CFA1F";
    private static final String CLSID_TARGET_FACTORY = "348EF17D-6C81-4982-92B4-EE188A43867A";
    private static final String IID_TARGET_FACTORY = "D2959BF7-B31B-4A3D-9600-712EB1335BA4";
    private static final String CLSID_WIC_FACTORY = "CACAF262-9370-4615-A13B-9F5539DA4C0A";
    private static final String IID_WIC_FACTORY = "EC5EC8A9-C395-4314-9C77-54D7A935FF70";

    private static @Nullable Boolean available;

    private WindowsPrinter() {
    }

    /** The libraries a print calls, opened by the first print and not by each. */
    private static final class Libraries {
        static final SharedLibrary OLE32 = APIUtil.apiCreateLibrary("ole32.dll");
        static final SharedLibrary COMBASE = APIUtil.apiCreateLibrary("combase.dll");
        static final SharedLibrary COMDLG32 = APIUtil.apiCreateLibrary("comdlg32.dll");
        static final SharedLibrary KERNEL32 = APIUtil.apiCreateLibrary("kernel32.dll");
        static final SharedLibrary WINSPOOL = APIUtil.apiCreateLibrary("winspool.drv");
        static final SharedLibrary SHLWAPI = APIUtil.apiCreateLibrary("shlwapi.dll");
        static final SharedLibrary SHCORE = APIUtil.apiCreateLibrary("shcore.dll");
        static final SharedLibrary D3D11 = APIUtil.apiCreateLibrary("d3d11.dll");
        static final SharedLibrary D2D1 = APIUtil.apiCreateLibrary("d2d1.dll");
        static final SharedLibrary PDF = APIUtil.apiCreateLibrary("Windows.Data.Pdf.dll");
        static final SharedLibrary PRNTVPT = APIUtil.apiCreateLibrary("prntvpt.dll");
    }

    /**
     * @return whether the renderer and the print control are here: true on every supported Windows
     *         (10 and later), false where a library is missing, which is logged once
     */
    static synchronized boolean isAvailable() {
        if (available == null) {
            available = load();
        }
        return available;
    }

    private static boolean load() {
        if (Pointer.POINTER_SIZE != 8) {
            return false;
        }
        try {
            boolean all = Libraries.PDF.getFunctionAddress("PdfCreateRenderer") != NULL
                    && Libraries.D2D1.getFunctionAddress("D2D1CreateFactory") != NULL
                    && Libraries.COMDLG32.getFunctionAddress("PrintDlgExW") != NULL
                    && Libraries.PRNTVPT.getFunctionAddress("PTConvertDevModeToPrintTicket") != NULL
                    && Libraries.SHCORE.getFunctionAddress("CreateRandomAccessStreamOverStream") != NULL
                    && Libraries.COMBASE.getFunctionAddress("RoGetActivationFactory") != NULL;
            if (!all) {
                LOG.log(Level.WARNING, "a function of the print path is missing; printing stays unavailable");
            }
            return all;
        } catch (RuntimeException | LinkageError missing) {
            LOG.log(Level.WARNING, "a library of the print path cannot be loaded; printing stays unavailable", missing);
            return false;
        }
    }

    /**
     * Shows the dialog on a thread of its own, owned by {@code hwnd}, and prints if it is confirmed.
     *
     * @param done the answer, called on the UI thread
     */
    static void print(long hwnd, PrintJob job, Consumer<PrintResult> done) {
        if (!isAvailable()) {
            done.accept(PrintResult.UNAVAILABLE);
            return;
        }
        Threads.daemon("limn-print-dialog", () -> {
            PrintResult result = PrintResult.FAILED;
            try {
                result = run(hwnd, job);
            } catch (Throwable error) {
                LOG.log(Level.WARNING, "not printed, the Windows print path failed: " + job.title(), error);
            }
            PrintResult answer = result;
            Ui.post(() -> done.accept(answer));
        });
    }

    // ---- the request ------------------------------------------------------------------------

    private static PrintResult run(long hwnd, PrintJob job) {
        // The dialog and a printer's file prompt answer an automation client from this process's
        // threads; see UiaClientWarmUp.
        UiaClientWarmUp.ensure();
        int init = (int) Ffi.call(fn(Libraries.OLE32, "CoInitializeEx"), "pi>i", NULL, COINIT_APARTMENTTHREADED);
        long document = NULL;
        try {
            document = load(job);
            if (document == NULL) {
                return PrintResult.FAILED;
            }
            int pages = intOut(document, GET_PAGE_COUNT);
            if (pages <= 0) {
                LOG.log(Level.WARNING, "not printed, the PDF of {0} has no pages", job.title());
                return PrintResult.FAILED;
            }
            Chosen chosen = dialog(hwnd, job, pages);
            if (chosen == null) {
                return PrintResult.CANCELLED;
            }
            try {
                return spool(document, chosen, job.title());
            } finally {
                MemoryUtil.nmemFree(chosen.devMode);
            }
        } finally {
            if (document != NULL) {
                release(document);
            }
            if (init >= 0) {
                Ffi.call(fn(Libraries.OLE32, "CoUninitialize"), ">v");
            }
        }
    }

    /** The PDF read by the runtime's {@code PdfDocument}, or NULL when it cannot be (logged). */
    private static long load(PrintJob job) {
        long statics = NULL;
        long memory = NULL;
        long stream = NULL;
        long operation = NULL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            String name = "Windows.Data.Pdf.PdfDocument";
            PointerBuffer hstring = stack.mallocPointer(1);
            check("WindowsCreateString", (int) Ffi.call(fn(Libraries.COMBASE, "WindowsCreateString"), "pip>i",
                    MemoryUtil.memAddress(stack.UTF16(name, false)), name.length(), MemoryUtil.memAddress(hstring)));
            PointerBuffer out = stack.mallocPointer(1);
            int hr = (int) Ffi.call(fn(Libraries.COMBASE, "RoGetActivationFactory"), "ppp>i", hstring.get(0),
                    guid(stack, IID_PDF_DOCUMENT_STATICS), MemoryUtil.memAddress(out));
            Ffi.call(fn(Libraries.COMBASE, "WindowsDeleteString"), "p>i", hstring.get(0));
            check("RoGetActivationFactory(Windows.Data.Pdf.PdfDocument)", hr);
            statics = out.get(0);

            ByteBuffer bytes = MemoryUtil.memAlloc(job.pdf().length);
            try {
                bytes.put(job.pdf()).flip();
                // The memory stream copies what it is given.
                memory = Ffi.call(fn(Libraries.SHLWAPI, "SHCreateMemStream"), "pi>p", MemoryUtil.memAddress(bytes),
                        job.pdf().length);
            } finally {
                MemoryUtil.memFree(bytes);
            }
            if (memory == NULL) {
                throw new IllegalStateException("SHCreateMemStream made no stream");
            }
            check("CreateRandomAccessStreamOverStream", (int) Ffi.call(fn(Libraries.SHCORE,
                    "CreateRandomAccessStreamOverStream"), "pipp>i", memory, 0, guid(stack, IID_RANDOM_ACCESS_STREAM),
                    MemoryUtil.memAddress(out)));
            stream = out.get(0);
            check("IPdfDocumentStatics::LoadFromStreamAsync",
                    com(statics, LOAD_FROM_STREAM_ASYNC, "pp", stream, MemoryUtil.memAddress(out)));
            operation = out.get(0);
            return await(stack, operation, job.title());
        } catch (IllegalStateException failed) {
            LOG.log(Level.WARNING, "not printed, the system's PDF renderer cannot read " + job.title(), failed);
            return NULL;
        } finally {
            for (long object : new long[] {operation, stream, memory, statics}) {
                if (object != NULL) {
                    release(object);
                }
            }
        }
    }

    /**
     * Waits for an {@code IAsyncOperation<PdfDocument>} by asking its status: this thread is the
     * operation's only listener, and a completion handler would be one more COM object to make.
     */
    private static long await(MemoryStack stack, long operation, String title) {
        PointerBuffer out = stack.mallocPointer(1);
        check("IAsyncOperation QueryInterface(IAsyncInfo)",
                com(operation, QUERY_INTERFACE, "pp", guid(stack, IID_ASYNC_INFO), MemoryUtil.memAddress(out)));
        long info = out.get(0);
        try {
            long deadline = System.nanoTime() + LOAD_TIMEOUT_MS * 1_000_000L;
            int status;
            while ((status = intOut(info, ASYNC_INFO_GET_STATUS)) == ASYNC_STARTED) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("the PDF was not read in " + LOAD_TIMEOUT_MS + " ms");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted reading the PDF", interrupted);
                }
            }
            if (status != ASYNC_COMPLETED) {
                throw new IllegalStateException("reading the PDF ended with status " + status + ", error 0x"
                        + Integer.toHexString(intOut(info, ASYNC_INFO_GET_ERROR_CODE)));
            }
            check("IAsyncOperation::GetResults", com(operation, ASYNC_GET_RESULTS, "p", MemoryUtil.memAddress(out)));
            return out.get(0);
        } finally {
            release(info);
        }
    }

    /** What the dialog was confirmed with. */
    private record Chosen(String printer, long devMode, int devModeSize, int[] pages) {
    }

    /** The dialog, or null when it was cancelled. */
    private static @Nullable Chosen dialog(long hwnd, PrintJob job, int pages) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long dialog = stack.ncalloc(8, PRINTDLGEX_SIZE, 1);
            long ranges = stack.ncalloc(4, MAX_PAGE_RANGES * 2, 4);
            MemoryUtil.memPutInt(ranges, 1);
            MemoryUtil.memPutInt(ranges + 4, pages);
            MemoryUtil.memPutInt(dialog, PRINTDLGEX_SIZE);
            MemoryUtil.memPutAddress(dialog + PD_OWNER, hwnd);
            MemoryUtil.memPutAddress(dialog + PD_DEV_MODE, job.knowsPaper() ? startingDevMode(job) : NULL);
            MemoryUtil.memPutInt(dialog + PD_FLAGS, PD_NOSELECTION | PD_NOCURRENTPAGE | PD_HIDEPRINTTOFILE
                    | PD_USEDEVMODECOPIESANDCOLLATE);
            MemoryUtil.memPutInt(dialog + PD_PAGE_RANGES, 1);
            MemoryUtil.memPutInt(dialog + PD_MAX_PAGE_RANGES, MAX_PAGE_RANGES);
            MemoryUtil.memPutAddress(dialog + PD_PAGE_RANGES_POINTER, ranges);
            MemoryUtil.memPutInt(dialog + PD_MIN_PAGE, 1);
            MemoryUtil.memPutInt(dialog + PD_MAX_PAGE, pages);
            MemoryUtil.memPutInt(dialog + PD_COPIES, 1);
            MemoryUtil.memPutInt(dialog + PD_START_PAGE, START_PAGE_GENERAL);
            int hr = (int) Ffi.call(fn(Libraries.COMDLG32, "PrintDlgExW"), "p>i", dialog);
            long devMode = MemoryUtil.memGetAddress(dialog + PD_DEV_MODE);
            long devNames = MemoryUtil.memGetAddress(dialog + PD_DEV_NAMES);
            try {
                check("PrintDlgExW", hr);
                if (MemoryUtil.memGetInt(dialog + PD_RESULT_ACTION) != PD_RESULT_PRINT) {
                    return null;
                }
                if (devMode == NULL || devNames == NULL) {
                    throw new IllegalStateException("the dialog answered with no printer");
                }
                String printer = deviceName(devNames);
                long locked = Ffi.call(fn(Libraries.KERNEL32, "GlobalLock"), "p>p", devMode);
                int size = Short.toUnsignedInt(MemoryUtil.memGetShort(locked + DM_SIZE))
                        + Short.toUnsignedInt(MemoryUtil.memGetShort(locked + DM_DRIVER_EXTRA));
                long copy = MemoryUtil.nmemAllocChecked(size);
                MemoryUtil.memCopy(locked, copy, size);
                Ffi.call(fn(Libraries.KERNEL32, "GlobalUnlock"), "p>i", devMode);
                boolean some = (MemoryUtil.memGetInt(dialog + PD_FLAGS) & PD_PAGENUMS) != 0;
                int count = MemoryUtil.memGetInt(dialog + PD_PAGE_RANGES);
                int[] flat = new int[count * 2];
                for (int i = 0; i < flat.length; i++) {
                    flat[i] = MemoryUtil.memGetInt(ranges + 4L * i);
                }
                return new Chosen(printer, copy, size, chosenPages(some, flat, pages));
            } finally {
                for (long global : new long[] {devMode, devNames}) {
                    if (global != NULL) {
                        Ffi.call(fn(Libraries.KERNEL32, "GlobalFree"), "p>p", global);
                    }
                }
            }
        }
    }

    /** The printer's name out of a {@code DEVNAMES}: offsets in characters from its start. */
    private static String deviceName(long devNames) {
        long locked = Ffi.call(fn(Libraries.KERNEL32, "GlobalLock"), "p>p", devNames);
        try {
            int device = Short.toUnsignedInt(MemoryUtil.memGetShort(locked + 2));
            return MemoryUtil.memUTF16(locked + 2L * device);
        } finally {
            Ffi.call(fn(Libraries.KERNEL32, "GlobalUnlock"), "p>i", devNames);
        }
    }

    /**
     * The default printer's own settings with the job's paper in them, in a movable block the dialog
     * takes and answers with; NULL when there is no default printer or its settings cannot be read,
     * and the dialog then starts from the printer's own paper.
     */
    private static long startingDevMode(PrintJob job) {
        Paper paper = paperFor(job.paperWidth(), job.paperHeight());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var length = stack.mallocInt(1);
            long getDefault = fn(Libraries.WINSPOOL, "GetDefaultPrinterW");
            Ffi.call(getDefault, "pp>i", NULL, MemoryUtil.memAddress(length));
            if (length.get(0) <= 0) {
                return NULL;
            }
            long name = stack.ncalloc(2, length.get(0), 2);
            if (Ffi.call(getDefault, "pp>i", name, MemoryUtil.memAddress(length)) == 0) {
                return NULL;
            }
            PointerBuffer printer = stack.mallocPointer(1);
            if (Ffi.call(fn(Libraries.WINSPOOL, "OpenPrinterW"), "ppp>i", name, MemoryUtil.memAddress(printer), NULL)
                    == 0) {
                return NULL;
            }
            try {
                long properties = fn(Libraries.WINSPOOL, "DocumentPropertiesW");
                int size = (int) Ffi.call(properties, "pppppi>i", NULL, printer.get(0), name, NULL, NULL, 0);
                if (size <= 0) {
                    return NULL;
                }
                long global = Ffi.call(fn(Libraries.KERNEL32, "GlobalAlloc"), "ip>p", GMEM_MOVEABLE_ZEROINIT, size);
                if (global == NULL) {
                    return NULL;
                }
                long devMode = Ffi.call(fn(Libraries.KERNEL32, "GlobalLock"), "p>p", global);
                int read = (int) Ffi.call(properties, "pppppi>i", NULL, printer.get(0), name, devMode, NULL,
                        DM_OUT_BUFFER);
                if (read >= 0) {
                    int fields = MemoryUtil.memGetInt(devMode + DM_FIELDS) | DM_ORIENTATION | DM_PAPERSIZE;
                    MemoryUtil.memPutShort(devMode + DM_ORIENTATION_AT, paper.orientation());
                    MemoryUtil.memPutShort(devMode + DM_PAPER_SIZE_AT, paper.size());
                    if (paper.size() == DMPAPER_USER) {
                        fields |= DM_PAPERLENGTH | DM_PAPERWIDTH;
                        MemoryUtil.memPutShort(devMode + DM_PAPER_LENGTH_AT, paper.lengthTenths());
                        MemoryUtil.memPutShort(devMode + DM_PAPER_WIDTH_AT, paper.widthTenths());
                    }
                    MemoryUtil.memPutInt(devMode + DM_FIELDS, fields);
                }
                Ffi.call(fn(Libraries.KERNEL32, "GlobalUnlock"), "p>i", global);
                if (read < 0) {
                    Ffi.call(fn(Libraries.KERNEL32, "GlobalFree"), "p>p", global);
                    return NULL;
                }
                return global;
            } finally {
                Ffi.call(fn(Libraries.WINSPOOL, "ClosePrinter"), "p>i", printer.get(0));
            }
        }
    }

    /** Draws the chosen pages into command lists and hands them to a print control over the printer. */
    private static PrintResult spool(long document, Chosen chosen, String title) {
        List<Long> held = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.mallocPointer(1);
            long d3d11 = d3dDevice(stack);
            held.add(d3d11);
            check("ID3D11Device QueryInterface(IDXGIDevice)",
                    com(d3d11, QUERY_INTERFACE, "pp", guid(stack, IID_DXGI_DEVICE), MemoryUtil.memAddress(out)));
            long dxgi = out.get(0);
            held.add(dxgi);
            check("D2D1CreateFactory", (int) Ffi.call(fn(Libraries.D2D1, "D2D1CreateFactory"), "ippp>i",
                    D2D1_FACTORY_TYPE_SINGLE_THREADED, guid(stack, IID_D2D1_FACTORY1), NULL, MemoryUtil.memAddress(out)));
            long factory = out.get(0);
            held.add(factory);
            check("ID2D1Factory1::CreateDevice", com(factory, FACTORY_CREATE_DEVICE, "pp", dxgi,
                    MemoryUtil.memAddress(out)));
            long device = out.get(0);
            held.add(device);
            check("ID2D1Device::CreateDeviceContext", com(device, DEVICE_CREATE_DEVICE_CONTEXT, "ip", 0,
                    MemoryUtil.memAddress(out)));
            long context = out.get(0);
            held.add(context);
            check("PdfCreateRenderer", (int) Ffi.call(fn(Libraries.PDF, "PdfCreateRenderer"), "pp>i", dxgi,
                    MemoryUtil.memAddress(out)));
            long renderer = out.get(0);
            held.add(renderer);

            long ticket = ticket(stack, chosen);
            held.add(ticket);
            long targets = create(stack, CLSID_TARGET_FACTORY, IID_TARGET_FACTORY);
            held.add(targets);
            ByteBuffer printer = stack.UTF16(chosen.printer);
            ByteBuffer job = stack.UTF16(title);
            int made = com(targets, CREATE_TARGET_FOR_PRINT_JOB, "ppppp", MemoryUtil.memAddress(printer),
                    MemoryUtil.memAddress(job), NULL, ticket, MemoryUtil.memAddress(out));
            if (made == PRINT_CANCELLED || made == USER_CANCELLED) {
                // A printer that writes a file asks for its name here, on this thread; measured with
                // the system's PDF printer, closing that prompt answers ERROR_PRINT_CANCELLED.
                return PrintResult.CANCELLED;
            }
            check("IPrintDocumentPackageTargetFactory::CreateDocumentPackageTargetForPrintJob", made);
            long target = out.get(0);
            held.add(target);
            long wic = create(stack, CLSID_WIC_FACTORY, IID_WIC_FACTORY);
            held.add(wic);
            ByteBuffer properties = stack.calloc(4, 12);
            properties.putInt(0, 0).putFloat(4, RASTER_DPI).putInt(8, D2D1_COLOR_SPACE_SRGB);
            check("ID2D1Device::CreatePrintControl", com(device, DEVICE_CREATE_PRINT_CONTROL, "pppp", wic, target,
                    MemoryUtil.memAddress(properties), MemoryUtil.memAddress(out)));
            long control = out.get(0);
            held.add(control);

            for (int index : chosen.pages) {
                addPage(stack, document, index, context, renderer, control);
            }
            check("ID2D1PrintControl::Close", com(control, PRINT_CONTROL_CLOSE, ""));
            return PrintResult.PRINTED;
        } catch (IllegalStateException failed) {
            LOG.log(Level.WARNING, "not printed, the system refused the job " + title, failed);
            return PrintResult.FAILED;
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                if (held.get(i) != NULL) {
                    release(held.get(i));
                }
            }
        }
    }

    /** One page: the renderer draws it into a command list, which the print control takes. */
    private static void addPage(MemoryStack outer, long document, int index, long context, long renderer,
                                long control) {
        try (MemoryStack stack = outer.push()) {
            PointerBuffer out = stack.mallocPointer(1);
            check("IPdfDocument::GetPage", com(document, GET_PAGE, "ip", index, MemoryUtil.memAddress(out)));
            long page = out.get(0);
            long list = NULL;
            try {
                ByteBuffer size = stack.calloc(4, 8); // Windows.Foundation.Size, in device-independent pixels
                check("IPdfPage::get_Size", com(page, PAGE_GET_SIZE, "p", MemoryUtil.memAddress(size)));
                float width = size.getFloat(0);
                float height = size.getFloat(4);
                check("ID2D1DeviceContext::CreateCommandList",
                        com(context, CONTEXT_CREATE_COMMAND_LIST, "p", MemoryUtil.memAddress(out)));
                list = out.get(0);
                com(context, CONTEXT_SET_TARGET, "p", list);
                com(context, CONTEXT_BEGIN_DRAW, "");
                // PDF_RENDER_PARAMS: the whole page, at its own size, on white.
                ByteBuffer params = stack.calloc(4, 44);
                params.putFloat(0, 0).putFloat(4, 0).putFloat(8, width).putFloat(12, height)
                        .putInt(16, Math.round(width)).putInt(20, Math.round(height))
                        .putFloat(24, 1).putFloat(28, 1).putFloat(32, 1).putFloat(36, 1);
                int drawn = com(renderer, RENDER_PAGE_TO_DEVICE_CONTEXT, "ppp", page, context,
                        MemoryUtil.memAddress(params));
                int ended = com(context, CONTEXT_END_DRAW, "pp", NULL, NULL);
                com(context, CONTEXT_SET_TARGET, "p", NULL);
                check("IPdfRendererNative::RenderPageToDeviceContext", drawn);
                check("ID2D1DeviceContext::EndDraw", ended);
                check("ID2D1CommandList::Close", com(list, COMMAND_LIST_CLOSE, ""));
                check("ID2D1PrintControl::AddPage", addPage(stack, control, list, width, height));
            } finally {
                if (list != NULL) {
                    release(list);
                }
                release(page);
            }
        }
    }

    /**
     * {@code ID2D1PrintControl::AddPage(list, D2D1_SIZE_F, NULL, NULL, NULL)}: the size is a structure
     * of two floats passed by value, which x64 passes in one integer register and ARM64 in two
     * floating-point ones, so the call goes through libffi with the structure's own type.
     */
    private static int addPage(MemoryStack stack, long control, long list, float width, float height) {
        FFICIF cif = AddPageCall.CIF;
        ByteBuffer values = stack.calloc(8, 6 * 8);
        long base = MemoryUtil.memAddress(values);
        values.putLong(0, control).putLong(8, list).putFloat(16, width).putFloat(20, height)
                .putLong(24, NULL).putLong(32, NULL).putLong(40, NULL);
        PointerBuffer slots = stack.mallocPointer(6);
        for (int i = 0; i < 6; i++) {
            slots.put(i, base + 8L * i);
        }
        ByteBuffer result = stack.calloc(8, 8);
        LibFFI.ffi_call(cif, slot(control, PRINT_CONTROL_ADD_PAGE), result, slots);
        return result.getInt(0);
    }

    /** The call interface of {@code AddPage}, made once. */
    private static final class AddPageCall {
        static final FFICIF CIF;

        static {
            PointerBuffer elements = MemoryUtil.memAllocPointer(3);
            elements.put(0, LibFFI.ffi_type_float.address()).put(1, LibFFI.ffi_type_float.address()).put(2, NULL);
            FFIType size = FFIType.calloc();
            size.type(LibFFI.FFI_TYPE_STRUCT);
            size.elements(elements);
            CIF = APIUtil.apiCreateCIF(LibFFI.ffi_type_sint32, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                    size, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer);
        }
    }

    /** A D3D11 device that Direct2D can draw through: the hardware's, or the software rasterizer's. */
    private static long d3dDevice(MemoryStack stack) {
        PointerBuffer out = stack.mallocPointer(1);
        long create = fn(Libraries.D3D11, "D3D11CreateDevice");
        for (int driver : new int[] {D3D_DRIVER_TYPE_HARDWARE, D3D_DRIVER_TYPE_WARP}) {
            int hr = (int) Ffi.call(create, "pipipiippp>i", NULL, driver, NULL, D3D11_CREATE_DEVICE_BGRA_SUPPORT, NULL,
                    0, D3D11_SDK_VERSION, MemoryUtil.memAddress(out), NULL, NULL);
            if (hr >= 0 && out.get(0) != NULL) {
                return out.get(0);
            }
            LOG.log(Level.DEBUG, "D3D11CreateDevice({0}) answered 0x{1}", driver, Integer.toHexString(hr));
        }
        throw new IllegalStateException("no D3D11 device, hardware or software");
    }

    /** The job's print ticket, made by the printer's provider from the dialog's DEVMODE, at its start. */
    private static long ticket(MemoryStack stack, Chosen chosen) {
        PointerBuffer out = stack.mallocPointer(1);
        check("CreateStreamOnHGlobal", (int) Ffi.call(fn(Libraries.OLE32, "CreateStreamOnHGlobal"), "pip>i", NULL, 1,
                MemoryUtil.memAddress(out)));
        // The stream and the memory it owns were let go of only when the conversion was refused;
        // a printer whose provider would not open (PTOpenProvider failing) leaked them.
        return filledOrReleased(out.get(0), stream -> {
            ByteBuffer printer = stack.UTF16(chosen.printer);
            check("PTOpenProvider", (int) Ffi.call(fn(Libraries.PRNTVPT, "PTOpenProvider"), "pip>i",
                    MemoryUtil.memAddress(printer), 1, MemoryUtil.memAddress(out)));
            long provider = out.get(0);
            try {
                check("PTConvertDevModeToPrintTicket", (int) Ffi.call(fn(Libraries.PRNTVPT,
                        "PTConvertDevModeToPrintTicket"), "pipip>i", provider, chosen.devModeSize, chosen.devMode,
                        K_PT_JOB_SCOPE, stream));
            } finally {
                Ffi.call(fn(Libraries.PRNTVPT, "PTCloseProvider"), "p>i", provider);
            }
            com(stream, STREAM_SEEK, "pip", 0L, 0, NULL); // LARGE_INTEGER 0, STREAM_SEEK_SET
        }, WindowsPrinter::release);
    }

    /**
     * Hands on a COM object just made once {@code fill} has filled it, and releases it when
     * {@code fill} throws, whatever it throws: until it is handed on, nothing else holds it.
     *
     * @param object  the object, made by the caller
     * @param fill    what makes it ready
     * @param release lets go of it ({@link #release} for a COM object)
     * @return {@code object}, filled
     */
    static long filledOrReleased(long object, java.util.function.LongConsumer fill,
                                 java.util.function.LongConsumer release) {
        boolean filled = false;
        try {
            fill.accept(object);
            filled = true;
            return object;
        } finally {
            if (!filled) {
                release.accept(object);
            }
        }
    }

    private static long create(MemoryStack stack, String clsid, String iid) {
        PointerBuffer out = stack.mallocPointer(1);
        check("CoCreateInstance(" + clsid + ")", (int) Ffi.call(fn(Libraries.OLE32, "CoCreateInstance"), "ppipp>i",
                guid(stack, clsid), NULL, CLSCTX_INPROC_SERVER, guid(stack, iid), MemoryUtil.memAddress(out)));
        return out.get(0);
    }

    // ---- what is decided without a printer ------------------------------------------------------

    /**
     * A paper for {@code DEVMODE}: its number, its orientation, and for a size of its own its length
     * and width in tenths of a millimetre (portrait: length is the longer side).
     */
    record Paper(short size, short orientation, short lengthTenths, short widthTenths) {
    }

    /** The standard papers by their {@code DMPAPER_} number, portrait width and height in millimetres. */
    private static final int[][] STANDARD = {
            {1, 2159, 2794},    // DMPAPER_LETTER, 8.5 x 11 in
            {5, 2159, 3556},    // DMPAPER_LEGAL, 8.5 x 14 in
            {3, 2794, 4318},    // DMPAPER_TABLOID, 11 x 17 in
            {7, 1842, 2667},    // DMPAPER_EXECUTIVE, 7.25 x 10.5 in
            {8, 2970, 4200},    // DMPAPER_A3
            {9, 2100, 2970},    // DMPAPER_A4
            {11, 1480, 2100},   // DMPAPER_A5
            {12, 2570, 3640},   // DMPAPER_B4 (JIS)
            {13, 1820, 2570},   // DMPAPER_B5 (JIS)
    };

    /**
     * The {@code DEVMODE} paper for a page of {@code width} by {@code height} points: a standard
     * paper when both sides are within a millimetre of one, else {@link #DMPAPER_USER} with the
     * sides in tenths of a millimetre; landscape when the page is wider than tall.
     */
    static Paper paperFor(float width, float height) {
        boolean landscape = width > height;
        int shortSide = tenthsOfMillimetre(Math.min(width, height));
        int longSide = tenthsOfMillimetre(Math.max(width, height));
        short orientation = landscape ? DMORIENT_LANDSCAPE : DMORIENT_PORTRAIT;
        for (int[] paper : STANDARD) {
            if (Math.abs(paper[1] - shortSide) <= 10 && Math.abs(paper[2] - longSide) <= 10) {
                return new Paper((short) paper[0], orientation, (short) paper[2], (short) paper[1]);
            }
        }
        return new Paper(DMPAPER_USER, orientation, (short) Math.min(longSide, Short.MAX_VALUE),
                (short) Math.min(shortSide, Short.MAX_VALUE));
    }

    private static int tenthsOfMillimetre(float points) {
        return Math.round(points * 254f / 72f);
    }

    /**
     * The pages to print, counted from 0: every page, or the dialog's ranges ({@code from, to}
     * pairs counted from 1) held to the document and each page once, in the order the ranges name
     * them.
     */
    static int[] chosenPages(boolean ranges, int[] fromTo, int pageCount) {
        if (!ranges) {
            int[] all = new int[pageCount];
            for (int i = 0; i < pageCount; i++) {
                all[i] = i;
            }
            return all;
        }
        java.util.LinkedHashSet<Integer> pages = new java.util.LinkedHashSet<>();
        for (int i = 0; i + 1 < fromTo.length; i += 2) {
            int from = Math.max(1, Math.min(fromTo[i], fromTo[i + 1]));
            int to = Math.min(pageCount, Math.max(fromTo[i], fromTo[i + 1]));
            for (int page = from; page <= to; page++) {
                pages.add(page - 1);
            }
        }
        return pages.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- COM through vtables --------------------------------------------------------------------

    /**
     * Calls a method by its vtable slot.
     *
     * @param signature the arguments after {@code this}, as {@link Ffi} spells them; the result is
     *                  the HRESULT
     */
    private static int com(long object, int slot, String signature, long... args) {
        long[] all = new long[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return (int) Ffi.call(slot(object, slot), "p" + signature + ">i", all);
    }

    /** A getter that answers a 32-bit value through its one out-pointer. */
    private static int intOut(long object, int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var value = stack.callocInt(1);
            check("a getter at slot " + slot, com(object, slot, "p", MemoryUtil.memAddress(value)));
            return value.get(0);
        }
    }

    private static void release(long object) {
        Ffi.call(slot(object, RELEASE), "p>i", object);
    }

    private static long slot(long object, int slot) {
        return MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(object) + (long) slot * Long.BYTES);
    }

    private static long fn(SharedLibrary library, String name) {
        long address = library.getFunctionAddress(name);
        if (address == NULL) {
            throw new IllegalStateException(library.getName() + " exports no " + name);
        }
        return address;
    }

    private static long guid(MemoryStack stack, String text) {
        return MemoryUtil.memAddress(WindowsFileDialogs.guid(stack, text));
    }

    private static void check(String what, int hr) {
        if (hr < 0) {
            throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hr));
        }
    }
}
