package lk.coopfed.knoweb.kernel.internal.idempotency;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final UUID ANONYMOUS_USER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredResult> find(Key key) {

        UUID userId = databaseUser(key.userId());

        List<Row> rows = jdbc.query(
                """
                select request_hash,
                       response_status,
                       response_body
                from kernel.idempotency_key
                where user_id = ?
                  and idempotency_key = ?
                  and expires_at > now()
                """,
                (rs, rowNum) -> new Row(
                        rs.getString("request_hash"), rs.getInt("response_status"), rs.getString("response_body")),
                userId,
                key.value());

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Row row = rows.getFirst();

        if (!row.requestHash().equals(key.requestHash())) {
            throw mismatch(key);
        }

        return Optional.of(new StoredResult(row.status(), row.body()));
    }

    @Override
    @Transactional
    public void store(Key key, StoredResult result) {

        UUID userId = databaseUser(key.userId());

        // An expired key may be reused after the required 24-hour lifetime.
        jdbc.update(
                """
                delete from kernel.idempotency_key
                where user_id = ?
                  and idempotency_key = ?
                  and expires_at <= now()
                """,
                userId,
                key.value());

        jdbc.update(
                """
                insert into kernel.idempotency_key (
                    user_id,
                    idempotency_key,
                    request_hash,
                    response_status,
                    response_body,
                    created_at,
                    expires_at
                )
                values (?, ?, ?, ?, ?, now(), now() + interval '24 hours')
                on conflict (user_id, idempotency_key) do nothing
                """,
                userId,
                key.value(),
                key.requestHash(),
                result.status(),
                result.body());

        // Also handles the race where another node inserted the key first.
        List<Row> stored = jdbc.query(
                """
                select request_hash,
                       response_status,
                       response_body
                from kernel.idempotency_key
                where user_id = ?
                  and idempotency_key = ?
                  and expires_at > now()
                """,
                (rs, rowNum) -> new Row(
                        rs.getString("request_hash"), rs.getInt("response_status"), rs.getString("response_body")),
                userId,
                key.value());

        if (stored.isEmpty()) {
            throw new IllegalStateException("Idempotency result was not persisted");
        }

        if (!stored.getFirst().requestHash().equals(key.requestHash())) {
            throw mismatch(key);
        }
    }

    private static UUID databaseUser(UUID userId) {
        return userId == null ? ANONYMOUS_USER : userId;
    }

    private static ProblemException mismatch(Key key) {
        return new ProblemException("idempotency.request_mismatch", Map.of("key", key.value()));
    }

    private record Row(String requestHash, int status, String body) {}
}
