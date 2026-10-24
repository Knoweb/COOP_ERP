package lk.coopfed.knoweb.m3pricing.internal.list;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.RetailPriceListAssortmentQuery;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.stereotype.Component;

/**
 * Answers M2's {@link RetailPriceListAssortmentQuery} from published retail price lists (doc 22 section 3.8; doc 23).
 */
@Component
class RetailPriceListQuestionsForCatalogue implements RetailPriceListAssortmentQuery {

    private final PriceListStore store;

    RetailPriceListQuestionsForCatalogue(PriceListStore store) {
        this.store = store;
    }

    @Override
    public List<UUID> skusOnRetailList(UUID ownerEntityId, ScopeContext scope) {
        List<PriceListView> lists = store.list(PriceListStore.RETAIL, PriceListStore.PUBLISHED);
        List<UUID> result = new ArrayList<>();
        for (PriceListView pl : lists) {
            if (ownerEntityId == null || ownerEntityId.equals(pl.ownerEntityId())) {
                for (PriceListLineView line : store.lines(pl.priceListId())) {
                    result.add(line.skuId());
                }
            }
        }
        return result;
    }
}
