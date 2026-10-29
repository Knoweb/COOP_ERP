package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m1party.api.LocationActivationPrerequisite;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The posted opening balance required before a shop or warehouse first becomes active (F-10). */
@Component
class OpeningBalanceActivationPrerequisite implements LocationActivationPrerequisite {

    private static final Set<String> STOCK_LOCATIONS = Set.of("SHOP", "WAREHOUSE");

    private final JdbcTemplate jdbc;

    OpeningBalanceActivationPrerequisite(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void requireSatisfied(UUID ownerEntityId, UUID locationId, String locationType) {
        if (!STOCK_LOCATIONS.contains(locationType)) {
            return;
        }
        Boolean posted = jdbc.queryForObject(
                "select exists (select 1 from inventory.opening_balance"
                        + " where owner_entity_id = ? and location_id = ? and status = 'POSTED')",
                Boolean.class,
                ownerEntityId,
                locationId);
        if (!Boolean.TRUE.equals(posted)) {
            throw new ProblemException("m5.opening.required_before_activation", Map.of("locationId", locationId));
        }
    }
}
