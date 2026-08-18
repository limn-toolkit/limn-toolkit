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
