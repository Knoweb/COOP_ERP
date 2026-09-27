package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * M5 put a confirmed GRN into the receiver's stock (25A section 6.2, doc 25 flow 6.1): the lots
 * exist and their RECEIPT movements are posted, each announced by {@code stock.moved.v1}. This
 * event says the GRN as a whole was applied, so a projection or M4 need not count movements.
 *
 * @param grnId      the GRN document the movements cite
 * @param movements  how many movements were posted (GOOD and DAMAGED lots of every line)
 */
public record StockReceived(UUID grnId, UUID ownerEntityId, UUID locationId, int movements) implements DomainEvent {

    public static final String TYPE = "stock.received.v1";
}
