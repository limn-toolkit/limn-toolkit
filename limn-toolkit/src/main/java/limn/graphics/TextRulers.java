package limn.graphics;

import limn.backend.Installed;

/**
 * Process-wide {@link TextRuler} registry, installed by the running backend
 * (mirrors the {@code Ui} facade lifecycle). Scenes pick it up by default;
 * anything can override locally for tests.
 *
 * <p>The backend's ruler measures through unsynchronized font caches and is
 * therefore <b>UI thread only</b> (enforced). Precompute text metrics off
 * thread via {@code Ui.async} handing the result back, not by measuring there.
 */
public final class TextRulers {

    private static final Installed<TextRuler> INSTALLED = new Installed<>(TextRuler.NONE,
            "no TextRuler installed. Is the backend started?");

    private TextRulers() {
    }

    /** Installs the backend's ruler (called once at backend startup). */
    public static void install(TextRuler ruler) {
        INSTALLED.install(ruler);
    }

    /** Resets to {@link TextRuler#NONE} (backend shutdown). */
    public static void uninstall(TextRuler ruler) {
        INSTALLED.uninstall(ruler);
    }

    /** @return the installed ruler (never null; {@link TextRuler#NONE} without a backend) */
    public static TextRuler get() {
        return INSTALLED.current();
    }
}
