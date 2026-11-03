package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when opening stock is imported in bulk for a location (CAT-11).
 *
 * <p>Topic: {@code inventory.opening_stock_bulk_imported.v1}.
 *
 * @param importId unique identifier of the bulk import
 * @param ownerEntityId owning society or federation entity
 * @param locationId location where opening stock was imported
 * @param openingBalanceId ID of the created opening balance (in DRAFT status awaiting signatures)
 * @param totalRows total rows parsed
 * @param importedLines number of opening balance lines created
 */
public record OpeningStockBulkImported(
        UUID importId, UUID ownerEntityId, UUID locationId, UUID openingBalanceId, int totalRows, int importedLines)
        implements DomainEvent {

    public static final String TYPE = "inventory.opening_stock_bulk_imported.v1";
}
