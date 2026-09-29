package limn.backend;

import java.util.Objects;
import limn.internal.lang.Checks;
import org.jspecify.annotations.Nullable;

/**
 * Initial configuration for a {@link NativeWindow}: start from {@link #of}, then change what differs
 * from a plain window with a wither.
 *
 * <pre>{@code
 * backend.createWindow(WindowConfig.of("Hello, Limn", 480, 320));
 * backend.createWindow(WindowConfig.of("Tools", 300, 500).resizable(false).floating(true));
 * }</pre>
 *
 * <p>A class and not a record: a record's canonical constructor is public, so every option
 * added to it broke every caller that listed the options by position, and six of the eleven were
 * booleans a reader could not tell apart. A new option is a new wither here, and no caller changes.
 *
 * <p>Immutable: every wither returns a copy.
 */
public final class WindowConfig {

    /**
     * {@link #screenX()}/{@link #screenY()} meaning <em>wherever the desktop would put it</em>,
     * which is the default and what every window wanted until one of them had to be reproducible.
     */
    public static final int ANY_POSITION = Integer.MIN_VALUE;

    private final String title;
    private final int width;
    private final int height;
    private final boolean visible;
    private final boolean resizable;
    private final boolean decorated;
    private final boolean floating;
    private final boolean transparent;
    private final boolean focusOnShow;
    private final int screenX;
    private final int screenY;
    private final SizeLimits sizeLimits;
    private final @Nullable NativeWindow owner;
    private final boolean popupOfOwner;
    private final float ownerX;
    private final float ownerY;

    private WindowConfig(String title, int width, int height, boolean visible, boolean resizable,
                         boolean decorated, boolean floating, boolean transparent,
                         boolean focusOnShow, int screenX, int screenY, SizeLimits sizeLimits) {
        this(title, width, height, visible, resizable, decorated, floating, transparent, focusOnShow,
                screenX, screenY, sizeLimits, null, false, Float.NaN, Float.NaN);
    }

    private WindowConfig(String title, int width, int height, boolean visible, boolean resizable,
                         boolean decorated, boolean floating, boolean transparent,
                         boolean focusOnShow, int screenX, int screenY, SizeLimits sizeLimits,
                         @Nullable NativeWindow owner, boolean popupOfOwner, float ownerX, float ownerY) {
        this.title = Objects.requireNonNull(title, "title");
        Checks.positiveSize(width, height, "window size");
        this.width = width;
        this.height = height;
        this.visible = visible;
        this.resizable = resizable;
        this.decorated = decorated;
        this.floating = floating;
        this.transparent = transparent;
        this.focusOnShow = focusOnShow;
        this.screenX = screenX;
        this.screenY = screenY;
        this.sizeLimits = sizeLimits;
        this.owner = owner;
        this.popupOfOwner = popupOfOwner;
        this.ownerX = ownerX;
        this.ownerY = ownerY;
    }

    /**
     * A plain window: visible, resizable, decorated, opaque, taking the focus when shown, wherever
     * the desktop puts it. The common case, and where every other one starts.
     *
     * @param title  title bar text
     * @param width  initial width in logical points
     * @param height initial height in logical points
     * @return the configuration
     */
    public static WindowConfig of(String title, int width, int height) {
        return new WindowConfig(title, width, height, true, true, true, false, false, true,
                ANY_POSITION, ANY_POSITION, SizeLimits.NONE);
    }

    /**
     * Undecorated, floating, transparent, non-focus-stealing window, created hidden so it can be
     * positioned before {@link NativeWindow#show()}: the shape of a combo/menu popup. It is
     * {@code owner}'s own content drawn outside its frame, so it closes and moves with the owner and
     * stays usable under a modal the owner hosts in its scene; place it with {@link #atInOwner}.
     *
     * @param owner  the window whose content this popup is
     * @param width  initial width in logical points
     * @param height initial height in logical points
     * @return the configuration
     * @throws NullPointerException if {@code owner} is {@code null}
     */
    public static WindowConfig popup(NativeWindow owner, int width, int height) {
        Objects.requireNonNull(owner, "owner");
        WindowConfig shape = of("popup", width, height).visible(false).resizable(false).decorated(false)
                .floating(true).transparent(true).focusOnShow(false);
        return shape.withOwner(owner, true);
    }

