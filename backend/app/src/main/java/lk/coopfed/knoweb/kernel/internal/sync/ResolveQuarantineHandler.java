package lk.coopfed.knoweb.kernel.internal.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncQuarantineResolved;
import lk.coopfed.knoweb.kernel.internal.security.PermissionGate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ResolveQuarantine (wave 2, decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md
 * (5); CR-32-1 item 2). A quarantined till event is a fact central could not accept (doc 32
 * sections 3.3 step 6 and 7); S4 says it is never lost, so it is not purged by time but resolved
 * by a person: REPAIRED when the till resent a correct copy (doc 32 section 8), DISCARDED when
 * nothing will, with a reason, once. The row stays as the record that a fact was refused and why;
 * {@link QuarantineRawRetentionJob} removes its raw event a retention after the resolution.
 *
 * <p>The rule of a command handler, as the kernel's other operations follow it
 * ({@link SequenceReset}): the guards, the mutation, {@code audit.record}, {@code events.publish},
 * in one transaction.
 *
 * <pre>
 *   a user of the entity (OWN) with sync.quarantine.resolve   403 permission.denied
 *   a row the caller can see (its entity, its shop)           404 sync.quarantine.not_found
 *   not resolved before                                       422 sync.quarantine.already_resolved
 * </pre>
 *
 * Audit {@code SYNC_QUARANTINE_RESOLVED} (REVIEW), event {@code sync.quarantine.resolved.v1}.
 */
@Component
public class ResolveQuarantineHandler {

    static final String PERMISSION = "sync.quarantine.resolve";
    static final String AUDIT_RESOLVED = "SYNC_QUARANTINE_RESOLVED";
    static final Set<String> RESOLUTIONS = Set.of("REPAIRED", "DISCARDED");

    /** The row as resolved. */
    record Resolved(
            UUID quarantineId, UUID deviceId, long deviceSeq, String reason, String resolution, Instant resolvedAt) {}

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final PermissionGate gate;
    private final Clock clock;

    ResolveQuarantineHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, PermissionGate gate, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.gate = gate;
        this.clock = clock;
    }

    /**
     * @param resolution REPAIRED or DISCARDED (the slice's enum)
     * @param reasonCode why, e.g. RESENT_CORRECTED (the slice's pattern)
     * @param reasonText optional
     * @throws ProblemException {@code permission.denied}, {@code mfa.required},
     *                          {@code sync.quarantine.not_found}, {@code sync.quarantine.already_resolved}
     */
    @Transactional
    public Resolved resolve(
            ScopeContext ctx, UUID quarantineId, String resolution, String reasonCode, String reasonText) {
        // 1. A user of the entity, with the permission (and its second factor, when it asks one).
        if (ctx.userId() == null || ctx.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("permission.denied", Map.of("permission", PERMISSION));
        }
        gate.require(ctx, PERMISSION);
        if (!RESOLUTIONS.contains(resolution)) {
            // The slice's enum refuses anything else over HTTP; this is the guard for any other caller.
            throw new ProblemException("request.invalid");
        }
        // 2. A row the caller can see: row-level security keeps it to the caller's entity and shop.
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select device_id, device_seq, reason, resolved_at from kernel.sync_quarantine
                 where quarantine_id = ?
                   for update
                """,
                quarantineId);
        if (rows.isEmpty()) {
            throw new ProblemException("sync.quarantine.not_found");
        }
        Map<String, Object> row = rows.getFirst();
        // 3. Once: a resolution is never changed (the trigger of kernel V0084 holds the same rule).
        if (row.get("resolved_at") != null) {
            throw new ProblemException("sync.quarantine.already_resolved");
        }

        Instant now = clock.instant();
        String reason =
                reasonText == null || reasonText.isBlank() ? reasonCode : reasonCode + ": " + reasonText.strip();
        jdbc.update(
                """
                update kernel.sync_quarantine
                   set resolved_at = ?, resolved_by_user_id = ?, resolution = ?, resolution_reason = ?
                 where quarantine_id = ?
                """,
                Timestamp.from(now),
                ctx.userId(),
                resolution,
                reason,
                quarantineId);

        UUID deviceId = (UUID) row.get("device_id");
        long deviceSeq = ((Number) row.get("device_seq")).longValue();
        String quarantinedFor = (String) row.get("reason");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("resolution", resolution);
        after.put("reasonCode", reasonCode);
        after.put("deviceId", deviceId);
        after.put("deviceSeq", deviceSeq);
        after.put("quarantinedFor", quarantinedFor);
        audit.record(AUDIT_RESOLVED, Subject.of("sync_quarantine", quarantineId), null, after, ctx, reason);
        events.publish(
                new SyncQuarantineResolved(quarantineId, deviceId, deviceSeq, quarantinedFor, resolution, reasonCode));
        return new Resolved(quarantineId, deviceId, deviceSeq, quarantinedFor, resolution, now);
    }
}
