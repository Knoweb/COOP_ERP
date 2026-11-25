package lk.coopfed.knoweb.m3pricing.internal.review;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of {@link PricingReviewJob}: the unchecked triggers, and the lists one breaches.
 * Public and transactional with a ScopeContext argument, which is what the kernel's scope aspect
 * puts on the session (the job passes its federation-wide view).
 */
@Component
public class ReviewTriggers {

    static final String BATCH_MRP = "BATCH_MRP";
    static final String GAZETTE = "GAZETTE";

    /** A recorded event to review: a batch's printed MRP or a control price. */
    public record Trigger(
            UUID triggerId,
            UUID ownerEntityId,
            String kind,
            UUID skuId,
            UUID sourceId,
            String sourceRef,
            BigDecimal thresholdPrice,
            String uomCode) {}

    /** A society's (or the Federation's) list of one kind in force, above the threshold in that unit. */
    public record Breach(UUID ownerEntityId, String listKind, String uomCode, BigDecimal listPrice) {}

    private final JdbcTemplate jdbc;

    ReviewTriggers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every trigger not yet checked, oldest first. */
    @Transactional(readOnly = true)
    public List<Trigger> unchecked(ScopeContext scope) {
        return jdbc.query(
                "select trigger_id, owner_entity_id, kind, sku_id, source_id, source_ref, threshold_price, uom_code"
                        + " from pricing.review_trigger where checked_at is null order by received_at, trigger_id",
                (rs, i) -> new Trigger(
                        rs.getObject("trigger_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("kind"),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("source_id", UUID.class),
                        rs.getString("source_ref"),
                        rs.getBigDecimal("threshold_price"),
                        rs.getString("uom_code")));
    }

    /**
     * Whether the trigger is checked already, locked, in the caller's scope (the check handler's
     * guard); empty when the caller cannot see it.
     */
    Optional<Boolean> checkedForUpdate(UUID triggerId) {
        return jdbc
                .query(
                        "select checked_at is not null as checked from pricing.review_trigger"
                                + " where trigger_id = ? for update",
                        (rs, i) -> rs.getBoolean("checked"),
                        triggerId)
                .stream()
                .findFirst();
    }

    /**
     * The lists in force on {@code date} (M3R-04): of each list, the newest PUBLISHED or SUPERSEDED
     * version whose apply_from is on or before the date, as PriceListStore.versionInForce reads it
     * (a SUPERSEDED version answers until its successor's apply_from). Of those, per owner and
     * kind, the highest line of the SKU in {@code uomCode} above the threshold: a line in another
     * unit is not compared (no unit conversion yet; 23A section 11). A batch's MRP binds the
     * RETAIL lists only; a ceiling every kind, as before.
     */
    @Transactional(readOnly = true)
    public List<Breach> breaches(Trigger trigger, String uomCode, LocalDate date, ScopeContext scope) {
        if (uomCode == null) {
            return List.of();
        }
        String kinds = BATCH_MRP.equals(trigger.kind()) ? " and pl.kind = 'RETAIL'" : "";
        return jdbc.query(
                "select v.owner_entity_id, v.kind, l.uom_code, max(l.price) as price"
                        + " from (select distinct on (pl.root_price_list_id) pl.price_list_id, pl.owner_entity_id,"
                        + " pl.kind from pricing.price_list pl"
                        + " where pl.status in ('PUBLISHED', 'SUPERSEDED') and pl.apply_from <= ?" + kinds
                        + " order by pl.root_price_list_id, pl.version desc) v"
                        + " join pricing.price_list_line l on l.price_list_id = v.price_list_id"
                        + " where l.sku_id = ? and l.uom_code = ? and l.price > ?"
                        + " group by v.owner_entity_id, v.kind, l.uom_code"
                        + " order by v.owner_entity_id, v.kind",
                (rs, i) -> new Breach(
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("uom_code"),
                        rs.getBigDecimal("price")),
                Date.valueOf(date),
                trigger.skuId(),
                uomCode,
                trigger.thresholdPrice());
    }
}
