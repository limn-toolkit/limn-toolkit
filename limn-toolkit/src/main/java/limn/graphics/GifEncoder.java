package limn.graphics;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The built-in GIF encoder: an animated image with every frame, each with its time, and the turns
 * it asks for; a still image as one picture. Installed when {@link Images} loads, after
 * {@link PngEncoder}, and here by name for an application that emptied the encoders and wants
 * this one back: {@code Images.installEncoder(GifEncoder.INSTANCE)}.
 *
 * <p>A GIF holds 256 colours, one of which stands for a pixel that is not there. A picture of
 * more is written in the ones that stand for it best, chosen once for a whole animation and with
 * no dithering, and a pixel less than half there is written as not there. The quality asked for
 * is not used: there is nothing in the format for it to turn.
 */
public final class GifEncoder implements ImageEncoder {

    /** The one instance; it holds no state. */
    public static final ImageEncoder INSTANCE = new GifEncoder();

    private GifEncoder() {
    }

    @Override
    public String name() {
        return "gif";
    }

    @Override
    public boolean supports(ImageEncodeOptions options) {
        return ImageFormat.GIF.equals(options.format());
    }

    @Override
    public void encode(Image image, ImageEncodeOptions options, OutputStream out) throws IOException {
        out.write(GifWriter.encode(image));
    }
}
