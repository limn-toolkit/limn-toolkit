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
 * <p><b>What one sample is.</b> The scene publishes a tree and then emits that tree's difference
 * (§1.10), and the macOS bridge drains the queue at the top of the <em>next</em> ordinary publish.
 * So a sample belongs to a publish and carries the events emitted since the publish before it —
 * which are precisely the events that publish drained — together with the publish's own wall
 * time and, when the inner bridge is the platform's, the drain's own time and how many of those
 * events reached AppKit as a notification.
 *
 * <p>Test-side on purpose: it is a probe's instrument and not a thing an application installs.
 */
public final class TimingBridge implements AccessibilityBridge {

    /**
     * One publish, as measured.
     *
     * @param number       which publish this was, from one
     * @param events       how many events the scene emitted between the previous publish and this
     *                     one — one frame's difference, and what this publish drained
     * @param publishNanos how long the inner bridge's {@code publish} took, wall clock
     * @param drainNanos   how long its drain took, or {@code -1} when the inner bridge is not the
     *                     platform's and has no drain to time
     * @param posted       how many of the drained events reached the platform, or {@code -1}
     * @param reentrant    whether the platform was on the stack, in which case nothing was drained
     */
    public record Sample(int number, int events, long publishNanos, long drainNanos, int posted,
                         boolean reentrant) {
    }

    private final AccessibilityBridge inner;
    private final AxBridge ax;
    private final List<Sample> samples = new ArrayList<>();
    private int emittedSinceLastPublish;

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
        int events = emittedSinceLastPublish;
        emittedSinceLastPublish = 0;
        long started = System.nanoTime();
        inner.publish(tree, reentrant);
        long publishNanos = System.nanoTime() - started;
        samples.add(new Sample(samples.size() + 1, events, publishNanos,
                ax == null || reentrant ? -1 : ax.lastDrainNanos(),
                ax == null || reentrant ? -1 : ax.lastDrainPosted(),
                reentrant));
    }

    @Override
    public void emit(AccessibleEvent event) {
        emittedSinceLastPublish++;
        inner.emit(event);
    }

    @Override
    public void attach(Host host) {
        inner.attach(host);
    }

    @Override
    public void detach() {
        inner.detach();
    }

    /** @return every publish measured so far, in order. */
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
     * The numbers §13.19 asks for, over every ordinary publish that drained at least one event.
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
            if (!sample.reentrant() && sample.events() > 0) busy.add(sample);
        }
        StringBuilder out = new StringBuilder();
        out.append("timing: ").append(samples.size()).append(" publishes, ")
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