    /**
     * A styled window created hidden (position, then {@link NativeWindow#show()}).
     *
     * @param style       decoration/translucency (see {@link WindowStyle})
     * @param floating    always-on-top
     * @param focusOnShow whether showing it steals input focus
     */
    public static WindowConfig styled(String title, int width, int height, WindowStyle style,
                                      boolean floating, boolean focusOnShow) {
        return of(title, width, height).visible(false).resizable(false)
                .decorated(style.decorated()).floating(floating).transparent(style.transparent())
                .focusOnShow(focusOnShow);
    }

    /** @return title bar text */
    public String title() {
        return title;
    }

    /** @return initial width in logical points */
    public int width() {
        return width;
    }

    /** @return initial height in logical points */
    public int height() {
        return height;
    }

    /**
     * @return whether the window starts shown; {@code false} for offscreen or screenshot rendering and
     *         for a window positioned before {@link NativeWindow#show()}
     */
    public boolean visible() {
        return visible;
    }

    /** @return whether the user may resize the window */
    public boolean resizable() {
        return resizable;
    }

    /** @return whether the window has the native title bar and border ({@code false} for popups) */
    public boolean decorated() {
        return decorated;
    }

    /** @return whether the window stays above others (popups, tooltips) */
    public boolean floating() {
        return floating;
    }

    /**
     * @return whether the framebuffer is transparent: pixels with alpha &lt; 1 composite over whatever
     *         is behind the window (rounded popup corners, translucent panels)
     */
    public boolean transparent() {
        return transparent;
    }

    /**
     * @return whether {@link NativeWindow#show()} takes the input focus ({@code false} keeps it in the
     *         parent: combo popups)
     */
    public boolean focusOnShow() {
        return focusOnShow;
    }

    /** @return initial x in native screen coordinates, or {@link #ANY_POSITION} */
    public int screenX() {
        return screenX;
    }

    /** @return initial y in native screen coordinates, or {@link #ANY_POSITION} */
    public int screenY() {
        return screenY;
    }

    /**
     * @return the bounds the user may resize the window within; {@link SizeLimits#NONE} unless
     *         {@link #minSize} or {@link #maxSize} set one
     */
    public SizeLimits sizeLimits() {
        return sizeLimits;
    }

    /**
     * A copy the user cannot make smaller than {@code width}&times;{@code height}. A window whose
     * content stops being usable below some size says so here, where the window is made, rather
     * than with {@link NativeWindow#setSizeLimits} once it is on screen. The limits bound the
     * user's resizing; a window opened at a size outside them is brought within them as it opens.
     *
     * @param width  the narrowest, in logical points; {@code 0} leaves the width open
     * @param height the shortest; {@code 0} leaves the height open
     * @return a copy with that minimum and this configuration's maximum
     * @throws IllegalArgumentException when it would be above a maximum already set
     */
    public WindowConfig minSize(int width, int height) {
        return new WindowConfig(title, this.width, this.height, visible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY,
                new SizeLimits(width, height, sizeLimits.maxWidth(), sizeLimits.maxHeight()), owner, popupOfOwner, ownerX, ownerY);
    }

    /**
     * A copy the user cannot make larger than {@code width}&times;{@code height}; see
     * {@link #minSize}.
     *
     * @param width  the widest, in logical points; {@code 0} leaves the width open
     * @param height the tallest; {@code 0} leaves the height open
     * @return a copy with that maximum and this configuration's minimum
     * @throws IllegalArgumentException when it would be below a minimum already set
     */
    public WindowConfig maxSize(int width, int height) {
        return new WindowConfig(title, this.width, this.height, visible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY,
                new SizeLimits(sizeLimits.minWidth(), sizeLimits.minHeight(), width, height), owner, popupOfOwner, ownerX, ownerY);
    }

