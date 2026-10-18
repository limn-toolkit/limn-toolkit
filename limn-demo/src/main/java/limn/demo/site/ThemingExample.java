package limn.demo.site;

import limn.components.ComboBox;
import limn.components.Theme;
import limn.i18n.I18nString;

/**
 * The worked example the theming guide shows for switching a palette while the program runs: a
 * picker over the palettes the toolkit ships. Compiled by {@code ./gradlew check}, so the sample
 * a reader copies builds, and tested for what the page says of it.
 */
public final class ThemingExample {

    private ThemingExample() {
    }

    // #region guide:theme-picker
    /** A theme picker: every window the application has open repaints in the palette chosen. */
    static ComboBox<I18nString> themePicker() {
        ComboBox<I18nString> picker = ComboBox.localized(
                Theme.builtins().stream().map(Theme::displayName).toList());
        picker.setSelectedIndex(Theme.builtins().indexOf(Theme.current()));
        // apply, and not setCurrent: it makes the palette current, which repaints every window
        // on the palette's background, and applies the palette's own face.
        picker.onSelect(() -> Theme.builtins().get(picker.selectedIndex()).apply());
        return picker;
    }
    // #endregion

    // #region guide:elevation
    /** A panel that lies over the page: the surface, and the shadow the theme gives one that covers. */
    static limn.components.Surface panelOver(limn.scene.Widget<?> content) {
        return new limn.components.Surface(new limn.scene.layout.Padding(limn.scene.Insets.all(12), content))
                .setElevation(limn.components.Elevation.OVERLAY);
    }

    /** A palette whose menus and panels cast a deeper shadow than the built-in ones. */
    static Theme deeper(Theme base) {
        return base.toBuilder().name(base.name() + ", deep")
                .elevation(limn.components.Elevation.OVERLAY,
                        new limn.components.ElevationShadow(12, 28, 0.20f, 2, 0.12f))
                .build();
    }
    // #endregion
}
