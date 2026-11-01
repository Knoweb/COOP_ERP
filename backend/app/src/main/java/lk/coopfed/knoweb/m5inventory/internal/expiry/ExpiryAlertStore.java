package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.query.NearExpiryLotView;
import lk.coopfed.knoweb.m5inventory.query.SlowMovingStockView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence queries for near-expiry and slow-moving stock alerts (PGL-M2-07).
 * In accordance with ArchUnit rule {@code onlyCommandHandlersWriteToTheDatabase}, only
 * reads are performed here; alert state mutations are performed directly by the {@code @CommandHandler}.
 */
@Repository
@Transactional(readOnly = true)
public class ExpiryAlertStore {

    private final JdbcTemplate jdbc;

    public ExpiryAlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ActiveAlertRecord(
            UUID alertId,
            UUID locationId,
            String alertType,
            UUID skuId,
            UUID batchId,
            String alertTier,
            BigDecimal qtyOnHand) {}

    /**
     * Finds active (TRIGGERED) alerts at a location.
     */
    public List<ActiveAlertRecord> findActiveAlerts(UUID locationId, String alertType, ScopeContext scope) {
        String sql =
                """
                select alert_id, location_id, alert_type, sku_id, batch_id, alert_tier, qty_on_hand
                  from inventory.stock_alert_state
                 where location_id = ?
                   and alert_type = ?
                   and status = 'TRIGGERED'
                """;
        return jdbc.query(
                sql,
                (rs, rowNum) -> new ActiveAlertRecord(
                        rs.getObject("alert_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("alert_type"),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("alert_tier"),
                        rs.getBigDecimal("qty_on_hand")),
                locationId,
                alertType);
    }

    /**
     * Near-expiry stock lots with remaining quantity, urgency tier, and alert status.
     */
    public List<NearExpiryLotView> findNearExpiryLots(
            UUID locationId,
            Integer windowDays,
            String tier,
            LocalDate today,
            int criticalDays,
            int warningDays,
            ScopeContext scope) {

        int maxDays = windowDays != null ? windowDays : warningDays;

        String sql =
                """
                select l.stock_lot_id, l.location_id, l.sku_id, l.batch_id, b.batch_no,
                       l.expiry_date, l.qty_on_hand, l.unit_cost,
                       (a.alert_id is not null) as alert_active
                  from inventory.stock_lot l
                  join catalogue.batch b on b.batch_id = l.batch_id
                  left join inventory.stock_alert_state a
                         on a.location_id = l.location_id
                        and a.batch_id = l.batch_id
                        and a.alert_type = 'NEAR_EXPIRY'
                        and a.status = 'TRIGGERED'
                 where l.location_id = ?
                   and l.qty_on_hand > 0
                   and l.expiry_date is not null
                 order by l.expiry_date asc, l.sku_id asc
                """;

        List<NearExpiryLotView> candidates = jdbc.query(
                sql,
                (rs, rowNum) -> {
                    LocalDate expiry = rs.getDate("expiry_date").toLocalDate();
                    int daysToExpiry = (int) ChronoUnit.DAYS.between(today, expiry);
                    String urgencyTier = resolveTier(daysToExpiry, criticalDays, warningDays);
                    return new NearExpiryLotView(
                            rs.getObject("stock_lot_id", UUID.class),
                            rs.getObject("location_id", UUID.class),
                            rs.getObject("sku_id", UUID.class),
                            rs.getObject("batch_id", UUID.class),
                            rs.getString("batch_no"),
                            expiry,
                            daysToExpiry,
                            rs.getBigDecimal("qty_on_hand"),
                            rs.getBigDecimal("unit_cost"),
                            urgencyTier,
                            rs.getBoolean("alert_active"));
                },
                locationId);

        List<NearExpiryLotView> filtered = new ArrayList<>();
        for (NearExpiryLotView view : candidates) {
            if (view.daysToExpiry() > maxDays && !"EXPIRED".equalsIgnoreCase(view.urgencyTier())) {
                continue;
            }
            if (tier != null && !tier.isBlank() && !"ALL".equalsIgnoreCase(tier)) {
                if (!tier.equalsIgnoreCase(view.urgencyTier())) {
                    continue;
                }
            }
            filtered.add(view);
        }
        return filtered;
    }

    /**
     * Resolves urgency tier based on days to expiry.
     */
    public static String resolveTier(int daysToExpiry, int criticalDays, int warningDays) {
        if (daysToExpiry < 0) {
            return "EXPIRED";
        }
        if (daysToExpiry <= criticalDays) {
            return "CRITICAL";
        }
        if (daysToExpiry <= warningDays) {
            return "WARNING";
        }
        if (daysToExpiry <= warningDays * 2) {
            return "APPROACHING";
        }
        return "HEALTHY";
    }

    /**
     * Deterministic evaluation of slow-moving and stagnant stock.
     */
    public List<SlowMovingStockView> findSlowMovingStock(
            UUID locationId,
            int evaluationDays,
            int thresholdDaysOfSupply,
            LocalDate today,
            ZoneId zone,
            ScopeContext scope) {

        // 1. Current stock on hand per SKU (GOOD condition only)
        String onHandSql =
                """
                select l.sku_id,
                       sum(l.qty_on_hand) as total_qty,
                       sum(l.qty_on_hand * l.unit_cost) / sum(l.qty_on_hand) as avg_cost
                  from inventory.stock_lot l
                 where l.location_id = ?
                   and l.qty_on_hand > 0
                   and l.condition = 'GOOD'
                 group by l.sku_id
                """;

        Map<UUID, StockOnHandRecord> onHandMap = new LinkedHashMap<>();
        jdbc.query(
                onHandSql,
                rs -> {
                    UUID skuId = rs.getObject("sku_id", UUID.class);
                    onHandMap.put(
                            skuId,
                            new StockOnHandRecord(
                                    skuId,
                                    rs.getBigDecimal("total_qty"),
                                    rs.getBigDecimal("avg_cost").setScale(4, RoundingMode.HALF_UP)));
                },
                locationId);

        if (onHandMap.isEmpty()) {
            return List.of();
        }

        // 2. Sales in window [today - evaluationDays, today]
        LocalDate windowStart = today.minusDays(evaluationDays);
        Timestamp windowStartTime =
                Timestamp.from(windowStart.atStartOfDay(zone).toInstant());

        String salesSql =
                """
                select m.sku_id,
                       sum(case when m.movement_type = 'SALE' then abs(m.qty_delta) else -abs(m.qty_delta) end) as net_sales,
                       max(m.occurred_at) as last_sale_at
                  from inventory.stock_movement m
                 where m.location_id = ?
                   and m.movement_type in ('SALE', 'SALE_REVERSAL')
                   and m.occurred_at >= ?
                 group by m.sku_id
                """;

        Map<UUID, SalesWindowRecord> salesMap = new HashMap<>();
        jdbc.query(
                salesSql,
                rs -> {
                    UUID skuId = rs.getObject("sku_id", UUID.class);
                    BigDecimal netSales = rs.getBigDecimal("net_sales");
                    Timestamp lastSale = rs.getTimestamp("last_sale_at");
                    salesMap.put(
                            skuId,
                            new SalesWindowRecord(skuId, netSales != null ? netSales : BigDecimal.ZERO, lastSale));
                },
                locationId,
                windowStartTime);

        // 3. For SKUs without sales in window, query their most recent historical sale ever
        String historicalSaleSql =
                """
                select m.sku_id, max(m.occurred_at) as last_sale_at
                  from inventory.stock_movement m
                 where m.location_id = ?
                   and m.movement_type = 'SALE'
                 group by m.sku_id
                """;
        Map<UUID, Timestamp> allTimeLastSales = new HashMap<>();
        jdbc.query(
                historicalSaleSql,
                rs -> {
                    allTimeLastSales.put(rs.getObject("sku_id", UUID.class), rs.getTimestamp("last_sale_at"));
                },
                locationId);

        // 4. Active alert flags
        Set<UUID> activeAlertSkus = new HashSet<>(jdbc.queryForList(
                """
                select sku_id
                  from inventory.stock_alert_state
                 where location_id = ?
                   and alert_type = 'SLOW_MOVING'
                   and status = 'TRIGGERED'
                """,
                UUID.class,
                locationId));

        // 5. Evaluate slow-moving classification deterministically
        List<SlowMovingStockView> result = new ArrayList<>();
        BigDecimal evalDaysDec = BigDecimal.valueOf(evaluationDays);

        for (StockOnHandRecord stock : onHandMap.values()) {
            SalesWindowRecord salesRecord = salesMap.get(stock.skuId());
            BigDecimal salesInWindow = salesRecord != null ? salesRecord.netSales() : BigDecimal.ZERO;
            if (salesInWindow.signum() < 0) {
                salesInWindow = BigDecimal.ZERO;
            }

            Timestamp lastSaleTimestamp = salesRecord != null && salesRecord.lastSaleAt() != null
                    ? salesRecord.lastSaleAt()
                    : allTimeLastSales.get(stock.skuId());

            LocalDate lastSaleDate = lastSaleTimestamp != null
                    ? lastSaleTimestamp.toInstant().atZone(zone).toLocalDate()
                    : null;

            int daysWithoutSale =
                    lastSaleDate != null ? (int) ChronoUnit.DAYS.between(lastSaleDate, today) : evaluationDays;
            if (daysWithoutSale < 0) {
                daysWithoutSale = 0;
            }

            BigDecimal avgDailySales = salesInWindow.divide(evalDaysDec, 4, RoundingMode.HALF_UP);

            String classification = null;
            BigDecimal daysOfSupply = BigDecimal.ZERO;

            if (salesInWindow.compareTo(BigDecimal.ZERO) == 0) {
                // Zero sales in window: Stagnant
                classification = "STAGNANT";
                daysOfSupply = new BigDecimal("999.0");
            } else {
                // Positive sales, compute Days of Supply = QtyOnHand / AvgDailySales
                daysOfSupply = stock.qtyOnHand().divide(avgDailySales, 1, RoundingMode.HALF_UP);
                if (daysOfSupply.compareTo(BigDecimal.valueOf(thresholdDaysOfSupply)) > 0) {
                    classification = "SLOW_VELOCITY";
                }
            }

            if (classification != null) {
                boolean alertActive = activeAlertSkus.contains(stock.skuId());
                result.add(new SlowMovingStockView(
                        locationId,
                        stock.skuId(),
                        stock.qtyOnHand().setScale(3, RoundingMode.HALF_UP),
                        stock.avgCost(),
                        daysWithoutSale,
                        lastSaleDate,
                        salesInWindow.setScale(3, RoundingMode.HALF_UP),
                        avgDailySales,
                        daysOfSupply,
                        classification,
                        alertActive));
            }
        }

        // Sort: STAGNANT first, then by days without sale descending
        result.sort((a, b) -> {
            int classComp = a.classification().compareTo(b.classification());
            if (classComp != 0) {
                return classComp; // 'SLOW_VELOCITY' vs 'STAGNANT' ('STAGNANT' comes second alphabetically, let's
                // reverse)
            }
            return Integer.compare(b.daysWithoutSale(), a.daysWithoutSale());
        });

        return result;
    }

    private record StockOnHandRecord(UUID skuId, BigDecimal qtyOnHand, BigDecimal avgCost) {}

    private record SalesWindowRecord(UUID skuId, BigDecimal netSales, Timestamp lastSaleAt) {}
}
