package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The dashboard (28A section 5: "dashboard -> { tiles: [{tileId, value, delta?, freshness,
 * drillReportId}] }"). The tiles are data (seed/m8reporting/dashboard-tiles.yaml); a tile with
 * nothing to show for the caller's scope is left out.
 */
public record Dashboard(List<Tile> tiles, Instant freshness) {

    /**
     * @param tileId        the tile's id in dashboard-tiles.yaml
     * @param labelId       the message id of the tile's name
     * @param value         a count, an amount for a MONEY tile, a percentage for a PERCENT tile
     * @param kind          COUNT, MONEY or PERCENT
     * @param drillReportId the report the tile opens, if any ("exceptions": the exception queue)
     * @param trend         for a trend tile, the eight weeks ending today, oldest first; empty otherwise
     */
    public record Tile(
            String tileId, String labelId, BigDecimal value, String kind, String drillReportId, List<Point> trend) {

        public Tile {
            trend = trend == null ? List.of() : List.copyOf(trend);
        }
    }

    /**
     * One week of a trend.
     *
     * @param from  the first day of the week (the week ends six days later)
     * @param value the week's figure
     */
    public record Point(LocalDate from, BigDecimal value) {}
}
