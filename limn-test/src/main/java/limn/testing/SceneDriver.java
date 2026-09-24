package limn.testing;

import limn.backend.WindowInput;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.internal.SceneAccess;

import java.util.List;
import java.util.Objects;

/**
 * Drives a scene as a window would: pointer, keys, text, focus, resizes and the close, delivered to
 * the input the scene hands its window and dispatched when the batch ends. What a test of an
 * application's own UI uses in place of a display, and what the toolkit's own tests use, which is
 * what keeps it honest.
 *
 * <p>Two levels. The {@link WindowInput} methods are the raw calls a backend makes, one event each,
 * queued until {@link #inputBatchEnded()} dispatches them, exactly as a frame's input arrives. The
 * gestures — {@link #click(Widget)}, {@link #press(int)}, {@link #type}, {@link #moveTo}, {@link #scroll} — are a
 * whole user action each, batch included, and return the driver so they can be chained.
 *
 * <pre>{@code
 * Scene scene = new Scene(form);
 * scene.layoutPass(400, 300);
 * drive(scene).click(nameField).type("Ada").press(Keys.ENTER);
 * }</pre>
 *
 * <p>The scene's input is not on {@link Scene} itself, because an application never calls it: a
 * window does. A driver is cheap, and {@code drive(scene)} may be called per event.
 */
public final class SceneDriver implements WindowInput {

    private final Scene scene;
    private final WindowInput input;

    private SceneDriver(Scene scene) {
        this.scene = scene;
        this.input = SceneAccess.input(scene);
    }

    /**
     * @param scene the scene to drive
     * @return a driver delivering to that scene's window input
     */
    public static SceneDriver drive(Scene scene) {
        return new SceneDriver(Objects.requireNonNull(scene, "scene"));
    }

    /** @return the scene this driver delivers to */
    public Scene scene() {
        return scene;
    }

    // ------------------------------------------------------------------ the gestures

    /**
     * A left click at a point: the pointer moves there, the button goes down and up, the batch ends.
     *
     * @param x logical x in the scene
     * @param y logical y in the scene
     * @return this driver
     */
    public SceneDriver click(float x, float y) {
        input.mouseMoved(x, y);
        input.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        input.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        input.inputBatchEnded();
        return this;
    }

    /**
     * A left click at the centre of a widget, where the last layout pass placed it.
     *
     * @param widget a widget in this driver's scene, laid out
     * @return this driver
     * @throws IllegalArgumentException when the widget is not in this scene
     */
    public SceneDriver click(Widget<?> widget) {
        if (widget.scene() != scene) {
            throw new IllegalArgumentException(widget + " is not in the scene this driver drives");
        }
        return click(widget.localToSceneX() + widget.width() / 2,
                widget.localToSceneY() + widget.height() / 2);
    }

    /**
     * A left click at the centre of the widget with this {@linkplain Widget#setId id}.
     *
     * @param id the id, as {@link Scene#find(String)} looks it up
     * @return this driver
     * @throws IllegalArgumentException when no widget in the scene has that id
     */
    public SceneDriver click(String id) {
        Widget<?> widget = scene.find(id);
        if (widget == null) {
            throw new IllegalArgumentException("no widget with id \"" + id + "\" in the scene this driver drives");
        }
        return click(widget);
    }

    /**
     * Moves the pointer and ends the batch: a hover.
     *
     * @param x logical x in the scene
     * @param y logical y in the scene
     * @return this driver
     */
    public SceneDriver moveTo(float x, float y) {
        input.mouseMoved(x, y);
        input.inputBatchEnded();
        return this;
    }

    /**
     * A wheel or trackpad scroll over a point.
     *
     * <p><b>A scroll can be dropped as the tail of a gesture.</b> A fresh key press that a widget
     * acts on ends the wheel stream still arriving, which is what stops a trackpad's inertia from
     * scrolling away what an arrow key just revealed. So a scroll that arrives within 150 ms of
     * the previous one, on the scene's clock, after such a key press, is dropped, and with the
     * wall clock a test's calls are microseconds apart. A test that scrolls, presses a key and
     * scrolls again builds its scene with a clock it advances ({@code new Scene(root, clock)})
     * and moves that clock past 150 ms before the second scroll.
     *
     * @param deltaX horizontal amount, in wheel notches
     * @param deltaY vertical amount, in wheel notches; positive scrolls up, as the platforms report
     * @param x      logical x of the pointer
     * @param y      logical y of the pointer
     * @return this driver
     */
    public SceneDriver scroll(float deltaX, float deltaY, float x, float y) {
        input.scrolled(deltaX, deltaY, x, y);
        input.inputBatchEnded();
        return this;
    }

    /**
     * A key pressed and released with no modifier.
     *
     * @param key the key code, from {@link Keys}
     * @return this driver
     */
    public SceneDriver press(int key) {
        return press(key, 0);
    }

    /**
     * A key pressed and released with modifiers held.
     *
     * @param key       the key code, from {@link Keys}
     * @param modifiers the {@code Keys.MOD_*} bits held
     * @return this driver
     */
    public SceneDriver press(int key, int modifiers) {
        input.keyEvent(key, true, false, modifiers);
        input.keyEvent(key, false, false, modifiers);
        input.inputBatchEnded();
        return this;
    }

    /**
     * Text typed into whatever has the keyboard, one code point at a time, as the platform delivers
     * committed characters. Keys that are not text (Enter, Tab, arrows) are {@link #press}.
     *
     * @param text the text
     * @return this driver
     */
    public SceneDriver type(String text) {
        text.codePoints().forEach(input::charTyped);
        input.inputBatchEnded();
        return this;
    }

    // ------------------------------------------------------------ the raw window input

    @Override
    public void mouseMoved(float x, float y) {
        input.mouseMoved(x, y);
    }

    @Override
    public void mouseDelta(float dx, float dy) {
        input.mouseDelta(dx, dy);
    }

    @Override
    public void mouseButton(int button, boolean pressed, int modifiers, float x, float y, int clickCount) {
        input.mouseButton(button, pressed, modifiers, x, y, clickCount);
    }

    @Override
    public void scrolled(float deltaX, float deltaY, float x, float y) {
        input.scrolled(deltaX, deltaY, x, y);
    }

    @Override
    public void keyEvent(int key, boolean pressed, boolean repeat, int modifiers) {
        input.keyEvent(key, pressed, repeat, modifiers);
    }

    @Override
    public void charTyped(int codepoint) {
        input.charTyped(codepoint);
    }

    @Override
    public void preeditChanged(String text, int[] blockSizes, int focusedBlock, int caret) {
        input.preeditChanged(text, blockSizes, focusedBlock, caret);
    }

    @Override
    public void pointerEntered(boolean entered) {
        input.pointerEntered(entered);
    }

    @Override
    public void windowResized(float logicalWidth, float logicalHeight) {
        input.windowResized(logicalWidth, logicalHeight);
    }

    @Override
    public void filesDropped(List<java.nio.file.Path> paths) {
        input.filesDropped(paths);
    }

    @Override
    public void windowFocusChanged(boolean focused) {
        input.windowFocusChanged(focused);
    }

    @Override
    public void inputBatchEnded() {
        input.inputBatchEnded();
    }

    @Override
    public void windowClosed() {
        input.windowClosed();
    }
}
