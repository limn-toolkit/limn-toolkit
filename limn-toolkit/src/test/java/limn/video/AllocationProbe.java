package limn.video;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;

/**
 * Measures what a stretch of work allocates on the calling thread, for the two claims in this
 * package that a steady-state loop allocates nothing at all: the converter's and the clock's.
 *
 * <p>The counter is exact rather than sampled: it reports the bytes the thread requested, not what
 * survived a collection, so a single {@code new} inside a measured loop shows up. The work is passed
 * as a {@link Runnable} whose capture happens at the call site, before measuring starts, so building
 * it costs the measurement nothing.
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
}
