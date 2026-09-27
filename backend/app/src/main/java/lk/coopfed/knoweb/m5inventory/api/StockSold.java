package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * M5 deducted a till receipt's sales from the shop's lots (25A section 6.2, receipt.issued.v1):
 * its SALE movements are posted, each announced by {@code stock.moved.v1}.
 *
 * @param documentId  the receipt the movements cite
 * @param movements   how many SALE movements were posted
 * @param unresolved  lines that could not be posted (no batch and no lot of the item), flagged
 */
public record StockSold(UUID documentId, UUID ownerEntityId, UUID locationId, int movements, int unresolved)
        implements DomainEvent {

    public static final String TYPE = "stock.sold.v1";
}
