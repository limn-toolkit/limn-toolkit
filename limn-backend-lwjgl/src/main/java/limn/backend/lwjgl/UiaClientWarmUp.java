package limn.backend.lwjgl;

import limn.backend.Platform;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Starts UI Automation in this process from a thread of COM's multithreaded apartment, once, before
 * the first system dialog the process shows: a file chooser, the print dialog, a printer's prompt
 * for a file name. Only under an ARM64 Java on Windows, the one place it was measured to matter.
 *
 * <p><b>What it prevents, measured on Windows 11 ARM64 on 2026-10-22.</b> A system dialog in this
 * process (the save chooser, the system's PDF printer's "save the print output as" prompt) answers
 * an automation client in another process from the dialog's own thread, a single-threaded
 * apartment, inside the client's {@code SendMessage}. Under an ARM64 JDK the first such answer of
 * the process made a call out of the apartment, COM refused it
 * ({@code RPC_E_CANTCALLOUT_ININPUTSYNCCALL}), and nothing caught the refusal: a client reading the
 * save chooser before anything else of the process killed it in six runs of six, and the printer's
 * prompt in every run, whether the print ran in a single-threaded apartment or the multithreaded one
 * (the system then shows the prompt on a thread of its own, and that thread died instead). An x64
 * JDK on the same machine did not die, nor did a native ARM64 shell showing the same save chooser,
 * read cold by the same client (the system's text editor survived too, but it had been driven
 * through UI Automation to open its print dialog, so it was not cold). Which part of an ARM64 JVM
 * makes the difference is not known: every one of these processes had the automation core loaded
 * before the client came (a system dialog loads it), and the x64 and ARM64 JVMs had the same
 * automation and text-input modules, read on the guest. What is known is that the first answer is
 * the question, not the dialog. Once a client of this process had read anything from a thread of the
 * multithreaded apartment, every dialog after it answered the outside client in every run: three of
 * three with the desktop's root read here, three of three with this process's own window, four of
 * four when the outside client had read the application's window first, and two of two through the
 * printer's prompt. Two cheaper calls into the library from that thread
 * ({@code UiaGetReservedNotSupportedValue}, {@code UiaHostProviderFromHwnd}) did not do it.
 *
 * <p>So this creates the system's automation client here, on a daemon thread of the multithreaded
 * apartment, reads the desktop root's name, and lets everything go: about 16 ms, once per process,
 * on the thread that is about to show the dialog, never on the user-interface thread. It asks
 * nothing of the application's windows, so it needs no accessibility bridge and cannot wait on the
 * user-interface thread. A failure is logged and changes nothing else: the dialog is shown anyway.
 */
final class UiaClientWarmUp {

    private static final System.Logger LOG = System.getLogger(UiaClientWarmUp.class.getName());

    /** Where it was measured to matter: Windows under an ARM64 Java. */
    private static final boolean NEEDED = Platform.current().isWindows()
            && "aarch64".equals(System.getProperty("os.arch"));

    /** How long the thread about to show a dialog waits for it, at most. */
    private static final long WAIT_MS = 2_000;

    private static final int COINIT_MULTITHREADED = 0x0;
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final String CLSID_CUIAUTOMATION = "FF48DBA4-60EF-4201-AA87-54103EEF594E";
    private static final String IID_IUIAUTOMATION = "30CBE57D-D9D0-452A-AB13-7AC5AC4825EE";
    /** {@code IUnknown::Release}. */
    private static final int RELEASE = 2;
    /** {@code IUIAutomation::GetRootElement}. */
    private static final int GET_ROOT_ELEMENT = 5;
    /** {@code IUIAutomationElement::get_CurrentName}. */
    private static final int GET_CURRENT_NAME = 23;

    private static boolean done;

    private UiaClientWarmUp() {
    }

    /**
     * Makes sure UI Automation has been started from the multithreaded apartment in this process;
     * returns at once after the first call, and at once where it is not needed. Called on the thread
     * about to show a system dialog, never on the user-interface thread.
     */
    static void ensure() {
        if (!NEEDED) {
            return;
        }
        synchronized (UiaClientWarmUp.class) {
            if (done) {
                return;
            }
            done = true;
        }
        Thread thread = new Thread(UiaClientWarmUp::start, "limn-uia-warm-up");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(WAIT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void start() {
        SharedLibrary ole32 = APIUtil.apiCreateLibrary("ole32.dll");
        SharedLibrary oleaut32 = APIUtil.apiCreateLibrary("oleaut32.dll");
        int init = JNI.invokePPI(NULL, COINIT_MULTITHREADED, ole32.getFunctionAddress("CoInitializeEx"));
        long automation = NULL;
        long root = NULL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocPointer(1);
            int hr = JNI.invokePPPPPI(MemoryUtil.memAddress(guid(stack, CLSID_CUIAUTOMATION)), NULL,
                    CLSCTX_INPROC_SERVER, MemoryUtil.memAddress(guid(stack, IID_IUIAUTOMATION)),
                    MemoryUtil.memAddress(out), ole32.getFunctionAddress("CoCreateInstance"));
            if (hr < 0 || out.get(0) == NULL) {
                LOG.log(Level.WARNING, "UI Automation could not be started for the system's dialogs: 0x{0}",
                        Integer.toHexString(hr));
                return;
            }
            automation = out.get(0);
            hr = JNI.invokePPI(automation, MemoryUtil.memAddress(out), slot(automation, GET_ROOT_ELEMENT));
            if (hr < 0 || out.get(0) == NULL) {
                LOG.log(Level.WARNING, "UI Automation answered no desktop root: 0x{0}", Integer.toHexString(hr));
                return;
            }
            root = out.get(0);
            hr = JNI.invokePPI(root, MemoryUtil.memAddress(out), slot(root, GET_CURRENT_NAME));
            if (hr >= 0 && out.get(0) != NULL) {
                JNI.invokePV(out.get(0), oleaut32.getFunctionAddress("SysFreeString"));
            }
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(Level.WARNING, "UI Automation could not be started for the system's dialogs", failed);
        } finally {
            if (root != NULL) {
                JNI.invokePI(root, slot(root, RELEASE));
            }
            if (automation != NULL) {
                JNI.invokePI(automation, slot(automation, RELEASE));
            }
            if (init >= 0) {
                JNI.invokeV(ole32.getFunctionAddress("CoUninitialize"));
            }
            ole32.free();
            oleaut32.free();
        }
    }

    private static long slot(long object, int index) {
        return MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(object) + (long) index * Long.BYTES);
    }

    private static ByteBuffer guid(MemoryStack stack, String text) {
        UUID id = UUID.fromString(text);
        ByteBuffer bytes = stack.malloc(16).order(ByteOrder.LITTLE_ENDIAN);
        long high = id.getMostSignificantBits();
        bytes.putInt(0, (int) (high >>> 32)).putShort(4, (short) (high >>> 16)).putShort(6, (short) high);
        long low = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes.put(8 + i, (byte) (low >>> (56 - 8 * i)));
        }
        return bytes;
    }
}
