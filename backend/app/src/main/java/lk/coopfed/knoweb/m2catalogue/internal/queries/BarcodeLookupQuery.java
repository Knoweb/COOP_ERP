package lk.coopfed.knoweb.m2catalogue.internal.queries;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.GtinParser;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LookupByBarcode (22A section 7): 1) the exact (barcode, symbology) ACTIVE row gives the SKU,
 * the unit and the batch link; 2) else, with a GTIN and a lot, the SKU by its GTIN barcode and
 * the batch by (sku, lot) through batch_lookup, so a 2D code resolves a batch without a
 * registered batch barcode; 3) an INTERNAL code counts in the owner's scope only.
 *
 * <p>Every query runs under the caller's scope: row-level security shows the caller its own
 * rows and those of SHARED items. An INTERNAL row of another entity on a SHARED item is visible
 * through shared_read today (a finding against M2-01), so the SQL keeps INTERNAL rows to the
 * caller's own entity itself; a factory code is federation-wide (B-I2).
 *
 * <p>sell_through comes from location_assortment (M2-09) and thumbKey from sku_image (M2-06);
 * until those tables exist the answer is false and null. The per-instance cache of 22A section
 * 7 is not here yet either (see the module README).
 */
@Component
class BarcodeLookupQuery {

    private static final String FACTORY_SYMBOLOGIES =
            "('EAN13', 'EAN8', 'UPCA', 'GS1_128', 'GS1_DATAMATRIX', 'GS1_QR')";

    /** The registry row and the SKU facts the item card needs, in one read. */
    private record Hit(
            UUID skuId,
            String skuCode,
            String nameEn,
            String nameSi,
            String nameTa,
            String baseUomCode,
            boolean hasPrintedMrp,
            boolean soldByWeight,
            String uomCode,
            UUID batchId) {}

