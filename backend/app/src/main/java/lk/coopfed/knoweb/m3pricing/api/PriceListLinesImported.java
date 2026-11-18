package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when price list lines are bulk-imported into a draft list (CAT-13).
 */
public record PriceListLinesImported(UUID priceListId, UUID ownerEntityId, int lineCount, boolean replaced)
        implements DomainEvent {

    public static final String TYPE = "price_list.lines_imported.v1";
}
