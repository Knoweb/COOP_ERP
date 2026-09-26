package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** conversion.defined.v1 (doc 22 section 5.3: sku, uom, factor, effective_from). */
public record ConversionDefined(
        UUID skuId,
        UUID ownerEntityId,
        String uomCode,
        BigDecimal factorToBase,
        LocalDate effectiveFrom,
        LocalDate effectiveTo)
        implements DomainEvent {

    public static final String TYPE = "conversion.defined.v1";
}
