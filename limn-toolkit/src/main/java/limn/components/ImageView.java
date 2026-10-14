package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.animation.ImageAnimation;
import limn.graphics.Image;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragStartEvent;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.Transfer;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Displays an {@link Image}: a full-color picture, or a monochrome/mask icon
 * when a {@link #setTint tint} is set (the image's alpha becomes the shape,
 * recolored by the tint, so an icon follows the theme). {@link Fit} controls
 * how the image maps into the widget bounds.
 *
 * <p>A view can let its picture be <b>dragged</b> to another widget or another program
 * ({@link #setDragEnabled}); it does not unless asked, most pictures in a form being decoration.
 */
public final class ImageView extends Widget<ImageView> {

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
    /** The clockwork of an animated image; null for a still one. Before the constructor, which makes it. */
    private @Nullable ImageAnimation animation;
    private boolean playing = true;
    private float speed = 1;
    private ImageAnimation.Loop loop = ImageAnimation.Loop.FILE;
    private Fit fit = Fit.CONTAIN;
    private Color tint;
    private float preferredWidth = -1;
    private float preferredHeight = -1;
    private boolean dragEnabled;
    private boolean dragWithinWindow;
    private @Nullable String dragFileName;

    /** The longest side of the picture shown under the pointer while a large view is dragged. */
    private static final float DRAG_PREVIEW_MAX = 240;

    /** Shows an image at its natural size until the layout says otherwise. */
    public ImageView(@Nullable Image image) {
        this.image = image;
        this.animation = image != null && image.isAnimated() ? new ImageAnimation(image) : null;
    }

    /**
     * Replaces the image, re-measuring if its natural size differs. An animated image is played
     * from its first frame, unless {@link #setPlaying setPlaying(false)} holds it. UI thread only.
     */
    public ImageView setImage(@Nullable Image newImage) {
        Ui.checkUiThread();
        this.image = newImage;
        this.animation = newImage != null && newImage.isAnimated()
                ? new ImageAnimation(newImage).setSpeed(speed).setLoop(loop).setPlaying(playing) : null;
        markNeedsLayout();
        invalidate();
        return this;
    }

    // ------------------------------------------------------------- animation

    private boolean ticking;
    /** The frame the last paint drew, which the clock compares the frame of the moment with. */
    private @Nullable Image painted;
    private @Nullable Runnable onFinished;

    /**
     * Plays an animated image, or holds the frame shown. A view plays unless told not to: a
     * picture that moves is expected to, and makes no sound. Playing one that has finished
     * starts it again. Nothing happens to a still image, and the setting is kept for the next
     * image the view is given. UI thread only.
     */
    public ImageView setPlaying(boolean play) {
        Ui.checkUiThread();
        this.playing = play;
        if (animation != null) {
            animation.setPlaying(play);
            invalidate(); // the paint is what starts the clock again
        }
        return this;
    }

    /** @return whether an animated image is moving now: playing, and not at its end */
    public boolean isPlaying() {
        return animation != null && animation.isPlaying();
    }

    /**
     * Sets how fast an animated image plays: 1 is the image's own times, 2 twice as fast.
     *
     * @throws IllegalArgumentException if {@code newSpeed} is not above 0
     */
    public ImageView setSpeed(float newSpeed) {
        Ui.checkUiThread();
        if (!(newSpeed > 0) || Float.isInfinite(newSpeed)) {
            throw new IllegalArgumentException("a speed is above 0: " + newSpeed);
        }
        this.speed = newSpeed;
        if (animation != null) {
            animation.setSpeed(newSpeed);
        }
        return this;
    }

    /**
     * Sets how many turns an animated image makes: as many as the image asks for
     * ({@link ImageAnimation.Loop#FILE}, unless set), without end, once, a number of times, or
     * forward and back.
     */
    public ImageView setLoop(ImageAnimation.Loop newLoop) {
        Ui.checkUiThread();
        this.loop = Objects.requireNonNull(newLoop, "newLoop");
        if (animation != null) {
            animation.setLoop(newLoop);
            invalidate();
        }
        return this;
    }

    /**
     * Shows one frame of an animated image, from the start of its time.
     *
     * @throws IndexOutOfBoundsException if the image has no such frame
     */
    public ImageView seekFrame(int frame) {
        Ui.checkUiThread();
        Objects.checkIndex(frame, image == null ? 0 : image.frameCount());
        if (animation != null) {
            animation.seekFrame(frame);
            invalidate();
        }
        return this;
    }

    /** @return which frame of an animated image is shown, from 0; 0 for a still image */
    public int frameIndex() {
        return animation == null ? 0 : animation.frameIndex();
    }

    /**
     * Sets what is told when an animated image's last turn ends. One that turns without end
     * never tells.
     *
     * @param handler the handler, or {@code null} for none
     */
    public ImageView onFinished(@Nullable Runnable handler) {
        Ui.checkUiThread();
        this.onFinished = handler;
        return this;
    }

    /** Starts the clock of the animation shown, from the paint that shows it. */
    private void keepTime() {
        limn.scene.Scene home = scene();
        ImageAnimation shown = animation;
        if (ticking || home == null || shown == null || !shown.isPlaying()) {
            return;
        }
        ticking = true;
        home.addTicker(seconds -> {
            // The clock stops with what it was for: another image, a view out of its scene, or
            // one nobody sees, which the next paint starts again.
            if (animation != shown || scene() != home || !isShowing()) {
                ticking = false;
                return false;
            }
            shown.advance(seconds);
            if (shown.frame() != painted) {
                invalidate(); // its time came, or a frame being decoded as it plays has arrived
            }
            if (shown.isFinished() && onFinished != null) {
                onFinished.run();
            }
            if (!shown.isPlaying()) {
                ticking = false;
                return false;
            }
            return true;
        });
    }

    /** How the image fills its box when the two aspect ratios differ. */
    public ImageView setFit(Fit newFit) {
        Ui.checkUiThread();
        this.fit = Objects.requireNonNull(newFit, "newFit");
        invalidate();
        return this;
    }

    /** Tints the image (icon mode); {@code null} draws it in full color. */
    public ImageView setTint(@Nullable Color newTint) {
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

    /**
     * Lets the user drag the picture out of this view (default off), as
     * {@link DataFormat#IMAGE}, for a target to copy: a drop area of this program, an editor, a
     * folder. A target may call what it did a move; the view keeps its picture all the same. What follows the pointer is the picture as the view shows it, with its own
     * transparency, made smaller when the view is large. From the keyboard, the scene's drag key
     * picks it up when the view is focusable. UI thread only.
     *
     * @param enabled whether the picture can be dragged
     * @return this view
     */
    public ImageView setDragEnabled(boolean enabled) {
        Ui.checkUiThread();
        dragEnabled = enabled;
        return this;
    }

    /**
     * Has a dragged picture offered as a file as well, under this name (default: not). Some
     * targets take a picture only as a file, the desktop and a file manager among them; for
     * those the picture is written as a PNG to the temporary directory when one asks for it, see
     * {@link Transfer#withImageAsFile}. It is not done unless asked, since it writes to disk.
     * UI thread only.
     *
     * @param name the file's name, such as {@code "logo.png"}, or {@code null} for no file
     * @return this view
     */
    public ImageView setDragFileName(@Nullable String name) {
        Ui.checkUiThread();
        dragFileName = name;
        return this;
    }

    /**
     * Keeps a drag of the picture inside its window (default: not): no other program is offered
     * it, and no other window. UI thread only.
     *
     * @param within whether a drag stays in the window it began in
     * @return this view
     */
    public ImageView setDragWithinWindow(boolean within) {
        Ui.checkUiThread();
        dragWithinWindow = within;
        return this;
    }

    /** @return whether a drag of the picture stays in its window */
    public boolean isDragWithinWindow() {
        return dragWithinWindow;
    }

    /** @return whether the picture can be dragged */
    public boolean isDragEnabled() {
        return dragEnabled;
    }

    @Override
    protected @Nullable Drag onDragStart(DragStartEvent event) {
        Image picture = image;
        if (!dragEnabled || picture == null || width() <= 0 || height() <= 0) {
            return null;
        }
        float scale = Math.min(1, DRAG_PREVIEW_MAX / Math.max(width(), height()));
        float holdX = event.isKeyboard() ? 0 : (event.x() - localToSceneX()) * scale;
        float holdY = event.isKeyboard() ? 0 : (event.y() - localToSceneY()) * scale;
        // Named for a keyboard drag by the scene, from the name the application gave the view.
        Transfer content = Transfer.of(DataFormat.IMAGE, picture);
        String file = dragFileName;
        Drag drag = Drag.of(file == null ? content : content.withImageAsFile(file));
        if (dragWithinWindow) {
            drag.withinWindow();
        }
        return drag
                // A copy is what is proposed; a move is allowed beside it because some programs
                // take a picture only from a source that allows one, and say they moved it when
                // they have only taken it. The view keeps its picture either way.
                .allow(limn.transfer.DropAction.COPY, limn.transfer.DropAction.MOVE)
                .preview(width() * scale, height() * scale, holdX, holdY, canvas -> {
                    canvas.save();
                    try {
                        canvas.scale(scale, scale);
                        onPaint(canvas);
                    } finally {
                        canvas.restore();
                    }
                });
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
        // The frame of the moment, for an image that moves; its size is the image's.
        Image image = animation != null ? animation.frame() : this.image;
        painted = image;
        keepTime();
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
            Color effective = isEnabled() ? tint : Theme.of(this).disabledText();
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
