package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * price_list.lines_set.v1: the lines of a draft were replaced. Not in doc 23 section 5.3; the
 * build requires every handler to publish one (AGENTS.md). Ids and a count only.
 */
public record PriceListLinesSet(UUID priceListId, UUID ownerEntityId, int lineCount) implements DomainEvent {

    public static final String TYPE = "price_list.lines_set.v1";
}
