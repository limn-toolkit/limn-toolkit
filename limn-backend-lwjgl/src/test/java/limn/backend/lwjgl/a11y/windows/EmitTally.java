package limn.backend.lwjgl.a11y.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two counts ADR&nbsp;039 &sect;13.19 asks the Windows guest for, kept apart from the bridge
 * that produces them so that the arithmetic can be checked on a machine with no UI Automation.
 *
 * <p>A frame is what lies between two {@link #published() publishes}: the scene hands a bridge one
 * tree and then every event of that tree's difference, so the events that arrive after a publish
 * and before the next are that frame's, exactly. An emit is either <em>raised</em> — the bridge
 * held an element for the node, so the call went into {@code uiautomationcore} — or <em>skipped</em>,
 * because no client had ever asked for that node and there was nobody to tell. Only the raised
 * ones say what a raise costs; the skipped ones are a map lookup and are counted separately so
 * they cannot pull the median down.
 */
final class EmitTally {

    private final List<Integer> eventsPerFrame = new ArrayList<>();
    private final List<Long> raisedNanos = new ArrayList<>();
    private final List<Long> skippedNanos = new ArrayList<>();
    private int inThisFrame;
    private int raisedInThisFrame;
    private boolean open;

    /** A publish: closes the frame being counted, if one was, and opens the next. */
    void published() {
        if (open) {
            eventsPerFrame.add(inThisFrame);
        }
        inThisFrame = 0;
        raisedInThisFrame = 0;
        open = true;
    }

    /**
     * One emit of the open frame.
     *
     * @param nanos  how long the bridge's own {@code emit} took, by {@code System.nanoTime()}
     * @param raised whether the bridge held an element for the node and so reached the platform
     */
    void emitted(long nanos, boolean raised) {
        inThisFrame++;
        if (raised) {
            raisedInThisFrame++;
            raisedNanos.add(nanos);
        } else {
            skippedNanos.add(nanos);
        }
    }

    /** Closes the open frame without opening another, for the end of a run. */
    void close() {
        if (open) {
            eventsPerFrame.add(inThisFrame);
            open = false;
        }
        inThisFrame = 0;
        raisedInThisFrame = 0;
    }

    /** @return events counted so far in the frame that is open, or zero between frames */
    int eventsInOpenFrame() {
        return inThisFrame;
    }

    /** @return of those, how many reached the platform */
    int raisedInOpenFrame() {
        return raisedInThisFrame;
    }

    /** @return how many frames have been closed */
    int frames() {
        return eventsPerFrame.size();
    }

    /** @return the most events any closed frame carried, or zero */
    int maxPerFrame() {
        int max = 0;
        for (int n : eventsPerFrame) {
            max = Math.max(max, n);
        }
        return max;
    }

    /** @return the median over closed frames, or zero with none: the "typical" frame */
    int medianPerFrame() {
        if (eventsPerFrame.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(eventsPerFrame);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    /** @return how many emits reached the platform, over the whole run */
    int raised() {
        return raisedNanos.size();
    }

    /** @return how many emits found no element and went nowhere */
    int skipped() {
        return skippedNanos.size();
    }

    long minRaiseNanos() {
        return raisedNanos.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    long medianRaiseNanos() {
        return median(raisedNanos);
    }

    long maxRaiseNanos() {
        return raisedNanos.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    long medianSkipNanos() {
        return median(skippedNanos);
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    /** @return one line per closed frame's count, then the totals, for a log */
    String summary() {
        return String.format(Locale.ROOT,
                "TALLY frames=%d events/frame max=%d median=%d | raised=%d ns min=%d median=%d max=%d"
                        + " | skipped=%d ns median=%d | per frame=%s",
                frames(), maxPerFrame(), medianPerFrame(), raised(), minRaiseNanos(),
                medianRaiseNanos(), maxRaiseNanos(), skipped(), medianSkipNanos(), eventsPerFrame);
    }
}
