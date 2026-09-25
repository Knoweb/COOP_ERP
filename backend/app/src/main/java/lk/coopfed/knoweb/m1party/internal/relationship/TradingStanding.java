package lk.coopfed.knoweb.m1party.internal.relationship;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The type and status of an entity, which the opening guards need for both parties. The seller
 * may not read the buyer's {@code party.entity} row under row-level security (it carries the
 * VAT and registration numbers and the officer), so this asks the narrow function
 * {@code party.trading_standing} (m1party V0007), which answers these two facts to an OWN
 * caller and nothing else.
 */
@Component
class TradingStanding {

    /** What the guards know of a party. */
    record Standing(String entityType, String status) {

        boolean canTrade() {
            return "ACTIVE".equals(status) || "ONBOARDING".equals(status);
        }
    }

    private final JdbcTemplate jdbc;

    TradingStanding(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when the entity does not exist (or the caller is not in an OWN scope). */
    Optional<Standing> of(UUID entityId) {
        return jdbc
                .query(
                        "select entity_type, status from party.trading_standing(?)",
                        (rs, row) -> new Standing(rs.getString("entity_type"), rs.getString("status")),
                        entityId)
                .stream()
                .findFirst();
    }
}
