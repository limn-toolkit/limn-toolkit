package limn.testfixtures;

import limn.graphics.Canvas;
import limn.scene.Scene;
import limn.testing.AllocationProbe;
import limn.testing.NoopCanvas;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.LongSupplier;

/**
 * The work a step of a scene costs, in units that are the same on every machine: how many widgets
 * were painted, how many drawing commands they issued, how many times the application was asked
 * for content, and how many bytes the step allocated.
 * Not time: a count that grows is a regression on a fast machine and on a slow one alike.
 *
 * @param painted   widgets painted by the last frame of the step
 * @param commands  drawing and filling commands the step's frames issued
 * @param asked     times the application's own code was called for content during the step: a
 *                  model for its children, a column for a cell's value, a list for a row's widget
 * @param allocated bytes the step allocated, the least of several runs, or -1 where the virtual
 *                  machine does not count
 */
public record Work(int painted, int commands, long asked, long allocated) {

    /** A canvas that draws nothing and counts what it was asked to draw. */
    public static final class Counting {
        private final NoopCanvas under;
        private int commands;
        public final Canvas canvas;

        public Counting(int width, int height) {
            under = new NoopCanvas(width, height);
            canvas = (Canvas) Proxy.newProxyInstance(Canvas.class.getClassLoader(), new Class<?>[] {Canvas.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("draw") || name.startsWith("fill") || name.startsWith("stroke")) {
                            commands++;
                        }
                        try {
                            return method.invoke(under, args);
                        } catch (InvocationTargetException thrown) {
                            throw thrown.getCause();
                        }
                    });
        }

        public int take() {
            int counted = commands;
            commands = 0;
            return counted;
        }
    }

    /**
     * Measures a step that is done and then framed.
     *
     * @param scene   the scene the step acts on
     * @param canvas  what its frames are drawn on
     * @param step    the step: an input, a change of state; run several times, so it must be one
     *                that can be repeated, a scroll by one more row
     * @param width   the scene's width
     * @param height  the scene's height
     * @param asked   a count the application's code keeps of the times it was called
     */
    public static Work of(Scene scene, Counting canvas, Runnable step, int width, int height, LongSupplier asked) {
        Runnable framed = () -> {
            step.run();
            scene.layoutPass(width, height);
            scene.renderFrame(canvas.canvas);
        };
        for (int warm = 0; warm < 30; warm++) {
            framed.run();
        }
        canvas.take();
        long askedBefore = asked.getAsLong();
        framed.run();
        long calls = asked.getAsLong() - askedBefore;
        int commands = canvas.take();
        int painted = (int) scene.metrics().paintedWidgets().last();
        long allocated = AllocationProbe.isSupported() ? AllocationProbe.leastAllocatedBy(framed, 12) : -1;
        return new Work(painted, commands, calls, allocated);
    }

    /**
     * Fails when a count of work passed its ceiling, saying what to do about it.
     *
     * @param what     what was counted, for the message
     * @param measured the count
     * @param ceiling  the most it may be
     */
    public static void within(String what, long measured, long ceiling) {
        if (measured > ceiling) {
            throw new AssertionError(what + ": " + measured + ", over its budget of " + ceiling
                    + ". A count of work is the same on every machine, so this is a change in the code and not"
                    + " noise: find what made it grow; raise the budget only for growth that was meant, and say"
                    + " why where the budget is written");
        }
    }

    /**
     * Fails when the same step costs more over a large content than over a small one: what is
     * drawn must be exactly the same, and what is allocated may differ by a tenth.
     *
     * @param what  what was done, for the message
     * @param small the step over the small content
     * @param large the step over the large one
     */
    public static void sameAtAnySize(String what, Work small, Work large) {
        if (small.painted != large.painted || small.commands != large.commands || small.asked != large.asked) {
            throw new AssertionError(what + " costs by the size of its content: " + small + " small, " + large
                    + " large. A screen shows the same number of rows whatever the list holds");
        }
        if (small.allocated >= 0 && large.allocated > small.allocated + small.allocated / 10 + 1024) {
            throw new AssertionError(what + " allocates by the size of its content: " + small.allocated
                    + " bytes small, " + large.allocated + " large");
        }
    }
}
