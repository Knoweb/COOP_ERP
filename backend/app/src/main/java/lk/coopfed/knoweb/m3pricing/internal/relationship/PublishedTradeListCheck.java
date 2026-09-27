package lk.coopfed.knoweb.m3pricing.internal.relationship;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListStore;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * M3's answer to the question M1 asks when a relationship is activated or its terms amended
 * (21A section 6; doc 21 section 3.2): may this price list be bound to a relationship of this
 * seller? Yes when it is a TRADE list of the seller with a published version. A list published
 * later can be bound later, at activation (doc 23 flow 6.2). This bean replaces M1's stub
 * AcceptingPriceListCheck, which is deleted in the same change (PR #104).
 *
 * <p>Asked in the seller's OWN scope, inside M1's transaction: row-level security shows the
 * seller its own lists, so a list of another entity is "not found", as it should be.
 */
@Component
@Transactional(readOnly = true)
class PublishedTradeListCheck implements TradePriceListCheck {

    private final PriceListStore store;

    PublishedTradeListCheck(PriceListStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> refusal(UUID priceListId, UUID sellerEntityId, ScopeContext scope) {
        if (priceListId == null) {
            return Optional.of("m3.price_list.not_found");
        }
        Optional<PriceListView> list = store.find(priceListId);
        if (list.isEmpty()) {
            return Optional.of("m3.price_list.not_found");
        }
        if (!PriceListStore.isTrade(list.get().kind())) {
            return Optional.of("m3.price_list.not_trade");
        }
        if (!list.get().ownerEntityId().equals(sellerEntityId)) {
            return Optional.of("m3.price_list.not_the_sellers");
        }
        if (store.published(list.get().rootPriceListId()).isEmpty()) {
            return Optional.of("m3.price_list.not_published");
        }
        return Optional.empty();
    }
}