    private static final String SELECT_HIT =
            """
            select s.sku_id, s.sku_code, s.short_name_en, s.short_name_si, s.short_name_ta,
                   s.base_uom_code, s.has_printed_mrp, s.sold_by_weight,
                   b.uom_code, b.batch_id
            from catalogue.sku_barcode b
            join catalogue.sku s on s.sku_id = b.sku_id
            where b.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    BarcodeLookupQuery(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<LookupResult> lookup(BarcodeLookup lookup, ScopeContext scope) {
        if (lookup == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }

        String barcode = blankToNull(lookup.barcode());
        String symbology = blankToNull(lookup.symbology());
        String gtin = blankToNull(lookup.gtin());
        String lot = blankToNull(lookup.lot());
        LocalDate expiry = lookup.expiry();

        // A GS1 element string sent whole (a 2D code the till did not split): split it here.
        if (barcode != null && gtin == null) {
            Optional<GtinParser.Gs1Elements> elements = GtinParser.parseElementString(barcode, LocalDate.now(clock));
            if (elements.isPresent()) {
                gtin = elements.get().gtin();
                lot = lot == null ? elements.get().lot() : lot;
                expiry = expiry == null ? elements.get().expiry() : expiry;
            }
        }

        // 1) and 3): the exact registry row, factory codes for everyone and INTERNAL ones of the
        // caller's own entity, the factory row first when both exist.
        Optional<Hit> exact = Optional.empty();
        if (barcode != null) {
            exact = exactRow(barcode, symbology, scope.entityId());
        }
        if (exact.isEmpty() && gtin != null) {
            exact = exactRow(gtin, symbology, scope.entityId());
        }
        if (exact.isPresent()) {
            Hit hit = exact.get();
            Optional<LookupResult.BatchRef> batch = hit.batchId() != null
                    ? batchById(hit.batchId())
                    : (lot != null ? batchByLot(hit.skuId(), lot, expiry) : Optional.empty());
            return Optional.of(result(hit, batch.orElse(null)));
        }

        // 2): the GTIN of a 2D code registered under any factory symbology (a GTIN-14 with
        // leading zeros is the same item as its EAN-13), then the batch by number.
        if (gtin != null && GtinParser.isValidGtin(gtin)) {
            Optional<Hit> byGtin = factoryRowByGtin(gtin);
            if (byGtin.isPresent()) {
                Hit hit = byGtin.get();
                Optional<LookupResult.BatchRef> batch =
                        lot != null ? batchByLot(hit.skuId(), lot, expiry) : Optional.empty();
                return Optional.of(result(hit, batch.orElse(null)));
            }
        }

        return Optional.empty();
    }

    private Optional<Hit> exactRow(String barcode, String symbology, UUID entityId) {
        StringBuilder sql = new StringBuilder(SELECT_HIT)
                .append(" and b.barcode = ? and (b.symbology <> 'INTERNAL' or b.owner_entity_id = ?)");
        List<Object> params = new java.util.ArrayList<>(List.of(barcode, entityId));
        if (symbology != null) {
            sql.append(" and b.symbology = ?");
            params.add(symbology.strip().toUpperCase());
        }
        sql.append(" order by case when b.symbology = 'INTERNAL' then 1 else 0 end, b.symbology");
        return jdbc.query(sql.toString(), BarcodeLookupQuery::mapHit, params.toArray()).stream()
                .findFirst();
    }

    private Optional<Hit> factoryRowByGtin(String gtin) {
        return jdbc
                .query(
                        SELECT_HIT + " and b.symbology in " + FACTORY_SYMBOLOGIES
                                + " and ltrim(b.barcode, '0') = ? order by b.symbology",
                        BarcodeLookupQuery::mapHit,
                        GtinParser.withoutLeadingZeros(gtin))
                .stream()
                .findFirst();
    }

    private Optional<LookupResult.BatchRef> batchById(UUID batchId) {
        return jdbc
                .query(
                        "select batch_id, batch_no, expiry_date, printed_mrp from catalogue.batch where batch_id = ?",
                        BarcodeLookupQuery::mapBatch,
                        batchId)
                .stream()
                .findFirst();
    }

    /**
     * The batch of the SKU with that number (index batch_lookup). When the code carried an
     * expiry, a batch with that expiry wins; a REGISTERED batch wins over a SUPERSEDED one.
     */
    private Optional<LookupResult.BatchRef> batchByLot(UUID skuId, String lot, LocalDate expiry) {
        return jdbc
                .query(
                        """
                        select batch_id, batch_no, expiry_date, printed_mrp
                        from catalogue.batch
                        where sku_id = ? and batch_no = ?
                        order by case when expiry_date = ? then 0 else 1 end,
                                 case when status = 'REGISTERED' then 0 else 1 end,
                                 created_at desc
                        """,
                        BarcodeLookupQuery::mapBatch,
                        skuId,
                        lot,
                        expiry)
                .stream()
                .findFirst();
    }

    private Optional<BigDecimal> factorInForce(UUID skuId, String uomCode) {
        LocalDate today = LocalDate.now(clock);
        return jdbc
                .query(
                        """
                        select factor_to_base
                        from catalogue.sku_uom_conversion
                        where sku_id = ? and uom_code = ?
                          and effective_from <= ?
                          and (effective_to is null or effective_to >= ?)
                        """,
                        (rs, row) -> rs.getBigDecimal("factor_to_base"),
                        skuId,
                        uomCode,
                        today,
                        today)
                .stream()
                .findFirst();
    }

    private LookupResult result(Hit hit, LookupResult.BatchRef batch) {
        BigDecimal factor = hit.uomCode().equals(hit.baseUomCode())
                ? BigDecimal.ONE
                : factorInForce(hit.skuId(), hit.uomCode()).orElse(null);

        boolean fallbackSi = hit.nameSi() == null;
        boolean fallbackTa = hit.nameTa() == null;

        return new LookupResult(
                hit.skuId(),
                hit.skuCode(),
                hit.nameEn(),
                fallbackSi ? hit.nameEn() : hit.nameSi(),
                fallbackTa ? hit.nameEn() : hit.nameTa(),
                fallbackSi,
                fallbackTa,
                hit.uomCode(),
                factor,
                batch,
                null,
                false,
                hit.hasPrintedMrp(),
                hit.soldByWeight());
    }

    private static Hit mapHit(ResultSet rs, int row) throws SQLException {
        return new Hit(
                rs.getObject("sku_id", UUID.class),
                rs.getString("sku_code"),
                rs.getString("short_name_en"),
                rs.getString("short_name_si"),
                rs.getString("short_name_ta"),
                rs.getString("base_uom_code"),
                rs.getBoolean("has_printed_mrp"),
                rs.getBoolean("sold_by_weight"),
                rs.getString("uom_code"),
                rs.getObject("batch_id", UUID.class));
    }

    private static LookupResult.BatchRef mapBatch(ResultSet rs, int row) throws SQLException {
        return new LookupResult.BatchRef(
                rs.getObject("batch_id", UUID.class),
                rs.getString("batch_no"),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getBigDecimal("printed_mrp"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
