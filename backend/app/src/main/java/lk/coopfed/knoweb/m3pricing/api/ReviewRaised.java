package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** pricing.review_raised.v1 (doc 23 section 4.3) */
public record ReviewRaised(String kind, UUID skuId, UUID ownerEntityId, String details) implements DomainEvent {

    public static final String TYPE = "pricing.review_raised.v1";
}
