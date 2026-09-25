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
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRetired;
import lk.coopfed.knoweb.m2catalogue.api.RetireBarcode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.barcode.retire")
class RetireBarcodeHandler implements Handles<RetireBarcode, UUID> {

    static final String AUDIT_RETIRED = "BARCODE_RETIRED";

    private final BarcodeRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    RetireBarcodeHandler(BarcodeRepository repository, AuditFacade audit, EventPublisher events, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RetireBarcode command, ScopeContext scope) {
        Barcode barcode = repository
                .findById(command.barcodeId())
                .orElseThrow(
                        () -> new ProblemException("request.field.invalid", Map.of("reason", "Barcode not found")));

        if (!Barcode.STATUS_ACTIVE.equals(barcode.getStatus())) {
            throw new ProblemException(
                    "request.field.invalid", Map.of("reason", "Only an ACTIVE barcode can be retired"));
        }

        if (Barcode.SYMBOLOGY_INTERNAL.equals(barcode.getSymbology())
                && !barcode.getOwnerEntityId().equals(scope.activeScope().entityId())) {
            throw new ProblemException(
                    "request.forbidden", Map.of("reason", "You cannot retire another entity's internal barcode"));
        }

        barcode.retire();
        repository.save(barcode);

        Subject subject = Subject.of("barcode", barcode.getId());
        audit.record(
                AUDIT_RETIRED,
                subject,
                null,
                Map.of("status", Barcode.STATUS_RETIRED, "reason", command.reason()),
                scope);

        events.publish(new BarcodeRetired(barcode.getId(), command.reason(), clock.instant()));

        return barcode.getId();
    }
}
