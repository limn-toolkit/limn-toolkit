package limn.backend;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * GPU rendering entry point for one window. Hands out the high-level
 * {@link Canvas} that widgets draw through; implementations own every GL call.
 * Nothing above the backend module may touch OpenGL.
 */
public interface GpuRenderer {

    /**
     * @return the canvas for the current frame, only valid while inside a
     *         {@link FrameCallback}
     */
    Canvas canvas();

    /** Clears the framebuffer to the given non-premultiplied RGBA color (0–1). */
    void clear(float red, float green, float blue, float alpha);

    default void clear(Color color) {
        clear(color.r(), color.g(), color.b(), color.a());
    }

    /**
     * Schedules a capture of this frame: after the frame callback returns and
     * all batched geometry is flushed to the framebuffer (and before the buffer
     * swap), the pixels are read back and handed to {@code sink}. This is the
     * basis of the {@code --screenshot} verification mode, and the way an
     * application gets what a window is showing without writing a file. Must be
     * called from inside a {@link FrameCallback}; {@code sink} runs on the UI
     * thread, still inside that frame, before the swap.
     *
     * <p>The image is <b>display-referred already</b>: a window's framebuffer
     * holds what the composite produced, sRGB-encoded, tonemapped if anything
     * in the frame needed it. The wrong edit this prevents is applying a display
     * transform to it, which would tonemap the frame a second time; that
     * transform belongs to {@link limn.graphics.ReadableSurface}, whose subject
     * is an offscreen target that has <em>not</em> been through the composite.
     *
     * <p>Alpha is straight and the rows are top-down, as {@link Image} requires,
     * whatever the underlying graphics API's own conventions are. The read is a
     * synchronous GPU stall by construction; it is the point where the CPU
     * waits for the frame it just submitted.
     */
    void captureFramebuffer(Consumer<Image> sink);

    /**
     * Schedules a capture of this frame to a PNG file, creating the parent
     * directories if they are missing.
     *
     * <p>Deliberately a thin default over {@link #captureFramebuffer(Consumer)}
     * plus {@link Images#save}: the toolkit has exactly one PNG writer, and a
     * renderer that grew its own would be a second one that drifts.
     */
    default void captureFramebuffer(Path pngFile) {
        java.util.Objects.requireNonNull(pngFile, "pngFile");
        captureFramebuffer(image -> Images.save(image, ImageFormat.PNG, pngFile));
    }
}
