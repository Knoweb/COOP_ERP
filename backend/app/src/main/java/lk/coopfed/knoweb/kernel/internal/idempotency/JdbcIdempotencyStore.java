package lk.coopfed.knoweb.kernel.internal.idempotency;

import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final int windowHours;

    public JdbcIdempotencyStore(
            JdbcTemplate jdbc, @Value("${coop-erp.idempotency.retention-hours:24}") int windowHours) {

        if (windowHours < 24) {
            throw new IllegalArgumentException(
                    "coop-erp.idempotency.retention-hours must be at least 24: the key is unique per UTC day, so a shorter window"
                            + " would refuse the reuse of a key it has already expired");
        }

        this.jdbc = jdbc;
        this.windowHours = windowHours;
    }

    @Override
    public Claim claim(Key key) {
        requireTransaction();

        // The table primary key contains created_on because PostgreSQL requires the
        // partition key in the unique key. This transaction-scoped lock keeps the
        // logical (user, idempotency-key) unique across UTC-day partitions too.
        jdbc.queryForList(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)
                )
                """,
                key.userId() + ":" + key.value());

        List<Row> existing = jdbc.query(
                """
                        SELECT
                            request_hash,
                            response_status,
                            response_body
                        FROM kernel.idempotency_key
                        WHERE user_id = ?
                          AND idempotency_key = ?
                          AND created_on >=
                              (
                                  (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                                  - make_interval(hours => ?)
                              )::date
                          AND created_at >=
                              CURRENT_TIMESTAMP
                              - make_interval(hours => ?)
                        ORDER BY created_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new Row(
                        rs.getString("request_hash"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body")),
                key.userId(),
                key.value(),
                windowHours,
                windowHours);

        if (!existing.isEmpty()) {
            Row row = existing.getFirst();

            if (!row.requestHash().equals(key.requestHash())) {
                throw new ProblemException("idempotency.request_mismatch", Map.of("key", key.value()));
            }

            if (row.responseStatus() == null) {
                throw new IllegalStateException("Idempotency claim committed without a result");
            }

            return new Replay(new StoredResult(row.responseStatus(), row.responseBody()));
        }

        int inserted = jdbc.update(
                """
                        INSERT INTO kernel.idempotency_key (
                            user_id,
                            idempotency_key,
                            created_on,
                            request_hash
                        )
                        VALUES (
                            ?,
                            ?,
                            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,
                            ?
                        )
                        """,
                key.userId(),
                key.value(),
                key.requestHash());

        if (inserted != 1) {
            throw new IllegalStateException("Idempotency claim was not inserted");
        }

        return new Claimed();
    }

    @Override
    public void complete(Key key, StoredResult result) {

        requireTransaction();

        int updated = jdbc.update(
                """
                        UPDATE kernel.idempotency_key
                           SET response_status = ?,
                               response_body = ?
                         WHERE user_id = ?
                           AND idempotency_key = ?
                           AND request_hash = ?
                           AND response_status IS NULL
                           AND created_on >=
                               (
                                   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                                   - make_interval(hours => ?)
                               )::date
                           AND created_at >=
                               CURRENT_TIMESTAMP
                               - make_interval(hours => ?)
                        """,
                result.status(),
                result.body(),
                key.userId(),
                key.value(),
                key.requestHash(),
                windowHours,
                windowHours);

        if (updated != 1) {
            throw new IllegalStateException("Idempotency result has no matching claim");
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotency store must run inside the command transaction");
        }
    }

    private record Row(String requestHash, Integer responseStatus, String responseBody) {}
}
