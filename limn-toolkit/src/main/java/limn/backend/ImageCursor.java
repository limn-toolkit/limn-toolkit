package limn.backend;

import limn.graphics.Image;

/**
 * A custom mouse cursor built from an {@link Image} (a themed crosshair, a
 * brush outline, a spell-target reticle), where the ten stock {@link Cursor}
 * shapes are not enough.
 *
 * <p>Widgets request one via {@link limn.scene.Widget#setImageCursor}; the
 * scene resolves it exactly like the standard shapes (walking up from the
 * hovered leaf, an image cursor winning over a shape declared on the same
 * widget) and pushes it to the window through
 * {@link NativeWindow#setImageCursor}.
 *
 * <p>The hotspot is the image pixel that sits on the click point (a crosshair
 * centers it; an arrow puts it at the tip). Two ImageCursors are equal when
 * they share the same {@link Image} <em>instance</em> and hotspot (images
 * compare by identity). Reuse one Image per cursor design, since the backend
 * caches the native cursor object it creates per distinct ImageCursor.
 *
 * <p>An {@linkplain Image#isAnimated() animated} image makes a cursor that plays: the window
 * shows each frame at the image's own times, as many turns as it asks for, with the one hotspot
 * for every frame, and keeps a native cursor of every frame while it is shown. It counts as one
 * cursor in the backend's cache however many frames it has. The system's wish for less motion is
 * not applied to it, as it is not to any animated image.
 *
 * <p>Platform note: the image is used at its pixel size 1:1 in screen
 * coordinates; there is no HiDPI variant selection at the native layer, so on
 * scaled displays a 32 px cursor appears visually smaller than UI content at
 * the same nominal size. Size cursors for the display class you target.
 *
 * @param image    the cursor bitmap (straight-alpha RGBA, top-down)
 * @param hotspotX hotspot x within the image, pixels, {@code [0, width)}
 * @param hotspotY hotspot y within the image, pixels, {@code [0, height)}
 */
public record ImageCursor(Image image, int hotspotX, int hotspotY) {

    public ImageCursor {
        if (image == null) {
            throw new IllegalArgumentException("image is null");
        }
        if (hotspotX < 0 || hotspotX >= image.width()
                || hotspotY < 0 || hotspotY >= image.height()) {
            throw new IllegalArgumentException("hotspot " + hotspotX + "," + hotspotY
                    + " outside " + image.width() + "x" + image.height() + " image");
        }
    }
}
