package lk.coopfed.knoweb.m3pricing.internal.policy;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads of pricing.mrp_policy (every authenticated scope reads it; writes are SetMrpPolicy's). */
@Component
class MrpPolicyStore {

    static final String AUTO_LOWEST = "AUTO_LOWEST";
    static final String PICKER = "PICKER";

    /** A stored row, as it is. */
    record Row(
            UUID policyId,
            UUID skuId,
            String policy,
            BigDecimal gapAmount,
            BigDecimal gapPercent,
            UUID ownerEntityId,
            UUID setBy,
            java.time.Instant setAt) {}

    private static final String COLUMNS =
            "policy_id, sku_id, policy, picker_gap_amount, picker_gap_percent, owner_entity_id, set_by, set_at";

    private final JdbcTemplate jdbc;

    MrpPolicyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The SKU row of one owner, if any (at most one: the unique index). */
    Optional<Row> forSku(UUID skuId, UUID ownerEntityId) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from pricing.mrp_policy where sku_id = ? and owner_entity_id = ?",
                        MrpPolicyStore::row,
                        skuId,
                        ownerEntityId)
                .stream()
                .findFirst();
    }

    /** The SKU rows of one owner, by SKU. */
    List<Row> ofOwner(UUID ownerEntityId) {
        return jdbc.query(
                "select " + COLUMNS + " from pricing.mrp_policy where sku_id is not null and owner_entity_id = ?"
                        + " order by sku_id",
                MrpPolicyStore::row,
                ownerEntityId);
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(
                rs.getObject("policy_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getString("policy"),
                rs.getBigDecimal("picker_gap_amount"),
                rs.getBigDecimal("picker_gap_percent"),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("set_by", UUID.class),
                rs.getTimestamp("set_at").toInstant());
    }
}
