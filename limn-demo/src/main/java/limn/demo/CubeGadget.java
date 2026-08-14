package limn.demo;

import limn.backend.Backend;
import limn.backend.Display;
import limn.backend.NativeWindow;
import limn.backend.ScreenRect;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Viewport3D;
import limn.components.Theme;
import limn.graphics.Color;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.math.Quat;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Graphics3D;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.RenderPass;
import limn.render3d.Sampler;
import limn.render3d.scene.LightNode;
import limn.render3d.scene.MeshNode;
import limn.render3d.scene.Node;
import limn.render3d.scene.Scene3D;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop gadget demo: numbered spinning cubes (one saturated color per face)
 * bouncing over the desktop in ONE fullscreen, undecorated, TRANSPARENT,
 * always-on-top {@link Overlay} window. Everything is stock Limn: the
 * popup-grade transparent window, a {@link Viewport3D} whose {@link Scene3D}
 * clears to alpha 0, the premultiplied surface composite, and dynamic
 * mouse-passthrough so empty space stays click-through.
 *
 * <p>Run: {@code ./gradlew :limn-demo:run --args="--gadget [n]"}, or the
 * kitchen sink's "Launch cube" button (each click adds a cube to the shared
 * overlay). Left-drag moves a cube, right-click removes it (the last removal
 * closes the overlay), ESC closes.
 * {@code --gadget shot <file.png>} renders one cube head-on, headless, for
 * texture-orientation verification (see {@link #shot}).
 */
final class CubeGadget {

    private static final int SIZE = 240; // logical viewport edge of a shot window

    private CubeGadget() {
    }

    /** Fills the shot window with its single verification viewport. */
    private static final class ShotRoot extends Widget {
        private final Viewport3D viewport;

        ShotRoot(Viewport3D viewport) {
            this.viewport = viewport;
            add(viewport);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            viewport.measure(Constraints.tight(width(), height()));
            viewport.layoutBox(0, 0, width(), height());
        }
    }

    static void run(String shotFile) {
        try (Backend backend = new LwjglBackend()) {
            shot(backend, shotFile);
            backend.runEventLoop();
        }
    }

    /**
     * Standalone swarm ({@code --gadget <n>}): {@code count} cubes bouncing in
     * ONE fullscreen transparent overlay: one OS window, one swap, so the swarm
     * holds refresh rate no matter how many cubes (a single draw call each). The
     * overlay is mouse-transparent over empty space, so desktop clicks fall
     * through; it grabs the mouse only over a cube (left-drag moves it, right
     * click removes it). No window ever moves; no re-center flash. See
     * {@link Overlay}.
     */
    static void run(int count, boolean preciseHitTest) {
        try (Backend backend = new LwjglBackend()) {
            sharedOverlay = spawnOverlay(backend, count);
            sharedOverlay.preciseHitTest = preciseHitTest;
            backend.runEventLoop();
        }
    }

    // The overlay every "Launch cube" click feeds. One per process: the demo
    // runs a single backend; a click after the overlay closed (last cube
    // removed, ESC) simply builds a fresh one.
    private static Overlay sharedOverlay;

    /**
     * Kitchen-sink "Launch cube": adds one cube to the shared fullscreen
     * overlay, creating it on the first click (rendering flags inherited from
     * the launching scene so the partial/debug toggles carry over).
     */
    static void spawn(Backend backend, Scene owner) {
        if (sharedOverlay != null && !sharedOverlay.window.isClosed()) {
            sharedOverlay.spawn(1);
            return;
        }
        sharedOverlay = spawnOverlay(backend, 1);
        Scene overlayScene = sharedOverlay.scene();
        if (owner != null && overlayScene != null) {
            overlayScene.inheritRenderingFlags(owner);
        }
    }

    /**
     * Kitchen-sink "Clear cubes": closes the shared overlay (every cube at
     * once) if one is open. (ESC or right-clicking the last cube does the
     * same from the overlay itself.)
     */
    static void closeOverlay() {
        if (sharedOverlay != null) {
            if (!sharedOverlay.window.isClosed()) {
                sharedOverlay.window.requestClose();
            }
            sharedOverlay = null; // next "Launch cube" builds a fresh swarm
        }
    }

    /** Builds the overlay window, sizes it to the full display, raises it above the chrome, shows it. */
    private static Overlay spawnOverlay(Backend backend, int count) {
        NativeWindow window = backend.createWindow(new WindowConfig(
                "Limn Cubes", 400, 300, false, false,
                false /* undecorated */, true /* always on top */,
                true /* transparent framebuffer */, false /* no focus steal */));
        // Cover the WHOLE display (full bounds, not the work area) and float
        // above the OS chrome so the cubes can fly over the menu bar and Dock.
        // Transparent regions still show that chrome through (the overlay is
        // above it) and passthrough keeps it clickable.
        Display display = window.display();
        if (display != null) {
            ScreenRect b = display.bounds();
            float f = window.logicalToScreenFactor();
            window.setSize(Math.round(b.width() / f), Math.round(b.height() / f));
            window.setScreenPosition(b.x(), b.y());
        }
        Overlay overlay = new Overlay(window, count);
        Scene scene = new Scene(overlay);
        scene.setBackground(Color.TRANSPARENT); // desktop shows through
        scene.bind(window);
        window.setMousePassthrough(true); // empty space is click-through from the start
        // The cubes animate every frame, so this ticker runs every frame too:
        // poll the cursor and flip passthrough as it enters/leaves a cube.
        scene.addTicker(dt -> {
            overlay.updatePassthrough();
            return true;
        });
        window.show();
        window.setAboveSystemChrome(true); // raise past the menu bar AFTER show orders it in
        return overlay;
    }

    /**
     * The texture-orientation verification harness: one cube, deterministic
     * pose, digit 7 (the only 7-segment digit with no rotational symmetry)
     * rendered head-on into an invisible cube-sized window and captured to
     * {@code shotFile}. {@code LIMN_CUBE_POSE=front|back|right|left|top|bottom}
     * picks the face.
     */
    private static void shot(Backend backend, String shotFile) {
        NativeWindow window = backend.createWindow(new WindowConfig(
                "Limn Cube", SIZE, SIZE, false, false,
                false /* undecorated */, true /* always on top */,
                true /* transparent framebuffer */, false /* no focus steal */));

        Viewport3D viewport = new Viewport3D();
        viewport.camera().eye(new Vec3(0, 0, 3.1f)).target(Vec3.ZERO);
        Scene3D[] scene3d = {null};
        Node[] cube = {null};
        viewport.setRenderer((pass, t) -> {
            if (scene3d[0] == null) {
                Scene3D built = new Scene3D()
                        .background(new Vec4(0, 0, 0, 0)) // THE point: clear to alpha 0
                        .ambient(new Vec3(0.30f, 0.30f, 0.32f));
                cube[0] = built.root().add(new MeshNode(
                        Graphics3D.upload(cubeMesh()),
                        Material.Pbr.of(1f, 1f, 1f).roughness(0.55f).textured(
                                Graphics3D.uploadTexture(numberAtlas(7),
                                        limn.render3d.Sampler.smooth()))));
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.45f, 0.9f, 0.7f), new Vec3(1f, 1f, 1f), 2.4f)));
                scene3d[0] = built;
            }
            float half = (float) (Math.PI / 2);
            Quat pose = switch (String.valueOf(System.getenv("LIMN_CUBE_POSE"))) {
                case "back" -> Quat.fromAxisAngle(Vec3.UNIT_Y, (float) Math.PI);
                case "right" -> Quat.fromAxisAngle(Vec3.UNIT_Y, -half);
                case "left" -> Quat.fromAxisAngle(Vec3.UNIT_Y, half);
                case "top" -> Quat.fromAxisAngle(Vec3.UNIT_X, half);
                case "bottom" -> Quat.fromAxisAngle(Vec3.UNIT_X, -half);
                default -> Quat.fromAxisAngle(Vec3.UNIT_X, 0f); // front
            };
            cube[0].transform(new Transform3D(Vec3.ZERO, pose, Vec3.ONE));
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene3d[0].render(pass, viewport.camera(), aspect);
        });
        viewport.onDispose(() -> {
            if (scene3d[0] != null) {
                scene3d[0].dispose();
                scene3d[0] = null;
            }
        });

        Scene scene = new Scene(new ShotRoot(viewport));
        scene.setBackground(Color.TRANSPARENT); // desktop shows through
        scene.bind(window);
        window.captureNextFrame(java.nio.file.Path.of(shotFile));
        window.requestFrame();
        limn.concurrent.Ui.postDelayed(window::requestClose, 1500);
    }

    /**
     * Reflects the velocity off a wall, perturbing the rebound angle by ±50%
     * and keeping it away from both extremes (never perpendicular, never
     * grazing), so the path can't lock into a straight ping-pong loop.
     *
     * @param normal   the axis of the wall normal (0 = vertical wall, 1 = horizontal)
     * @param awaySign the rebound direction along that axis (+1 or -1)
     */
    static void bounce(float[] vel, int normal, float awaySign, float speed) {
        int tangent = 1 - normal;
        // Signed angle between the mirrored rebound and the wall normal.
        float theta = (float) Math.atan2(vel[tangent], Math.abs(vel[normal]));
        float sign = theta == 0 ? (Math.random() < 0.5 ? -1 : 1) : Math.signum(theta);
        float magnitude = Math.abs(theta) * (0.5f + (float) Math.random()); // ±50% amplitude
        magnitude = Math.max(0.20f, Math.min(1.20f, magnitude)); // ~11°..69° off the normal
        vel[normal] = (float) Math.cos(magnitude) * speed * awaySign;
        vel[tangent] = (float) Math.sin(magnitude) * speed * sign;
    }

    // ------------------------------------------------- shared swarm (one viewport)
    // A cube bouncing/spinning on the z=0 plane of a shared Scene3D. Reused by
    // the fullscreen desktop {@link Overlay} and the kitchen "Cubes" viewport:
    // one mesh, one atlas per cube, one draw call each.

    /** One numbered cube: its scene node, plane position/velocity, and random spin. */
    static final class Cube {
        final MeshNode node;
        final int number;
        final float[] pos;  // world x,y on the z=0 plane
        final float[] vel;  // world units/s
        final Vec3 axis = randomAxis();
        final float spinSpeed = (float) (0.6 + Math.random() * 0.9);
        final float spinPhase = (float) (Math.random() * Math.PI * 2);

        Cube(MeshNode node, int number, float x, float y, float vx, float vy) {
            this.node = node;
            this.number = number;
            this.pos = new float[]{x, y};
            this.vel = new float[]{vx, vy};
        }
    }

    /** Uploads cube {@code number} into {@code scene} (sharing {@code mesh}); random start/heading. */
    static Cube spawnCube(Scene3D scene, GpuMesh mesh, int number,
                          float maxX, float maxY, float speed) {
        MeshNode node = scene.root().add(new MeshNode(mesh,
                Material.Pbr.of(1f, 1f, 1f).roughness(0.55f).textured(
                        Graphics3D.uploadTexture(numberAtlas(number), Sampler.smooth()))));
        double angle = Math.random() * Math.PI * 2;
        return new Cube(node, number,
                (float) ((Math.random() * 2 - 1) * maxX * 0.8f),
                (float) ((Math.random() * 2 - 1) * maxY * 0.8f),
                (float) (Math.cos(angle) * speed), (float) (Math.sin(angle) * speed));
    }

    /** Integrates one cube, bounces it off the ±extent walls, and applies its spin. */
    static void stepCube(Cube c, float dt, float maxX, float maxY, float speed, double t) {
        c.pos[0] += c.vel[0] * dt;
        c.pos[1] += c.vel[1] * dt;
        if (c.pos[0] <= -maxX || c.pos[0] >= maxX) {
            bounce(c.vel, 0, c.pos[0] <= -maxX ? 1 : -1, speed);
            c.pos[0] = Math.max(-maxX, Math.min(c.pos[0], maxX));
        }
        if (c.pos[1] <= -maxY || c.pos[1] >= maxY) {
            bounce(c.vel, 1, c.pos[1] <= -maxY ? 1 : -1, speed);
            c.pos[1] = Math.max(-maxY, Math.min(c.pos[1], maxY));
        }
        spinCube(c, t);
    }

    /** Writes the cube's node transform: its plane position plus the time-based spin. */
    static void spinCube(Cube c, double t) {
        c.node.transform(new Transform3D(new Vec3(c.pos[0], c.pos[1], 0),
                Quat.fromAxisAngle(c.axis, (float) t * c.spinSpeed + c.spinPhase), Vec3.ONE));
    }

    // ------------------------------------------------- fullscreen desktop overlay

    /**
     * A single transparent, always-on-top window covering the work area, hosting
     * every cube in ONE {@link Viewport3D}: one OS surface and one buffer swap
     * for the whole swarm (vs. one window per cube, whose per-window present +
     * WindowServer traffic degrade superlinearly). Because no window ever moves
     * there is no re-center flash either.
     *
     * <p>Mouse: over empty space the window is
     * {@linkplain NativeWindow#setMousePassthrough passthrough} so desktop clicks
     * fall through; a per-frame poll of the {@linkplain NativeWindow#cursorX
     * cursor} flips passthrough off the moment the pointer is over a cube, so the
     * window then receives events: a left-drag moves that cube, a right-click
     * removes it (the last removal closes the window). The {@link Viewport3D} is
     * kept {@code enabled=false} so it never consumes the press itself; input
     * bubbles up to this root, which does its own cube hit-testing.
     */
    private static final class Overlay extends Widget {
        private static final float EYE_Z = 12f;         // camera distance
        private static final float HALF_DIAG = 1.08f;   // cube bounding radius (world units)
        private static final float WORLD_SPEED = 2.6f;  // world units/s

        private final Viewport3D viewport = new Viewport3D();
        private final NativeWindow window;
        private final List<Cube> cubes = new ArrayList<>();
        // Textures of removed cubes, freed in the renderer where the GL context is
        // current (a mouse handler runs with no context bound).
        private final List<GpuTexture> pendingDispose = new ArrayList<>();
        private Scene3D scene3d;
        private GpuMesh mesh;
        private int pendingSpawns;
        private int spawnCounter; // monotonic: numbers stay unique across removals
        private float extentX;    // half-extent of the visible z=0 plane, world units
        private float extentY;
        private double lastT;
        private Cube dragging;
        private boolean passthrough = true;

        // Hit-test mode (see cubeAt()). Toggled live with 'P'; the developer
        // picks the start mode ("--gadget [n] precise" on the command line).
        private boolean preciseHitTest;
        // HUD badge announcing the active hit-test mode: shown at spawn and on
        // every 'P' toggle, fading out after a moment so the desktop stays clean.
        private static final double BADGE_HOLD_SECONDS = 2.5;
        private static final double BADGE_FADE_SECONDS = 0.6;
        private double badgeShownAtT = Double.NaN;

        Overlay(NativeWindow window, int count) {
            this.window = window;
            this.pendingSpawns = Math.max(1, count);
            viewport.camera().eye(new Vec3(0, 0, EYE_Z)).target(Vec3.ZERO);
            viewport.setEnabled(false); // events bubble to this root, not the viewport
            viewport.setRenderer(this::renderSwarm);
            viewport.onDispose(this::disposeScene);
            add(viewport);
            setFocusable(true); // ESC once the window has taken focus
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            viewport.measure(Constraints.tight(width(), height()));
            viewport.layoutBox(0, 0, width(), height());
        }

        private void renderSwarm(RenderPass pass, double t) {
            if (scene3d == null) {
                scene3d = new Scene3D()
                        .background(new Vec4(0, 0, 0, 0)) // desktop shows through
                        .ambient(new Vec3(0.30f, 0.30f, 0.32f));
                scene3d.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.45f, 0.9f, 0.7f), new Vec3(1f, 1f, 1f), 2.4f)));
                mesh = Graphics3D.upload(cubeMesh());
                lastT = t;
                badgeShownAtT = t; // announce the initial hit-test mode
            }
            // GL context is current here: free the textures of removed cubes.
            for (int i = 0; i < pendingDispose.size(); i++) {
                pendingDispose.get(i).dispose();
            }
            pendingDispose.clear();

            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            extentY = (float) Math.tan(viewport.camera().fovyRadians() / 2) * EYE_Z;
            extentX = extentY * aspect;
            float maxX = Math.max(0.1f, extentX - HALF_DIAG);
            float maxY = Math.max(0.1f, extentY - HALF_DIAG);
            while (pendingSpawns > 0) {
                pendingSpawns--;
                cubes.add(spawnCube(scene3d, mesh, ++spawnCounter, maxX, maxY, WORLD_SPEED));
            }
            float dt = (float) Math.max(0, Math.min(0.1, t - lastT));
            lastT = t;
            for (int i = 0; i < cubes.size(); i++) {
                Cube c = cubes.get(i);
                if (c == dragging) {
                    spinCube(c, t); // held by the mouse: no travel, keeps spinning
                } else {
                    stepCube(c, dt, maxX, maxY, WORLD_SPEED, t);
                }
            }
            scene3d.render(pass, viewport.camera(), aspect);
        }

        /**
         * The cube under window point (sx,sy) in logical points, else null.
         *
         * <p>Two hit-test modes ('P' toggles at runtime):
         * <ul>
         *   <li><b>Grab</b> (default): a plane-projected box of the cube's 3D
         *       bounding half-diagonal, larger than the silhouette, so a
         *       spinning cube is easy to catch (fat hitboxes are standard
         *       grab UX). ~6 flops per cube.</li>
         *   <li><b>Precise</b>: a camera ray through the cursor intersected
         *       with each cube's oriented box at its rendered pose, exact to
         *       the silhouette (the geometric equivalent of pixel-perfect for
         *       a convex opaque mesh, minus MSAA edge feathering). ~60 flops
         *       per cube; nearest hit wins on overlap. Still microseconds per
         *       frame; the loose default is a feel choice, not a perf one.</li>
         * </ul>
         */
        private Cube cubeAt(float sx, float sy) {
            float vw = viewport.width();
            float vh = viewport.height();
            if (extentX <= 0 || extentY <= 0 || vw <= 0 || vh <= 0 || cubes.isEmpty()) {
                return null;
            }
            float wx = (sx / vw * 2 - 1) * extentX;
            float wy = (1 - sy / vh * 2) * extentY; // screen y is down, world y is up
            Cube best = null;
            if (preciseHitTest) {
                // Ray from the eye through the cursor: it reaches (wx, wy, 0)
                // at t=1, so origin (0,0,EYE_Z), direction (wx, wy, -EYE_Z).
                float bestT = Float.MAX_VALUE;
                for (int i = 0; i < cubes.size(); i++) {
                    Cube c = cubes.get(i);
                    float t = rayHitCube(c, wx, wy);
                    if (t >= 0 && t < bestT) {
                        best = c;
                        bestT = t; // nearest surface wins where cubes overlap
                    }
                }
            } else {
                float bestD = Float.MAX_VALUE;
                for (int i = 0; i < cubes.size(); i++) {
                    Cube c = cubes.get(i);
                    float dx = wx - c.pos[0];
                    float dy = wy - c.pos[1];
                    float d = dx * dx + dy * dy;
                    if (Math.abs(dx) <= HALF_DIAG && Math.abs(dy) <= HALF_DIAG && d < bestD) {
                        best = c;
                        bestD = d; // nearest center wins where grab boxes overlap
                    }
                }
            }
            return best;
        }

        /**
         * Intersects the cursor ray (origin (0,0,{@link #EYE_Z}), direction
         * (wx, wy, -EYE_Z)) with {@code c}'s oriented cube via the slab test in
         * the cube's local space: the pose is read from the node, so the test
         * matches exactly what was rendered. @return entry distance t, or -1.
         */
        private static float rayHitCube(Cube c, float wx, float wy) {
            Transform3D world = c.node.transform();
            Quat inverse = world.rotation().conjugate();
            Vec3 t = world.translation();
            Vec3 origin = inverse.rotate(new Vec3(-t.x(), -t.y(), EYE_Z - t.z()));
            Vec3 dir = inverse.rotate(new Vec3(wx, wy, -EYE_Z));
            float[] o = {origin.x(), origin.y(), origin.z()};
            float[] d = {dir.x(), dir.y(), dir.z()};
            float tMin = 0f;
            float tMax = Float.MAX_VALUE;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(d[axis]) < 1e-7f) {
                    if (o[axis] < -H || o[axis] > H) {
                        return -1; // parallel outside the slab
                    }
                    continue;
                }
                float t1 = (-H - o[axis]) / d[axis];
                float t2 = (H - o[axis]) / d[axis];
                tMin = Math.max(tMin, Math.min(t1, t2));
                tMax = Math.min(tMax, Math.max(t1, t2));
                if (tMin > tMax) {
                    return -1;
                }
            }
            return tMin;
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case PRESS -> {
                    Cube hit = cubeAt(event.x(), event.y());
                    if (hit == null) {
                        return; // empty space (only reached in the frame passthrough lags a flip)
                    }
                    if (event.button() == Keys.MOUSE_RIGHT) {
                        removeCube(hit);
                    } else if (event.button() == Keys.MOUSE_LEFT) {
                        dragging = hit;
                    }
                    event.consume();
                }
                case DRAG -> {
                    if (dragging != null) {
                        dragTo(event.x(), event.y());
                        event.consume();
                    }
                }
                case RELEASE -> dragging = null;
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (event.isPressed() && event.key() == Keys.ESCAPE) {
                window.requestClose();
            } else if (event.isPressed() && event.key() == 'P') { // Keys: 'A'..'Z' are 65..90
                preciseHitTest = !preciseHitTest;
                badgeShownAtT = lastT; // re-announce the mode
            }
        }

        @Override
        protected void onPaintOverlay(limn.graphics.Canvas canvas) {
            // The mode badge rides the cube animation (the viewport repaints
            // every frame, so the fade animates for free) and stops painting,
            // leaving the desktop clean, once fully faded.
            double shown = lastT - badgeShownAtT;
            if (Double.isNaN(badgeShownAtT) || shown > BADGE_HOLD_SECONDS + BADGE_FADE_SECONDS) {
                return;
            }
            float alpha = shown <= BADGE_HOLD_SECONDS
                    ? 1f
                    : 1f - (float) ((shown - BADGE_HOLD_SECONDS) / BADGE_FADE_SECONDS);
            String text = (preciseHitTest
                    ? "Hit-test: PRECISE (cube silhouette)"
                    : "Hit-test: GRAB (generous box)")
                    + "; click a cube, then P toggles";
            limn.graphics.Font font = Theme.current().body;
            TextMetrics m = textRuler().measure(text, font);
            float padX = 14;
            float padY = 8;
            float w = m.width() + padX * 2;
            float h = m.height() + padY * 2;
            float x = (width() - w) / 2;
            float y = 64; // below the menu-bar strip the overlay covers
            canvas.fillRoundRect(x, y, w, h, h / 2,
                    new Color(0.10f, 0.10f, 0.12f, 0.85f * alpha));
            canvas.drawText(text, x + padX, y + padY + m.ascent(), font,
                    new Color(1f, 1f, 1f, 0.95f * alpha));
        }

        /** Pins the dragged cube under the cursor, clamped to the visible extents. */
        private void dragTo(float sx, float sy) {
            float vw = viewport.width();
            float vh = viewport.height();
            if (vw <= 0 || vh <= 0) {
                return;
            }
            float maxX = Math.max(0.1f, extentX - HALF_DIAG);
            float maxY = Math.max(0.1f, extentY - HALF_DIAG);
            dragging.pos[0] = Math.max(-maxX, Math.min((sx / vw * 2 - 1) * extentX, maxX));
            dragging.pos[1] = Math.max(-maxY, Math.min((1 - sy / vh * 2) * extentY, maxY));
        }

        private void removeCube(Cube c) {
            cubes.remove(c);
            if (dragging == c) {
                dragging = null;
            }
            if (scene3d != null) {
                scene3d.root().children().remove(c.node);
                if (c.node.material() instanceof Material.Pbr pbr && pbr.baseColorTexture() != null) {
                    pendingDispose.add(pbr.baseColorTexture()); // freed next render (context current)
                }
            }
            if (cubes.isEmpty()) {
                window.requestClose(); // nothing left to show
            }
            viewport.invalidate();
        }

        /** Adds {@code n} more cubes on the next frame (the "Launch cube" button). */
        void spawn(int n) {
            pendingSpawns += n;
        }

        /**
         * Polls the cursor and flips mouse-passthrough: transparent to the mouse
         * over empty space, opaque while the pointer is over a cube (or dragging),
         * so a grab reaches the window while desktop clicks otherwise fall through.
         */
        void updatePassthrough() {
            boolean overCube;
            if (dragging != null) {
                overCube = true; // never drop a drag mid-gesture
            } else {
                float cx = window.cursorX();
                float cy = window.cursorY();
                overCube = !Float.isNaN(cx) && cubeAt(cx, cy) != null;
            }
            if (overCube == passthrough) { // state changed (passthrough == !overCube)
                passthrough = !overCube;
                window.setMousePassthrough(passthrough);
                // Drag hint riding the SAME mode-aware test: the MOVE cursor
                // appears exactly where a grab would land (the generous box in
                // grab mode, the cube silhouette in precise mode) and clears
                // over empty space, where the passthrough hands the cursor to
                // whatever sits beneath anyway. Set on the widget so the
                // scene's hover resolution agrees with it, AND pushed to the
                // window directly: before the first mouse event arrives there
                // is no hover yet (a cube can fly under a stationary cursor),
                // and both paths always resolve to the same shape here.
                setCursor(overCube ? limn.backend.Cursor.MOVE : null);
                window.setCursor(overCube
                        ? limn.backend.Cursor.MOVE : limn.backend.Cursor.DEFAULT);
            }
        }

        private void disposeScene() {
            if (scene3d != null) {
                for (int i = 0; i < pendingDispose.size(); i++) {
                    pendingDispose.get(i).dispose(); // removed-cube textures not in the graph
                }
                pendingDispose.clear();
                scene3d.dispose(); // shared mesh + every remaining cube texture
                scene3d = null;
                mesh = null;
                cubes.clear();
                dragging = null;
            }
        }
    }

    // ------------------------------------------------- cube mesh + atlas
    // ONE mesh and ONE texture atlas (six colored, numbered cells) = a single
    // draw call per frame. Six separate planes/materials cost six draws, each
    // with its own program/UBO/texture binds, measured at ~0.7 ms of driver
    // overhead per cube per frame on macOS GL.

    private static final float H = 0.62f; // half edge
    private static final int CELL = 128;  // atlas cell edge (3 × 2 cells)
    private static final int ATLAS_W = 3 * CELL;
    private static final int ATLAS_H = 2 * CELL;
    private static final int[] FACE_RGB = {
            0x3E63DD, // cell 0: front  (blue)
            0xF5D90A, // cell 1: back   (yellow)
            0xE93D82, // cell 2: right  (magenta)
            0x05A2C2, // cell 3: left   (cyan)
            0xE5484D, // cell 4: top    (red)
            0x30A46C, // cell 5: bottom (green)
    };

    /** The whole cube as one mesh: 24 vertices, 12 triangles, atlas UVs. */
    static MeshData cubeMesh() {
        // Per face: TL, TR, BR, BL in the face's natural reading orientation
        // when viewed head-on (+Y up for the sides, dice convention top/bottom).
        float[][] corners = {
                {-H, H, H,   H, H, H,   H, -H, H,   -H, -H, H},     // front  (+Z)
                {H, H, -H,   -H, H, -H, -H, -H, -H, H, -H, -H},     // back   (−Z)
                {H, H, H,    H, H, -H,  H, -H, -H,  H, -H, H},      // right  (+X)
                {-H, H, -H,  -H, H, H,  -H, -H, H,  -H, -H, -H},    // left   (−X)
                {-H, H, -H,  H, H, -H,  H, H, H,    -H, H, H},      // top    (+Y)
                {-H, -H, H,  H, -H, H,  H, -H, -H,  -H, -H, -H},    // bottom (−Y)
        };
        float[][] normals = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0},
        };
        float[] pos = new float[6 * 4 * 3];
        float[] nrm = new float[6 * 4 * 3];
        float[] uv = new float[6 * 4 * 2];
        int[] idx = new int[6 * 6];
        float inset = 2f; // px inside each cell: no linear-filter bleed across cells
        for (int f = 0; f < 6; f++) {
            System.arraycopy(corners[f], 0, pos, f * 12, 12);
            for (int c = 0; c < 4; c++) {
                System.arraycopy(normals[f], 0, nrm, f * 12 + c * 3, 3);
            }
            int col = f % 3;
            int row = f / 3;
            float u0 = (col * CELL + inset) / ATLAS_W;
            float u1 = ((col + 1) * CELL - inset) / ATLAS_W;
            float v0 = (row * CELL + inset) / ATLAS_H;
            float v1 = ((row + 1) * CELL - inset) / ATLAS_H;
            // TL, TR, BR, BL; v0 is the first texture row (the digit's top).
            float[] faceUv = {u0, v0, u1, v0, u1, v1, u0, v1};
            System.arraycopy(faceUv, 0, uv, f * 8, 8);
            int b = f * 4;
            int[] faceIdx = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(faceIdx, 0, idx, f * 6, 6);
        }
        return new MeshData()
                .put(limn.render3d.VertexAttribute.POSITION, pos)
                .put(limn.render3d.VertexAttribute.NORMAL, nrm)
                .put(limn.render3d.VertexAttribute.UV0, uv)
                .indices(idx);
    }

    // The number is rasterized procedurally (7-segment digits): no font
    // machinery, no AWT, just rectangles into an RGBA byte grid.

    /** Segment presence per digit, in A(top) B(tr) C(br) D(bottom) E(bl) F(tl) G(mid) order. */
    private static final boolean[][] SEGMENTS = {
            {true, true, true, true, true, true, false},    // 0
            {false, true, true, false, false, false, false}, // 1
            {true, true, false, true, true, false, true},    // 2
            {true, true, true, true, false, false, true},    // 3
            {false, true, true, false, false, true, true},   // 4
            {true, false, true, true, false, true, true},    // 5
            {true, false, true, true, true, true, true},     // 6
            {true, true, true, false, false, false, false},  // 7
            {true, true, true, true, true, true, true},      // 8
            {true, true, true, true, false, true, true},     // 9
    };

    /** Six colored cells, each stamped with the cube's number. */
    static limn.render3d.TextureData numberAtlas(int number) {
        byte[] px = new byte[ATLAS_W * ATLAS_H * 4];
        for (int f = 0; f < 6; f++) {
            int cellX = (f % 3) * CELL;
            int cellY = (f / 3) * CELL;
            int rgb = FACE_RGB[f];
            fillRect(px, cellX, cellY, CELL, CELL,
                    (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            drawNumber(px, cellX, cellY, number);
        }
        return new limn.render3d.TextureData(ATLAS_W, ATLAS_H, px, limn.render3d.ColorSpace.SRGB);
    }

    /** Draws {@code number} centered in the cell at {@code (cellX, cellY)}. */
    private static void drawNumber(byte[] px, int cellX, int cellY, int number) {
        String digits = Integer.toString(number);
        int digitH = 64;
        int digitW = 38;
        int gap = 12;
        int total = digits.length() * digitW + (digits.length() - 1) * gap;
        int scale = total > CELL - 16 ? (CELL - 16) * 1000 / total : 1000; // fit many digits
        digitW = digitW * scale / 1000;
        digitH = digitH * scale / 1000;
        gap = gap * scale / 1000;
        total = digits.length() * digitW + (digits.length() - 1) * gap;
        int x0 = cellX + (CELL - total) / 2;
        int y0 = cellY + (CELL - digitH) / 2;
        for (int i = 0; i < digits.length(); i++) {
            drawDigit(px, x0 + i * (digitW + gap), y0, digitW, digitH, digits.charAt(i) - '0');
        }
    }

    private static void drawDigit(byte[] px, int x, int y, int w, int h, int digit) {
        boolean[] seg = SEGMENTS[digit];
        int t = Math.max(5, w / 4); // segment thickness
        int half = h / 2;
        if (seg[0]) {
            fillRect(px, x, y, w, t, 32, 32, 34);                       // A: top
        }
        if (seg[1]) {
            fillRect(px, x + w - t, y, t, half, 32, 32, 34);            // B: top-right
        }
        if (seg[2]) {
            fillRect(px, x + w - t, y + half, t, h - half, 32, 32, 34); // C: bottom-right
        }
        if (seg[3]) {
            fillRect(px, x, y + h - t, w, t, 32, 32, 34);               // D: bottom
        }
        if (seg[4]) {
            fillRect(px, x, y + half, t, h - half, 32, 32, 34);         // E: bottom-left
        }
        if (seg[5]) {
            fillRect(px, x, y, t, half, 32, 32, 34);                    // F: top-left
        }
        if (seg[6]) {
            fillRect(px, x, y + half - t / 2, w, t, 32, 32, 34);        // G: middle
        }
    }

    private static void fillRect(byte[] px, int x, int y, int w, int h, int r, int g, int b) {
        for (int yy = Math.max(0, y); yy < Math.min(ATLAS_H, y + h); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(ATLAS_W, x + w); xx++) {
                int i = (yy * ATLAS_W + xx) * 4;
                px[i] = (byte) r;
                px[i + 1] = (byte) g;
                px[i + 2] = (byte) b;
                px[i + 3] = (byte) 255;
            }
        }
    }

    static Vec3 randomAxis() {
        // Random unit axis, kept away from degenerate near-zero vectors.
        while (true) {
            float x = (float) (Math.random() * 2 - 1);
            float y = (float) (Math.random() * 2 - 1);
            float z = (float) (Math.random() * 2 - 1);
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 0.2f && len <= 1f) {
                return new Vec3(x / len, y / len, z / len);
            }
        }
    }
}
