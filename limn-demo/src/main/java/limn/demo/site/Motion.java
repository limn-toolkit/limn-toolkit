package limn.demo.site;

import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What a hand does to one component while the gallery is filming it.
 *
 * <p>A script is written against widgets ("to the middle of this button, press, wait") and
 * played one frame at a time, because the driver has exactly one lever: capture frame
 * <i>n</i>, advance to <i>n+1</i>, capture again.
 *
 * <p><b>A step's widget is looked up on the frame that step begins, never before.</b> This
 * file used to compile the whole script into a list of positions before the first frame
 * rendered, and two things a film has to do were impossible under that. A widget that does
 * not exist yet could not be named at all: the theme editor's colour picker is a dialog, and
 * there is nothing to aim at until the pointer has clicked a colour well. And a widget that
 * moves during the film kept the coordinate it had at frame zero: the same editor's derive
 * button sits inside a ScrollView, and the published film showed the pointer gliding to
 * where that button had been and pressing on empty canvas.
 *
 * <p>What is still settled before the first frame is the frame COUNT. Every step declares its
 * own length, so {@link Film#frames()} answers while the scene is still at rest, which is what
 * lets the manifest promise a file count and the site loop the result without a seam.
 *
 * <pre>{@code
 * Motion.script()
 *         .from(0, 0)                  // off the top-left corner
 *         .to(button, 0.5f, 0.5f, 8)   // glide to the middle over 8 frames
 *         .hold(4)                     // let the hover transition run
 *         .press().hold(5).release()
 *         .hold(10)
 * }</pre>
 *
 * <p>The frame count is the whole timing model: the gallery's clock steps a fixed 20 ms per
 * frame whatever the machine does, so eight frames is 160 ms here and 160 ms everywhere.
 * Nothing in a script is expressed in seconds, because nothing about the capture is.
 */
final class Motion {

    /**
     * How far one frame of a {@link #reveal} turns the wheel, in notches. A ScrollView moves
     * 48 pt a notch, so this is 24 pt a frame, and a list travels its way into view in four or
     * five of them.
     *
     * <p>Half a notch rather than a whole one, because a whole one reads as a jump; and not a
     * quarter, which reads better and costs the published film about 50 kB. Every frame of a
     * scroll repaints the entire column, so it is the most expensive kind of page there is in
     * the animation the site encodes, and that animation has a hard size ceiling.
     */
    private static final float REVEAL_NOTCH = 0.5f;

    /**
     * Frames a reveal keeps scrolling for after its target has come into view, so a beat does
     * not end with the thing it just revealed flush against the edge it came in past.
     */
    private static final int REVEAL_OVERSHOOT = 2;

    /**
     * One frame of the film.
     *
     * @param typed the character to deliver on this frame, or 0. A film types by handing the
     *              scene one codepoint per frame: that is what the focused field would get
     *              from a keyboard, and spreading it over frames is what makes it read as
     *              typing rather than as text appearing
     * @param wheel wheel notches to deliver at the pointer on this frame, or 0. Negative is
     *              the direction a hand turns a wheel to read further down a list
     */
    record Frame(float x, float y, boolean down, boolean pointerVisible, int typed, float wheel) {

        Frame(float x, float y, boolean down, boolean pointerVisible) {
            this(x, y, down, pointerVisible, 0, 0);
        }
    }

    /**
     * A widget a step aims at, looked up on the frame that step begins.
     *
     * <p>A lookup rather than a widget, so that a script can name something the scene does not
     * have yet. An implementation searches the live tree and is expected to throw when it finds
     * nothing, naming what it looked for and what it found instead: a capture that silently
     * films a pointer over nothing is the defect this whole seam exists to prevent, and every
     * finder in {@code Films} is written that way.
     */
    @FunctionalInterface
    interface Target {

        /** @return the widget, or null when the live scene has none */
        Widget find();
    }

    /**
     * A point on a widget: which widget, whereabouts on its box, and what to call it.
     *
     * <p>{@code what} is read by nothing but the failure messages, and each of those is a
     * capture that would otherwise have been published with the pointer in the wrong place.
     */
    private record Aim(String what, Target target, float fx, float fy) {
    }

    /** A step, kept unresolved until the film reaches it. */
    private sealed interface Step {

        record Glide(Aim aim, int frames) implements Step {
        }

        record Hold(int frames) implements Step {
        }

        record Button(boolean down) implements Step {
        }

        record Type(String text, int framesPerCharacter) implements Step {
        }

        record Reveal(Aim aim, int frames) implements Step {
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private float startX;
    private float startY;
    private boolean pointerVisible = true;

    private Motion() {
    }

    /** A script that shows a pointer using the component. */
    static Motion script() {
        return new Motion();
    }

    /**
     * A script with no pointer in it: the component animates on its own and the frames are
     * there to show it. A spinner needs no hand, and drawing one implies a gesture that is
     * not what makes it move.
     */
    static Motion unattended(int frames) {
        Motion motion = new Motion();
        motion.pointerVisible = false;
        return motion.hold(frames);
    }

    /** Where the pointer starts, in scene points. Off-frame is fine and usually right. */
    Motion from(float x, float y) {
        this.startX = x;
        this.startY = y;
        return this;
    }

    /**
     * Glides to a point inside {@code target}, given as a fraction of its box, over
     * {@code frames} frames. The path is eased at both ends: a pointer that starts and stops
     * at full speed reads as a jump cut rather than as a hand.
     */
    Motion to(Widget target, float fx, float fy, int frames) {
        Objects.requireNonNull(target, "target");
        // A widget the script already holds is still read at the moment its step begins, not
        // here: it may have been laid out somewhere else by then, which is exactly what the
        // theme editor's ScrollView does to the widgets inside it.
        return to(target.getClass().getSimpleName(), () -> target, fx, fy, frames);
    }

    /**
     * The same glide, to a widget the script cannot hold because the scene does not have one
     * yet. {@code what} names it for the failure message if the lookup comes back empty.
     */
    Motion to(String what, Target target, float fx, float fy, int frames) {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(target, "target");
        if (frames < 1) {
            throw new IllegalArgumentException("frames must be >= 1, got " + frames);
        }
        steps.add(new Step.Glide(new Aim(what, target, fx, fy), frames));
        return this;
    }

    /** Stays put for {@code frames} frames, which is how a transition gets time to run. */
    Motion hold(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("frames must be >= 1, got " + frames);
        }
        steps.add(new Step.Hold(frames));
        return this;
    }

    /** Presses the left button where the pointer is. Costs one frame. */
    Motion press() {
        steps.add(new Step.Button(true));
        return this;
    }

    /** Releases it. Costs one frame. */
    Motion release() {
        steps.add(new Step.Button(false));
        return this;
    }

    /**
     * Types {@code text} into whatever has focus, one character every
     * {@code framesPerCharacter} frames.
     *
     * <p>Click the field first: this delivers characters to the scene, and a scene with
     * nothing focused has nowhere to put them. Three frames a character is 60 ms, which is
     * a brisk but human rate: one character a frame reads as a paste.
     */
    Motion type(String text, int framesPerCharacter) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        if (framesPerCharacter < 1) {
            throw new IllegalArgumentException("framesPerCharacter must be >= 1");
        }
        steps.add(new Step.Type(text, framesPerCharacter));
        return this;
    }

    /**
     * Turns the wheel where the pointer is until every corner of {@code target} would answer
     * a press, and then a little further, taking {@code frames} frames however few of them
     * the scrolling needs.
     *
     * <p>Glide onto the scrolling area first. This delivers a wheel to whatever is under the
     * pointer, and a wheel over the panel beside a ScrollView scrolls nothing; the step throws
     * on its last frame rather than letting the beat after it aim at a widget still off the
     * screen. Which way the list travels is read off the target when the step begins.
     *
     * <p>A fixed frame count rather than "as many as it takes": a film whose length depended
     * on how far a list happened to travel would write a different number of files on a
     * machine whose layout differed by one row, and the manifest promises that number.
     */
    Motion reveal(String what, Target target, int frames) {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(target, "target");
        if (frames < 1) {
            throw new IllegalArgumentException("frames must be >= 1, got " + frames);
        }
        steps.add(new Step.Reveal(new Aim(what, target, 0.5f, 0.5f), frames));
        return this;
    }

    /**
     * The script, ready to be advanced a frame at a time.
     *
     * <p>Ask once the scene has been laid out, and keep the answer: a {@link Film} is the
     * playhead as well as the script, and every {@link Film#next()} is the frame after the
     * one before it.
     */
    Film film() {
        return new Film(List.copyOf(steps), pointerVisible, startX, startY);
    }

    /**
     * A script being played: one {@link #next()} per rendered frame, with each step's widget
     * looked up in the scene as that step starts.
     */
    static final class Film {

        private final List<Step> steps;
        private final boolean pointerVisible;
        private final int frames;

        private int emitted;
        private int step;
        private int within;
        private boolean started;
        private float x;
        private float y;
        private boolean down;
        /** What the current step is aimed at, found on the frame it began. */
        private Widget aimed;
        private float fromX;
        private float fromY;
        private float toX;
        private float toY;
        /** A reveal's direction, and how much scrolling is left once the target is in view. */
        private float wheelDirection;
        private int overshoot;

        private Film(List<Step> steps, boolean pointerVisible, float startX, float startY) {
            this.steps = steps;
            this.pointerVisible = pointerVisible;
            this.x = startX;
            this.y = startY;
            int total = 1; // the still it opens on
            for (Step each : steps) {
                total += length(each);
            }
            this.frames = total;
        }

        /**
         * How long this film is, answerable before a frame of it has been played and before
         * any widget it names has been found. The driver writes one file per frame and the
         * manifest carries the count, so it cannot be a number that emerges as the film runs.
         */
        int frames() {
            return frames;
        }

        /** The next frame, and the last one is {@link #frames()} calls in. */
        Frame next() {
            if (emitted >= frames) {
                throw new IllegalStateException("this film is " + frames
                        + " frames long and the driver asked for one more");
            }
            if (emitted++ == 0) {
                // The film opens on the component at rest, with no pointer anywhere in it.
                // That is partly how a film should open and mostly load-bearing: the site
                // measures the crop box for the whole animation on this frame, and an arrow
                // that had already entered it would be content the trim has to keep, which is
                // how the slider's film came out uncropped, at the full size of the capture
                // canvas.
                return new Frame(x, y, false, false);
            }
            while (within >= length(steps.get(step))) {
                step++;
                within = 0;
                started = false;
            }
            Step current = steps.get(step);
            if (!started) {
                started = true;
                begin(current);
            }
            Frame frame = play(current);
            within++;
            return frame;
        }

        /**
         * Starts a step. This is the moment a target is looked up, and the only one: the
         * scene has just rendered every frame before this, so a dialog opened three frames
         * ago is in it, laid out, and a row that scrolled is where it now sits.
         */
        // `instanceof` patterns rather than a switch over the sealed type: this module is
        // compiled at the toolkit's own source level, and switch patterns are not in it.
        private void begin(Step current) {
            if (current instanceof Step.Glide glide) {
                aimed = resolve(glide.aim());
                fromX = x;
                fromY = y;
                toX = aimed.localToSceneX() + aimed.width() * glide.aim().fx();
                toY = aimed.localToSceneY() + aimed.height() * glide.aim().fy();
                requireReaches(glide.aim(), aimed, toX, toY);
            } else if (current instanceof Step.Reveal reveal) {
                aimed = resolve(reveal.aim());
                // Read once, not per frame: a target that has just come into view can have
                // its centre on the other side of the pointer, and a direction re-read every
                // frame would then scroll it straight back out.
                wheelDirection = aimed.localToSceneY() + aimed.height() / 2 > y ? -1 : 1;
                overshoot = REVEAL_OVERSHOOT;
            }
        }

        /** The frame this step is on. */
        private Frame play(Step current) {
            if (current instanceof Step.Glide glide) {
                float t = ease((within + 1f) / glide.frames());
                x = fromX + (toX - fromX) * t;
                y = fromY + (toY - fromY) * t;
            } else if (current instanceof Step.Button button) {
                down = button.down();
            } else if (current instanceof Step.Type typing) {
                int gap = within % typing.framesPerCharacter();
                if (gap == 0) {
                    return new Frame(x, y, down, pointerVisible,
                            typing.text().charAt(within / typing.framesPerCharacter()), 0);
                }
            } else if (current instanceof Step.Reveal reveal) {
                return new Frame(x, y, down, pointerVisible, 0, wheelFor(reveal));
            }
            return new Frame(x, y, down, pointerVisible, 0, 0);
        }

        /** How far this frame of a reveal turns the wheel, and whether it is done scrolling. */
        private float wheelFor(Step.Reveal reveal) {
            boolean arrived = reaches(aimed);
            if (arrived && overshoot > 0) {
                overshoot--;
                return wheelDirection * REVEAL_NOTCH;
            }
            if (arrived) {
                return 0;
            }
            if (within == reveal.frames() - 1) {
                throw new IllegalStateException("a film step turned the wheel for "
                        + reveal.frames() + " frames at (" + Math.round(x) + ", " + Math.round(y)
                        + ") to bring " + reveal.aim().what() + " into view and it is still out"
                        + " of it, laid out at " + box(aimed) + "; the wheel was going to "
                        + describe(hitAt(aimed, x, y)));
            }
            return wheelDirection * REVEAL_NOTCH;
        }

        /** How many frames a step is, which is what makes {@link #frames()} answerable. */
        private static int length(Step current) {
            if (current instanceof Step.Glide glide) {
                return glide.frames();
            }
            if (current instanceof Step.Hold hold) {
                return hold.frames();
            }
            if (current instanceof Step.Type typing) {
                return typing.text().length() * typing.framesPerCharacter();
            }
            if (current instanceof Step.Reveal reveal) {
                return reveal.frames();
            }
            return 1; // press, release
        }

        /**
         * The widget a step names, or a failure that says which step and what the scene has.
         *
         * <p>A widget with no box has been built but never laid out, which for an in-scene
         * dialog means the frames between the click that opened it and this step were not
         * enough: the overlay is pushed while input is dispatched and measured in the render
         * after that.
         */
        private static Widget resolve(Aim aim) {
            Widget widget = aim.target().find();
            if (widget == null) {
                throw new IllegalStateException("a film step aims at " + aim.what()
                        + ", and the live scene has none");
            }
            if (widget.width() <= 0 || widget.height() <= 0) {
                throw new IllegalStateException("a film step aims at " + aim.what()
                        + ", which is in the scene and has never been laid out (" + box(widget)
                        + "); the step before it has to hold for longer");
            }
            return widget;
        }

        /**
         * Refuses a glide whose destination is not on the widget it names.
         *
         * <p>This is the published defect, in the form the capture can catch: the derive
         * button was scrolled out of its viewport, {@code localToSceneY} answered anyway, and
         * the film showed the pointer travelling to a blank stretch of the window and pressing
         * there. A widget out of view is still laid out; hit-testing is the thing that knows.
         */
        private static void requireReaches(Aim aim, Widget widget, float pointX, float pointY) {
            if (reaches(widget, pointX, pointY)) {
                return;
            }
            throw new IllegalStateException("a film step glides to " + aim.what() + " at ("
                    + Math.round(pointX) + ", " + Math.round(pointY) + "), where a press would"
                    + " land on " + describe(hitAt(widget, pointX, pointY)) + "; the widget is"
                    + " laid out at " + box(widget) + ", off the visible area or behind"
                    + " something else");
        }

        /**
         * Whether {@code widget} is far enough into its viewport to aim at: its top edge, its
         * middle and its bottom edge all answer a press.
         *
         * <p>Down the centre line rather than at the four corners, because a ScrollView's bar
         * floats over the right edge of its content by default and answers presses while it is
         * showing, which the pointer arriving over the view is enough to make it do. A row
         * stretched to the full width would then never test as reachable at all, and a reveal
         * would scroll it past its own viewport looking for a corner it cannot have.
         */
        private static boolean reaches(Widget widget) {
            float middle = widget.localToSceneX() + widget.width() / 2;
            float top = widget.localToSceneY() + 1;
            float bottom = widget.localToSceneY() + widget.height() - 1;
            return reaches(widget, middle, top) && reaches(widget, middle, bottom)
                    && reaches(widget, middle, (top + bottom) / 2);
        }

        /** Whether a press at this scene point lands on {@code target} or inside it. */
        private static boolean reaches(Widget target, float sceneX, float sceneY) {
            for (Widget hit = hitAt(target, sceneX, sceneY); hit != null; hit = hit.parent()) {
                if (hit == target) {
                    return true;
                }
            }
            return false;
        }

        /**
         * What a press at this scene point would land on, asked of the tree {@code target}
         * itself lives in.
         *
         * <p>Not asked of the scene's root, because a dialog is not under it: Scene keeps
         * overlays in a list of their own and gives them a scene but never a parent, so the
         * walk starts from whichever ancestor has no parent. For a widget in the window that
         * is the root; for one in an open dialog it is the overlay.
         */
        private static Widget hitAt(Widget target, float sceneX, float sceneY) {
            Widget top = target;
            while (top.parent() != null) {
                top = top.parent();
            }
            return top.hitTest(sceneX - top.x(), sceneY - top.y());
        }

        private static String describe(Widget widget) {
            return widget == null ? "nothing"
                    : widget.getClass().getSimpleName() + " at " + box(widget);
        }

        private static String box(Widget widget) {
            return String.format(Locale.ROOT, "%.0f,%.0f %.0fx%.0f", widget.localToSceneX(),
                    widget.localToSceneY(), widget.width(), widget.height());
        }

        /** Smoothstep: zero velocity at both ends, which makes the glide read as a hand. */
        private static float ease(float t) {
            return t * t * (3 - 2 * t);
        }
    }
}
