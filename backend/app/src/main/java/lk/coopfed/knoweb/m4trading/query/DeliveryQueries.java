package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The delivery note queries of doc 24 section 5.2; row-level security decides what a caller sees. */
public interface DeliveryQueries {

    Optional<DeliveryView> getDeliveryNote(UUID deliveryNoteId, ScopeContext scope);

    /** The notes the caller's entity sent (SELLER) or is to receive (BUYER), newest first; a buyer sees issued notes only. */
    List<DeliveryView> listDeliveryNotes(OrderQueries.Role role, ScopeContext scope);
}
