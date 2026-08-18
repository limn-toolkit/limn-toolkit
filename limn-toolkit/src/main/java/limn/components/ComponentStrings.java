package limn.components;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The component chrome that is small enough not to deserve a domain of its own:
 * a placeholder and a failure message.
 *
 * <p>Each domain owns its file family and registers it here, in a static block, so
 * touching one of its strings is what makes its translations available. There is no
 * central list of bundles to keep in step with the classes that need them, and a
 * domain nobody uses costs nothing.
 *
 * @see ColorPickerStrings
 * @see ThemeStrings
 */
final class ComponentStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/components"));
    }

    static final I18nString SEARCH_PLACEHOLDER =
            new I18nString("limn.searchField.placeholder", "Search…");

    static final I18nString VIEWPORT3D_NO_BACKEND =
            new I18nString("limn.viewport3d.noBackend", "3D unavailable (no GPU backend)");

    static final I18nString VIDEO_NO_BACKEND =
            new I18nString("limn.videoView.noBackend", "Video unavailable (no GPU backend)");

    static final I18nString VIDEO_DECODE_FAILED =
            new I18nString("limn.videoView.decodeFailed", "This video cannot be played");

    // The text widgets' context menu. Plain platform verbs on purpose: these are the four rows a
    // user has read in every other application, and a cleverer word here is one they have to
    // stop and parse.
    static final I18nString TEXT_MENU_CUT =
            new I18nString("limn.textMenu.cut", "Cut");

    static final I18nString TEXT_MENU_COPY =
            new I18nString("limn.textMenu.copy", "Copy");

    static final I18nString TEXT_MENU_PASTE =
            new I18nString("limn.textMenu.paste", "Paste");

    static final I18nString TEXT_MENU_SELECT_ALL =
            new I18nString("limn.textMenu.selectAll", "Select All");

    // The dialog a ColorPickerButton raises. Here rather than in ColorPickerStrings
    // because two of the three are the words every dialog in every application uses,
    // and this is the first place the toolkit itself has had to supply them: a Dialog
    // an application builds takes its own button captions.
    static final I18nString COLOR_TITLE = new I18nString("limn.color.title", "Colour");
    static final I18nString OK = new I18nString("limn.ok", "OK");
    static final I18nString CANCEL = new I18nString("limn.cancel", "Cancel");

    private ComponentStrings() {
    }
}
