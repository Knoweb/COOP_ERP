package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The goods of an approved claim left the buyer's stock back to the seller (25A section 6.2,
 * ClaimReturn): {@code movements} RETURN_TO_SELLER movements citing the claim, {@code qty} in all.
 */
public record StockReturnedToSeller(UUID claimId, UUID ownerEntityId, UUID locationId, int movements, BigDecimal qty)
        implements DomainEvent {

    public static final String TYPE = "stock.returned_to_seller.v1";
}
