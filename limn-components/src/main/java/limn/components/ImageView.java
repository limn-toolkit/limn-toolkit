package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.Objects;

/**
 * Displays an {@link Image}: a full-color picture, or a monochrome/mask icon
 * when a {@link #setTint tint} is set (the image's alpha becomes the shape,
 * recolored by the tint, so an icon follows the theme). {@link Fit} controls
 * how the image maps into the widget bounds.
 */
public class ImageView extends Widget {

    public enum Fit {
        /** Scale to fill the box, preserving aspect ratio; may crop. */
        COVER,
        /** Scale to fit inside the box, preserving aspect ratio; may letterbox. */
        CONTAIN,
        /** Stretch to the box, ignoring aspect ratio. */
        FILL,
        /** Natural pixel size (1px = 1 logical point), centered. */
        NONE
    }

    private Image image;
    private Fit fit = Fit.CONTAIN;
    private Color tint;
    private float preferredWidth = -1;
    private float preferredHeight = -1;

    /** Shows an image at its natural size until the layout says otherwise. */
    public ImageView(Image image) {
        this.image = image;
    }

    /** Replaces the image, re-measuring if its natural size differs. UI thread only. */
    public ImageView setImage(Image newImage) {
        Ui.checkUiThread();
        this.image = newImage;
        markNeedsLayout();
        return this;
    }

    /** How the image fills its box when the two aspect ratios differ. */
    public ImageView setFit(Fit newFit) {
        Ui.checkUiThread();
        this.fit = Objects.requireNonNull(newFit, "newFit");
        invalidate();
        return this;
    }

    /** Tints the image (icon mode); {@code null} draws it in full color. */
    public ImageView setTint(Color newTint) {
        Ui.checkUiThread();
        this.tint = newTint;
        invalidate();
        return this;
    }

    /** Fixed preferred size in logical points ({@code -1} = natural image size). */
    public ImageView setPreferredSize(float width, float height) {
        Ui.checkUiThread();
        this.preferredWidth = width;
        this.preferredHeight = height;
        markNeedsLayout();
        return this;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        float w = preferredWidth >= 0 ? preferredWidth : (image != null ? image.width() : 0);
        float h = preferredHeight >= 0 ? preferredHeight : (image != null ? image.height() : 0);
        return constraints.constrain(w, h);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        if (image == null) {
            return;
        }
        float boxW = width();
        float boxH = height();
        float imgW = image.width();
        float imgH = image.height();
        float drawW;
        float drawH;
        switch (fit) {
            case FILL -> {
                drawW = boxW;
                drawH = boxH;
            }
            case NONE -> {
                drawW = imgW;
                drawH = imgH;
            }
            case COVER -> {
                float scale = Math.max(boxW / imgW, boxH / imgH);
                drawW = imgW * scale;
                drawH = imgH * scale;
            }
            default -> { // CONTAIN
                float scale = Math.min(boxW / imgW, boxH / imgH);
                drawW = imgW * scale;
                drawH = imgH * scale;
            }
        }
        float x = (boxW - drawW) / 2;
        float y = (boxH - drawH) / 2;
        boolean clip = fit == Fit.COVER || fit == Fit.NONE;
        if (clip) {
            canvas.save();
            canvas.clipRect(0, 0, boxW, boxH);
        }
        if (tint != null) {
            Color effective = isEnabled() ? tint : Theme.current().disabledText;
            canvas.drawImage(image, x, y, drawW, drawH, effective);
        } else {
            canvas.drawImage(image, x, y, drawW, drawH);
        }
        if (clip) {
            canvas.restore();
        }
    }
}
