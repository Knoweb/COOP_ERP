package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** Published when an individual two-party authorized opening receivable is loaded (CAT-12). */
public record OpeningReceivableLoaded(
        UUID openingReceivableId,
        UUID documentId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal amount,
        UUID sellerSignedBy,
        UUID buyerSignedBy)
        implements DomainEvent {

    public static final String TYPE = "trading.opening_receivable_loaded.v1";
}
