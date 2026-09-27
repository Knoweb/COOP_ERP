package lk.coopfed.knoweb.m4trading.internal.integration;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.InventoryAvailability;
import org.springframework.stereotype.Component;

/**
 * The demo's answer to {@link InventoryAvailability} until M5's availability query exists
 * (M4-02, decided 27 September 2026): every item asked for is available in the quantity of the
 * register item {@code m4.demo.availability_qty} (trading.availability_mode INDICATIVE). M5's
 * implementation replaces this class.
 */
@Component
class DemoInventoryAvailability implements InventoryAvailability {

    static final String QUANTITY_ITEM = "m4.demo.availability_qty";

    private final ConfigRegistry config;

    DemoInventoryAvailability(ConfigRegistry config) {
        this.config = config;
    }

    @Override
    public Map<UUID, BigDecimal> availability(UUID sellerEntityId, Collection<UUID> skuIds, ScopeContext scope) {
        BigDecimal quantity =
                config.get(QUANTITY_ITEM, scope).map(BigDecimal::new).orElse(BigDecimal.ZERO);
        Map<UUID, BigDecimal> available = new LinkedHashMap<>();
        for (UUID skuId : skuIds) {
            available.put(skuId, quantity);
        }
        return available;
    }
}
