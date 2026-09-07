package limn.scene;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;

/**
 * Measures what a stretch of work allocates on the calling thread, for the claim in this package
 * that a frame nobody is listening to costs no memory at all.
 *
 * <p>The counter is exact rather than sampled: it reports the bytes the thread requested, not what
 * survived a collection, so a single {@code new} inside a measured loop shows up. The work is passed
 * as a {@link Runnable} whose capture happens at the call site, before measuring starts, so building
 * it costs the measurement nothing.
 *
 * <p>A copy of the one under {@code limn.video.decode}, and deliberately a copy: it is a test
 * utility of five lines whose only alternative is a shared test-support package that every module
 * would then depend on, and the claim it measures here — that a frame with nothing listening
 * allocates nothing at all — has to be measurable from this package.
 */
final class AllocationProbe {

    private AllocationProbe() {
    }

    /**
     * @return whether this virtual machine counts per-thread allocation; where it does not, an
     *         allocation test has nothing to measure and must be skipped rather than passed
     */
    static boolean isSupported() {
        return ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported();
    }

    /**
     * Runs {@code work} once to load its classes and link its call sites, then measures it
     * {@code attempts} more times.
     *
     * <p>For a claim about <em>one</em> measurement, such as an absolute zero. Two numbers that are
     * going to be compared with each other have to come from
     * {@link #typicalAllocatedByEach(Runnable, Runnable, int)} instead, for the reason written there.
     *
     * @return the smallest measurement, in bytes. The smallest, and not the first or the mean,
     *         because the just-in-time compiler occasionally charges a few kilobytes of its own
     *         bookkeeping to whichever thread tripped it, which would make a loop that allocates
     *         nothing look as though it does; work that genuinely allocates does so on every attempt.
     */
    static long leastAllocatedBy(Runnable work, int attempts) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        work.run();
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < attempts; attempt++) {
            long before = bean.getThreadAllocatedBytes(thread);
            work.run();
            least = Math.min(least, bean.getThreadAllocatedBytes(thread) - before);
        }
        return least;
    }

    /**
     * Measures two variants of the same work <b>alternately</b>, and returns what each of them
     * <b>typically</b> allocated, in order.
     *
     * <p>Both words are the fix for a failure that looked like flakiness with no cause: two of
     * these numbers subtracted, differing by thirty-two bytes on a whole-suite run, never on a
     * targeted one, and never twice in the same test.
     *
     * <p><b>Alternately, because the two numbers are going to be compared.</b> A difference
     * between them only means something if the machine was in the same state for both, and over
     * sixty frames measured one window after the other it is not: the just-in-time compiler
     * changes its mind about the frame path, sometimes part way through a window. One recorded
     * pair had the second window run at 23056 bytes for thirty-eight frames and 23280 for the
     * remaining twenty-two, so its minimum was a state the first window never saw. Interleaved,
     * such a change lands in both within a frame of each other.
     *
     * <p><b>Typically, because the counter is an approximation and the minimum is its worst
     * reading.</b> {@code getThreadAllocatedBytes} accounts at the granularity of the thread's
     * allocation buffer rather than per object, so a single sample can fall below the true
     * per-frame cost and the next makes it up. Taking the smallest of sixty samples therefore
     * selects exactly the most under-reported one, and which window that lands in is chance. The
     * recorded case is unambiguous: sixty frames flat at 3000 bytes in one window, and 3000 in the
     * other for fifty-nine of them with a single frame at 2968 — the widget's describe pass had
     * cost nothing at all, and the thirty-two bytes were the counter's.
     *
     * <p>The median keeps the property the minimum was chosen for, which is that the compiler
     * occasionally charges a few kilobytes of its own bookkeeping to whichever thread tripped it;
     * it simply filters an outlier on both sides instead of one. Nothing is widened: each side is
     * still one exact number and the caller still asserts an exact equality.
     *
     * @param first    one variant
     * @param second   the other
     * @param attempts how many times to run each; the median wants an odd count and takes the
     *                 lower of the two middle samples when it is given an even one
     * @return {@code {typical for first, typical for second}}, in bytes
     */
    static long[] typicalAllocatedByEach(Runnable first, Runnable second, int attempts) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        first.run();
        second.run();
        long[] byFirst = new long[attempts];
        long[] bySecond = new long[attempts];
        for (int attempt = 0; attempt < attempts; attempt++) {
            long before = bean.getThreadAllocatedBytes(thread);
            first.run();
            long between = bean.getThreadAllocatedBytes(thread);
            second.run();
            long after = bean.getThreadAllocatedBytes(thread);
            byFirst[attempt] = between - before;
            bySecond[attempt] = after - between;
        }
        return new long[] {median(byFirst), median(bySecond)};
    }

    private static long median(long[] samples) {
        long[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        return sorted[(sorted.length - 1) / 2];
    }
}
