package limn.scene;

import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Icon;
import limn.graphics.internal.IconTimeline;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The clocks of the animated icons one widget paints, made at the first such paint. Every
 * component draws its icons through {@link Icon#paint}, which asks the widget painting for the
 * frame of the moment here, so no component carries a clock of its own.
 *
 * <p>An icon's clock is the image view's: its time is the scene's, it starts at the paint that
 * first shows the icon, and between two frames it sleeps on a delayed task set for the moment the
 * next is due, then repaints the boxes the icon was painted in and nothing more. A widget painting
 * an icon ten frames a second costs ten frames a second, whatever the display's rate. It runs as
 * a ticker only while the scene's time is stopped.
 *
 * <p>It stops where nobody sees it: in a widget out of its scene or not showing, or one whose
 * last paint did not paint the icon, a row scrolled away. The frame it reached is kept, and the
 * next paint that shows the icon goes on from there. Several boxes of one icon in one widget, the
 * rows of a list showing one spinner, share one clock.
 */
final class IconClocks {

    /** The clocks a widget keeps of icons its last paint did not paint, before it forgets one. */
    private static final int KEPT = 16;

    private final Widget<?> owner;
    /** Counts the widget's paints; a clock whose icon the last one painted carries its number. */
    private int pass;
    private final List<Clock> clocks = new ArrayList<>(2);

    IconClocks(Widget<?> owner) {
        this.owner = owner;
    }

    /** The {@link limn.graphics.internal.IconPlayer.Player} the scene installs. */
    static int frameOf(Object painter, Icon icon, float x, float y, float size) {
        return ((Widget<?>) painter).iconClocks().frameOf(icon, x, y, size);
    }

    /** A paint of the widget begins: the boxes painted from here on are the ones a frame repaints. */
    void newPass() {
        pass++;
    }

    private int frameOf(Icon icon, float x, float y, float size) {
        Clock clock = null;
        for (Clock candidate : clocks) {
            if (candidate.timeline.icon() == icon) {
                clock = candidate;
                break;
            }
        }
        if (clock == null) {
            forgetOne();
            clock = new Clock(new IconTimeline(icon));
            clocks.add(clock);
        }
        clock.paintedIn(x, y, size);
        clock.keepTime();
        return clock.timeline.frame();
    }

    /** Past {@link #KEPT}, forgets the clock painted longest ago that the last paint did not paint. */
    private void forgetOne() {
        if (clocks.size() < KEPT) {
            return;
        }
        Clock oldest = null;
        for (Clock clock : clocks) {
            if (clock.pass != pass && (oldest == null || clock.pass - oldest.pass < 0)) {
                oldest = clock;
            }
        }
        if (oldest != null) {
            oldest.stop();
            clocks.remove(oldest);
        }
    }

    /** The widget left its scene: every clock stops where it is, and holds the widget no longer. */
    void stop() {
        for (Clock clock : clocks) {
            clock.stop();
        }
    }

    /** One icon's clock. */
    private final class Clock {

        final IconTimeline timeline;
        /** The widget's paint that last painted the icon. */
        int pass;
        /** x, y and side of each box the icon was painted in by that paint. */
        private float[] boxes = new float[3];
        private int boxCount;
        private boolean ticking;
        /** When the clock went to sleep, on the scene's clock; -1 when it is not asleep. */
        private long sleptAt = -1;
        private @Nullable Subscription alarm;

        Clock(IconTimeline timeline) {
            this.timeline = timeline;
            this.pass = IconClocks.this.pass - 1;
        }

        void paintedIn(float x, float y, float size) {
            if (pass != IconClocks.this.pass) {
                pass = IconClocks.this.pass;
                boxCount = 0;
            }
            for (int at = 0; at < boxCount * 3; at += 3) {
                if (boxes[at] == x && boxes[at + 1] == y && boxes[at + 2] == size) {
                    return;
                }
            }
            if (boxCount * 3 == boxes.length) {
                boxes = java.util.Arrays.copyOf(boxes, boxes.length * 2);
            }
            boxes[boxCount * 3] = x;
            boxes[boxCount * 3 + 1] = y;
            boxes[boxCount * 3 + 2] = size;
            boxCount++;
        }

        /** Starts the clock from the paint that shows the icon, unless it runs already. */
        void keepTime() {
            Scene home = owner.scene();
            if (ticking || alarm != null || home == null || !timeline.isPlaying()) {
                return;
            }
            step(home, 0);
        }

        /** Moves the icon on and sets the clock for its next frame: asleep, or ticking. */
        private void step(Scene home, double seconds) {
            if (timeline.advance(seconds)) {
                for (int at = 0; at < boxCount * 3; at += 3) {
                    owner.invalidate(boxes[at], boxes[at + 1], boxes[at + 2], boxes[at + 2]);
                }
            }
            if (!timeline.isPlaying()) {
                return;
            }
            double rate = home.isPaused() ? 0 : home.timeScale();
            double due = timeline.secondsUntilNextFrame();
            if (due > 0 && rate > 0) {
                sleep(home, due / rate);
            } else if (!ticking) {
                tick(home); // late, or the scene's time is stopped
            }
        }

        private void tick(Scene home) {
            ticking = true;
            home.addTicker(seconds -> {
                if (!stillFor(home)) {
                    ticking = false;
                    return false;
                }
                step(home, seconds);
                if (alarm != null || !timeline.isPlaying()) {
                    ticking = false; // asleep again, or at its end
                    return false;
                }
                return true;
            });
        }

        /**
         * Whether the clock still has somebody to play for: the widget in the scene it was set
         * in and showing, and its last paint one that painted the icon.
         */
        private boolean stillFor(Scene home) {
            return owner.scene() == home && owner.isShowing() && pass == IconClocks.this.pass
                    && clocks.contains(this);
        }

        private void sleep(Scene home, double wallSeconds) {
            sleptAt = home.clock().getAsLong();
            alarm = Ui.postDelayed(() -> {
                alarm = null;
                long since = sleptAt;
                sleptAt = -1;
                if (stillFor(home)) {
                    double wall = Math.max(0, (home.clock().getAsLong() - since) / 1e9);
                    step(home, wall * (home.isPaused() ? 0 : home.timeScale()));
                }
            }, Math.max(1, (long) Math.ceil(wallSeconds * 1000)));
        }

        /** Stops where it is, the time asleep not counted: nobody saw it pass. */
        void stop() {
            Subscription waiting = alarm;
            if (waiting != null) {
                waiting.cancel();
                alarm = null;
            }
            sleptAt = -1;
        }
    }
}
