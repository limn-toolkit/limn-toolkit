package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.animation.ImageAnimation;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.SvgIcon;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragStartEvent;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.Transfer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Displays an {@link Image}: a full-color picture, or a monochrome/mask icon
 * when a {@link #setTint tint} is set (the image's alpha becomes the shape,
 * recolored by the tint, so an icon follows the theme). {@link Fit} controls
 * how the image maps into the widget bounds.
 *
 * <p><b>Or an {@link Icon}</b>, drawn at the view's own size and density each time either changes:
 * an SVG illustration stays sharp at any size and on any screen. An icon's natural size is an
 * SVG's own, a bitmap icon's largest picture, and 24 points for any other. {@link Fit}, the tint
 * and dragging behave as for an image, a tintable icon taking the tint as its colour; an animated
 * icon plays here with the controls an animated image has.
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
    /** The icon shown instead of an image, or null. */
    private @Nullable Icon icon;
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
     * Shows an icon at its natural size until the layout says otherwise, drawn at the view's own
     * size and density.
     *
     * @param icon the icon, or {@code null} for nothing
     */
    public ImageView(@Nullable Icon icon) {
        this.icon = icon;
        this.animation = icon != null && icon.isAnimated() ? new ImageAnimation(timing(icon)) : null;
    }

    /**
     * Replaces what is shown with an icon, re-measuring if its natural size differs. An animated
     * icon is played from its first frame, unless {@link #setPlaying setPlaying(false)} holds it.
     * UI thread only.
     *
     * @param newIcon the icon, or {@code null} for nothing
     * @return this view
     */
    public ImageView setIcon(@Nullable Icon newIcon) {
        Ui.checkUiThread();
        wake();
        this.image = null;
        this.icon = newIcon;
        this.animation = newIcon != null && newIcon.isAnimated()
                ? new ImageAnimation(timing(newIcon)).setSpeed(speed).setLoop(loop).setPlaying(playing) : null;
        markNeedsLayout();
        invalidate();
        return this;
    }

    /** @return the icon shown, or {@code null} when the view shows an image or nothing */
    public @Nullable Icon icon() {
        return icon;
    }

    /** @return the image shown, or {@code null} when the view shows an icon or nothing */
    public @Nullable Image image() {
        return image;
    }

    /**
     * An icon's frames and times as an image of one-pixel frames: the clockwork plays it as it
     * plays a picture, and the frame it reaches is the icon's frame drawn.
     */
    private static Image timing(Icon icon) {
        List<Image> frames = new ArrayList<>(icon.frameCount());
        int[] millis = new int[icon.frameCount()];
        for (int f = 0; f < millis.length; f++) {
            frames.add(new Image(1, 1, new byte[4]));
            millis[f] = Math.max(1, icon.frameMillis(f));
        }
        return Images.animation(frames, millis, icon.loopCount());
    }

    /** The default side of an icon that says nothing of its own size. */
    private static final float ICON_SIDE = 24;

    /** @return the natural width of what is shown, in logical points */
    private float naturalWidth() {
        return natural()[0];
    }

    private float naturalHeight() {
        return natural()[1];
    }

    /** The natural size of the icon shown, read once per icon. */
    private float @Nullable [] iconNatural;
    private @Nullable Icon iconNaturalOf;

    private float[] natural() {
        if (image != null) {
            return new float[] {image.width(), image.height()};
        }
        if (icon == null) {
            return new float[] {0, 0};
        }
        if (iconNaturalOf != icon || iconNatural == null) {
            iconNatural = naturalOf(icon);
            iconNaturalOf = icon;
        }
        return iconNatural;
    }

    private static float[] naturalOf(Icon icon) {
        if (icon instanceof SvgIcon svg) {
            try {
                return new float[] {svg.width(), svg.height()};
            } catch (IllegalStateException noRenderer) {
                return new float[] {ICON_SIDE, ICON_SIDE};
            }
        }
        if (icon instanceof limn.graphics.BitmapIcon) {
            Image largest = icon.image(Integer.MAX_VALUE, false);
            return new float[] {largest.width(), largest.height()};
        }
        return new float[] {ICON_SIDE, ICON_SIDE};
    }

    /**
     * Replaces the image, re-measuring if its natural size differs. An animated image is played
     * from its first frame, unless {@link #setPlaying setPlaying(false)} holds it. UI thread only.
     */
    public ImageView setImage(@Nullable Image newImage) {
        Ui.checkUiThread();
        wake();
        this.image = newImage;
        this.icon = null;
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
            wake();
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
            wake();
            animation.setSpeed(newSpeed);
            invalidate(); // the paint sets the clock for the new speed
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
            wake();
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
        Objects.checkIndex(frame, image != null ? image.frameCount() : icon != null ? icon.frameCount() : 0);
        if (animation != null) {
            wake();
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

    /**
     * Starts the clock of the animation shown, from the paint that shows it.
     *
     * <p>The clock does not run every frame of the display. Between two frames of the picture
     * it sleeps, on a delayed task set for the moment the next one is due; woken, it moves the
     * animation on by the scene's time it slept and asks for the one frame that draws the
     * change. A picture of ten frames a second costs ten frames a second, whatever the rate of
     * the display. It runs as a ticker, every frame of the display, only while the frame that
     * is due is still being decoded, or while the scene's time is stopped, when the scene asks
     * for no frame at all.
     */
    private void keepTime() {
        limn.scene.Scene home = scene();
        ImageAnimation shown = animation;
        if (ticking || alarm != null || home == null || shown == null || !shown.isPlaying()) {
            return;
        }
        step(home, shown, 0);
    }

    /** Moves the animation on and sets the clock for its next frame: asleep, or ticking. */
    private void step(limn.scene.Scene home, ImageAnimation shown, double seconds) {
        shown.advance(seconds);
        if (shown.frame() != painted) {
            invalidate(); // its time came, or a frame being decoded as it plays has arrived
        }
        if (shown.isFinished() && onFinished != null) {
            onFinished.run();
        }
        if (!shown.isPlaying() || animation != shown) {
            return;
        }
        double rate = home.isPaused() ? 0 : home.timeScale();
        double due = shown.secondsUntilNextFrame();
        if (due > 0 && rate > 0) {
            sleep(home, shown, due / rate);
        } else if (!ticking) {
            tick(home, shown); // late, or the scene's time is stopped
        }
    }

    private void tick(limn.scene.Scene home, ImageAnimation shown) {
        ticking = true;
        home.addTicker(seconds -> {
            if (!stillFor(home, shown)) {
                ticking = false;
                return false;
            }
            step(home, shown, seconds);
            if (alarm != null || !shown.isPlaying()) {
                ticking = false; // asleep again, or at its end
                return false;
            }
            return true;
        });
    }

    /**
     * Whether the clock is still for what it was set for. It stops with another image, a view
     * out of its scene, or one nobody sees, which the next paint starts again.
     */
    private boolean stillFor(limn.scene.Scene home, ImageAnimation shown) {
        return animation == shown && scene() == home && isShowing();
    }

    /** When the clock went to sleep, on the scene's clock; -1 when it is not asleep. */
    private long sleptAt = -1;
    /** The wake of a clock asleep, or null. */
    private limn.concurrent.@Nullable Subscription alarm;

    private void sleep(limn.scene.Scene home, ImageAnimation shown, double wallSeconds) {
        sleptAt = home.clock().getAsLong();
        alarm = Ui.postDelayed(() -> {
            alarm = null;
            if (stillFor(home, shown)) {
                double seconds = slept(home);
                sleptAt = -1;
                step(home, shown, seconds);
            } else {
                sleptAt = -1;
            }
        }, Math.max(1, (long) Math.ceil(wallSeconds * 1000)));
    }

    /** @return the scene's seconds since the clock went to sleep */
    private double slept(limn.scene.Scene home) {
        double wall = (home.clock().getAsLong() - sleptAt) / 1e9;
        return Math.max(0, wall) * (home.isPaused() ? 0 : home.timeScale());
    }

    /**
     * Counts the time a sleeping clock has slept and cancels its wake, before a change that the
     * time already passed must not be measured by: a new speed, frame, turn count or image. The
     * paint the change asks for sets the clock again.
     */
    private void wake() {
        limn.scene.Scene home = scene();
        if (alarm != null) {
            alarm.cancel();
            alarm = null;
            if (home != null && animation != null && sleptAt >= 0) {
                animation.advance(slept(home));
            }
        }
        sleptAt = -1;
    }

    @Override
    protected void onDetached() {
        super.onDetached();
        wake(); // a view out of its scene is not woken, nor held by a task on the calendar
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
        if (picture == null && icon != null && width() > 0 && height() > 0) {
            picture = iconBitmap(Math.max(width(), height()), 1);
        }
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
        float w = preferredWidth >= 0 ? preferredWidth : naturalWidth();
        float h = preferredHeight >= 0 ? preferredHeight : naturalHeight();
        return constraints.constrain(w, h);
    }

    /** The icon drawn so that {@code side} logical points are its longer side, at the frame of the moment. */
    private Image iconBitmap(float side, float contentScale) {
        Icon shown = icon;
        int px = Math.max(1, Math.min(8192, Math.round(side * contentScale)));
        int frame = animation != null ? animation.frameIndex() : 0;
        return shown.image(px, Theme.of(this).isDark(), frame);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        if (image == null && icon == null) {
            return;
        }
        // The frame of the moment, for an image that moves; its size is the image's.
        Image image = animation != null && icon == null ? animation.frame() : this.image;
        painted = animation != null ? animation.frame() : image;
        keepTime();
        float boxW = width();
        float boxH = height();
        float imgW = image != null ? image.width() : naturalWidth();
        float imgH = image != null ? image.height() : naturalHeight();
        if (!(imgW > 0) || !(imgH > 0)) {
            return;
        }
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
        boolean mask = false;
        if (image == null) {
            // An icon, drawn for the box it fills at this screen's density.
            image = iconBitmap(Math.max(drawW, drawH), canvas.contentScale());
            mask = icon.tintable();
        }
        if (tint != null) {
            Color effective = isEnabled() ? tint : Theme.of(this).disabledText();
            if (mask) {
                canvas.drawImageMask(image, x, y, drawW, drawH, effective);
            } else {
                canvas.drawImage(image, x, y, drawW, drawH, effective);
            }
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
