package lk.coopfed.knoweb.m2catalogue.internal.unit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of catalogue.uom and catalogue.sku_uom_conversion for the conversion handler. Every
 * query runs inside the handler's transaction, under the caller's scope, so row-level security
 * applies (the caller sees its own conversions and those of SHARED items). The writes stay in
 * the handler: only a command handler writes (ArchitectureTests).
 */
@Component
class ConversionStore {

    /** One row of catalogue.sku_uom_conversion. */
    record ConversionRow(
            UUID skuId,
            String uomCode,
            BigDecimal factorToBase,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID ownerEntityId) {

        Map<String, Object> auditState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("skuId", skuId);
            state.put("uomCode", uomCode);
            state.put("factorToBase", factorToBase);
            state.put("effectiveFrom", effectiveFrom);
            state.put("effectiveTo", effectiveTo);
            state.put("ownerEntityId", ownerEntityId);
            return Collections.unmodifiableMap(state);
        }
    }

    private final JdbcTemplate jdbc;

    ConversionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the unit is a weight, or empty when the unit does not exist. */
    Optional<Boolean> unitIsWeight(String uomCode) {
        return jdbc
                .query(
                        "select is_weight from catalogue.uom where uom_code = ?",
                        (rs, row) -> rs.getBoolean("is_weight"),
                        uomCode)
                .stream()
                .findFirst();
    }

    /** The open-ended row of (sku, unit): the one a new definition closes. */
    Optional<ConversionRow> findOpenRow(UUID skuId, String uomCode) {
        return jdbc
                .query(
                        """
                        select sku_id, uom_code, factor_to_base, effective_from, effective_to, owner_entity_id
                        from catalogue.sku_uom_conversion
                        where sku_id = ? and uom_code = ? and effective_to is null
                        """,
                        (rs, row) -> new ConversionRow(
                                rs.getObject("sku_id", UUID.class),
                                rs.getString("uom_code"),
                                rs.getBigDecimal("factor_to_base"),
                                rs.getObject("effective_from", LocalDate.class),
                                rs.getObject("effective_to", LocalDate.class),
                                rs.getObject("owner_entity_id", UUID.class)),
                        skuId,
                        uomCode)
                .stream()
                .findFirst();
    }
}
