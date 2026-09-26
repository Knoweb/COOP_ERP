package lk.coopfed.knoweb.m1party.internal.relationship;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AcceptingPriceListCheck} while nothing else implements
 * {@link TradePriceListCheck}. A bean method, not a scanned component: a scanned class with
 * {@code @ConditionalOnMissingBean} finds its own definition and drops itself. Deleted with the
 * stub the day M3 answers the question.
 */
@Configuration(proxyBeanMethods = false)
class PriceListStubConfiguration {

    @Bean
    @ConditionalOnMissingBean(TradePriceListCheck.class)
    TradePriceListCheck acceptingPriceListCheck(@Value("${coop-erp.party.stub-price-lists:}") List<UUID> seededLists) {
        return new AcceptingPriceListCheck(seededLists);
    }
}
