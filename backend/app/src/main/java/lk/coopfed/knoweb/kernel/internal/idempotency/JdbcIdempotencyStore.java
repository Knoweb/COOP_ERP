package lk.coopfed.knoweb.kernel.internal.idempotency;

import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(Key key) {
        requireTransaction();

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
                ON CONFLICT (
                    user_id,
                    idempotency_key,
                    created_on
                )
                DO NOTHING
                """,
                key.userId(),
                key.value(),
                key.requestHash());

        if (inserted == 1) {
            return new Claimed();
        }

        List<Row> rows = jdbc.query(
                """
                SELECT
                    request_hash,
                    response_status,
                    response_body
                FROM kernel.idempotency_key
                WHERE user_id = ?
                  AND idempotency_key = ?
                  AND created_on =
                      (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
                """,
                (rs, rowNum) -> new Row(
                        rs.getString("request_hash"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body")),
                key.userId(),
                key.value());

        if (rows.size() != 1) {
            throw new IllegalStateException("Idempotency claim conflict completed without a visible row");
        }

        Row row = rows.getFirst();

        if (!row.requestHash().equals(key.requestHash())) {
            throw new ProblemException("idempotency.request_mismatch", Map.of("key", key.value()));
        }

        if (row.responseStatus() == null || row.responseBody() == null) {
            throw new IllegalStateException("Committed idempotency row has no recorded result");
        }

        return new Replay(new StoredResult(row.responseStatus(), row.responseBody()));
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
                  AND created_on =
                      (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
                  AND request_hash = ?
                """,
                result.status(),
                result.body(),
                key.userId(),
                key.value(),
                key.requestHash());

        if (updated != 1) {
            throw new IllegalStateException("Idempotency result has no matching claim");
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {

            throw new IllegalStateException("IdempotencyStore must run inside the command transaction");
        }
    }

    private record Row(String requestHash, Integer responseStatus, String responseBody) {}
}
