package limn.demo.site;

import limn.animation.ColorTransition;
import limn.animation.Easing;
import limn.animation.Motion;
import limn.animation.Transition;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Opacity;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Transform;

/**
 * The worked examples the animations guide shows: a value that eases to where it is sent, a
 * colour that does, a pulse that repeats, a ticker for motion that is not a move from one value
 * to another, a part of a window fading in and out, and one sliding in and turning over.
 * Compiled by {@code ./gradlew check} and run by {@code AnimationExampleTest}, so what the guide
 * says they do is what they do.
 */
public final class AnimationExample {

    private AnimationExample() {
    }

    // #region guide:animation-transition
    /** A lamp that fades on and off. */
    public static final class Lamp extends Widget<Lamp> {

        // One transition for one number: how lit the lamp is, from 0 to 1.
        private final Transition lit = new Transition(this).duration(0.18).easing(Easing.EASE_OUT);

        /** Turns the lamp on or off; the picture follows over the next frames. */
        public Lamp setOn(boolean on) {
            Ui.checkUiThread();
            lit.to(on ? 1 : 0);       // where it is going; it repaints itself while it gets there
            return this;
        }

        /** @return how lit the lamp is drawn now, from 0 to 1 */
        public float brightness() {
            return lit.value();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(32, 32);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            float glow = lit.value(); // read at every paint: the eased value of this frame
            canvas.fillCircle(width() / 2, height() / 2, 8 + 6 * glow,
                    Theme.of(this).primary().withAlpha(0.25f + 0.75f * glow));
        }
    }
    // #endregion

    // #region guide:animation-color
    /** A swatch whose fill goes to the hover colour and back. */
    public static final class Swatch extends Widget<Swatch> {

        private ColorTransition fill;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(96, 32);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            Theme theme = Theme.of(this);
            if (event.type() == MouseEvent.Type.ENTER) {
                fill().to(theme.primaryHover());
            } else if (event.type() == MouseEvent.Type.EXIT) {
                fill().to(theme.primary());
            }
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRoundRect(0, 0, width(), height(), 6, fill().value());
        }

