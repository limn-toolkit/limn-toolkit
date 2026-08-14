package limn.demo.site;

import limn.backend.Cursor;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ColorPicker;
import limn.components.ColorPickerButton;
import limn.components.PasswordField;
import limn.components.Slider;
import limn.components.SplitPane;
import limn.components.Spinner;
import limn.components.TextField;
import limn.components.chart.BarChart;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
import limn.components.Viewport3D;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The pointer's script for each entry that has one.
 *
 * <p><b>Only some components are filmed, and the test is whether the movement IS the
 * information.</b> A button's hover and press are states you cannot see in a still; a chart's
 * hovered category lifts toward the reader; a still of an indeterminate progress bar is a bar
 * with a stripe in an arbitrary place. A label, a separator or an image view gain nothing
 * from moving, and an animation that shows nothing is a page that costs more and says the
 * same.
 *
 * <p><b>A script visits every control the scene contains, not just the first one.</b> A
 * capture that shows four checkboxes and touches one reads as three of them being broken.
 * Each script below is written by looking at what its scene actually holds and deciding what
 * a person would do with it.
 *
 * <p>The scripts live here rather than beside the scenes so that what the site publishes as
 * "the code that produced this picture" stays the component's own code. A reader is being
 * shown how to build a button, not how this repository films one.
 */
final class Films {

    /** Where the pointer waits before it enters the frame: outside it, at the top left. */
    private static final float OFF_X = -24;
    private static final float OFF_Y = -24;

    /**
     * Frames for one glide, one pause before acting, and one pause after.
     *
     * <p>Read as time, because that is what a reader experiences: at the capture's fixed
     * 20 ms per frame these are 440 ms of travel, 240 ms of hovering before the press, and
     * 320 ms to look at what happened. The first cut of these was half as long and played
     * as a twitch: a pointer that arrives and clicks inside a fifth of a second is not
     * showing anybody anything.
     */
    private static final int TRAVEL = 22;
    private static final int SETTLE = 12;
    private static final int LOOK = 16;

    private Films() {
    }

    /** Every film the gallery runs, by entry id. */
    static Function<GalleryScenes.Built, Motion> forEntry(String id) {
        return switch (id) {
            case "button" -> Films::button;
            case "text-field" -> Films::textField;
            case "password-field" -> Films::passwordField;
            case "color-picker" -> Films::colorPicker;
            case "viewport-3d" -> Films::viewport3d;
            case "checkbox" -> Films::checkbox;
            case "slider" -> Films::slider;
            case "spinner" -> Films::spinner;
            case "split-pane" -> Films::splitPane;
            case "bar-chart" -> built -> sweep(find(built, BarChart.class, 0));
            case "line-chart" -> built -> sweep(find(built, LineChart.class, 0));
            case "donut-chart" -> Films::donutChart;
            // Exactly one sweep, so the loop is seamless. The bar's period is 1.1 s and the
            // capture steps 20 ms a frame, which is 55; at 60 the film ended a tenth of a
            // cycle past where it started and the join read as a stutter, not as a sweep.
            case "progress-indeterminate" -> built -> Motion.unattended(54);
            // The board scrolls 12.5 pt a second over a 10 pt cell, so two whole cells
            // (one seamless loop) take 1.6 s, which is 80 rendered frames. A film always
            // renders one more than it is asked for, the still it opens on, hence 79.
            case "backdrop-panel" -> built -> Motion.unattended(79);
            default -> null;
        };
    }

    /**
     * The script for a filmed <b>showcase</b> entry, a whole application window rather than one
     * component.
     *
     * <p>Kept apart from {@link #forEntry} because the bar is different, not because the machinery
     * is. A component is filmed when the movement is the information; a screen is filmed only when
     * the screen's whole claim is something a still cannot carry. There is one: the theme editor
     * exists to re-skin a running window, and a still of it is a form with colour wells in it.
     */
    static Function<GalleryScenes.Built, Motion> forShowcase(String id) {
        return switch (id) {
            case "theme-editor" -> Films::themeEditor;
            default -> null;
        };
    }

