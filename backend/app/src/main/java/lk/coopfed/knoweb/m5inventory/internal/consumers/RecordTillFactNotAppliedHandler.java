package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.TillFactNotApplied;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A till fact M5 has no hook for yet is made visible, never dropped (wave 2, M5-04; decided 6
 * October 2026: {@code docs/progress/deviations/2026-10-06-wave2-stock-movements.md} (6); AGENTS.md:
 * "never reject a fact uploaded by a till for a business reason; apply it and flag it"). The till's
 * {@code count.recorded}, {@code writeoff.requested}, {@code repack.executed} and {@code
 * transfer.issued} bundles each get their hook with the till features that send them (CR-30-1);
 * until then each is one {@code TILL_FACT_NOT_APPLIED} (REVIEW) on the exception report.
 *
 * <p>Guards: the device's OWN scope at its shop ({@code m5.sale.location_required}, the shape the
 * sync gateway delivers every till fact in); a fact type. Mutation: none. Audit
 * {@code TILL_FACT_NOT_APPLIED}; event {@code till_fact.not_applied.v1}. Permission: the system
 * applies it with no user; {@code inv.stock.view} is the code of the reads, as for ApplySale.
 */
@Service
@CommandHandler(permission = "inv.stock.view")
class RecordTillFactNotAppliedHandler implements Handles<RecordTillFactNotApplied, Void> {

    static final String AUDIT_NOT_APPLIED = "TILL_FACT_NOT_APPLIED";

    private final AuditFacade audit;
    private final EventPublisher events;

    RecordTillFactNotAppliedHandler(AuditFacade audit, EventPublisher events) {
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RecordTillFactNotApplied command, ScopeContext scope) {
        if (scope == null
                || scope.policyClass() != PolicyClass.OWN
                || scope.entityId() == null
                || scope.locationId() == null) {
            throw new ProblemException("m5.sale.location_required");
        }
        if (command.factType() == null || command.factType().isBlank()) {
            throw new ProblemException("m5.till_fact.type_required");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("factType", command.factType());
        after.put("locationId", scope.locationId());
        if (command.documentId() != null) {
            after.put("documentId", command.documentId());
        }
        audit.record(
                AUDIT_NOT_APPLIED,
                Subject.of("location", scope.locationId()),
                null,
                after,
                scope,
                "A till uploaded a stock fact central does not apply yet; it is kept and needs a person");
        events.publish(new TillFactNotApplied(
                scope.entityId(), scope.locationId(), scope.deviceId(), command.factType(), command.documentId()));
        return null;
    }
}
