package limn.backend.lwjgl;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;

/**
 * Measures what a stretch of work allocates on the calling thread, for the
 * claim that uploading a picture allocates nothing per picture.
 *
 * <p>The counter is exact rather than sampled: it reports the bytes the thread
 * requested, not what survived a collection, so a single {@code new} inside a
 * measured loop shows up.
 */
final class AllocationProbe {

    private AllocationProbe() {
    }

    /**
     * @return whether this virtual machine counts per-thread allocation; where
     *         it does not, an allocation test has nothing to measure and must
     *         be skipped rather than passed
     */
    static boolean isSupported() {
        return ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported();
    }

    /**
     * Runs {@code work} once to load its classes and link its call sites, then
     * measures it {@code attempts} more times.
     *
     * @return the smallest measurement, in bytes: the smallest and not the
     *         first or the mean, because the just-in-time compiler occasionally
     *         charges a few kilobytes of its own bookkeeping to whichever
     *         thread tripped it, while work that genuinely allocates does so on
     *         every attempt
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
