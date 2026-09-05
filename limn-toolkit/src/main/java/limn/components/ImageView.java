package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
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

    /**
     * Publishes this view as one {@link Accessible.Role#IMAGE} node, always, and declares nothing
     * else about it.
     *
     * <p>The role is the whole announcement, and it is what makes the widget describe itself at
     * all. A picture is information by construction — it is the application's, not the toolkit's,
     * and nothing else in the tree carries it — so the class may not answer the paints-and-says-
     * nothing warning with {@code paintsDecoration()}, which is the seam for a wash or a rule and
     * would delete every picture in silence. It also fixes a quieter fault: a view an application
     * had already named, or one carrying a tooltip, survived the transparency predicate on the
     * strength of that name and published under the builder's default role, so the very case a
     * picture is named for reached a reader as a group box wearing the picture's name.
     *
     * <p>No name is declared, on purpose. The widget holds no {@link limn.i18n.I18nString} and an
     * {@link Image} carries only its size and its pixels, so there is nothing here to derive one
     * from, and a caption sitting beside a picture is never read as its name. All three ways a
     * picture does get one stay the application's and cost this class nothing: a tooltip through
     * the walk's free default, a bound caption, or {@code setAccessibleName}. Nothing is formatted,
     * so a damaged frame that changed nothing allocates no memory to say so.
     *
     * <p>Striking a picture out is likewise the application's call through
     * {@code setAccessibleIgnored(true)} and never this class's, including when it holds no image
     * yet. A class-level ignore is consulted after the application's own name, caption and role
     * have been written, so it would silently overrule all three with no way to appeal — and on a
     * photograph that has not loaded it would make the node appear and vanish as pictures arrive,
     * throwing away the alt text a reader was given and churning structure for a fact nobody asked
     * about.
     *
     * <p>The published box is the widget's own and never the ink. {@link Fit} centres the drawing
     * and lets it letterbox or overflow, so the two genuinely differ; but the box is what
     * {@code hitTest} claims, and a node cut down to the drawing would put a magnifier's cursor
     * somewhere the pointer does not agree with, at the price of running the fit arithmetic again
     * on every publish.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.IMAGE);
    }
}
