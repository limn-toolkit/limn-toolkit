package limn.backend;

/**
 * System clipboard (UTF-8 text). The LWJGL backend maps it to
 * {@code glfwGet/SetClipboardString}; tests inject mocks.
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

    void set(String text);
}
