package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** cheque.cleared.v1 (24A section 6.3): the cheque of a receipt cleared. */
public record ChequeCleared(UUID receiptId, UUID sellerEntityId, UUID buyerEntityId, BigDecimal amount)
        implements DomainEvent {

    public static final String TYPE = "cheque.cleared.v1";
}
