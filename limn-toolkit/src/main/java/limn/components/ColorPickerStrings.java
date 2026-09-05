package limn.components;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The colour picker's own vocabulary: the three notations and the channel letters.
 *
 * <p>Most languages keep RGB and CMYK as they are (they are read as symbols rather
 * than as words), but not all: French says RVB and CMJN, and its channel letters
 * follow (R, V, B / T, S, V / C, M, J, N). That is the reason these are keys at all,
 * and the reason the tab captions stop coming from {@code Format.name()}: an enum
 * constant is API, and API cannot be translated.
 */
final class ColorPickerStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/colorpicker"));
    }

    static final I18nString FORMAT_RGB = new I18nString("limn.colorPicker.format.rgb", "RGB");
    static final I18nString FORMAT_HSV = new I18nString("limn.colorPicker.format.hsv", "HSV");
    static final I18nString FORMAT_CMYK = new I18nString("limn.colorPicker.format.cmyk", "CMYK");

    static final I18nString CHANNEL_R = new I18nString("limn.colorPicker.channel.r", "R");
    static final I18nString CHANNEL_G = new I18nString("limn.colorPicker.channel.g", "G");
    static final I18nString CHANNEL_B = new I18nString("limn.colorPicker.channel.b", "B");
    static final I18nString CHANNEL_H = new I18nString("limn.colorPicker.channel.h", "H");
    static final I18nString CHANNEL_S = new I18nString("limn.colorPicker.channel.s", "S");
    static final I18nString CHANNEL_V = new I18nString("limn.colorPicker.channel.v", "V");
    static final I18nString CHANNEL_C = new I18nString("limn.colorPicker.channel.c", "C");
    static final I18nString CHANNEL_M = new I18nString("limn.colorPicker.channel.m", "M");
    static final I18nString CHANNEL_Y = new I18nString("limn.colorPicker.channel.y", "Y");
    static final I18nString CHANNEL_K = new I18nString("limn.colorPicker.channel.k", "K");
    static final I18nString CHANNEL_ALPHA = new I18nString("limn.colorPicker.channel.alpha", "A");

    /**
     * What the hex field is called when it is read out rather than looked at.
     *
     * <p>The one string here that names nothing on screen. Every channel has a letter beside it
     * that an assistive technology can be pointed at, and the hex field has a "#" beside it, which
     * is a typographic mark rather than a word: a reader speaks it "number sign", which names the
     * field after its punctuation. So the field carries this instead, and the "#" stays what it
     * is. It is a key like the rest because a notation's name is not automatically the same word
     * in every language, and this bundle is where that question is already answered.
     */
    static final I18nString HEX = new I18nString("limn.colorPicker.hex", "Hex");

    /**
     * What the hue ramp is called when it is read out rather than looked at.
     *
     * <p>Another string here that names nothing on screen, and the one control in this widget
     * that nothing outside it could ever name: the ramp has no caption beside it, no tooltip, and
     * no accessor an application can reach, so a word the toolkit holds is the only name it can
     * have. Deliberately the channel's whole word and not its letter &mdash; the letter is the
     * HSV rail's name, and the ramp and that rail are on screen together.
     *
     * <p>A key like the rest, because a channel's name is not the same word in every language,
     * and this bundle is where that question is already answered: French renames the notation to
     * TSV after this very word.
     */
    static final I18nString HUE = new I18nString("limn.colorPicker.hue", "Hue");

    /**
     * What the saturation/value plane is called when it is read out rather than looked at, and
     * what its two axes are called under it.
     *
     * <p>Three more strings that name nothing on screen, for the hue ramp's reason: the plane has
     * no caption beside it, no tooltip, and no accessor an application could reach, so a word the
     * toolkit holds is the only name it can have. The two axes are the whole words rather than the
     * letters for that reason too &mdash; S and V are the HSV rails' names, and the plane and
     * those rails are on screen together.
     *
     * <p>Keys like the rest, because a channel's name is not the same word in every language: the
     * value axis is one of the two French renames after which that notation is TSV, and saturation
     * happens to be the same word in French and falls back to the English declared here, as
     * {@link #HEX} does.
     */
    static final I18nString FIELD =
            new I18nString("limn.colorPicker.field", "Saturation and value");

    /** The plane's horizontal axis, named for a reader; see {@link #FIELD}. */
    static final I18nString AXIS_SATURATION =
            new I18nString("limn.colorPicker.axis.saturation", "Saturation");

    /** The plane's vertical axis, named for a reader; see {@link #FIELD}. */
    static final I18nString AXIS_VALUE = new I18nString("limn.colorPicker.axis.value", "Value");

    /**
     * What the before/after swatch says: the colour showing now, and the one the picker opened on.
     *
     * <p>The second string here that names nothing on screen, and the first parameterized key in
     * this package. The swatch paints two halves and no text, and the earlier of the two is the one
     * fact the picker publishes nowhere else &mdash; the current colour is also the hex field's
     * text and the channel numbers, the opening one is only ever drawn. So the swatch's whole
     * content is the comparison, and the comparison is one sentence rather than two nodes.
     *
     * <p>Both arguments arrive as already-rendered hex strings, never as colours and never as
     * numbers: {@link I18nString#format} routes through {@code MessageFormat}, which localises a
     * numeric argument, and a hex triplet is a spelling rather than a quantity.
     *
     * <p><b>For a translator:</b> this pattern carries two slots, so their order is yours to
     * choose &mdash; a language that says the old colour first moves {@code {1}} in front of
     * {@code {0}} and nothing else changes. Being parameterized, it also pays
     * {@code MessageFormat}'s two escaping rules that the plain keys above do not: an apostrophe
     * has to be doubled, and a brace that is not opening a slot has to be quoted.
     */
    static final I18nString SWATCH =
            new I18nString("limn.colorPicker.swatch", "Colour {0}, was {1}");

    /** The tab caption for a notation; {@code Format.name()} is API, not display text. */
    static I18nString format(ColorPicker.Format format) {
        return switch (format) {
            case RGB -> FORMAT_RGB;
            case HSV -> FORMAT_HSV;
            case CMYK -> FORMAT_CMYK;
        };
    }

    /** The channel letters of a notation, in the order its row shows them. */
    static I18nString[] channels(ColorPicker.Format format) {
        return switch (format) {
            case RGB -> new I18nString[]{CHANNEL_R, CHANNEL_G, CHANNEL_B};
            case HSV -> new I18nString[]{CHANNEL_H, CHANNEL_S, CHANNEL_V};
            case CMYK -> new I18nString[]{CHANNEL_C, CHANNEL_M, CHANNEL_Y, CHANNEL_K};
        };
    }

    private ColorPickerStrings() {
    }
}
