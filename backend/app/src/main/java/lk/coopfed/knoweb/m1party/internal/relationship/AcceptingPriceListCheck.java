package lk.coopfed.knoweb.m1party.internal.relationship;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The stub of 21A section 11 ("Price-list validation at ActivateRelationship: M3 query
 * interface stubbed until doc 23; stub returns true for the seed list"): M3 has no TRADE price
 * lists yet, so there is nothing to check a list against, and every list is accepted. The
 * handler already asks the question and its tests already assert the call and the refusal, so
 * the day M3 implements {@link TradePriceListCheck}, this class is deleted and nothing else in
 * M1 changes. It says so at WARN each time, so that the gap is visible in the log.
 */
@Component
class AcceptingPriceListCheck implements TradePriceListCheck {

    private static final Logger log = LoggerFactory.getLogger(AcceptingPriceListCheck.class);

    @Override
    public Optional<String> refusal(UUID priceListId, UUID sellerEntityId, ScopeContext scope) {
        log.warn(
                "price list {} accepted for seller {} without a check: M3 does not implement TradePriceListCheck yet",
                priceListId,
                sellerEntityId);
        return Optional.empty();
    }
}
