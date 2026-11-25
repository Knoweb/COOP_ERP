package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** Published when a batch of inter-entity receivable opening balances is imported (CAT-12). */
public record ReceivablesBulkImported(
        UUID importId,
        UUID sellerEntityId,
        int totalRows,
        int importedCount,
        BigDecimal totalAmount,
        List<UUID> documentIds)
        implements DomainEvent {

    public static final String TYPE = "trading.receivables_bulk_imported.v1";
}
