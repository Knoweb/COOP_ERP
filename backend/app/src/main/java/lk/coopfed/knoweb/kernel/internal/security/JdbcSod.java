package lk.coopfed.knoweb.kernel.internal.security;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Sod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Separation of duties over M1's {@code sod_pair} (19A section 3): a pair in INSTANCE mode
 * forbids one person doing both on the same document. The pairs are stored with the codes in
 * order ({@code permission_a < permission_b}), federation-wide (no owner) or the entity's.
 * A ROLE mode pair is stricter (doc 19 section 3.2: no role holds both halves) and forbids the
 * same person on one document as well; M1 enforces the role half when a role is authored.
 */
@Component
class JdbcSod implements Sod {

    private final JdbcTemplate jdbc;

    JdbcSod(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void assertDistinct(ScopeContext ctx, String requestPermission, String approvePermission, UUID requesterId) {
        if (ctx == null || ctx.userId() == null || requesterId == null) {
            return;
        }
        if (!ctx.userId().equals(requesterId)) {
            return;
        }

        String a = requestPermission.compareTo(approvePermission) <= 0 ? requestPermission : approvePermission;
        String b = requestPermission.compareTo(approvePermission) <= 0 ? approvePermission : requestPermission;

        Integer pairs = jdbc.queryForObject(
                """
                select count(*) from security.sod_pair
                 where permission_a = ? and permission_b = ? and mode in ('INSTANCE', 'ROLE')
                   and (owner_entity_id is null or owner_entity_id = ?)
                """,
                Integer.class,
                a,
                b,
                ctx.entityId());

        if (pairs != null && pairs > 0) {
            throw new ProblemException(
                    "sod.same_person", Map.of("request", requestPermission, "approve", approvePermission));
        }
    }
}
