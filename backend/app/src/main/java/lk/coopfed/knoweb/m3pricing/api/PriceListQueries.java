package lk.coopfed.knoweb.m3pricing.api;

import lk.coopfed.knoweb.kernel.api.ScopeContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to price lists. Every method takes the caller's {@link ScopeContext}: the kernel
 * puts it on the database transaction and row-level security does the filtering. No query
 * ever adds "where owner_entity_id = ..." itself.
 */
public interface PriceListQueries {

    /** Empty when the price list does not exist or the caller's scope may not see it. */
    Optional<PriceListView> find(UUID id, ScopeContext scope);

    /** The price lists visible in the caller's scope, newest first. */
    List<PriceListView> list(ScopeContext scope);
}
