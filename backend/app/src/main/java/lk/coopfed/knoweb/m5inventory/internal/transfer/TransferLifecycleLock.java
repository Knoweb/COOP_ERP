package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Serialises lifecycle decisions for one transfer until the current transaction completes. */
@Component
class TransferLifecycleLock {

    private static final String KEY_PREFIX = "inventory.transfer:";

    private final JdbcTemplate jdbc;

    TransferLifecycleLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void acquire(UUID transferId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, KEY_PREFIX + transferId);
    }
}
