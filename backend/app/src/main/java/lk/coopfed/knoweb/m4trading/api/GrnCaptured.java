package lk.coopfed.knoweb.m4trading.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** grn.captured.v1 (doc 24 section 5.3): a GRN draft exists, against a drop or a local supply. */
public record GrnCaptured(
        UUID grnId,
        UUID receiverEntityId,
        UUID receiverLocationId,
        UUID sellerEntityId,
        UUID dropId,
        UUID deliveryDocumentId,
        UUID supplierId,
        LocalDate receivedOn,
        int lineCount)
        implements DomainEvent {

    public static final String TYPE = "grn.captured.v1";
}
