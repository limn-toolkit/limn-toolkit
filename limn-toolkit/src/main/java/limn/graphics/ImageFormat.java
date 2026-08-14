package limn.graphics;

import java.util.Locale;
import java.util.Objects;

/**
 * An encoded image format, named by its IANA media type: what a caller asks
 * {@link Images#encode(Image, ImageFormat)} for and what an {@link ImageEncoder}
 * declares it can produce.
 *
 * <p>Deliberately a value and not an enum. A format is contributed by whichever
 * encoder is installed, so a closed set would mean that adding JPEG or WebP
 * required a toolkit release even though nothing in the toolkit would encode it.
 * The price is that a misspelled media type is a runtime failure ("no encoder
 * accepts …") rather than a compile error, which is why {@link #PNG} is a
 * constant: the one format the toolkit itself always provides never has to be
 * spelled out.
 *
 * <p>The media type <em>is</em> the identity. It is trimmed and lower-cased on
 * construction, so {@code new ImageFormat("IMAGE/PNG")} equals {@link #PNG}, and
 * an encoder may compare with {@code equals} rather than parsing the string.
 * Parameters are not understood: {@code "image/png; foo=bar"} is a different
 * format from {@code "image/png"} and nothing will accept it.
 *
 * @param mediaType lower-case IANA media type, e.g. {@code "image/png"}
 */
public record ImageFormat(String mediaType) {

    /**
     * Portable Network Graphics: lossless, 8-bit RGBA with straight alpha,
     * which is exactly {@link Image}'s pixel contract, so a PNG round trip
     * through this toolkit is bit-exact. Always encodable: the toolkit's own
     * encoder is installed without a backend (see {@link Images#installEncoder}).
     */
    public static final ImageFormat PNG = new ImageFormat("image/png");

    public ImageFormat {
        Objects.requireNonNull(mediaType, "mediaType");
        mediaType = mediaType.trim().toLowerCase(Locale.ROOT);
        if (mediaType.indexOf('/') <= 0) {
            throw new IllegalArgumentException(
                    "media type must look like type/subtype, got \"" + mediaType + "\"");
        }
    }

    @Override
    public String toString() {
        return mediaType;
    }
}
