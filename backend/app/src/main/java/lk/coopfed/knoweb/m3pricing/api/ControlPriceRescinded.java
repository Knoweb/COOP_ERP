package lk.coopfed.knoweb.m3pricing.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** control_price.rescinded.v1 (doc 23 section 5.3): the ceiling holds until lastDay and no longer. */
public record ControlPriceRescinded(UUID controlPriceId, UUID skuId, LocalDate lastDay, String gazetteReference)
        implements DomainEvent {

    public static final String TYPE = "control_price.rescinded.v1";
}
