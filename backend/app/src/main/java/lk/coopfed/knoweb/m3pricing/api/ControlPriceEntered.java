package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * control_price.entered.v1 (doc 23 section 5.3: "scope, ceiling, effective range, gazette ref").
 * Consumers: the snapshot (urgent, every location; M3-09), the gazette review (M3-08), M8.
 *
 * @param closedControlPriceId the ceiling of the same SKU closed at effectiveFrom - 1, if any
 */
public record ControlPriceEntered(
        UUID controlPriceId,
        UUID skuId,
        BigDecimal ceilingPrice,
        String ceilingUomCode,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String gazetteReference,
        UUID closedControlPriceId)
        implements DomainEvent {

    public static final String TYPE = "control_price.entered.v1";
}
