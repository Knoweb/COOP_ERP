package lk.coopfed.knoweb.m1party.api;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The M3 question ActivateRelationship asks (21A section 6; doc 21 section 3.2): may this
 * price list be bound to a relationship of this seller, that is, is it a TRADE list of the
 * seller and published?
 *
 * <p>Why it is declared here and not in M3: M3 depends on M1 (23A section 4 lists
 * {@code m1party::api} and {@code m1party::query}), so M1 cannot call an M3 package without a
 * cycle. M1 publishes the question and M3 answers it by implementing this interface. Until M3
 * does, M1 carries a stub that accepts every list (21A section 11: "M3 query interface stubbed
 * until doc 23"); when M3's implementation lands, the stub
 * ({@code internal.relationship.AcceptingPriceListCheck}) is deleted in the same pull request.
 */
public interface TradePriceListCheck {

    /**
     * Empty when the list may be bound; otherwise M3's reason, a message id of M3's catalogue
     * such as {@code prc.price_list.not_published}.
     *
     * @param priceListId    the list the relationship names
     * @param sellerEntityId the relationship's seller, who must own the list
     * @param scope          the caller's scope (the seller's OWN scope)
     */
    Optional<String> refusal(UUID priceListId, UUID sellerEntityId, ScopeContext scope);
}
