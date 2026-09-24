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

        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("Consumer name must not be blank");
        }

        if (eventId == null) {
            throw new IllegalArgumentException("Event id must not be null");
        }

        if (handler == null) {
            throw new IllegalArgumentException("Event handler must not be null");
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {

            throw new IllegalStateException("InboxGuard must run in the consumer transaction");
        }

        int inserted = jdbc.update(
                """
                        INSERT INTO kernel.event_inbox (
                            consumer,
                            event_id,
                            outcome
                        )
                        VALUES (?, ?, 'APPLIED')
                        ON CONFLICT (consumer, event_id)
                        DO NOTHING
                        """,
                consumer,
                eventId);

        if (inserted == 0) {
            return false;
        }

        handler.run();

        return true;
    }
}
