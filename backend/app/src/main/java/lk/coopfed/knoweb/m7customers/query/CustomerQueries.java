package lk.coopfed.knoweb.m7customers.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The society's customers (27A section 7, LookupCustomer and the customer card). Row-level
 * security decides what the caller sees: the customers its society registered and those it holds
 * an account for.
 */
public interface CustomerQueries {

    /**
     * The customers matching a name (trigram, ignoring case) or a phone number (E.164 after
     * normalising, current primary), at most {@code limit}, by name. Both null: the first
     * {@code limit} customers by name.
     */
    List<CustomerSummary> search(String name, String phone, int limit, ScopeContext scope);

    /** The customer card; empty when the customer does not exist or the scope may not see it. */
    Optional<CustomerCard> card(UUID customerId, ScopeContext scope);
}
