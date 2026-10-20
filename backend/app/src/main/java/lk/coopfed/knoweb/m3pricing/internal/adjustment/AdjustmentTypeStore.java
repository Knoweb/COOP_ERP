package lk.coopfed.knoweb.m3pricing.internal.adjustment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.m3pricing.query.AdjustmentKind;
import lk.coopfed.knoweb.m3pricing.query.AdjustmentTypeView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Storage and queries for registered adjustment types (pricing.adjustment_type).
 * Read access is governed by RLS (policy everyone_reads).
 */
@Component
public class AdjustmentTypeStore {

    private static final String COLUMNS =
            "code, name_en, kind, permission_code, max_percent, max_amount, status, owner_entity_id";

    private final JdbcTemplate jdbc;

    AdjustmentTypeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AdjustmentTypeView> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "select " + COLUMNS + " from pricing.adjustment_type where code = ?",
                        AdjustmentTypeStore::row,
                        code)
                .stream()
                .findFirst();
    }

    public List<AdjustmentTypeView> list() {
        return jdbc.query(
                "select " + COLUMNS + " from pricing.adjustment_type order by code", AdjustmentTypeStore::row);
    }

    private static AdjustmentTypeView row(ResultSet rs, int rowNum) throws SQLException {
        return new AdjustmentTypeView(
                rs.getString("code"),
                rs.getString("name_en"),
                AdjustmentKind.valueOf(rs.getString("kind")),
                rs.getString("permission_code"),
                rs.getBigDecimal("max_percent"),
                rs.getBigDecimal("max_amount"),
                rs.getString("status"),
                rs.getObject("owner_entity_id", UUID.class));
    }
}
