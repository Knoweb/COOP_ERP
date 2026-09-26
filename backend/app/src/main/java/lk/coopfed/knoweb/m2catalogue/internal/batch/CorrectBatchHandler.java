package lk.coopfed.knoweb.m2catalogue.internal.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BatchCorrected;
import lk.coopfed.knoweb.m2catalogue.api.CorrectBatch;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.internal.batch.BatchStore.BatchRow;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CorrectBatchMrp / CorrectBatchExpiry (22A section 6; doc 22 section 3.7, B-I8). Guards: an OWN
 * scope; the batch exists and is REGISTERED (a SUPERSEDED batch was corrected already, and its
 * replacement is the one to correct); the caller is the Federation or holds a lot of the batch
 * (M5's answer through {@link InventoryLotQuery}; until M5 exists nobody holds one, so only the
 * Federation corrects); MFA (the permission cat.batch.correct asks for it); a reason; a new
 * value that differs. Mutation: a replacement batch citing the old one, owned by the caller; the
 * trigger batch_correction (V0004) marks the old batch SUPERSEDED and re-points its identity, as
 * the database's owner, because the old row belongs to the registering entity.
 */
@Service
@CommandHandler(permission = "cat.batch.correct", requiresMfa = true)
public class CorrectBatchHandler implements Handles<CorrectBatch, UUID> {

    static final String AUDIT_CORRECTED = "BATCH_CORRECTED";

    private final BatchStore store;
    private final FederationCaller federation;
    private final InventoryLotQuery lots;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    CorrectBatchHandler(
            BatchStore store,
            FederationCaller federation,
            InventoryLotQuery lots,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.federation = federation;
        this.lots = lots;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CorrectBatch command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        BatchGuards.requireOwnScope(scope);

        if (command.batchId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "batchId"));
        }
        BatchRow before = store.findById(command.batchId())
                .orElseThrow(() -> new ProblemException("m2.batch.not_found", Map.of("batchId", command.batchId())));
        if (!before.registered()) {
            throw new ProblemException("m2.batch.superseded", Map.of("batchId", command.batchId()));
        }

        if (!federation.isFederation(scope) && !lots.holdsLotOf(before.batchId(), scope.entityId())) {
            throw new ProblemException("m2.batch.not_holder", Map.of("batchId", command.batchId()));
        }

        String reason = BatchGuards.reason(command.reasonCode(), command.reasonText());

        BigDecimal printedMrp = command.printedMrp() == null ? before.printedMrp() : command.printedMrp();
        LocalDate expiryDate = command.expiryDate() == null ? before.expiryDate() : command.expiryDate();
        if (command.printedMrp() != null && command.printedMrp().signum() <= 0) {
            throw new ProblemException("m2.batch.mrp_invalid");
        }
        if (command.expiryDate() != null
                && before.manufactureDate() != null
                && command.expiryDate().isBefore(before.manufactureDate())) {
            throw new ProblemException("m2.batch.dates_invalid");
        }
        boolean mrpChanged =
                printedMrp != null && (before.printedMrp() == null || printedMrp.compareTo(before.printedMrp()) != 0);
        if (!mrpChanged && Objects.equals(expiryDate, before.expiryDate())) {
            throw new ProblemException("m2.batch.correction_unchanged", Map.of("batchId", command.batchId()));
        }

        BatchRow after = new BatchRow(
                Ids.next(),
                before.skuId(),
                before.supplierId(),
                before.batchNo(),
                before.manufactureDate(),
                expiryDate,
                printedMrp,
                before.originDocumentId(),
                before.synthetic(),
                before.batchId(),
                BatchStore.REGISTERED,
                scope.entityId());

        // created_at from the clock, not now(): the replacement keeps (sku, supplier, batch_no),
        // and V0001's unique index on those and created_at would refuse it beside a batch
        // registered earlier in the same transaction (now() is the transaction's start).
        jdbc.update(
                """
                insert into catalogue.batch
                    (batch_id, sku_id, supplier_id, batch_no, manufacture_date, expiry_date, printed_mrp,
                     origin_document_id, is_synthetic, corrects_batch_id, owner_entity_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                """,
                after.batchId(),
                after.skuId(),
                after.supplierId(),
                after.batchNo(),
                after.manufactureDate(),
                after.expiryDate(),
                after.printedMrp(),
                after.originDocumentId(),
                after.synthetic(),
                after.correctsBatchId(),
                after.ownerEntityId());

        audit.record(
                AUDIT_CORRECTED,
                Subject.of("batch", after.batchId()),
                before.auditState(),
                after.auditState(),
                scope,
                reason,
                null);

        events.publish(new BatchCorrected(
                after.batchId(),
                before.batchId(),
                after.skuId(),
                after.supplierId(),
                after.ownerEntityId(),
                after.batchNo(),
                after.expiryDate(),
                after.printedMrp(),
                command.reasonCode() == null || command.reasonCode().isBlank()
                        ? null
                        : command.reasonCode().strip()));

        return after.batchId();
    }
}
