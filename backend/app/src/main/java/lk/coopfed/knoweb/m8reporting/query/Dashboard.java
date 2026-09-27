package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The dashboard (28A section 5: "dashboard -> { tiles: [{tileId, value, freshness,
 * drillReportId}] }").
 */
public record Dashboard(List<Tile> tiles, Instant freshness) {

    /**
     * @param tileId        open-orders, deliveries-in-transit, grns-today, stock-value
     * @param labelId       the message id of the tile's name
     * @param value         a count, or an amount for a MONEY tile
     * @param kind          COUNT or MONEY
     * @param drillReportId the report the tile opens, if any
     */
    public record Tile(String tileId, String labelId, BigDecimal value, String kind, String drillReportId) {}
}
