package limn.backend;

/**
 * System clipboard (UTF-8 text). The LWJGL backend maps it to
 * {@code glfwGet/SetClipboardString}; tests inject mocks.
 *
 * <p><b>UI thread only</b>, like everything a scene hands out: GLFW's clipboard calls must be made
 * on the thread that runs the event loop, and an implementation may assume that it is called there.
 * A worker that has text for the clipboard posts it with {@code Ui.post}.
 */
public interface Clipboard {

    /** No-op clipboard for headless scenes/tests that never touch it. */
    Clipboard NONE = new Clipboard() {
        private String value = "";

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String text) {
            value = text == null ? "" : text;
        }
    };

    /** @return the clipboard text (empty string when unavailable, never null) */
    String get();

    /** @param text the new clipboard text; {@code null} is the empty string */
    void set(String text);
}
