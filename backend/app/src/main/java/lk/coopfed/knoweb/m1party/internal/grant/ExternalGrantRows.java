package lk.coopfed.knoweb.m1party.internal.grant;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.ExternalGrantView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads of {@code security.external_grant} shared by the handlers, the expiry job and the
 * queries. Reads only: the writes are in the three handlers (only a command handler writes).
 * Every read runs under the caller's row-level security, which is what limits it: the Federation
 * reads its grants (own_read), anybody else nothing. (The grantee_read policy of V0009 stays in
 * the schema, a merged migration being never edited; nothing reads through it since the kernel
 * resolves a grantee's entities itself, JdbcUserScopes.)
 */
@Component
class ExternalGrantRows {

    static final String ACTIVE = "ACTIVE";
    static final String EXPIRED = "EXPIRED";
    static final String REVOKED = "REVOKED";

    private static final String SELECT =
            """
            select grant_id, grantee_user_id, scope_entity_ids, valid_from, valid_until, reason, status
              from security.external_grant
            """;

    static final RowMapper<ExternalGrantView> MAPPER = ExternalGrantRows::map;

    private final JdbcTemplate jdbc;

    ExternalGrantRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One grant, in the scope already set on the current transaction. */
    Optional<ExternalGrantView> find(UUID grantId) {
        return jdbc.query(SELECT + " where grant_id = ?", MAPPER, grantId).stream()
                .findFirst();
    }

    /** Every grant visible to the scope set on the current transaction, latest end first. */
    List<ExternalGrantView> all() {
        return jdbc.query(SELECT + " order by valid_until desc, grant_id", MAPPER);
    }

    /**
     * The grants still ACTIVE whose window has passed at {@code at}, for the expiry job, read in
     * the scope it passes (the Federation's): public and transactional with a ScopeContext
     * argument, which is what the kernel's connection customizer applies.
     */
    @Transactional(readOnly = true)
    public List<UUID> dueForExpiry(ScopeContext scope, Instant at) {
        return jdbc.queryForList(
                """
                select grant_id from security.external_grant
                 where status = 'ACTIVE' and valid_until <= ?
                 order by valid_until, grant_id
                """,
                UUID.class,
                timestamp(at));
    }

    /**
     * The entities of the grantee's grants that are active at {@code at}. Runs in the grantee's
     * own transaction with no tenant scope (see ExternalGrantQueriesImpl), so only the policy on
     * {@code app.user_id} admits rows.
     */
    /** {@code from} plus whole calendar months, computed by the database as the CHECK constraint does. */
    Instant plusMonths(Instant from, int months) {
        return jdbc.queryForObject(
                        "select cast(? as timestamptz) + make_interval(months => ?)",
                        OffsetDateTime.class,
                        timestamp(from),
                        months)
                .toInstant();
    }

    /** Which of these entities do not exist, in the order given (read under party.entity's policies). */
    List<UUID> missingEntities(List<UUID> entityIds) {
        List<UUID> found = jdbc.queryForList(
                "select entity_id from party.entity where entity_id = any (cast(? as uuid[]))",
                UUID.class,
                uuidArray(entityIds));
        return entityIds.stream().filter(id -> !found.contains(id)).toList();
    }

    /** The kind and status of a user, if the caller's scope can see it. */
    Optional<Map<String, Object>> user(UUID userId) {
        return jdbc.queryForList("select user_kind, status from security.app_user where user_id = ?", userId).stream()
                .findFirst();
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** A PostgreSQL array literal, bound as text and cast in the statement. */
    static String uuidArray(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static ExternalGrantView map(ResultSet rs, int rowNum) throws SQLException {
        return new ExternalGrantView(
                rs.getObject("grant_id", UUID.class),
                rs.getObject("grantee_user_id", UUID.class),
                uuids(rs.getArray("scope_entity_ids")),
                rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                rs.getObject("valid_until", OffsetDateTime.class).toInstant(),
                rs.getString("reason"),
                rs.getString("status"));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray())
                .map(value -> value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()))
                .toList();
    }
}
