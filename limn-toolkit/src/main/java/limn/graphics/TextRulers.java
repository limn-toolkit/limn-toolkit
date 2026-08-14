package limn.graphics;

import java.util.Objects;

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

    private static volatile TextRuler installed = TextRuler.NONE;

    private TextRulers() {
    }

    /** Installs the backend's ruler (called once at backend startup). */
    public static void install(TextRuler ruler) {
        installed = Objects.requireNonNull(ruler, "ruler");
    }

    /** Resets to {@link TextRuler#NONE} (backend shutdown). */
    public static void uninstall(TextRuler ruler) {
        if (installed == ruler) {
            installed = TextRuler.NONE;
        }
    }

    /** @return the installed ruler (never null; {@link TextRuler#NONE} without a backend) */
    public static TextRuler get() {
        return installed;
    }
}
