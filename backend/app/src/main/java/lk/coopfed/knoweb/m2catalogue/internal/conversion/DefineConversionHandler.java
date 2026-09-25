package lk.coopfed.knoweb.m2catalogue.internal.conversion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ConversionDefined;
import lk.coopfed.knoweb.m2catalogue.api.DefineConversion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.conversion.define")
class DefineConversionHandler implements Handles<DefineConversion, UUID> {

    static final String AUDIT_DEFINED = "CONVERSION_DEFINED";

    private final ConversionRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    DefineConversionHandler(ConversionRepository repository, AuditFacade audit, EventPublisher events, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(DefineConversion command, ScopeContext scope) {
        LocalDate endOfTime = LocalDate.of(9999, 12, 31);

        repository
                .findBySkuIdAndFromUomAndToUomAndValidTo(command.skuId(), command.fromUom(), command.toUom(), endOfTime)
                .ifPresent(existing -> {
                    existing.close(command.validFrom().minusDays(1));
                    repository.save(existing);
                });

        UUID id = Ids.next();
        Conversion conversion = new Conversion(
                id, command.skuId(), command.fromUom(), command.toUom(), command.factor(), command.validFrom());
        repository.save(conversion);

        Subject subject = Subject.of("conversion", id);
        Map<String, Object> after = Map.of(
                "skuId", command.skuId(),
                "fromUom", command.fromUom(),
                "toUom", command.toUom(),
                "factor", command.factor(),
                "validFrom", command.validFrom());

        audit.record(AUDIT_DEFINED, subject, null, after, scope);

        events.publish(new ConversionDefined(
                id,
                command.skuId(),
                command.fromUom(),
                command.toUom(),
                command.factor(),
                command.validFrom(),
                clock.instant()));

        return id;
    }
}
