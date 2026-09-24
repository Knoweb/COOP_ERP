package lk.coopfed.knoweb.kernel.internal.event;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class InboxGuard {

    private final JdbcTemplate jdbc;

    public InboxGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean applyOnce(String consumer, UUID eventId, Runnable handler) {

        requireTransaction();

        requireConsumer(consumer);

        if (eventId == null) {
            throw new IllegalArgumentException("Event id must not be null");
        }

        if (handler == null) {
            throw new IllegalArgumentException("Event handler must not be null");
        }

        int claimed = jdbc.update(
                """
                        INSERT INTO kernel.event_inbox (
                            consumer,
                            event_id,
                            outcome,
                            last_error
                        )
                        VALUES (
                            ?,
                            ?,
                            'APPLIED',
                            NULL
                        )
                        ON CONFLICT (consumer, event_id)
                        DO UPDATE
                           SET applied_at = now(),
                               outcome = 'APPLIED',
                               last_error = NULL
                         WHERE kernel.event_inbox.outcome = 'FAILED'
                        """,
                consumer,
                eventId);

        if (claimed == 0) {
            return false;
        }

        handler.run();

        return true;
    }

    public void markFailed(String consumer, UUID eventId, String error) {

        requireTransaction();
        requireConsumer(consumer);

        if (eventId == null) {
            throw new IllegalArgumentException("Event id must not be null");
        }

        jdbc.update(
                """
                INSERT INTO kernel.event_inbox (
                    consumer,
                    event_id,
                    outcome,
                    last_error
                )
                VALUES (
                    ?,
                    ?,
                    'FAILED',
                    ?
                )
                ON CONFLICT (consumer, event_id)
                DO UPDATE
                   SET applied_at = now(),
                       outcome = 'FAILED',
                       last_error = EXCLUDED.last_error
                """,
                consumer,
                eventId,
                error);
    }

    private static void requireConsumer(String consumer) {

        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("Consumer name must not be blank");
        }
    }

    private static void requireTransaction() {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {

            throw new IllegalStateException("InboxGuard must run in the consumer transaction");
        }
    }
}
