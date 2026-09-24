package lk.coopfed.knoweb.kernel.internal.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcIdempotencyStorePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aKeyFromThePreviousUtcDayStillReplaysInsideTheConfiguredWindow() {

        UUID user = UUID.randomUUID();
        String value = "cross-day-" + UUID.randomUUID();
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String body = "\"existing-result\"";

        // Put the row in yesterday's daily partition while keeping created_at
        // inside the configured 24-hour semantic window.
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.idempotency_key (
                            user_id,
                            idempotency_key,
                            created_on,
                            request_hash,
                            response_status,
                            response_body,
                            created_at
                        )
                        VALUES (
                            ?,
                            ?,
                            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date - 1,
                            ?,
                            200,
                            ?,
                            ((date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '1 microsecond') AT TIME ZONE 'UTC')
                        )
                        """,
                        user,
                        value,
                        hash,
                        body);

        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

        IdempotencyStore.Claim claim = transaction.execute(status -> {
            jdbc.queryForObject(
                    """
                                    SELECT set_config(
                                        'app.user_id',
                                        ?,
                                        true
                                    )
                                    """,
                    String.class,
                    user.toString());

            return store.claim(new IdempotencyStore.Key(value, user, hash));
        });

        assertThat(claim).isEqualTo(new IdempotencyStore.Replay(new IdempotencyStore.StoredResult(200, body)));
    }
}
