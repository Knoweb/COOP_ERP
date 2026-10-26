package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.ItemProfile;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Duplicate detection engine integration for standard SKU commands (CAT-02, 22A section 6.1).
 *
 * <p>Identifies candidate duplicate suspects for new or updated SKUs against existing catalogue
 * items, adhering to the configured {@code catalogue.duplicate_threshold} (default 0.85).
 * Only reads from the database; actual suspect recording and event publishing remain in the
 * calling command handlers.
 */
@Component
class SkuDuplicateDetector {

    private final DuplicateScorer scorer;
    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;

    SkuDuplicateDetector(
            DuplicateScorer scorer, JdbcTemplate jdbc, @Autowired(required = false) ConfigRegistry config) {
        this.scorer = scorer;
        this.jdbc = jdbc;
        this.config = config;
    }

    public List<SuspectMatch> findDuplicates(Sku sku, ScopeContext scope) {
        if (sku == null || scope == null || scope.entityId() == null) {
            return List.of();
        }

        UUID ownerEntityId = scope.entityId();

        List<String> barcodes = jdbc.query(
                "select barcode from catalogue.sku_barcode where sku_id = ? and status = 'ACTIVE'",
                (rs, rowNum) -> rs.getString("barcode"),
                sku.getId());

        String brand = sku.attributes() != null && sku.attributes().get("brand") instanceof String b ? b : null;

        ItemProfile candidate = new ItemProfile(
                sku.getId(),
                sku.skuCode(),
                sku.shortNameEn(),
                sku.shortNameSi(),
                sku.shortNameTa(),
                sku.baseUomCode(),
                brand,
                barcodes);

        List<ItemProfile> existing = loadExistingProfiles(ownerEntityId, sku.getId());
        if (existing.isEmpty()) {
            return List.of();
        }

        double threshold = DuplicateScorer.DEFAULT_THRESHOLD;
        if (config != null) {
            threshold = config.get("catalogue.duplicate_threshold", scope)
                    .map(Double::parseDouble)
                    .orElse(DuplicateScorer.DEFAULT_THRESHOLD);
        }

        List<SuspectMatch> matches = scorer.scoreAgainstExisting(List.of(candidate), existing, threshold);
        if (matches.isEmpty()) {
            return List.of();
        }

        List<SuspectMatch> newMatches = new ArrayList<>();
        for (SuspectMatch m : matches) {
            UUID candId = sku.getId();
            UUID existId = m.matched().skuId();
            if (candId == null || existId == null || candId.equals(existId)) {
                continue;
            }

            UUID a = candId.toString().compareTo(existId.toString()) < 0 ? candId : existId;
            UUID b = candId.toString().compareTo(existId.toString()) < 0 ? existId : candId;

            Integer count = jdbc.queryForObject(
                    "select count(*) from catalogue.duplicate_suspect where sku_a = ? and sku_b = ?",
                    Integer.class,
                    a,
                    b);

            if (count == null || count == 0) {
                newMatches.add(m);
            }
        }

        return newMatches;
    }

    private List<ItemProfile> loadExistingProfiles(UUID ownerEntityId, UUID excludeSkuId) {
        return jdbc.query(
                """
                select s.sku_id, s.sku_code, s.short_name_en, s.short_name_si, s.short_name_ta, s.base_uom_code,
                       array_agg(b.barcode) filter (where b.barcode is not null) as barcodes
                from catalogue.sku s
                left join catalogue.sku_barcode b on b.sku_id = s.sku_id and b.status = 'ACTIVE'
                where (s.owner_entity_id = ? or s.status = 'SHARED')
                  and s.sku_id <> ?
                group by s.sku_id, s.sku_code, s.short_name_en, s.short_name_si, s.short_name_ta, s.base_uom_code
                limit 5000
                """,
                ps -> {
                    ps.setObject(1, ownerEntityId);
                    ps.setObject(2, excludeSkuId);
                },
                (rs, rowNum) -> {
                    UUID id = (UUID) rs.getObject("sku_id");
                    String code = rs.getString("sku_code");
                    String en = rs.getString("short_name_en");
                    String si = rs.getString("short_name_si");
                    String ta = rs.getString("short_name_ta");
                    String uom = rs.getString("base_uom_code");
                    java.sql.Array bArr = rs.getArray("barcodes");
                    List<String> bList = new ArrayList<>();
                    if (bArr != null) {
                        Object arrayObj = bArr.getArray();
                        if (arrayObj instanceof Object[] arr) {
                            for (Object o : arr) {
                                if (o != null) {
                                    bList.add(o.toString());
                                }
                            }
                        }
                    }
                    return new ItemProfile(id, code, en, si, ta, uom, null, bList);
                });
    }
}
