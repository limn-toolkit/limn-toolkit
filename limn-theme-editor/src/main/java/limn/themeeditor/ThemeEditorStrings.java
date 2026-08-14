package limn.themeeditor;

import limn.components.Theme;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

import java.util.EnumMap;
import java.util.Map;

/**
 * The editor's own vocabulary.
 *
 * <p><b>Declared, not translated.</b> Every string here is a key with English behind it,
 * so an application that wants the editor in another language registers a bundle for
 * {@code /limn/i18n/themeeditor} and every caption follows, but this module ships no
 * translation of its own, unlike the widget set, which ships nineteen. That is a decision
 * rather than an omission: what the editor names are the tokens of a design system, and a
 * palette author who reads {@code surfaceRaised} in the API is not helped by a screen that
 * calls it something else in a language the API does not speak. An application that
 * disagrees has every key.
 */
final class ThemeEditorStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/themeeditor"));
    }

    // --- the frame ---------------------------------------------------------

    static final I18nString NAME = key("name", "Name");
    static final I18nString NAME_PLACEHOLDER = key("namePlaceholder", "Untitled palette");
    static final I18nString DARK = key("dark", "Dark palette");
    static final I18nString START_FROM = key("startFrom", "Start from");
    /** The first entry of the base list: this palette did not start from a built-in. */
    static final I18nString BASE_CUSTOM = key("baseCustom", "Custom");
    static final I18nString APPLY_LIVE = key("applyLive", "Apply while editing");

    static final I18nString COPY = key("copy", "Copy");
    static final I18nString PASTE = key("paste", "Paste");
    static final I18nString REVERT = key("revert", "Revert");
    static final I18nString OPEN = key("open", "Open…");
    static final I18nString SAVE = key("save", "Save as…");

    /** {@code {0}} is the reason the text on the clipboard could not be read as a palette. */
    static final I18nString PASTE_FAILED = key("pasteFailed", "That is not a palette: {0}");
    static final I18nString COPIED = key("copied", "Palette copied to the clipboard.");
    /** {@code {0}} is a file name. */
    static final I18nString SAVED = key("saved", "Saved to {0}.");
    /** {@code {0}} is the reason a file could not be read or written. */
    static final I18nString FILE_FAILED = key("fileFailed", "That did not work: {0}");
    static final I18nString NO_FILE_DIALOGS =
            key("noFileDialogs", "This window cannot open a file chooser.");
    static final I18nString FILE_KIND = key("fileKind", "Limn theme");

    // --- the derivations ---------------------------------------------------

    static final I18nString DERIVE_ACCENT = key("deriveAccent", "Derive from the accent");
    static final I18nString DERIVE_DISABLED = key("deriveDisabled", "Derive from the surfaces");
    static final I18nString DERIVE_SEMANTIC = key("deriveSemantic", "Reset to the standard tones");

    // --- the report --------------------------------------------------------

    static final I18nString LEGIBILITY = key("legibility", "Legibility");
    static final I18nString LEGIBILITY_CLEAN =
            key("legibilityClean", "Every tone clears the bar it is measured against.");
    /** {@code {0}} errors, {@code {1}} warnings, {@code {2}} notes. */
    static final I18nString LEGIBILITY_COUNTS =
            key("legibilityCounts", "{0} failing, {1} to look at, {2} noted");

    // --- the preview -------------------------------------------------------

    static final I18nString PREVIEW = key("preview", "Preview");
    static final I18nString PREVIEW_TITLE = key("previewTitle", "The quick brown fox");
    static final I18nString PREVIEW_BODY =
            key("previewBody", "Body text on a card, and the muted line beneath it.");
    /** One caption for all four accent states: the preview shows one button, four times. */
    static final I18nString PREVIEW_ACTION = key("previewAction", "Action");

    // --- the sections ------------------------------------------------------

    static final I18nString SECTION_SHAPE = key("section.shape", "Shape");
    static final I18nString CORNER_SCALE = key("cornerScale", "Corner roundness");
    /** {@code {0}} is the resulting medium radius, in points, at the default size step. */
    static final I18nString CORNER_SCALE_VALUE = key("cornerScaleValue", "{0} pt");

    static final I18nString SECTION_TYPE = key("section.type", "Typeface");
    static final I18nString FONT_FAMILY = key("fontFamily", "Font");
    /** The first entry in the font picker: no preference, so the toolkit's own face is used. */
    static final I18nString FONT_DEFAULT = key("fontDefault", "The toolkit's own");
    /** Shown when the chosen family is not installed on this machine. */
    static final I18nString FONT_MISSING =
            key("fontMissing", "Not on this machine, so it will fall back to the toolkit's own.");

    static final I18nString SECTION_SURFACES = key("section.surfaces", "Surfaces");
    static final I18nString SECTION_ACCENT = key("section.accent", "Accent");
    static final I18nString SECTION_TEXT = key("section.text", "Text");
    static final I18nString SECTION_CHROME = key("section.chrome", "Chrome");
    static final I18nString SECTION_SEMANTIC = key("section.semantic", "Semantic");

    // --- the tokens --------------------------------------------------------

    private static final Map<Theme.Token, I18nString> TOKEN_NAMES =
            new EnumMap<>(Theme.Token.class);

    static {
        name(Theme.Token.BACKGROUND, "Canvas");
        name(Theme.Token.SURFACE, "Surface");
        name(Theme.Token.SURFACE_RAISED, "Raised surface");
        name(Theme.Token.PRIMARY, "Accent");
        name(Theme.Token.PRIMARY_HOVER, "Accent, hovered");
        name(Theme.Token.PRIMARY_PRESSED, "Accent, pressed");
        name(Theme.Token.ON_PRIMARY, "Label on the accent");
        name(Theme.Token.TEXT, "Text");
        name(Theme.Token.TEXT_MUTED, "Muted text");
        name(Theme.Token.OUTLINE, "Outline");
        name(Theme.Token.FOCUS_RING, "Focus ring");
        name(Theme.Token.DISABLED_FILL, "Disabled fill");
        name(Theme.Token.DISABLED_TEXT, "Disabled text");
        name(Theme.Token.SCRIM, "Modal veil");
        name(Theme.Token.DANGER, "Danger");
        name(Theme.Token.SUCCESS, "Success");
        name(Theme.Token.WARNING, "Warning");
        name(Theme.Token.INFO, "Information");
    }

    /**
     * The caption for a tone. Every token has one; {@code ThemeEditorTest} says so, which
     * is what stops a token added to {@link Theme} from reaching the screen as a blank row.
     */
    static I18nString of(Theme.Token token) {
        I18nString known = TOKEN_NAMES.get(token);
        return known != null ? known : I18nString.literal(token.key());
    }

    private static void name(Theme.Token token, String english) {
        TOKEN_NAMES.put(token, key("token." + token.key(), english));
    }

    private static I18nString key(String suffix, String english) {
        return new I18nString("limn.themeEditor." + suffix, english);
    }

    private ThemeEditorStrings() {
    }
}
