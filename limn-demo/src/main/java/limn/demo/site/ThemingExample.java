package limn.demo.site;

import limn.components.ComboBox;
import limn.components.Label;
import limn.components.Theme;
import limn.components.Tone;
import limn.concurrent.Subscription;
import limn.graphics.Font;
import limn.graphics.Fonts;
import limn.i18n.I18nString;
import limn.scene.layout.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * The worked examples the theming guide shows for switching a palette or the font while the
 * program runs: a picker over the palettes the toolkit ships, and one over the families the
 * backend found. Compiled by {@code ./gradlew check}, so the sample a reader copies builds, and
 * tested for what the page says of it.
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
        // on the palette's background, and switches to the palette's own face once it is read.
        picker.onSelect(() -> Theme.builtins().get(picker.selectedIndex()).apply());
        return picker;
    }
    // #endregion

    // #region guide:font-picker
    /** A font picker over the families the backend has found, rebuilt as it finds more. */
    static final class FontPicker extends Row {

        private Subscription listening;
        private List<String> shown = List.of();
        private ComboBox<String> picker;

        @Override
        protected void onAttached() {
            // The list grows once the operating system's families have been read, and the font
            // can be switched elsewhere: by a theme, from another screen.
            listening = Fonts.observeChanges(this::refresh);
            refresh(); // what moved while this was off screen told no listener
        }

        @Override
        protected void onDetached() {
            listening.cancel();
        }

        private void refresh() {
            List<String> families = new ArrayList<>();
            families.add(Font.DEFAULT_FAMILY); // the toolkit's own face
            families.addAll(Fonts.available());
            if (!families.equals(shown)) {
                shown = families;
                ComboBox<String> combo = new ComboBox<>(families,
                        family -> family.equals(Font.DEFAULT_FAMILY) ? "Default" : family);
                // Worn once its faces have been read off the UI thread; a later pick overtakes it.
                combo.onSelect(() -> Fonts.setDefaultFamilyWhenResident(combo.selectedItem()));
                List.copyOf(children()).forEach(this::remove);
                add(combo);
                picker = combo;
            }
            // The font in use, however it was chosen; this write is not a pick.
            picker.setSelectedIndex(Math.max(0, shown.indexOf(Fonts.defaultFamily())));
        }
    }
    // #endregion

    // #region guide:theme-tone
    /** A status line in the success colour of whichever palette is current, now and after a switch. */
    static Label savedLine() {
        return new Label("Saved two minutes ago").setTone(Tone.SUCCESS);
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
