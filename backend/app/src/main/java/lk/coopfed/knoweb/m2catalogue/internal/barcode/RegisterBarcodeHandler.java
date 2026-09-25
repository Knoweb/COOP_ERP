package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRegistered;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBarcode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@CommandHandler(permission = "cat.barcode.register")
class RegisterBarcodeHandler implements Handles<RegisterBarcode, UUID> {

    static final String AUDIT_REGISTERED = "BARCODE_REGISTERED";

    private final BarcodeRepository repository;
    private final BatchSkuQueries batchQueries;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    RegisterBarcodeHandler(BarcodeRepository repository, BatchSkuQueries batchQueries, AuditFacade audit, EventPublisher events, Clock clock) {
        this.repository = repository;
        this.batchQueries = batchQueries;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RegisterBarcode command, ScopeContext scope) {
        UUID ownerId = Barcode.SYMBOLOGY_INTERNAL.equals(command.symbology()) ? scope.activeScope().entityId() : null;

        if (Barcode.SYMBOLOGY_FACTORY.equals(command.symbology())) {
            repository.findByBarcodeAndSymbologyAndStatus(command.barcode(), command.symbology(), Barcode.STATUS_ACTIVE)
                    .ifPresent(existing -> {
                        throw new ProblemException("request.field.invalid", Map.of("reason", "FACTORY barcode already exists"));
                    });
        } else if (Barcode.SYMBOLOGY_INTERNAL.equals(command.symbology())) {
            repository.findByBarcodeAndSymbologyAndOwnerEntityIdAndStatus(command.barcode(), command.symbology(), ownerId, Barcode.STATUS_ACTIVE)
                    .ifPresent(existing -> {
                        throw new ProblemException("request.field.invalid", Map.of("reason", "INTERNAL barcode already exists for this owner"));
                    });
        } else {
            throw new ProblemException("request.field.invalid", Map.of("reason", "Unsupported symbology"));
        }

        if (command.batchId() != null) {
            if (!batchQueries.isBatchOfSku(command.batchId(), command.skuId())) {
                throw new ProblemException("request.field.invalid", Map.of("reason", "Batch does not belong to the SKU"));
            }
        }

        UUID id = Ids.next();
        Barcode barcode = new Barcode(
                id,
                command.barcode(),
                command.symbology(),
                command.skuId(),
                command.uom(),
                command.batchId(),
                ownerId
        );
        repository.save(barcode);

        Subject subject = Subject.of("barcode", id);
        Map<String, Object> after = Map.of(
                "barcode", command.barcode(),
                "symbology", command.symbology(),
                "skuId", command.skuId(),
                "uom", command.uom(),
                "batchId", command.batchId() != null ? command.batchId() : "null",
                "ownerId", ownerId != null ? ownerId : "null"
        );

        audit.record(AUDIT_REGISTERED, subject, null, after, scope);

        events.publish(new BarcodeRegistered(
                id,
                command.barcode(),
                command.symbology(),
                command.skuId(),
                command.uom(),
                command.batchId(),
                ownerId,
                clock.instant()
        ));

        return id;
    }
}
