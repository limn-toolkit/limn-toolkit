package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A bridge standing in front of another one, counting what the scene hands it and timing what it
 * does with it — the instrument behind ADR&nbsp;039 §13.19's macOS half.
 *
 * <p>The record says the event queue's capacity is a policy with no measurement behind it, and
 * names the two numbers that would justify one: how many events one frame's difference produces
 * while a list scrolls and a slider is dragged with VoiceOver attached, and what a frame's drain
 * costs. This wrapper takes both off the seam an application already uses —
 * {@code NativeWindow#setAccessibility} — so that what is measured is exactly what a scene hands a
 * bridge, and never a tree published by hand.
 *
 * <p><b>What one sample is.</b> The scene publishes a tree, emits that tree's difference (§1.10) and
 * ends the frame, and the macOS bridge drains its queue when the frame ends (MACOS-NEW-8; until
 * then it drained at the top of the next publish that changed the tree, and a sample was a
 * publish). So a sample is one frame that published or emitted something: the events emitted since
 * the previous frame ended — which are precisely the events this frame's end drained, a reentrant
 * publish's included — the wall time of the publishes in between and, when the inner bridge is the
 * platform's, the drain's own time and how many of those events reached AppKit as a notification.
 * A frame that did neither is not a sample.
 *
 * <p>Test-side on purpose: it is a probe's instrument and not a thing an application installs.
 */
public final class TimingBridge implements AccessibilityBridge {

    /**
     * One frame, as measured.
     *
     * @param number       which sample this was, from one
     * @param events       how many events the scene emitted since the previous frame ended — one
     *                     frame's difference, and what this frame's end drained
     * @param publishes    how many publishes the inner bridge was handed in that time
     * @param publishNanos how long those publishes took, wall clock, together; zero when none
     * @param drainNanos   how long the frame end's drain took, or {@code -1} when the inner bridge is
     *                     not the platform's or there was nothing to drain
     * @param posted       how many of the drained events reached the platform, or {@code -1}
     * @param reentrant    whether one of those publishes had the platform on the stack
     */
    public record Sample(int number, int events, int publishes, long publishNanos, long drainNanos,
                         int posted, boolean reentrant) {
    }

    private final AccessibilityBridge inner;
    private final AxBridge ax;
    private final List<Sample> samples = new ArrayList<>();
    private int emittedSinceFrameEnd;
    private int publishesSinceFrameEnd;
    private long publishNanosSinceFrameEnd;
    private boolean reentrantSinceFrameEnd;

    /**
     * @param inner the bridge to measure, which is the platform's when there is one
     */
    public TimingBridge(AccessibilityBridge inner) {
        this.inner = inner;
        this.ax = inner instanceof AxBridge platform ? platform : null;
    }

    /** @return the bridge being measured, for a probe that prints what it is holding. */
    public AccessibilityBridge inner() {
        return inner;
    }

    @Override
    public boolean isListening() {
        return inner.isListening();
    }

    @Override
    public boolean needsPrimingPublish() {
        return inner.needsPrimingPublish();
    }

    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        long started = System.nanoTime();
        inner.publish(tree, reentrant);
        publishNanosSinceFrameEnd += System.nanoTime() - started;
        publishesSinceFrameEnd++;
        reentrantSinceFrameEnd |= reentrant;
    }

    @Override
    public void emit(AccessibleEvent event) {
        emittedSinceFrameEnd++;
        inner.emit(event);
    }

    @Override
    public void frameEnded() {
        inner.frameEnded();
        int events = emittedSinceFrameEnd;
        if (events == 0 && publishesSinceFrameEnd == 0) return;
        // Every event emitted since the last frame end is on the platform bridge's queue, so a
        // frame with events is a frame whose end drained; one without has no drain to report.
        boolean drained = ax != null && events > 0;
        samples.add(new Sample(samples.size() + 1, events, publishesSinceFrameEnd,
                publishNanosSinceFrameEnd,
                drained ? ax.lastDrainNanos() : -1,
                drained ? ax.lastDrainPosted() : -1,
                reentrantSinceFrameEnd));
        emittedSinceFrameEnd = 0;
        publishesSinceFrameEnd = 0;
        publishNanosSinceFrameEnd = 0;
        reentrantSinceFrameEnd = false;
    }

    @Override
    public void attach(Host host) {
        inner.attach(host);
    }

    @Override
    public void detach() {
        inner.detach();
    }

    /** @return every frame measured so far, in order. */
    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    /**
     * @param from how many samples the caller has already seen
     * @return the samples after those
     */
    public List<Sample> samplesSince(int from) {
        return List.copyOf(samples.subList(Math.min(from, samples.size()), samples.size()));
    }

    /**
     * The numbers §13.19 asks for, over every frame that drained at least one event.
     *
     * <p>Quiet publishes are left out of the typical figures on purpose: a frame that changed
     * nothing tells nothing about what a difference costs, and a median over mostly-quiet frames
     * would say a scroll is free.
     *
     * @return one line per figure, ready for a log
     */
    public String summary() {
        List<Sample> busy = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.events() > 0) busy.add(sample);
        }
        StringBuilder out = new StringBuilder();
        out.append("timing: ").append(samples.size()).append(" frames, ")
                .append(busy.size()).append(" of them with events\n");
        if (busy.isEmpty()) return out.toString();
        long[] events = busy.stream().mapToLong(Sample::events).toArray();
        long[] publish = busy.stream().mapToLong(Sample::publishNanos).toArray();
        out.append("  events per frame: max ").append(max(events))
                .append(", median ").append(median(events)).append('\n');
        out.append("  publish ns: max ").append(max(publish))
                .append(", median ").append(median(publish)).append('\n');
        if (ax != null) {
            long[] drain = busy.stream().mapToLong(Sample::drainNanos).toArray();
            long[] posted = busy.stream().mapToLong(Sample::posted).toArray();
            out.append("  drain ns: max ").append(max(drain))
                    .append(", median ").append(median(drain)).append('\n');
            out.append("  posted per frame: max ").append(max(posted))
                    .append(", median ").append(median(posted)).append('\n');
            out.append("  collapses: ").append(ax.collapses())
                    .append(" (capacity ").append(AxEvents.CAPACITY).append(")\n");
        }
        return out.toString();
    }

    private static long max(long[] values) {
        return Arrays.stream(values).max().orElse(0);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