        // Made at first use and not in the constructor: the theme's colours and durations are
        // the ones in effect where the widget is shown.
        private ColorTransition fill() {
            if (fill == null) {
                Theme theme = Theme.of(this);
                fill = new ColorTransition(this, theme.primary())
                        .duration(theme.animHover).easing(theme.animEasing);
            }
            return fill;
        }
    }
    // #endregion

    // #region guide:animation-repeat
    /** A dot that breathes while something is being waited for. */
    public static final class Breathing extends Widget<Breathing> {

        private final Transition breath = new Transition(this).duration(0.9)
                .easing(Easing.EASE_IN_OUT).repeat(true);

        /** Starts or stops the breathing; stopped, the dot rests small. */
        public Breathing setWaiting(boolean waiting) {
            Ui.checkUiThread();
            if (waiting) {
                breath.to(1);         // with repeat, it goes there and back until told to stop
            } else {
                breath.snap(0);       // now, with no animation
            }
            return this;
        }

        /** @return whether it is breathing now */
        public boolean isBreathing() {
            return breath.isAnimating();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(24, 24);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillCircle(width() / 2, height() / 2, 4 + 4 * breath.value(), Theme.of(this).primary());
        }
    }
    // #endregion

    // #region guide:animation-ticker
    /** A hand that goes round for as long as it is on screen: a turn every two seconds. */
    public static final class Dial extends Widget<Dial> {

        private static final double SECONDS_A_TURN = 2;

        private double turn;          // 0 to 1, one whole turn
        private double held;          // time kept back while the user asked for less motion
        private boolean ticking;

        /** @return where the hand is, as a share of a turn from 0 to 1 */
        public double turn() {
            return turn;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(48, 48);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            start();
            double angle = turn * 2 * Math.PI;
            float cx = width() / 2;
            float cy = height() / 2;
            float reach = Math.min(cx, cy) - 4;
            canvas.drawLine(cx, cy, cx + (float) (Math.sin(angle) * reach),
                    cy - (float) (Math.cos(angle) * reach), 2, Theme.of(this).primary());
        }

        // Started by the first paint, which is when the widget is in a scene and on screen.
        private void start() {
            Scene scene = scene();
            if (ticking || scene == null) {
                return;
            }
            ticking = true;
            scene.addTicker(dt -> {
                if (!isShowing()) {
                    ticking = false;
                    return false;     // off screen: stop, and the next paint starts it again
                }
                // Every frame by its own length; a few times a second for a user who asked for
                // less motion, by the time that went by since the last step.
                double moved = Motion.stepped(dt, held);
                held = moved > 0 ? 0 : held + dt;
                if (moved > 0) {
                    turn = (turn + moved / SECONDS_A_TURN) % 1;
                    invalidate();     // the ticker moves the number; this is what repaints it
                }
                return true;          // again next frame
            });
        }
    }
    // #endregion

    // #region guide:animation-fade
    /** A notice, in the container that fades it and all it holds as one picture; hidden to start with. */
    static Opacity noticeOf(Widget<?> content) {
        return new Opacity(content).setVisible(false);
    }

    /** Fades the notice in where it was hidden. */
    static void showNotice(Opacity notice) {
        notice.setOpacity(0).setVisible(true);
        notice.fadeTo(1);       // over the theme's window animation
    }

    /** Fades it out, and hides it when the fade arrives. */
    static void dismissNotice(Opacity notice) {
        // At 0 it would still take a click, a Tab and a reader's attention: hidden when the fade arrives.
        notice.fadeTo(0, 0.25, () -> notice.setVisible(false));
    }
    // #endregion

    // #region guide:animation-transform
    /** How wide the drawer is: the distance it slides. */
    static final float DRAWER_WIDTH = 280;

    /** A drawer laid out at the end edge of a page, in the container that moves its picture; hidden to start with. */
    static Transform drawerOf(Widget<?> panel) {
        return new Transform(new SizedBox(DRAWER_WIDTH, SizedBox.UNSET, panel)).setVisible(false);
    }

    /** Slides the drawer in from the end edge: the right in a left-to-right window, the left in a right-to-left one. */
    static void openDrawer(Transform drawer) {
        drawer.setTranslation(DRAWER_WIDTH, 0).setVisible(true); // its own width toward the end: out of sight
        drawer.slideTo(0, 0);                                    // in, over the theme's window animation
    }

    /** Slides it out again, and hides it when the slide arrives. */
    static void closeDrawer(Transform drawer) {
        // Out of sight it would still take a click and a Tab: hidden when the slide arrives.
        drawer.slideTo(DRAWER_WIDTH, 0, 0.2, () -> drawer.setVisible(false));
    }

    /** Turns a card over: its two faces are layers of a stack, swapped while the card is edge-on. */
    static void turnOver(Transform card, Widget<?> front, Widget<?> back) {
        card.flipTo(Transform.Flip.FORWARD, () -> {
            front.setVisible(!front.isVisible());
            back.setVisible(!back.isVisible());
        });
    }
    // #endregion

    // #region guide:animation-time
    /** What the application may do to its own motion: stop it, slow it, start it again. */
    static void freeze(Scene scene, boolean frozen) {
        scene.setPaused(frozen);      // tickers of the scene, and transitions that asked for scene time
    }

    static void slowMotion(Scene scene) {
        scene.setTimeScale(0.25);     // a quarter of the speed; 1 is the clock's own
    }
    // #endregion

    // #region guide:animation-test
    /** A test's way of looking at an animation part of the way through: it moves the clock. */
    static boolean isPartWayAfter90Millis(Lamp lamp) {
        long[] nanos = {0};
        Scene scene = new Scene(lamp, () -> nanos[0]);   // the scene's clock is the test's
        limn.graphics.Canvas canvas = new limn.testing.NoopCanvas(200, 100);
        scene.layoutPass(200, 100);
        lamp.setOn(true);
        scene.renderFrame(canvas);      // the transition's first frame
        nanos[0] += 90_000_000;         // 90 ms later
        scene.renderFrame(canvas);
        return lamp.brightness() > 0 && lamp.brightness() < 1;
    }
    // #endregion
}
