package lk.coopfed.knoweb.m1party.internal.snapshot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The shop itself in its till's snapshot (doc 32 section 5.1, "configuration for the location
 * ... series assignments and primary-till flag"): the location row and its till positions, with
 * which position is the primary till. M1 owns both tables (21A section 3).
 *
 * <p>Snapshot tables:
 *
 * <ul>
 *   <li>{@code location}: one row, the shop (row id = location id);
 *   <li>{@code till_position}: the shop's positions (row id = till position id), retired ones too,
 *       so a till can tell a retired position from an unknown one.
 * </ul>
 *
 * <p>Runs in the device's scope (the kernel's snapshot builder calls it); only the device's own
 * shop is ever asked for.
 */
@Component
class ShopSnapshotContributor implements SnapshotContributor {

    static final String LOCATION = "location";
    static final String TILL_POSITION = "till_position";

    private final NamedParameterJdbcTemplate jdbc;

    ShopSnapshotContributor(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Set<String> tables() {
        return Set.of(LOCATION, TILL_POSITION);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return Map.of();
        }
        return read(table, shop, rowIds);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        return read(table, shop, null);
    }

    /** The rows of one table; {@code rowIds} null for all of them. */
    private Map<UUID, Map<String, Object>> read(String table, Shop shop, Collection<UUID> rowIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("location", shop.locationId()).addValue("ids", rowIds);
        String onlyIds = rowIds == null ? "" : " and %s in (:ids)";
        Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        switch (table) {
            case LOCATION ->
                jdbc.query(
                        """
                    select location_id, owner_entity_id, location_code, location_type, name_en, name_si, name_ta,
                           language, trading_hours::text as trading_hours, primary_till_position_id, status
                      from party.location
                     where location_id = :location
                    """
                                + onlyIds.formatted("location_id"),
                        params,
                        (ResultSet rs) -> {
                            rows.put(rs.getObject("location_id", UUID.class), location(rs));
                        });
            case TILL_POSITION ->
                jdbc.query(
                        """
                    select p.till_position_id, p.position_no, p.status,
                           (l.primary_till_position_id = p.till_position_id) as primary_till
                      from party.till_position p
                      join party.location l on l.location_id = p.location_id
                     where p.location_id = :location
                    """
                                + onlyIds.formatted("p.till_position_id"),
                        params,
                        (ResultSet rs) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("position_no", rs.getInt("position_no"));
                            row.put("status", rs.getString("status"));
                            row.put("primary_till", rs.getBoolean("primary_till"));
                            rows.put(rs.getObject("till_position_id", UUID.class), row);
                        });
            default -> throw new IllegalArgumentException("Not an M1 shop table: " + table);
        }
        return rows;
    }

    private static Map<String, Object> location(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("owner_entity_id", rs.getString("owner_entity_id"));
        row.put("location_code", rs.getString("location_code"));
        row.put("location_type", rs.getString("location_type"));
        row.put("name_en", rs.getString("name_en"));
        row.put("name_si", rs.getString("name_si"));
        row.put("name_ta", rs.getString("name_ta"));
        row.put("language", rs.getString("language"));
        // The trading hours as the JSON text M1 stores: the till reads them, central never
        // interprets them here.
        row.put("trading_hours", rs.getString("trading_hours"));
        row.put("primary_till_position_id", rs.getString("primary_till_position_id"));
        row.put("status", rs.getString("status"));
        return row;
    }
}
