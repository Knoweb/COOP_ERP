package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The availability question M4 asks of M5 (24A section 2: "Availability(seller locations,
 * skus)"; 24A section 11: "stubs with fixtures until doc 25"). M5's query package does not exist
 * yet, so M4 publishes the question and the demo answers from the register
 * ({@code internal.integration.DemoInventoryAvailability}, item {@code m4.demo.availability_qty}:
 * every item is available in that quantity). M5's availability query replaces the demo answer.
 */
public interface InventoryAvailability {

    /** The quantity of each item the seller can allocate today, in the item's base unit; an absent item is unavailable. */
    Map<UUID, BigDecimal> availability(UUID sellerEntityId, Collection<UUID> skuIds, ScopeContext scope);
}
