package lk.coopfed.knoweb.kernel.internal.render;

import java.time.Duration;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.ProblemException;

/**
 * How many Chromium processes this instance runs at once (wave 2, TWK-28, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-kernel-defaults-and-limits.md (5)). A browser is a few
 * hundred MB; with no cap, a burst of report requests could take the worker's memory. A render
 * waits for a free slot up to a bound and is otherwise answered 503 {@code render.busy}, which the
 * caller may try again.
 *
 * <p>A semaphore whose size is read on every call (the register's {@code render.max_concurrent}),
 * so a changed value applies at once; a lowered value lets the renders already running finish.
 */
final class RenderSlots {

    private int running;

    /** Runs the work in a slot, waiting at most {@code wait} for one. */
    <T> T run(int max, Duration wait, Supplier<T> work) {
        acquire(Math.max(1, max), wait);
        try {
            return work.get();
        } finally {
            release();
        }
    }

    private synchronized void acquire(int max, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (running >= max) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new ProblemException("render.busy");
            }
            try {
                wait(Math.max(1, left / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProblemException("render.busy");
            }
        }
        running++;
    }

    private synchronized void release() {
        running--;
        notifyAll();
    }

    /** For the tests and the health view. */
    synchronized int running() {
        return running;
    }
}
