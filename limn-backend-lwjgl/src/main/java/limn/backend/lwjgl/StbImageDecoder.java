package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * {@link ImageDecoder} backed by stb_image. Decodes to 4-channel RGBA with
 * straight alpha, top-down (row 0 = top) to match the canvas UV convention.
 * Pure CPU: no GL context needed, since the texture is uploaded later, at draw time.
 */
final class StbImageDecoder implements ImageDecoder {

    /**
     * The most pixels one image decodes to: 2<sup>28</sup>, a 16384&times;16384 image, 1 GiB of RGBA,
     * which also keeps {@code width * height * 4} inside an {@code int}. The header is read first
     * and a larger image refused before stb allocates anything: a file of a kilobyte whose header
     * claimed 23000&times;23000 decoded to 2 GB, and past about 23170&sup2; the array size overflowed.
     * Images come from users' files and from the colour bitmaps of an emoji font.
     */
    static final long MAX_PIXELS = 1L << 28;

    @Override
    public Image decode(byte[] fileBytes) {
        ByteBuffer encoded = OffHeap.copyOf(fileBytes);
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                if (!STBImage.stbi_info_from_memory(encoded, width, height, channels)) {
                    throw new IllegalArgumentException(
                            "stbi_info_from_memory failed: " + STBImage.stbi_failure_reason());
                }
                long claimed = (long) width.get(0) * height.get(0);
                if (claimed > MAX_PIXELS) {
                    throw new IllegalArgumentException("an image of " + width.get(0) + "x"
                            + height.get(0) + " pixels is larger than " + MAX_PIXELS
                            + " pixels, the most one image decodes to");
                }
                // stb loads top-down by default; force 4 channels (RGBA).
                STBImage.stbi_set_flip_vertically_on_load(false);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
                if (pixels == null) {
                    throw new IllegalArgumentException(
                            "stbi_load_from_memory failed: " + STBImage.stbi_failure_reason());
                }
                try {
                    int w = width.get(0);
                    int h = height.get(0);
                    byte[] rgba = new byte[w * h * 4];
                    // Absolute get: do NOT advance the buffer position, or
                    // stbi_image_free would free the wrong (offset) address.
                    pixels.get(0, rgba);
                    return new Image(w, h, rgba);
                } finally {
                    STBImage.stbi_image_free(pixels);
                }
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }
}
