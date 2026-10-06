package lk.coopfed.knoweb.m3pricing.internal.list;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of pricing.price_list and price_list_line. Row-level security limits every read to what
 * the caller's scope may see; nothing here filters by entity. Writes are in the handlers only
 * (the architecture rule: nothing but a command handler writes).
 */
@Component
public class PriceListStore {

    static final String TRADE = "TRADE";
    static final String RETAIL = "RETAIL";
    static final String ADVISORY = "ADVISORY";
    static final String DRAFT = "DRAFT";
    static final String PUBLISHED = "PUBLISHED";
    static final String SUPERSEDED = "SUPERSEDED";

    private static final String LIST_COLUMNS = "price_list_id, root_price_list_id, owner_entity_id, kind, name,"
            + " version, source_version_id, status, apply_from, published_at, created_at";

    private static final String LINE_COLUMNS =
            "line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, effective_to";

    private final JdbcTemplate jdbc;

    PriceListStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean isTrade(String kind) {
        return TRADE.equals(kind);
    }

    public Optional<PriceListView> find(UUID priceListId) {
        return jdbc
                .query(
                        "select " + LIST_COLUMNS + " from pricing.price_list where price_list_id = ?",
                        PriceListStore::list,
                        priceListId)
                .stream()
                .findFirst();
    }

    /**
     * The list row, locked until the transaction ends (M3-05): a second publish of the same draft
     * waits here, then reads the row as the first left it (PUBLISHED) and is refused.
     */
    Optional<PriceListView> findForUpdate(UUID priceListId) {
        return jdbc
                .query(
                        "select " + LIST_COLUMNS + " from pricing.price_list where price_list_id = ? for update",
                        PriceListStore::list,
                        priceListId)
                .stream()
                .findFirst();
    }

    public List<PriceListView> list(String kind, String status) {
        StringBuilder sql = new StringBuilder("select " + LIST_COLUMNS + " from pricing.price_list where true");
        List<Object> args = new ArrayList<>();
        if (kind != null) {
            sql.append(" and kind = ?");
            args.add(kind);
        }
        if (status != null) {
            sql.append(" and status = ?");
            args.add(status);
        }
        sql.append(" order by name, root_price_list_id, version desc");
        return jdbc.query(sql.toString(), PriceListStore::list, args.toArray());
    }

    public List<PriceListLineView> lines(UUID priceListId) {
        return jdbc.query(
                "select " + LINE_COLUMNS + " from pricing.price_list_line where price_list_id = ?"
                        + " order by sku_id, uom_code, tier_from_qty",
                PriceListStore::line,
                priceListId);
    }

    /** The PUBLISHED version of a list, if any (at most one: publication supersedes the previous). */
    public Optional<PriceListView> published(UUID rootPriceListId) {
        return jdbc
                .query(
                        "select " + LIST_COLUMNS + " from pricing.price_list"
                                + " where root_price_list_id = ? and status = 'PUBLISHED'",
                        PriceListStore::list,
                        rootPriceListId)
                .stream()
                .findFirst();
    }

    /**
     * The root id of the caller's own list of a kind, if it has one (RETAIL: at most one per society,
     * doc 23 section 3.1; its versions share the root). Read in the owner's scope.
     */
    public Optional<UUID> rootOfKind(String kind, UUID ownerEntityId) {
        return jdbc
                .queryForList(
                        "select distinct root_price_list_id from pricing.price_list where kind = ? and owner_entity_id = ?",
                        UUID.class,
                        kind,
                        ownerEntityId)
                .stream()
                .findFirst();
    }

    /** The newest ADVISORY version in force on the date, of any Federation list (readable by every scope). */
    public List<PriceListView> advisoryInForce(LocalDate date) {
        return jdbc.query(
                "select distinct on (root_price_list_id) " + LIST_COLUMNS + " from pricing.price_list"
                        + " where kind = 'ADVISORY' and status in ('PUBLISHED', 'SUPERSEDED') and apply_from <= ?"
                        + " order by root_price_list_id, version desc",
                PriceListStore::list,
                Date.valueOf(date));
    }

    boolean draftExists(UUID rootPriceListId) {
        Integer drafts = jdbc.queryForObject(
                "select count(*) from pricing.price_list where root_price_list_id = ? and status = 'DRAFT'",
                Integer.class,
                rootPriceListId);
        return drafts != null && drafts > 0;
    }

    int latestVersion(UUID rootPriceListId) {
        Integer version = jdbc.queryForObject(
                "select max(version) from pricing.price_list where root_price_list_id = ?",
                Integer.class,
                rootPriceListId);
        return version == null ? 0 : version;
    }

    /**
     * The version of a list in force on a date: the newest version, published or since superseded,
     * whose apply_from is on or before the date. Without the closure of the previous version's
     * lines (deferred for the demo), this is what keeps two versions from both answering.
     */
    public Optional<PriceListView> versionInForce(UUID rootPriceListId, LocalDate date) {
        return jdbc
                .query(
                        "select " + LIST_COLUMNS + " from pricing.price_list"
                                + " where root_price_list_id = ? and status in ('PUBLISHED', 'SUPERSEDED')"
                                + " and apply_from <= ? order by version desc limit 1",
                        PriceListStore::list,
                        rootPriceListId,
                        Date.valueOf(date))
                .stream()
                .findFirst();
    }

    private static PriceListView list(ResultSet rs, int row) throws SQLException {
        Date applyFrom = rs.getDate("apply_from");
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new PriceListView(
                rs.getObject("price_list_id", UUID.class),
                rs.getObject("root_price_list_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getInt("version"),
                rs.getObject("source_version_id", UUID.class),
                rs.getString("status"),
                applyFrom == null ? null : applyFrom.toLocalDate(),
                publishedAt == null ? null : publishedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static PriceListLineView line(ResultSet rs, int row) throws SQLException {
        Date effectiveTo = rs.getDate("effective_to");
        return new PriceListLineView(
                rs.getObject("line_id", UUID.class),
                rs.getObject("price_list_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getString("uom_code"),
                rs.getBigDecimal("tier_from_qty"),
                rs.getBigDecimal("price"),
                rs.getDate("effective_from").toLocalDate(),
                effectiveTo == null ? null : effectiveTo.toLocalDate());
    }
}
