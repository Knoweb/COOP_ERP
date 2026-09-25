package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

public record ConversionDefined(
        UUID conversionId,
        UUID skuId,
        String fromUom,
        String toUom,
        BigDecimal factor,
        LocalDate validFrom,
        Instant occurredAt)
        implements DomainEvent {
    public static final String TYPE = "catalogue.conversion.defined.v1";
}