    /**
     * @param newTitle title bar text, and what a window without one is called where the platform
     *                 names windows (an accessibility tree, a window list)
     * @return a copy with that title
     * @throws NullPointerException if {@code newTitle} is {@code null}
     */
    public WindowConfig title(String newTitle) {
        return new WindowConfig(newTitle, width, height, visible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /** @return a copy that starts shown or hidden */
    public WindowConfig visible(boolean newVisible) {
        return new WindowConfig(title, width, height, newVisible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /** @return a copy the user may, or may not, resize */
    public WindowConfig resizable(boolean newResizable) {
        return new WindowConfig(title, width, height, visible, newResizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /** @return a copy with, or without, the native title bar and border */
    public WindowConfig decorated(boolean newDecorated) {
        return new WindowConfig(title, width, height, visible, resizable, newDecorated, floating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /** @return a copy that stays, or does not stay, above other windows */
    public WindowConfig floating(boolean newFloating) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, newFloating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /** @return a copy with a transparent, or opaque, framebuffer */
    public WindowConfig transparent(boolean newTransparent) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                newTransparent, focusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /** @return a copy that does, or does not, take the input focus when shown */
    public WindowConfig focusOnShow(boolean newFocusOnShow) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                transparent, newFocusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /**
     * A copy created at a given point in native screen coordinates, rather than wherever the
     * desktop decides.
     *
     * <p>Placing a window <em>after</em> it exists is {@link NativeWindow#setScreenPosition} and
     * has always been possible; this is the same thing one step earlier, and the step matters for
     * exactly one reason: <b>a window's content scale is the scale of the monitor it is created
     * on</b>. A window that lands somewhere the caller did not choose is a window whose
     * framebuffer is a size the caller did not choose, and no arithmetic afterwards can undo that
     * &mdash; it has to be right at construction. Everything else about placement is cosmetic and
     * belongs to the desktop.
     *
     * <p>Not every desktop allows it: see {@link limn.backend.spi.WindowPeer#supportsAbsolutePositioning()}. Where
     * it does not, the request is ignored and the window opens where the compositor puts it,
     * which is the same outcome as never asking.
     *
     * @param x native screen x, or {@link #ANY_POSITION} to hand the choice back
     * @param y native screen y
     * @return a copy carrying the position
     */
    public WindowConfig at(int x, int y) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                transparent, focusOnShow, x, y, sizeLimits, owner, popupOfOwner, ownerX, ownerY);
    }

    /**
     * A copy created on a chosen {@link Display}, at the origin of its work area.
     *
     * <p>The intent-revealing form of {@link #at(int, int)}, and the one worth reaching for: what
     * a caller who cares almost always means is <em>this monitor</em>, because that is what fixes
     * the content scale. A capture harness that must produce the same pixels on a laptop with
     * three monitors as on a runner with one asks for
     * {@link Backend#primaryDisplay() the primary display} and stops caring which machine it is.
     *
     * @param display the monitor to open on; {@code null} (a headless backend's answer) leaves
     *                the placement to the desktop, so a caller need not branch
     * @return a copy anchored to that display, or {@code this} when there is none
     */
    public WindowConfig on(@Nullable Display display) {
        if (display == null) {
            return this;
        }
        ScreenRect area = display.workArea();
        return at(area.x(), area.y());
    }

    /**
     * A copy owned by {@code owner}: a window in its own right that closes when the owner closes and
     * moves when it moves, and that a modal over the owner blocks like any other window. A
     * non-modal dialog, a floating palette, a video opened from a document. Given at creation, so
     * a platform that makes a child window from its parent has it then.
     *
     * @param owner the window this one belongs to
     * @return a copy owned by it
     * @throws NullPointerException if {@code owner} is {@code null}
     */
    public WindowConfig ownedBy(NativeWindow owner) {
        return withOwner(Objects.requireNonNull(owner, "owner"), false);
    }

    private WindowConfig withOwner(NativeWindow newOwner, boolean asPopup) {
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, newOwner, asPopup, ownerX, ownerY);
    }

    /**
     * A copy created with its top-left corner at a point of its owner's content area, in the
     * owner's logical points: the way a popup is placed against the widget that opened it. The
     * point may lie outside the owner, as a list dropping below the window's bottom edge does. The
     * backend turns it into the platform's position, relative where the platform places a child
     * against its parent, as Wayland does.
     *
     * @param x left edge, in the owner's logical points from its content area's left
     * @param y top edge, in the owner's logical points from its content area's top
     * @return a copy carrying the position
     * @throws IllegalStateException if this configuration has no owner
     * @throws IllegalArgumentException if a coordinate is NaN or infinite
     */
    public WindowConfig atInOwner(float x, float y) {
        if (owner == null) {
            throw new IllegalStateException("atInOwner needs an owner: use popup(owner, …) or ownedBy(owner)");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("a position in the owner must be finite: " + x + ", " + y);
        }
        return new WindowConfig(title, width, height, visible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, owner, popupOfOwner, x, y);
    }

    /** @return the window this one belongs to, or {@code null} for a window of the application's own */
    public @Nullable NativeWindow owner() {
        return owner;
    }

    /**
     * @return whether this is its owner's content drawn outside its frame ({@link #popup}), rather
     *         than a window in its own right that the owner merely owns ({@link #ownedBy})
     */
    public boolean isPopup() {
        return popupOfOwner;
    }

    /** @return whether {@link #atInOwner} placed it; {@link #ownerX()} and {@link #ownerY()} answer then */
    public boolean hasPositionInOwner() {
        return !Float.isNaN(ownerX);
    }

    /** @return the left edge in the owner's logical points, or NaN when {@link #atInOwner} was not given */
    public float ownerX() {
        return ownerX;
    }

    /** @return the top edge in the owner's logical points, or NaN when {@link #atInOwner} was not given */
    public float ownerY() {
        return ownerY;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof WindowConfig c && c.width == width && c.height == height
                && c.visible == visible && c.resizable == resizable && c.decorated == decorated
                && c.floating == floating && c.transparent == transparent
                && c.focusOnShow == focusOnShow && c.screenX == screenX && c.screenY == screenY
                && c.title.equals(title) && c.sizeLimits.equals(sizeLimits) && c.owner == owner
                && c.popupOfOwner == popupOfOwner && Float.compare(c.ownerX, ownerX) == 0
                && Float.compare(c.ownerY, ownerY) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, width, height, visible, resizable, decorated, floating,
                transparent, focusOnShow, screenX, screenY, sizeLimits, System.identityHashCode(owner),
                popupOfOwner, ownerX, ownerY);
    }

    @Override
    public String toString() {
        return "WindowConfig[" + title + ", " + width + "x" + height + (visible ? "" : ", hidden")
                + (resizable ? "" : ", fixed") + (decorated ? "" : ", undecorated")
                + (floating ? ", floating" : "") + (transparent ? ", transparent" : "")
                + (focusOnShow ? "" : ", no focus") + (screenX == ANY_POSITION ? "" : ", at "
                + screenX + "," + screenY) + (sizeLimits.equals(SizeLimits.NONE) ? "" : ", " + sizeLimits)
                + (owner == null ? "" : (popupOfOwner ? ", popup of " : ", owned by ") + owner.title())
                + (hasPositionInOwner() ? " at " + ownerX + "," + ownerY : "")
                + "]";
    }
}
