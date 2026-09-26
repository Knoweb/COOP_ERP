package lk.coopfed.knoweb.m1party.internal.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One lock per entity for the guards that check first and write later: the last-user-manager
 * rule (doc 19 section 3.2, GUARDRAIL) and the per-person separation-of-duties rule (kernel.api
 * Sod). Under READ COMMITTED two commands that each read "two holders left" and each remove one
 * both commit and together break the rule. A transaction-level advisory lock on the entity id,
 * taken before the facts are read, makes the second command wait for the first and then read
 * what the first left. It is released with the transaction, so it is taken only inside one; it
 * serialises the security commands of one entity and nothing else.
 */
@Component
public class EntityLock {

    private final JdbcTemplate jdbc;

    public EntityLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Waits for, then holds until the transaction ends, the lock of this entity. */
    public void lock(UUID entityId) {
        // The advisory lock key is a bigint; hashtextextended gives one from the id's text.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, entityId.toString());
    }
}
