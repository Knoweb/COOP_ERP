package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeLinkedToBatch;
import lk.coopfed.knoweb.m2catalogue.api.LinkBarcodeToBatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.barcode.link")
class LinkBarcodeToBatchHandler implements Handles<LinkBarcodeToBatch, UUID> {

    static final String AUDIT_LINKED = "BARCODE_LINKED";

    private final BarcodeRepository repository;
    private final BatchSkuQueries batchQueries;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    LinkBarcodeToBatchHandler(
            BarcodeRepository repository,
            BatchSkuQueries batchQueries,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.repository = repository;
        this.batchQueries = batchQueries;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(LinkBarcodeToBatch command, ScopeContext scope) {
        Barcode barcode = repository
                .findById(command.barcodeId())
                .orElseThrow(
                        () -> new ProblemException("request.field.invalid", Map.of("reason", "Barcode not found")));

        if (!Barcode.STATUS_ACTIVE.equals(barcode.getStatus())) {
            throw new ProblemException("request.field.invalid", Map.of("reason", "Barcode is not active"));
        }

        if (Barcode.SYMBOLOGY_INTERNAL.equals(barcode.getSymbology())
                && !barcode.getOwnerEntityId().equals(scope.activeScope().entityId())) {
            throw new ProblemException(
                    "request.forbidden", Map.of("reason", "You cannot modify another entity's internal barcode"));
        }

        if (!batchQueries.isBatchOfSku(command.batchId(), barcode.getSkuId())) {
            throw new ProblemException("request.field.invalid", Map.of("reason", "Batch does not belong to the SKU"));
        }

        barcode.linkBatch(command.batchId());
        repository.save(barcode);

        Subject subject = Subject.of("barcode", barcode.getId());
        audit.record(AUDIT_LINKED, subject, null, Map.of("batchId", command.batchId()), scope);

        events.publish(new BarcodeLinkedToBatch(barcode.getId(), command.batchId(), clock.instant()));

        return barcode.getId();
    }
}
