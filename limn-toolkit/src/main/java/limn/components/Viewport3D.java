package limn.components;

import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.math.Aabb;
import limn.math.Mat4;
import limn.math.Ray;
import limn.math.Vec3;
import limn.render3d.Camera;
import limn.render3d.CameraController;
import limn.render3d.Graphics3D;
import limn.render3d.RenderPass;
import limn.render3d.RenderTarget;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A viewport that renders 3D content into an offscreen {@link RenderTarget} (with
 * MSAA) and composites it as a normal 2D layer, proving that GPU-rendered content
 * respects the scene's layering: overlays, dialogs, tooltips and clipping all
 * apply to it because it draws as one quad in the paint order.
 *
 * <p>For this milestone it shows the backend's built-in demo scene (a spinning,
 * depth-tested cube). It drives a continuous repaint while showing (like the
 * animation system) and pauses automatically when hidden; static content should
 * opt out via {@link #setAnimated setAnimated(false)} to repaint only on
 * invalidation. Its render target is released on detach via
 * {@link Scene#disposeLater} (deferred to a frame with the owning GL context
 * current).
 *
 * <p><b>Size axis:</b> this widget does not participate. Its content is a GPU render
 * target sized in device pixels by {@link #setPreferredSize} and the layout, not a
 * control laid out from a metric row, so no {@link limn.scene.ControlSize} step
 * changes what the 3D scene looks like. The one thing that <em>is</em> chrome (the
 * "no GPU backend" placeholder frame and its message) reads the row resolved on this
 * widget like every other component, so a viewport dropped into an XSMALL panel does
 * not fall back with a MEDIUM corner radius and MEDIUM body type.
 *
 * <p><b>Direction axis:</b> this widget does not participate either, and for a sharper reason
 * than the size one. A rendered scene is content, so a right-to-left window shows the same world
 * from the same side; and {@link #rayAt} takes viewport-local pixels, which are where the pointer
 * physically is, so picking, orbiting and the camera's normalised device coordinates all stay
 * physical. Reflecting any of them would put the pick on the far side of the scene from the
 * cursor. The chrome carve-out is the same one: the placeholder message is shaped for the
 * direction resolved on this widget, and stays centred, because a centre does not move.
 */
public class Viewport3D extends Widget {

    /** Draws the 3D scene into a pass each frame; {@code timeSeconds} advances while showing. */
    @FunctionalInterface
    public interface Renderer {
        void render(RenderPass pass, double timeSeconds);
    }

    private static final int SAMPLES = 4; // MSAA

    private float preferredWidth = 360;
    private float preferredHeight = 260;
    private float renderScale = 1f;
    /** Box to frame on the next paint, when the aspect ratio is finally known. */
    private Aabb pendingFit;
    private float pendingFitMargin;
    private RenderTarget surface;
    private double time = 0.9; // start at a 3/4 angle so a static frame reads as 3D
    private boolean ticking;
    private int tickGeneration; // invalidates a stale ticker after detach (blink-generation idiom)
    private boolean animated = true; // continuous repaint while showing (demo cube spins)
    private Renderer renderer; // null → the built-in demo cube
    private Runnable onDispose; // renderer-owned GPU cleanup, run context-current on detach
    private Camera camera = new Camera();
    private CameraController controller; // null → no camera interaction
    private Consumer<Ray> onClick;       // fired with the world ray on a (non-drag) click
    private Consumer<Image> pendingCapture; // one-shot readback, taken in the next paint
    private float lastX;
    private float lastY;
    private boolean dragged;
    private boolean leftGesture; // a LEFT press armed lastX/lastY for this gesture

    /** An empty viewport; give it something to draw with {@link #setRenderer}. */
    public Viewport3D() {
        setFocusable(true);
    }

    /** Sets the render callback; without one, the built-in demo cube is shown. */
    public Viewport3D setRenderer(Renderer renderer) {
        Ui.checkUiThread();
        this.renderer = renderer;
        invalidate();
        return this;
    }

    /**
     * Whether the viewport drives a continuous repaint while showing (default
     * {@code true}: right for content that moves every frame; {@code timeSeconds}
     * keeps advancing). Set {@code false} for <b>static</b> content: it then
     * re-renders only when invalidated: camera controllers already invalidate on
     * drag/zoom; call {@link #invalidate()} after mutating the scene. An idle
     * static viewport costs zero CPU/GPU, like the rest of the toolkit.
     */
    public Viewport3D setAnimated(boolean animated) {
        Ui.checkUiThread();
        this.animated = animated;
        invalidate(); // turning on re-arms the ticker at the next paint
        return this;
    }

    /**
     * Registers cleanup for GPU resources owned by the renderer, typically a
     * retained scene's {@code Scene3D::dispose}. It runs when this viewport is
     * detached from the tree, deferred to the owning window's next frame so the
     * GL context is current (the same path that releases the render target).
     * Kept across re-attachments: a renderer that rebuilds lazily should also
     * reset its own reference in this callback.
     */
    public Viewport3D onDispose(Runnable cleanup) {
        Ui.checkUiThread();
        this.onDispose = cleanup;
        return this;
    }

    /** Installs a camera controller (e.g. {@code new OrbitController(viewport.camera())}). */
    public Viewport3D setController(CameraController controller) {
        Ui.checkUiThread();
        this.controller = controller;
        setCursor(controller != null ? Cursor.MOVE : null);
        return this;
    }

    /**
     * Hands the next rendered frame of this viewport to {@code sink} as an {@link Image}, then
     * forgets it: one capture per call, not a subscription. Requests a repaint, so it works on a
     * viewport that has stopped animating.
     *
     * <p>The image is <b>display-referred</b>: what the viewport shows, with exposure, tonemap and
     * sRGB encode applied, straight alpha, top-down. It is ready to hand to
     * {@link limn.graphics.Images#encode}. The render target's own scene-referred contents are a
     * different picture and are reached through the target itself, not here; see
     * {@link limn.graphics.ReadableSurface}.
     *
     * <p>{@code sink} runs on the UI thread inside that frame, so the read cannot be moved off it;
     * a large capture should encode asynchronously ({@link limn.graphics.Images#saveAsync}) rather
     * than inside the sink.
     *
     * @throws NullPointerException if {@code sink} is null
     */
    public Viewport3D captureNext(Consumer<Image> sink) {
        Ui.checkUiThread();
        this.pendingCapture = Objects.requireNonNull(sink, "sink");
        invalidate();
        return this;
    }

    /** Fires with the world-space ray on a click (no drag); feed it to {@code Picker}. */
    public Viewport3D onClick(Consumer<Ray> listener) {
        Ui.checkUiThread();
        this.onClick = listener;
        return this;
    }

    /**
     * Renders the 3D content at a fraction of the viewport's device resolution and
     * upsamples it into the widget's box. Clamped to {@code [0.25, 1]}; 1 (the default)
     * renders at full resolution.
     *
     * <p>This is the cost lever for a heavy 3D view. The target holds four half-float
     * channels per pixel, so both its memory and its fill cost fall with the square of
     * the scale: 0.5 is a quarter of both. Nothing else changes: the widget's box, the
     * 2D layout around it and {@link #rayAt} picking are all in logical points and do not
     * move.
     *
     * <p>What it costs is sharpness. The upsample is bilinear, so edges soften; geometry
     * with thin features shows it first. Multisampling still applies, at the reduced
     * resolution.
     */
    public Viewport3D setRenderScale(float scale) {
        Ui.checkUiThread();
        float clamped = Math.min(1f, Math.max(0.25f, scale));
        if (renderScale != clamped) {
            renderScale = clamped;
            invalidate();
        }
        return this;
    }

    /** The fraction of device resolution the 3D content is rendered at; 1 by default. */
    public float renderScale() {
        return renderScale;
    }

    /**
     * Points the camera at {@code box} and backs it off far enough to hold the whole of
     * it, keeping the current viewing direction and field of view, and moving the clip
     * planes to bracket what it framed.
     *
     * <p>Fitting needs the viewport's aspect ratio, so a call made before the first layout
     * takes effect on the layout pass that supplies one instead of being lost, which is
     * how to frame content once, at construction. An {@linkplain Aabb#isEmpty() empty} box
     * is ignored.
     *
     * <p>The viewport does not know what its renderer draws, so it cannot find the box
     * itself; a scene graph reports one through {@code Scene3D.bounds()}.
     */
    public Viewport3D frameContent(Aabb box) {
        return frameContent(box, 1.1f);
    }

    /**
     * {@link #frameContent(Aabb)} with an explicit margin, the factor the fitted distance
     * is multiplied by, so 1 touches the edges and 1.1 leaves a tenth of the frame around
     * the content. Values below 1 crop it.
     */
    public Viewport3D frameContent(Aabb box, float margin) {
        Ui.checkUiThread();
        Objects.requireNonNull(box, "box");
        if (box.isEmpty()) {
            return this;
        }
        if (width() > 0 && height() > 0) {
            applyFit(box, margin, width() / height());
        } else {
            pendingFit = box;
            pendingFitMargin = margin;
        }
        invalidate();
        return this;
    }

    /**
     * Moves the camera so {@code box} fills the frame at {@code aspect}.
     *
     * <p>Fits the box's bounding <em>sphere</em> rather than the box, so the framing does
     * not change as the content spins: a box fitted edge-on would clip once it turned
     * corner-on. The binding axis is whichever half-angle is smaller: at a wide aspect
     * that is the vertical one, and at a tall aspect the horizontal one, which is why the
     * fit cannot be derived from the vertical field of view alone.
     */
    private void applyFit(Aabb box, float margin, float aspect) {
        float radius = box.extent().length() * 0.5f;
        if (radius <= 0) {
            radius = 1e-3f; // a single point still deserves a sane distance
        }
        float halfV = camera.fovyRadians() * 0.5f;
        float halfH = (float) Math.atan(Math.tan(halfV) * aspect);
        float half = Math.min(halfV, halfH);
        float distance = radius / (float) Math.sin(half) * Math.max(0.1f, margin);

        Vec3 center = box.center();
        Vec3 direction = camera.eye().sub(camera.target());
        if (direction.lengthSquared() < 1e-12f) {
            direction = Vec3.UNIT_Z;
        }
        direction = direction.normalize();
        camera.target(center).eye(center.add(direction.mul(distance)));
        // Bracket what was framed: a near plane far below the content is what spends the
        // depth buffer's precision, since it is the far/near ratio that decides it.
        camera.clip(Math.max(distance * 0.01f, (distance - radius) * 0.5f), distance + radius * 2f);
    }

    /**
     * The world-space ray through a viewport-local pixel (for picking).
     *
     * <p>{@code localX} is physical and stays physical in a right-to-left viewport: it is a
     * distance from the viewport's left edge, because that is what the pointer reports and what
     * an application computing its own coordinates will pass. A layout direction is a reading
     * order and this argument is not read; reflecting it here would return the ray through the
     * mirror image of the pixel the caller named.
     */
    public Ray rayAt(float localX, float localY) {
        float w = width();
        float h = height();
        float aspect = h > 0 ? w / h : 1;
        Mat4 inverseVp = camera.viewProjection(aspect).invert();
        float ndcX = w > 0 ? localX / w * 2 - 1 : 0;
        float ndcY = h > 0 ? 1 - localY / h * 2 : 0; // local y is down, NDC y is up
        Vec3 farPoint = inverseVp.transformPoint(new Vec3(ndcX, ndcY, 1));
        Vec3 origin = camera.eye();
        return Ray.of(origin, farPoint.sub(origin));
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        float lx = sceneToLocalX(event.x());
        float ly = sceneToLocalY(event.y());
        switch (event.type()) {
            case PRESS -> {
                // Only a LEFT press arms a camera/click gesture; other buttons
                // bubble untouched: reacting to their DRAG/RELEASE would reuse
                // the PREVIOUS gesture's lastX/lastY and jump the camera.
                if (event.button() == Keys.MOUSE_LEFT) {
                    leftGesture = true;
                    lastX = lx;
                    lastY = ly;
                    dragged = false;
                    event.consume();
                }
            }
            case DRAG -> {
                if (!leftGesture) {
                    return;
                }
                if (controller != null) {
                    controller.drag(lx - lastX, ly - lastY);
                    invalidate();
                }
                // Hand jitter, not an extent: the same physical wobble separates a click from
                // a drag at every step. Strokes.DRAG_SLOP already documents this line as its
                // site; the literal was the last copy of the value.
                if (Math.abs(lx - lastX) + Math.abs(ly - lastY) > Strokes.DRAG_SLOP) {
                    dragged = true;
                }
                lastX = lx;
                lastY = ly;
                event.consume();
            }
            case RELEASE -> {
                if (!leftGesture) {
                    return;
                }
                leftGesture = false;
                if (!dragged && onClick != null && width() > 0 && height() > 0) {
                    onClick.accept(rayAt(lastX, lastY));
                }
                event.consume();
            }
            case WHEEL -> {
                // Without a camera controller the wheel is not ours: let it
                // bubble so a page with a static viewport still scrolls.
                if (controller != null) {
                    controller.zoom(event.scrollY());
                    invalidate();
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    /** Replaces the camera. The instance is kept, so a controller can keep mutating it. */
    public Viewport3D setCamera(Camera camera) {
        Ui.checkUiThread();
        this.camera = camera;
        invalidate();
        return this;
    }

    /** The camera this viewport renders with: mutable, and mutated by any controller. */
    public Camera camera() {
        return camera;
    }

    /** The size this viewport asks for, in logical points. The layout may still override it. */
    public Viewport3D setPreferredSize(float width, float height) {
        Ui.checkUiThread();
        this.preferredWidth = width;
        this.preferredHeight = height;
        markNeedsLayout();
        return this;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(preferredWidth, preferredHeight);
    }

    @Override
    protected void onLayout() {
        // Where a deferred frameContent lands: the aspect ratio exists here, and framing is
        // camera arithmetic, so it must not sit behind the GPU-available branch in onPaint.
        if (pendingFit != null && width() > 0 && height() > 0) {
            applyFit(pendingFit, pendingFitMargin, width() / height());
            pendingFit = null;
        }
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        if (!Graphics3D.isAvailable()) {
            // The placeholder is the only token-sized chrome this widget owns, so the row is
            // resolved on the branch that draws it: the 3D path below sizes nothing from the
            // step and would pay a lookup per frame at 60 fps for a value it never reads.
            paintPlaceholder(canvas, theme, theme.tokensFor(this));
            return;
        }
        float pixelScale = canvas.contentScale() * renderScale;
        int widthPx = Math.max(1, Math.round(width() * pixelScale));
        int heightPx = Math.max(1, Math.round(height() * pixelScale));
        if (surface == null) {
            surface = Graphics3D.createTarget(widthPx, heightPx, SAMPLES);
        } else {
            surface.resize(widthPx, heightPx);
        }
        if (renderer != null) {
            Graphics3D.render(surface, camera, pass -> renderer.render(pass, time));
        } else {
            Graphics3D.renderDemoScene(surface, time); // built-in demo cube
        }
        canvas.drawSurface(surface, 0, 0, width(), height()); // resolved texture → this 2D layer
        if (pendingCapture != null) {
            // After the render, so the image is the frame just composited rather than the previous
            // one, and inside the paint, which is the only place this window's GL context is
            // current. Cleared first: a sink that throws must not be retried every frame.
            Consumer<Image> sink = pendingCapture;
            pendingCapture = null;
            sink.accept(surface.readDisplayReferred());
        }
        startTicking();
    }

    /**
     * The "no 3D backend" message, centred on both axes.
     *
     * <p><b>Centred, therefore unmirrored.</b> Both coordinates are half of what the box has left
     * over, so a right-to-left viewport draws this message in exactly the place a left-to-right
     * one does; there is no leading edge in the expression for a direction to reflect. The
     * direction is resolved here all the same, because it is what the message is <em>shaped</em>
     * against, and it is resolved on this branch only: the rendering path above reads no
     * direction and must not start paying for one per frame.
     */
    private void paintPlaceholder(Canvas canvas, Theme theme, SizeTokens t) {
        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), theme.surfaceRaised);
        Font font = t.body();
        String message = ComponentStrings.VIEWPORT3D_NO_BACKEND.get();
        ShapedText line = textRuler().shape(message, font,
                ShapedText.Direction.of(message, neutralBase()));
        TextMetrics metrics = line.metrics();
        Color ink = theme.textMuted;
        canvas.drawText(line, (width() - metrics.width()) / 2,
                (height() - metrics.height()) / 2 + metrics.ascent(), ink);
    }

    private void startTicking() {
        if (ticking || !animated || scene() == null || !isShowing()) {
            return;
        }
        ticking = true;
        int generation = ++tickGeneration;
        scene().addTicker(dt -> tick(generation, dt));
    }

    private boolean tick(int generation, double dtSeconds) {
        if (generation != tickGeneration) {
            // Superseded (detach + reattach in one frame): the old registration
            // is still in the scene's list; without this, both would advance
            // `time`, doubling the animation speed and per-frame work forever.
            return false;
        }
        time += dtSeconds;
        invalidate();
        boolean keepGoing = animated && isShowing();
        if (!keepGoing) {
            ticking = false; // re-armed by the next onPaint when shown/animated again
        }
        return keepGoing;
    }

    @Override
    protected void onDetached() {
        // scene() is still the scene being left, which is the one that owns the GL
        // context these resources belong to: hand them to it and they are freed at
        // its next frame (GPU disposal needs the context, only current in a frame).
        Scene leaving = scene();
        if (leaving != null) {
            if (surface != null) {
                leaving.disposeLater(surface);
            }
            if (onDispose != null) {
                leaving.disposeLater(onDispose);
            }
        }
        surface = null;
        ticking = false;
        tickGeneration++; // the old registration dies on its next call
    }
}
