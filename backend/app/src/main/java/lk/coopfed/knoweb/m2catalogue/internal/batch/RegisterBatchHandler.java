package lk.coopfed.knoweb.m2catalogue.internal.batch;

import java.util.Map;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistration;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisteredBatch;
import lk.coopfed.knoweb.m2catalogue.internal.batch.BatchStore.BatchRow;
import lk.coopfed.knoweb.m2catalogue.internal.batch.BatchStore.SkuTracking;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegisterBatch (22A section 6; doc 22 section 3.7), the internal command M4's GRN confirmation
 * and M5's repack call through {@link BatchRegistration}. Guards: an OWN scope; the SKU visible
 * and active (not DRAFT, not INACTIVE); the supplier, when given, visible and ACTIVE; the batch
 * number, or the synthetic one when the SKU is not batch-tracked or no number was printed; then
 * the (sku, supplier, batch_no) look-up, which answers the batch already registered; then the MRP
 * when the SKU has a printed MRP and the expiry when it is expiry-tracked. Mutation: one batch
 * row, owned by the registering entity (the trigger of V0003 writes its identity row).
 *
 * <p>Not here: the duplicate-suspect check on near-identical batch numbers (22A section 6.1)
 * needs {@code catalogue.duplicate_suspect}, which M2-08 adds.
 */
@Service
@CommandHandler(permission = CommandHandler.INTERNAL)
public class RegisterBatchHandler implements Handles<RegisterBatch, RegisteredBatch>, BatchRegistration {

    static final String AUDIT_REGISTERED = "BATCH_REGISTERED";

    private final BatchStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterBatchHandler(BatchStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public RegisteredBatch register(RegisterBatch command, ScopeContext scope) {
        return handle(command, scope);
    }

    @Override
    @Transactional
    public RegisteredBatch handle(RegisterBatch command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        BatchGuards.requireOwnScope(scope);

        if (command.skuId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "skuId"));
        }
        SkuTracking sku = store.skuTracking(command.skuId())
                .orElseThrow(() -> new ProblemException("m2.sku.not_found", Map.of("skuId", command.skuId())));
        if (!sku.active()) {
            throw new ProblemException("m2.batch.sku_not_active", Map.of("skuId", command.skuId()));
        }

        if (command.supplierId() != null) {
            String status = store.supplierStatus(command.supplierId())
                    .orElseThrow(() ->
                            new ProblemException("m2.supplier.not_found", Map.of("supplierId", command.supplierId())));
            if (!"ACTIVE".equals(status)) {
                throw new ProblemException("m2.supplier.not_active", Map.of("supplierId", command.supplierId()));
            }
        }

        boolean synthetic = !sku.batchTracked()
                || command.batchNo() == null
                || command.batchNo().isBlank();
        String batchNo = synthetic
                ? BatchGuards.syntheticBatchNo(command.originDocumentNo(), command.originLine())
                : BatchGuards.requireLength(command.batchNo().strip());

        store.lockIdentity(command.skuId(), command.supplierId(), batchNo);
        Optional<BatchRow> existing = store.findByIdentity(command.skuId(), command.supplierId(), batchNo);
        if (existing.isPresent()) {
            BatchRow row = existing.get();
            return new RegisteredBatch(row.batchId(), row.batchNo(), row.synthetic(), false);
        }

        BatchGuards.requireMrp(command.printedMrp(), sku.hasPrintedMrp());
        BatchGuards.requireExpiry(command.manufactureDate(), command.expiryDate(), sku.expiryTracked());

        BatchRow created = new BatchRow(
                Ids.next(),
                command.skuId(),
                command.supplierId(),
                batchNo,
                command.manufactureDate(),
                command.expiryDate(),
                command.printedMrp(),
                command.originDocumentId(),
                synthetic,
                null,
                BatchStore.REGISTERED,
                scope.entityId());

        jdbc.update(
                """
                insert into catalogue.batch
                    (batch_id, sku_id, supplier_id, batch_no, manufacture_date, expiry_date, printed_mrp,
                     origin_document_id, is_synthetic, owner_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                created.batchId(),
                created.skuId(),
                created.supplierId(),
                created.batchNo(),
                created.manufactureDate(),
                created.expiryDate(),
                created.printedMrp(),
                created.originDocumentId(),
                created.synthetic(),
                created.ownerEntityId());

        audit.record(AUDIT_REGISTERED, Subject.of("batch", created.batchId()), null, created.auditState(), scope);

        events.publish(new BatchRegistered(
                created.batchId(),
                created.skuId(),
                created.supplierId(),
                created.ownerEntityId(),
                created.batchNo(),
                created.manufactureDate(),
                created.expiryDate(),
                created.printedMrp(),
                created.synthetic(),
                created.originDocumentId()));

        return new RegisteredBatch(created.batchId(), created.batchNo(), created.synthetic(), true);
    }
}
