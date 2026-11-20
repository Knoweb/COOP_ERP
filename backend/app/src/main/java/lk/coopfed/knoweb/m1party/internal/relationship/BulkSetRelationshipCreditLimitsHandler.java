package lk.coopfed.knoweb.m1party.internal.relationship;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.BulkRelationshipCreditLimitReport;
import lk.coopfed.knoweb.m1party.api.BulkRelationshipCreditLimitReport.Problem;
import lk.coopfed.knoweb.m1party.api.BulkSetRelationshipCreditLimits;
import lk.coopfed.knoweb.m1party.api.BulkSetRelationshipCreditLimits.RelationshipLimitRow;
import lk.coopfed.knoweb.m1party.api.CreditLimitChanged;
import lk.coopfed.knoweb.m1party.api.RelationshipCreditLimitsBulkImported;
import lk.coopfed.knoweb.m1party.internal.bulk.BulkRelationshipCreditLimitsCsvParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk command handler to set trading relationship credit limits from a sheet or rows (CAT-14).
 *
 * <p>Enforces step-up authentication via {@link CreditLimitGate} requiring fresh MFA before
 * applying any limits. Operates with all-or-none validation within a single transaction boundary.
 */
@Service
@CommandHandler(permission = "bil.creditlimit.change")
public class BulkSetRelationshipCreditLimitsHandler
        implements Handles<BulkSetRelationshipCreditLimits, BulkRelationshipCreditLimitReport> {

    public static final String AUDIT_CREDIT_LIMITS_BULK_IMPORTED = "CREDIT_LIMITS_BULK_IMPORTED";

    private record ValidatedLimit(
            UUID buyerId, String identifier, Relationship relationship, BigDecimal creditLimit, String reason) {}

    private final CreditLimitGate creditLimitGate;
    private final RelationshipRepository repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId businessZone;
    private final AuditFacade audit;
    private final EventPublisher events;

    public BulkSetRelationshipCreditLimitsHandler(
            CreditLimitGate creditLimitGate,
            RelationshipRepository repository,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone,
            AuditFacade audit,
            EventPublisher events) {
        this.creditLimitGate = creditLimitGate;
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public BulkRelationshipCreditLimitReport handle(BulkSetRelationshipCreditLimits command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        // 1. Mandatory Step-Up Authentication and Permission Gate
        creditLimitGate.require(scope);

        // 2. Parse sheet if provided
        List<RelationshipLimitRow> rows = command.limits();
        if ((rows == null || rows.isEmpty())
                && command.sheet() != null
                && !command.sheet().isBlank()) {
            rows = BulkRelationshipCreditLimitsCsvParser.parse(command.sheet());
        }

        if (rows == null || rows.isEmpty()) {
            return new BulkRelationshipCreditLimitReport(BulkRelationshipCreditLimitReport.UPDATED, 0, List.of());
        }

        UUID sellerId = scope.entityId();
        List<Problem> problems = new ArrayList<>();
        List<ValidatedLimit> validated = new ArrayList<>();

        // 3. All-or-none validation phase
        for (int i = 0; i < rows.size(); i++) {
            RelationshipLimitRow row = rows.get(i);
            String identifier = row.buyerCode() != null
                    ? row.buyerCode()
                    : (row.buyerEntityId() != null ? row.buyerEntityId().toString() : "row-" + (i + 1));

            UUID buyerId = row.buyerEntityId();
            if (buyerId == null && row.buyerCode() != null && !row.buyerCode().isBlank()) {
                List<UUID> matching = jdbc.query(
                        "select entity_id from party.entity where entity_code = ?",
                        (rs, n) -> rs.getObject("entity_id", UUID.class),
                        row.buyerCode().trim());
                if (!matching.isEmpty()) {
                    buyerId = matching.get(0);
                }
            }

            if (buyerId == null) {
                problems.add(new Problem(identifier, "buyer", "m1.relationship.buyer_not_found"));
                continue;
            }

            if (buyerId.equals(sellerId)) {
                problems.add(new Problem(identifier, "buyer", "m1.relationship.self"));
                continue;
            }

            if (row.creditLimit() == null
                    || row.creditLimit().compareTo(BigDecimal.ZERO) < 0
                    || row.creditLimit().scale() > 2) {
                problems.add(new Problem(identifier, "creditLimit", "m1.relationship.limit_invalid"));
                continue;
            }

            List<UUID> rels = jdbc.query(
                    """
                    select relationship_id from party.entity_relationship
                     where seller_entity_id = ? and buyer_entity_id = ? and status in ('ACTIVE', 'DRAFT')
                     order by (status = 'ACTIVE') desc, created_at desc
                     limit 1
                    """,
                    (rs, n) -> rs.getObject("relationship_id", UUID.class),
                    sellerId,
                    buyerId);

            if (rels.isEmpty()) {
                problems.add(new Problem(identifier, "relationship", "m1.relationship.not_found"));
                continue;
            }

            Relationship rel = repository.findById(rels.get(0)).orElse(null);
            if (rel == null) {
                problems.add(new Problem(identifier, "relationship", "m1.relationship.not_found"));
                continue;
            }

            String reason = row.reason() != null && !row.reason().isBlank()
                    ? row.reason().trim()
                    : (command.defaultReason() != null ? command.defaultReason().trim() : "Bulk credit limit import");

            validated.add(new ValidatedLimit(buyerId, identifier, rel, row.creditLimit(), reason));
        }

        if (!problems.isEmpty()) {
            return new BulkRelationshipCreditLimitReport(BulkRelationshipCreditLimitReport.REJECTED, 0, problems);
        }

        // 4. Mutation phase (all items valid)
        LocalDate today = LocalDate.ofInstant(clock.instant(), businessZone);
        BigDecimal totalLimit = BigDecimal.ZERO;
        int count = 0;

        for (ValidatedLimit item : validated) {
            Relationship current = item.relationship();
            BigDecimal newLimit = item.creditLimit();
            BigDecimal oldLimit = current.creditLimit();

            if ("DRAFT".equals(current.status())) {
                jdbc.update(
                        "update party.entity_relationship set credit_limit = ? where relationship_id = ?",
                        newLimit,
                        current.getId());
                audit.record(
                        AmendRelationshipTermsHandler.AUDIT_CREDIT_LIMIT_CHANGED,
                        Subject.of("relationship", current.getId()),
                        ActivateRelationshipHandler.limitState(current.getId(), oldLimit),
                        ActivateRelationshipHandler.limitState(current.getId(), newLimit),
                        scope,
                        item.reason());
                events.publish(new CreditLimitChanged(
                        current.getId(),
                        null,
                        oldLimit,
                        newLimit,
                        current.effectiveFrom(),
                        current.sellerEntityId(),
                        current.buyerEntityId()));
            } else {
                // ACTIVE: effective-dated amendment row
                Relationship.Terms prev = current.terms();
                Relationship.Terms after = new Relationship.Terms(
                        prev.priceListId(),
                        newLimit,
                        prev.paymentTermsDays(),
                        prev.discrepancyWindowDays(),
                        prev.orderLockHoursBeforeEta(),
                        prev.allocationRule());
                Relationship next = current.amendedFrom(Ids.next(), after, today);
                if (current.effectiveFrom() != null && current.effectiveFrom().isAfter(today)) {
                    current.replace();
                } else {
                    current.closeBefore(today);
                }
                repository.saveAndFlush(current);
                repository.saveAndFlush(next);

                jdbc.update(
                        RelationshipRules.MARK_LIMIT_ANNOUNCED,
                        next.sellerEntityId(),
                        next.buyerEntityId(),
                        next.getId());

                audit.record(
                        AmendRelationshipTermsHandler.AUDIT_CREDIT_LIMIT_CHANGED,
                        Subject.of("relationship", next.getId()),
                        ActivateRelationshipHandler.limitState(current.getId(), oldLimit),
                        ActivateRelationshipHandler.limitState(next.getId(), newLimit),
                        scope,
                        item.reason());

                events.publish(new CreditLimitChanged(
                        next.getId(),
                        current.getId(),
                        oldLimit,
                        newLimit,
                        next.effectiveFrom(),
                        next.sellerEntityId(),
                        next.buyerEntityId()));
            }

            totalLimit = totalLimit.add(newLimit);
            count++;
        }

        // Zero-PII bulk audit and domain event
        audit.record(
                AUDIT_CREDIT_LIMITS_BULK_IMPORTED,
                Subject.of("entity", sellerId),
                null,
                Map.of("count", count, "totalCreditLimit", totalLimit.toPlainString()),
                scope);

        events.publish(new RelationshipCreditLimitsBulkImported(sellerId, count, totalLimit));

        return new BulkRelationshipCreditLimitReport(BulkRelationshipCreditLimitReport.UPDATED, count, List.of());
    }
}
