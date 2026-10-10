package limn.demo.site;

import limn.components.ComboBox;
import limn.components.Theme;
import limn.i18n.I18nString;
import limn.scene.Scene;

/**
 * The worked example the theming guide shows for switching a palette while the program runs: a
 * picker over the palettes the toolkit ships. Compiled by {@code ./gradlew check}, so the sample
 * a reader copies builds, and tested for what the page says of it.
 */
public final class ThemingExample {

    private ThemingExample() {
    }

    // #region guide:theme-picker
    /** A theme picker for a window. Hand it every scene the application has open. */
    static ComboBox<I18nString> themePicker(Scene... scenes) {
        ComboBox<I18nString> picker = ComboBox.localized(
                Theme.builtins().stream().map(Theme::displayName).toList());
        picker.setSelectedIndex(Theme.builtins().indexOf(Theme.current()));
        // apply, and not setCurrent: it makes the palette current, applies the palette's own
        // face, and repaints these scenes on the palette's background.
        picker.onSelect(() -> Theme.builtins().get(picker.selectedIndex()).apply(scenes));
        return picker;
    }
    // #endregion
}
