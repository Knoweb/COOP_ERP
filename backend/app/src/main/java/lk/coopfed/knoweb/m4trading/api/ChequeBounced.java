package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** cheque.bounced.v1 (24A section 6.3): the cheque of a receipt bounced; the receipt is reversed. */
public record ChequeBounced(
        UUID receiptId, UUID reversalId, UUID sellerEntityId, UUID buyerEntityId, BigDecimal amount, String reason)
        implements DomainEvent {

    public static final String TYPE = "cheque.bounced.v1";
}
