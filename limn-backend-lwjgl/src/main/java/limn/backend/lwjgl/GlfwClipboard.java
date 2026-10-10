package limn.backend.lwjgl;

import limn.backend.Clipboard;
import limn.concurrent.Subscription;
import limn.transfer.DataFormat;
import limn.transfer.Offer;
import limn.transfer.Transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

/**
 * The clipboard as GLFW has it: text, and nothing else, reaches other programs. It is what a
 * window hands out where no native clipboard of ours is running.
 *
 * <p>The rest of a transfer is kept here, so a picture or a record copied in this application
 * still pastes in it. It is the clipboard's content for as long as the system's text is still
 * the text that was set with it; once another program copies, the text differs and the kept
 * transfer is forgotten. A transfer with no text sets the empty string, for the same test.
 *
 * <p>GLFW tells of no change, so {@link #onChange} hears only what this application sets.
 */
final class GlfwClipboard implements Clipboard {

    // One clipboard for the process, whichever window asks.
    private static Transfer kept = Transfer.EMPTY;
    private static String keptText = "";
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private final LongSupplier window;
    private final Runnable uiThreadCheck;

    /**
     * @param window        the window to hand GLFW, or NULL once it is destroyed
     * @param uiThreadCheck throws off the UI thread: GLFW's clipboard is main-thread only
     */
    GlfwClipboard(LongSupplier window, Runnable uiThreadCheck) {
        this.window = window;
        this.uiThreadCheck = uiThreadCheck;
    }

    @Override
    public String text() {
        uiThreadCheck.run();
        String value = glfwGetClipboardString(window.getAsLong());
        return value == null ? "" : value;
    }

    @Override
    public void set(Transfer content) {
        Objects.requireNonNull(content, "content");
        uiThreadCheck.run();
        String text = content.get(DataFormat.TEXT);
        keptText = text == null ? "" : text;
        kept = content;
        glfwSetClipboardString(window.getAsLong(), keptText);
        for (Runnable listener : List.copyOf(LISTENERS)) {
            listener.run();
        }
    }

    @Override
    public Offer content() {
        String now = text();
        if (now.equals(keptText) && !kept.isEmpty()) {
            return kept.asOffer();
        }
        kept = Transfer.EMPTY;
        keptText = "";
        return now.isEmpty() ? Offer.EMPTY : Transfer.of(DataFormat.TEXT, now).asOffer();
    }

    @Override
    public Subscription onChange(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        uiThreadCheck.run();
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }
}