    /**
     * The editor being used: the shape drag, then a new accent chosen in the picker, then the
     * rest of the accent group derived from it.
     *
     * <p>Three claims, because that is what the screen makes and no still carries any of
     * them. The corner drag re-skins the window in the frame it happens: every field, button and
     * well rounds off under the thumb. The colour well raises a picker <em>inside</em> the window
     * (see {@code ThemeEditorExample}, which asks for {@code IN_SCENE}), and every tone it moves
     * is repainted live behind it, which is the one presentation where a picker is not simply in
     * the way. The derive then pulls the hovered and pressed tones onto the accent that was just
     * chosen, so the last beat is visibly caused by the one before it.
     *
     * <p>Every beat is a travel, a pause to act and a pause to look, in the constants below, so
     * that each one reads as a cause and then an effect: the eye follows the thumb and then
     * notices the corners. The pauses are what carry that, not the travels. An earlier cut of
     * the shape drag took three TRAVELs to cross the slider and read no better for it; what it
     * did do was cost the published film about 130 kB, which is a seventh of everything the
     * site will spend on this animation.
     *
     * <p>Half of what this script aims at does not exist when it is written. The picker and its
     * OK button are built by the click in beat 3, and the derive button is inside a ScrollView
     * below the fold, so it is not where it was laid out by the time the pointer wants it. Both
     * are named as lookups against the live scene and found on the frame their step begins: see
     * {@link Motion.Target}.
     *
     * <p><b>The arrow over the card is not guaranteed by the paint order.</b> {@code PointerLayer}
     * is a widget in the scene's root tree and {@code Scene} paints its overlays after the root,
     * so a full repaint puts the picker's card over the arrow. In the captured frames the arrow
     * is nonetheless on top wherever it moves, because a moving pointer damages its own rectangle
     * and the undamaged card is not repainted over it; what is missing is the dozen frames after
     * the card opens, where the pointer is standing still behind it. So the film reads, and the
     * one beat to keep away from is a press held still under a dialog that has just appeared.
     *
     * <p><b>Every frame here is a file, and the site has a ceiling for the film.</b>
     * {@code build-gallery.mjs} joins the frames into one animated WebP, steps down a quality
     * ladder until it fits 900 kB, and publishes the entry with no animation at all if the
     * bottom of that ladder is still over. Measured at the published width of 1024, this script
     * comes in at 825 kB in the dark palette and 857 kB in the light one with one rung of the
     * ladder still to spare; a first cut of it with longer drags and one more travel came to
     * 1041 kB at the bottom rung, and would have taken the film off the page silently.
     *
     * <p>What costs the bytes is not the frame count: a frame identical to the one before it is
     * collapsed into it. It is the pages where a lot of pixels move, and this screen has three
     * kinds. The picker's fade in and out are the worst (a card and a scrim over the whole
     * window, about 20 kB a page, and unavoidable if the picker is in the film at all); a scroll
     * repaints a whole column; a drag repaints every control the palette touches. So the wheel
     * beat runs from wherever the shape drag left the pointer rather than travelling back for
     * it, and no drag here is longer than it has to be.
     */
    private static Motion themeEditor(GalleryScenes.Built built) {
        // Index 0 is the corner slider: it is the only Slider the editor builds, and the preview
        // beside it carries none. A Slider added to ThemePreview would silently steal this film.
        Widget slider = find(built, Slider.class, 0);
        // The accent's own well. The section order is surfaces (3 wells) then accent, so PRIMARY is
        // the fourth, and it is the one worth opening, because the derive at the end reads every
        // other accent tone off it.
        Widget accentWell = find(built, ColorPickerButton.class, 3);

        return Motion.script()
                .from(OFF_X, OFF_Y)
                // 1. Shape. The whole window rounds off under the thumb.
                .to(slider, 0.06f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press()
                .to(slider, 0.94f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .release()
                .hold(LOOK)
                // 2. The wheel, turned where the drag left the pointer. The slider is inside the
                // token column's ScrollView, so this beat costs no travel of its own, and it
                // brings the derive button up from below the fold before anything aims at it. A
                // wheel delivered to the panel beside the view would scroll nothing, and the
                // reveal says so rather than letting a later glide aim off the screen.
                .reveal("the derive-from-the-accent button", () -> deriveAccent(built), 14)
                .hold(SETTLE)
                // 3. The accent's well, clicked. The picker is a dialog: it is pushed while the
                // release is dispatched and measured in the render after that, so the hold is
                // what turns it into something the next beat can aim at.
                .to(accentWell, 0.5f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press().hold(SETTLE).release()
                .hold(LOOK + SETTLE)
                // 4. The hue ramp first, which is the beat this film did not have. Saturation
                // and value cannot change a hue: dragging only the field made the accent a
                // stronger or darker violet, so the palette at the end of the film was the
                // palette at the start with the contrast turned up, and the derive that follows
                // had nothing new to read. The ramp runs red at the top through yellow, green,
                // cyan, blue and magenta back to red, so a sixth of the way down is orange: far
                // enough from the shipped violet that every derived tone, every button and the
                // preview card visibly move with it.
                .to("the picker's hue ramp", () -> pickerHueRamp(built), 0.5f, 0.30f, 12)
                .hold(6)
                .press()
                .to("the picker's hue ramp", () -> pickerHueRamp(built), 0.5f, 0.075f, 14)
                .hold(6)
                .release()
                .hold(SETTLE)
                // 5. Across the saturation/value field, from the washed-out corner to the
                // saturated one. The press alone already moves the accent, and every button, the
                // slider and the preview card behind the dialog follow the pointer as it drags.
                // The hue comes from the ramp under the field, so what this gesture moves is how
                // strong and how dark the accent is.
                //
                // It ends high on the value axis rather than in the corner, and that is a
                // decision about the published picture: the same fractions are dragged in both
                // palettes, and a strong tone taken far DOWN this field is dark enough that the
                // dark palette's near-black label tone disappears into the buttons it names. The
                // audit column reports exactly that while the pointer is on its way there, which
                // is the editor working; a film that ends on it is an advertisement for a broken
                // window.
                .to("the picker's saturation/value field", () -> pickerField(built),
                        0.14f, 0.14f, 14)
                .hold(SETTLE)
                .press()
                .hold(8)
                .to("the picker's saturation/value field", () -> pickerField(built),
                        0.86f, 0.10f, 14)
                .hold(8)
                .release()
                .hold(SETTLE)
                // 6. OK, which keeps it. Cancel is a change too in this widget: dismissing puts
                // the previous colour back, and the film would undo its own middle.
                .to("the picker's OK button", () -> dialogButton(built, "limn.ok"),
                        0.5f, 0.5f, 14)
                .hold(SETTLE)
                .press().hold(SETTLE).release()
                // The card fades out over 160 ms of wall time and holds every press until it has
                // gone. A shorter wait here films the next beat pressing a modal scrim, and 160 ms
                // is eight frames at the capture's step, so LOOK already clears it twice over.
                .hold(LOOK)
                // 7. Derive, on the button beat 2 brought into view. One press and every other
                // accent tone in the window follows the one just chosen: the largest change in
                // the film, and the one a still cannot carry.
                .to("the derive-from-the-accent button", () -> deriveAccent(built),
                        0.5f, 0.5f, 16)
                .hold(SETTLE)
                .press().hold(SETTLE).release()
                // The last beat of a loop, not a pause before one. Every hold in this script
                // merges into a single encoded page with a summed delay, so a long tail does not
                // read as time to look: it reads as the film stopping. One LOOK is the same beat
                // the other films end on.
                .hold(LOOK);
    }

    /**
     * The saturation/value field of the picker in the open dialog.
     *
     * <p>The field is a private class of {@link ColorPicker} and cannot be named from here, and
     * a fraction of the picker's whole box is not it either: the field's height is a size token
     * and everything under it grows with the tab that is showing. What it is, uniquely in that
     * subtree, is the surface that asks for a crosshair, which is the same fact a user reads off
     * the cursor.
     */
    /**
     * The picker's hue ramp: the one surface under the ColorPicker that is taller than it is
     * wide. It carries no cursor of its own, so it cannot be told apart the way
     * {@link #pickerField} tells the field apart, and shape is what is left. Nothing else in the
     * picker is portrait: the field is landscape, and every rail, the swatch and the hex row are
     * wide and short.
     */
    private static Widget pickerHueRamp(GalleryScenes.Built built) {
        Widget dialog = openDialog(built);
        List<Widget> pickers = under(dialog, ColorPicker.class);
        if (pickers.isEmpty()) {
            throw new IllegalStateException("the open dialog carries no ColorPicker; it holds "
                    + names(dialog));
        }
        for (Widget widget : under(pickers.get(0), Widget.class)) {
            if (widget.width() > 0 && widget.height() > widget.width() * 2) {
                return widget;
            }
        }
        throw new IllegalStateException("the ColorPicker in the open dialog has no portrait"
                + " surface, so its hue ramp cannot be told from its field and rails");
    }

    private static Widget pickerField(GalleryScenes.Built built) {
        Widget dialog = openDialog(built);
        List<Widget> pickers = under(dialog, ColorPicker.class);
        if (pickers.isEmpty()) {
            throw new IllegalStateException("the open dialog carries no ColorPicker; it holds "
                    + names(dialog));
        }
        for (Widget widget : under(pickers.get(0), Widget.class)) {
            if (widget.cursor() == Cursor.CROSSHAIR) {
                return widget;
            }
        }
        throw new IllegalStateException("the ColorPicker in the open dialog has no crosshair"
                + " surface, so its saturation/value field cannot be told from its ramps");
    }

    /** A button of the open dialog, by the i18n key of its caption. */
    private static Widget dialogButton(GalleryScenes.Built built, String key) {
        return buttonUnder(openDialog(built), key, "the open dialog");
    }

    /**
     * The derive-from-the-accent button, and only once nothing is over the editor.
     *
     * <p>The check for a dialog is not belt and braces. An overlay takes every press in the
     * scene, so a beat that aimed here while the picker was still fading out would film a
     * pointer pressing a button that never hears it, and {@code Motion}'s own hit test cannot
     * see that: it asks the tree the button is in, and the overlay is in a different one.
     */
    private static Widget deriveAccent(GalleryScenes.Built built) {
        Widget dialog = overlay(built);
        if (dialog != null) {
            throw new IllegalStateException("a film step aims into the editor while a modal"
                    + " overlay is still up, which would take the press instead; the beat"
                    + " before it has to hold for longer");
        }
        return findButton(built, "limn.themeEditor.deriveAccent");
    }

    /**
     * The open modal dialog's tree, or null when the scene is showing only its own root.
     *
     * <p>A dialog is not reachable from {@code root()}: {@code Scene.pushOverlay} keeps overlays
     * in a list of its own and gives them a scene but never a parent, and there is no public way
     * to enumerate them. What Scene does publish is the focused widget, and pushing an overlay
     * moves focus into it, so the topmost ancestor of whatever holds focus is the overlay while
     * one is up and the root the rest of the time.
     */
    private static Widget overlay(GalleryScenes.Built built) {
        Widget focused = built.scene().focusedWidget();
        if (focused == null) {
            return null;
        }
        Widget top = focused;
        while (top.parent() != null) {
            top = top.parent();
        }
        return top == built.scene().root() ? null : top;
    }

    /** The same, for a step that cannot proceed without one. */
    private static Widget openDialog(GalleryScenes.Built built) {
        Widget dialog = overlay(built);
        if (dialog == null) {
            throw new IllegalStateException("a film step aims into a dialog and the scene has no"
                    + " overlay open; the click that raises it either missed or has not been"
                    + " given enough frames to be dispatched and laid out");
        }
        return dialog;
    }

    /**
     * A button by the i18n key of its label, so a script names what it presses instead of counting
     * buttons. Throws when nothing matches, which fails the capture rather than filming a hover
     * over whatever happened to be at that index.
     */
    private static Widget findButton(GalleryScenes.Built built, String key) {
        return buttonUnder(built.scene().root(), key, "the filmed scene");
    }

    /** The same search over one subtree, which is how a dialog's own buttons are found. */
    private static Widget buttonUnder(Widget root, String key, String where) {
        List<String> seen = new ArrayList<>();
        for (Widget widget : under(root, Button.class)) {
            Button button = (Button) widget;
            String candidate = button.textSource() == null ? "" : button.textSource().key();
            if (key.equals(candidate)) {
                return button;
            }
            seen.add(candidate);
        }
        throw new IllegalStateException(where + " has no Button keyed '" + key
                + "'; it carries " + seen);
    }

    /** Everything of {@code type} in {@code root}'s tree, root included, in paint order. */
    private static List<Widget> under(Widget root, Class<? extends Widget> type) {
        List<Widget> queue = new ArrayList<>();
        List<Widget> found = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size(); i++) {
            Widget widget = queue.get(i);
            if (type.isInstance(widget)) {
                found.add(widget);
            }
            queue.addAll(widget.children());
        }
        return found;
    }

    /** What a subtree is made of, for a failure message that has to say what it did find. */
    private static List<String> names(Widget root) {
        List<String> found = new ArrayList<>();
        for (Widget widget : under(root, Widget.class)) {
            String name = widget.getClass().getSimpleName();
            if (!found.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }

    /** Primary, then secondary; the disabled one is left alone, because it does nothing. */
    private static Motion button(GalleryScenes.Built built) {
        Widget primary = find(built, Button.class, 0);
        Widget secondary = find(built, Button.class, 1);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(primary, 0.5f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press().hold(SETTLE).release()
                .hold(LOOK)
                .to(secondary, 0.5f, 0.5f, 16)
                .hold(SETTLE)
                .press().hold(SETTLE).release()
                .hold(LOOK + 8);
    }

    /** Click the empty field and type into it: the caret, the text and the focus ring. */
    private static Motion textField(GalleryScenes.Built built) {
        Widget filled = find(built, TextField.class, 0);
        Widget empty = find(built, TextField.class, 1);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(filled, 0.45f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press().hold(6).release()
                .hold(LOOK)
                .to(empty, 0.30f, 0.5f, 16)
                .hold(SETTLE)
                .press().hold(6).release()
                .hold(10)
                .type("hello", 4)
                .hold(LOOK + 10);
    }

    /** Typing into a password field, where what you see is the masking rather than the text. */
    private static Motion passwordField(GalleryScenes.Built built) {
        Widget field = find(built, PasswordField.class, 0);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(field, 0.5f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press().hold(6).release()
                .hold(10)
                .type(" staple", 4)
                .hold(LOOK + 12);
    }

    /**
     * Across the saturation/value field, which is the picker's largest surface and the one
     * whose whole job is being dragged through.
     */
    private static Motion colorPicker(GalleryScenes.Built built) {
        Widget picker = find(built, ColorPicker.class, 0);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(picker, 0.22f, 0.18f, TRAVEL)
                .hold(SETTLE)
                .press()
                .hold(6)
                .to(picker, 0.46f, 0.34f, 20)
                .hold(6)
                .to(picker, 0.14f, 0.42f, 20)
                .hold(6)
                .release()
                .hold(LOOK + 12);
    }

    /** Orbiting the camera, the one gesture a 3D viewport exists to answer. */
    private static Motion viewport3d(GalleryScenes.Built built) {
        Widget viewport = find(built, Viewport3D.class, 0);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(viewport, 0.5f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press()
                .hold(6)
                .to(viewport, 0.76f, 0.44f, 26)
                .hold(6)
                .to(viewport, 0.26f, 0.56f, 30)
                .hold(6)
                .release()
                .hold(LOOK + 12);
    }

    /** Uncheck the checked box, check the empty one, then throw the switch. */
    private static Motion checkbox(GalleryScenes.Built built) {
        Widget checked = find(built, Checkbox.class, 0);
        Widget unchecked = find(built, Checkbox.class, 1);
        Widget switchOff = find(built, Checkbox.class, 3);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(checked, 0.12f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press().hold(8).release()
                .hold(LOOK)
                .to(unchecked, 0.12f, 0.5f, 14)
                .hold(SETTLE)
                .press().hold(8).release()
                .hold(LOOK)
                .to(switchOff, 0.12f, 0.5f, 14)
                .hold(SETTLE)
                .press().hold(8).release()
                .hold(LOOK + 8);
    }

    /** Grab the thumb where it sits, take it down the track and back up past it. */
    private static Motion slider(GalleryScenes.Built built) {
        Widget slider = find(built, Slider.class, 0);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(slider, 0.65f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press()
                .hold(8)
                .to(slider, 0.18f, 0.5f, 22)
                .hold(10)
                .to(slider, 0.86f, 0.5f, 26)
                .hold(8)
                .release()
                .hold(LOOK + 8);
    }

    /** The increment, twice, then the decrement: the field's value is the thing that moves. */
    private static Motion spinner(GalleryScenes.Built built) {
        Widget spinner = find(built, Spinner.class, 0);
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(spinner, 0.93f, 0.28f, TRAVEL)
                .hold(SETTLE)
                .press().hold(8).release()
                .hold(10)
                .press().hold(8).release()
                .hold(LOOK)
                .to(spinner, 0.93f, 0.72f, 12)
                .hold(SETTLE)
                .press().hold(8).release()
                .hold(LOOK + 8);
    }

    /** The divider, taken both ways, which is the whole of what a split pane does. */
    private static Motion splitPane(GalleryScenes.Built built) {
        Widget split = find(built, SplitPane.class, 0);
        // The scene sets the ratio to 0.4, so the divider is 40% along and that is where the
        // press has to land: anywhere else is a press on a pane, which does nothing.
        return Motion.script()
                .from(OFF_X, OFF_Y)
                .to(split, 0.4f, 0.5f, TRAVEL)
                .hold(SETTLE)
                .press()
                .hold(8)
                .to(split, 0.68f, 0.5f, 24)
                .hold(10)
                .to(split, 0.28f, 0.5f, 24)
                .hold(8)
                .release()
                .hold(LOOK + 8);
    }

    /**
     * Across the plot, left to right, pausing on each category: a chart's hover lifts the
     * one under the pointer, and the sweep is what shows that it does.
     */
    private static Motion sweep(Widget chart) {
        Motion motion = Motion.script().from(OFF_X, OFF_Y).to(chart, 0.18f, 0.55f, TRAVEL);
        for (float fraction : new float[] {0.38f, 0.58f, 0.78f}) {
            motion.hold(SETTLE + 4).to(chart, fraction, 0.55f, 14);
        }
        return motion.hold(LOOK + 12);
    }

    /** Around the ring rather than across it: a donut's categories are arcs. */
    private static Motion donutChart(GalleryScenes.Built built) {
        Widget chart = find(built, DonutChart.class, 0);
        Motion motion = Motion.script().from(OFF_X, OFF_Y).to(chart, 0.62f, 0.24f, TRAVEL);
        // Points on a circle around the hole, which is where the arcs are; the hole itself
        // holds a widget and hovering it means nothing.
        float[][] ring = {{0.76f, 0.5f}, {0.62f, 0.76f}, {0.36f, 0.72f}, {0.26f, 0.42f}};
        for (float[] point : ring) {
            motion.hold(SETTLE + 2).to(chart, point[0], point[1], 14);
        }
        return motion.hold(LOOK + 12);
    }

    /**
     * The {@code index}-th widget of {@code type} in the built tree, in paint order.
     *
     * @throws IllegalStateException if there are not that many; a film aimed at a widget the
     *                               scene does not contain would otherwise capture a pointer
     *                               gliding to the corner, which looks like a rendering bug
     *                               rather than a broken script
     */
    private static Widget find(GalleryScenes.Built built, Class<? extends Widget> type, int index) {
        List<Widget> queue = new ArrayList<>();
        List<Widget> found = new ArrayList<>();
        queue.add(built.scene().root());
        for (int i = 0; i < queue.size(); i++) {
            Widget widget = queue.get(i);
            if (type.isInstance(widget)) {
                found.add(widget);
                if (found.size() > index) {
                    return widget;
                }
            }
            queue.addAll(widget.children());
        }
        throw new IllegalStateException("filmed scene has " + found.size() + " "
                + type.getSimpleName() + "(s), and the script wants index " + index);
    }
}
