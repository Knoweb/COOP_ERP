package lk.coopfed.knoweb.m1party.internal.relationship;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stub of 21A section 11 ("Price-list validation at ActivateRelationship: M3 query
 * interface stubbed until doc 23; stub returns true for the seed list"): M3 has no TRADE price
 * lists yet, so there is nothing to check a list against. The stub accepts the seeded lists
 * and nothing else ({@code coop-erp.party.stub-price-lists}, the ids of
 * {@code seed/m1party/relationships.dev.sql}), so that a mistyped id is refused today as M3
 * will refuse it tomorrow. The handlers already ask the question and their tests already
 * assert the call and the refusal, so the day M3 implements {@link TradePriceListCheck}, this
 * class and {@link PriceListStubConfiguration} are deleted and nothing else in M1 changes; until
 * then the configuration registers the stub only when no other bean answers, so that M3's bean
 * is not a second answer that stops the application. It says so at WARN each time a list is
 * accepted, so that the gap is visible in the log.
 */
class AcceptingPriceListCheck implements TradePriceListCheck {

    private static final Logger log = LoggerFactory.getLogger(AcceptingPriceListCheck.class);

    /** The refusal of a list the stub does not know: M3's own reason replaces it. */
    static final String UNKNOWN_LIST = "m1.relationship.price_list_unknown";

    private final Set<UUID> seededLists;

    AcceptingPriceListCheck(List<UUID> seededLists) {
        this.seededLists = Set.copyOf(seededLists);
    }

    @Override
    public Optional<String> refusal(UUID priceListId, UUID sellerEntityId, ScopeContext scope) {
        if (priceListId == null || !seededLists.contains(priceListId)) {
            return Optional.of(UNKNOWN_LIST);
        }
        log.warn(
                "price list {} accepted for seller {} as a seeded list only: M3 does not implement TradePriceListCheck yet",
                priceListId,
                sellerEntityId);
        return Optional.empty();
    }
}
