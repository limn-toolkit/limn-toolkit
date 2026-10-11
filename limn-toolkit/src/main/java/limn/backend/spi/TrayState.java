package limn.backend.spi;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a tray shows, whole: a backend is handed a new one whenever any part changes and shows the
 * difference.
 *
 * @param title       the icon's name, which a panel and a screen reader say; never empty
 * @param tooltip     the tooltip; empty for none
 * @param picture     the icon shown as it is, or {@code null} when the application set none
 * @param monochrome  the icon that takes the panel's colour, or {@code null}; a backend whose
 *                    system colours one prefers it, and one that cannot know the panel's colour
 *                    prefers {@code picture}
 * @param attention   whether the icon asks for attention; both pictures then already carry what
 *                    shows it
 * @param activates   whether a click that means "this application" is the application's; when
 *                    {@code false} every click opens the menu
 * @param rightToLeft whether the menu is laid out right to left
 */
public record TrayState(String title, String tooltip, @Nullable TrayPicture picture,
        @Nullable TrayPicture monochrome, boolean attention, boolean activates, boolean rightToLeft) {

    /** Checks the two texts. */
    public TrayState {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(tooltip, "tooltip");
    }
}
