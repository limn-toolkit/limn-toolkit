package limn.backend.lwjgl.transfer;

import java.util.ArrayList;
import java.util.List;

/**
 * The canonical names a {@link NativeClipboard} speaks, and their spellings on the two Linux
 * windowing systems, where a type is a string any program may write its own way.
 */
public final class ClipboardTypes {

    /** Plain text, as UTF-8 bytes. */
    public static final String TEXT = "text/plain;charset=utf-8";

    /** An HTML fragment, as UTF-8 bytes. */
    public static final String HTML = "text/html";

    /** A picture, as the bytes of an image file: PNG when written, any decodable kind when read. */
    public static final String IMAGE = "image/png";

    /** Files, as an RFC 2483 URI list. */
    public static final String FILES = "text/uri-list";

    /**
     * A picture as its pixels: the width and the height as two little-endian integers, then the
     * rows of straight-alpha RGBA, top first. Never announced to another program; it is what a
     * system's clipboard asks this one for when its own picture format is made of pixels.
     */
    public static final String PIXELS = "image/x-limn-pixels";

    private static final List<String> TEXT_SPELLINGS =
            List.of(TEXT, "UTF8_STRING", "text/plain", "TEXT", "STRING");
    private static final List<String> IMAGE_SPELLINGS =
            List.of(IMAGE, "image/jpeg", "image/bmp", "image/x-bmp", "image/gif");

    private ClipboardTypes() {
    }

    /**
     * The spellings to announce for a canonical type on X11 and Wayland, so that a program that
     * knows only an older one still finds it.
     *
     * @param canonical a canonical type
     * @return what to offer, the canonical name first
     */
    public static List<String> spellings(String canonical) {
        return canonical.equals(TEXT) ? TEXT_SPELLINGS : List.of(canonical);
    }

    /**
     * @param spelling a type another program asked for
     * @return the canonical type it is a spelling of, or itself
     */
    public static String canonical(String spelling) {
        return TEXT_SPELLINGS.contains(spelling) ? TEXT : spelling;
    }

    /**
     * What another program offers, as canonical types: one text and one image however many
     * spellings it wrote, every other type as it is.
     *
     * @param offered the type names as offered
     * @return the canonical types, in the order first met
     */
    public static List<String> canonical(List<String> offered) {
        List<String> out = new ArrayList<>();
        for (String type : offered) {
            String canonical = TEXT_SPELLINGS.contains(type) ? TEXT
                    : IMAGE_SPELLINGS.contains(type) ? IMAGE : type;
            if (!out.contains(canonical)) {
                out.add(canonical);
            }
        }
        return out;
    }

    /**
     * The spelling to ask another program for.
     *
     * @param canonical the canonical type wanted
     * @param offered   the type names as offered
     * @return the best spelling on offer, or null when there is none
     */
    public static @org.jspecify.annotations.Nullable String spellingIn(String canonical,
            List<String> offered) {
        List<String> spellings = canonical.equals(TEXT) ? TEXT_SPELLINGS
                : canonical.equals(IMAGE) ? IMAGE_SPELLINGS : List.of(canonical);
        for (String spelling : spellings) {
            if (offered.contains(spelling)) {
                return spelling;
            }
        }
        return null;
    }
}
