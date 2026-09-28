package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Runs work as if it were an earlier moment, for the demo loader only (DEMO-02): the application's
 * {@link java.time.Clock} and {@link BusinessDate} answer that moment on the calling thread while
 * the work runs, so the documents it issues through the ordinary handlers carry past business
 * dates and are numbered, audited and published as any other.
 *
 * <p>Not a back-dating feature. It refuses unless {@code coop-erp.demo.historical-time} is true,
 * which only the one-off container of {@code make demo-data} sets; no controller calls it, and an
 * architecture rule allows the demo package alone to depend on it. It refuses a moment in the
 * future, and it holds only on the calling thread: a listener on another thread, a job and every
 * request keep the real time.
 */
public interface HistoricalTime {

    <T> T at(Instant moment, Supplier<T> work);

    default void run(Instant moment, Runnable work) {
        at(moment, () -> {
            work.run();
            return null;
        });
    }
}
