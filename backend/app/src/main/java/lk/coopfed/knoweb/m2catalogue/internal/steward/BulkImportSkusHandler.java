package lk.coopfed.knoweb.m2catalogue.internal.steward;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BulkImportSkus;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspected;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport.DuplicateSuspectItem;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport.ValidationError;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import lk.coopfed.knoweb.m2catalogue.api.SkusBulkImported;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuCodeGenerator;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.ItemProfile;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import lk.coopfed.knoweb.m2catalogue.internal.steward.RowValidator.ValidationContext;
import lk.coopfed.knoweb.m2catalogue.internal.steward.Transliterator.TransliterationResult;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * High-performance catalogue bulk import handler (CAT-01, 22A section 6.2).
 *
 * <p>Capable of processing large catalogues (~20,000 SKUs) efficiently. Validates rows,
 * transliterates missing Sinhala and Tamil names, scores duplicates against existing items
 * and within sheet, and executes batch inserts via JDBC within one transactional boundary.
 */
@Service
@CommandHandler(permission = "cat.import.run")
public class BulkImportSkusHandler implements Handles<BulkImportSkus, ImportReport> {

    public static final String AUDIT_BULK_IMPORTED = "SKUS_BULK_IMPORTED";
    private static final int BATCH_SIZE = 1000;

    private record PreparedSku(
            UUID skuId,
            String skuCode,
            UUID ownerEntityId,
            String nameEn,
            String nameSi,
            String nameTa,
            String descEn,
            String descSi,
            String descTa,
            String baseUom,
            boolean soldByWeight,
            boolean batchTracked,
            boolean expiryTracked,
            boolean hasPrintedMrp,
            Short expiryWarningDays,
            UUID taxCategoryId,
            List<String> barcodes) {}

    private record PreparedBarcode(String barcode, UUID skuId, String uomCode, UUID ownerEntityId) {}

    private record PreparedSuspect(
            UUID suspectId, UUID skuA, UUID skuB, BigDecimal score, List<String> reasons, UUID ownerEntityId) {}

    private final JdbcTemplate jdbc;
    private final RowValidator validator;
    private final Transliterator transliterator;
    private final DuplicateScorer scorer;
    private final SkuCodeGenerator codeGenerator;
    private final AuditFacade audit;
    private final EventPublisher events;

    public BulkImportSkusHandler(
            JdbcTemplate jdbc,
            RowValidator validator,
            Transliterator transliterator,
            DuplicateScorer scorer,
            SkuCodeGenerator codeGenerator,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.transliterator = transliterator;
        this.scorer = scorer;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public ImportReport handle(BulkImportSkus command, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m2.sku.scope_required");
        }
        UUID ownerEntityId = scope.entityId();

        List<ImportSkuRow> rows = (command == null || command.rows() == null) ? List.of() : command.rows();
        if (rows.isEmpty()) {
            return new ImportReport(
                    ImportReport.STATUS_REJECTED,
                    0,
                    0,
                    0,
                    List.of(new ValidationError(0, "rows", "bulk.sku.empty_payload", "Payload contains no rows", "")),
                    List.of());
        }

        String mode = (command.mode() != null && command.mode().equalsIgnoreCase(BulkImportSkus.MODE_COMMIT))
                ? BulkImportSkus.MODE_COMMIT
                : BulkImportSkus.MODE_VALIDATE;

        // 1. Preload reference data (UOMs, tax categories, existing SKU codes and barcodes)
        ValidationContext valCtx = loadValidationContext(ownerEntityId);

        // 2. Validate all rows
        List<ValidationError> errors = validator.validateBatch(rows, valCtx);

        // 3. Complete missing Sinhala/Tamil translations via Transliterator
        List<ImportSkuRow> enrichedRows = new ArrayList<>(rows.size());
        for (ImportSkuRow r : rows) {
            TransliterationResult tr = transliterator.transliterateMissing(r.nameEn(), r.nameSi(), r.nameTa());
            enrichedRows.add(new ImportSkuRow(
                    r.rowNumber(),
                    r.skuCode(),
                    tr.nameEn(),
                    tr.nameSi(),
                    tr.nameTa(),
                    r.descriptionEn(),
                    r.descriptionSi(),
                    r.descriptionTa(),
                    r.baseUomCode(),
                    r.soldByWeight(),
                    r.batchTracked(),
                    r.expiryTracked(),
                    r.hasPrintedMrp(),
                    r.expiryWarningDays(),
                    r.taxCategoryCode(),
                    r.price(),
                    r.barcodes(),
                    r.brand(),
                    r.tags(),
                    r.attributes()));
        }

        // 4. Duplicate scoring
        List<ItemProfile> candidateProfiles = new ArrayList<>(enrichedRows.size());
        for (ImportSkuRow r : enrichedRows) {
            candidateProfiles.add(new ItemProfile(
                    null, r.skuCode(), r.nameEn(), r.nameSi(), r.nameTa(), r.baseUomCode(), r.brand(), r.barcodes()));
        }

        List<SuspectMatch> sheetSuspects =
                scorer.scoreWithinBatch(candidateProfiles, DuplicateScorer.DEFAULT_THRESHOLD);
        List<ItemProfile> existingDbProfiles = loadExistingProfiles(ownerEntityId);
        List<SuspectMatch> dbSuspects =
                scorer.scoreAgainstExisting(candidateProfiles, existingDbProfiles, DuplicateScorer.DEFAULT_THRESHOLD);

        List<DuplicateSuspectItem> reportSuspects = new ArrayList<>();
        for (SuspectMatch m : sheetSuspects) {
            reportSuspects.add(new DuplicateSuspectItem(
                    0,
                    m.candidate().nameEn(),
                    null,
                    m.matched().skuCode(),
                    m.matched().nameEn(),
                    m.score(),
                    m.reasons()));
        }
        for (SuspectMatch m : dbSuspects) {
            reportSuspects.add(new DuplicateSuspectItem(
                    0,
                    m.candidate().nameEn(),
                    m.matched().skuId(),
                    m.matched().skuCode(),
                    m.matched().nameEn(),
                    m.score(),
                    m.reasons()));
        }

        // If in VALIDATE mode, return report without saving
        if (BulkImportSkus.MODE_VALIDATE.equals(mode)) {
            int accepted = errors.isEmpty() ? enrichedRows.size() : 0;
            String status = errors.isEmpty() ? ImportReport.STATUS_VALIDATED : ImportReport.STATUS_REJECTED;
            return new ImportReport(status, enrichedRows.size(), accepted, errors.size(), errors, reportSuspects);
        }

        // If there are validation errors, reject commit
        if (!errors.isEmpty()) {
            return new ImportReport(
                    ImportReport.STATUS_REJECTED, enrichedRows.size(), 0, enrichedRows.size(), errors, reportSuspects);
        }

        // 5. COMMIT Mode: Prepare batch data and execute optimized JDBC batch inserts
        Set<String> generatedCodes = new HashSet<>(valCtx.existingSkuCodes());
        List<PreparedSku> preparedSkus = new ArrayList<>(enrichedRows.size());
        List<PreparedBarcode> preparedBarcodes = new ArrayList<>();
        Map<String, UUID> skuIdByCode = new HashMap<>();

        for (ImportSkuRow r : enrichedRows) {
            UUID skuId = Ids.next();
            String code = r.skuCode();
            if (code == null || code.isBlank()) {
                code = generateUniqueSkuCode(generatedCodes);
            }
            generatedCodes.add(code.toLowerCase(Locale.ROOT));
            skuIdByCode.put(code, skuId);

            String uom = r.baseUomCode().strip().toUpperCase(Locale.ROOT);
            String taxCode = r.taxCategoryCode().strip().toUpperCase(Locale.ROOT);
            UUID taxId = valCtx.taxCategoriesByCode().get(taxCode);

            preparedSkus.add(new PreparedSku(
                    skuId,
                    code,
                    ownerEntityId,
                    r.nameEn().strip(),
                    r.nameSi(),
                    r.nameTa(),
                    r.descriptionEn(),
                    r.descriptionSi(),
                    r.descriptionTa(),
                    uom,
                    Boolean.TRUE.equals(r.soldByWeight()),
                    Boolean.TRUE.equals(r.batchTracked()),
                    Boolean.TRUE.equals(r.expiryTracked()),
                    Boolean.TRUE.equals(r.hasPrintedMrp()),
                    r.expiryWarningDays(),
                    taxId,
                    r.barcodes()));

            if (r.barcodes() != null) {
                for (String b : r.barcodes()) {
                    if (b != null && !b.isBlank()) {
                        preparedBarcodes.add(new PreparedBarcode(b.strip(), skuId, uom, ownerEntityId));
                    }
                }
            }
        }

        // Prepare suspects against existing SKUs for database registration
        List<PreparedSuspect> preparedSuspects = new ArrayList<>();
        for (SuspectMatch m : dbSuspects) {
            UUID candId = skuIdByCode.get(m.candidate().skuCode());
            UUID existId = m.matched().skuId();
            if (candId != null && existId != null) {
                UUID a = candId.compareTo(existId) < 0 ? candId : existId;
                UUID b = candId.compareTo(existId) < 0 ? existId : candId;
                preparedSuspects.add(new PreparedSuspect(Ids.next(), a, b, m.score(), m.reasons(), ownerEntityId));
            }
        }

        // Execute batch inserts
        batchInsertSkus(preparedSkus);
        batchInsertBarcodes(preparedBarcodes);
        batchInsertSuspects(preparedSuspects);

        // 6. Audit & Domain Events
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entityId", ownerEntityId);
        summary.put("skusCount", preparedSkus.size());
        summary.put("barcodesCount", preparedBarcodes.size());
        summary.put("suspectsCount", preparedSuspects.size());

        audit.record(AUDIT_BULK_IMPORTED, Subject.of("entity", ownerEntityId), null, summary, scope);
        events.publish(new SkusBulkImported(
                ownerEntityId,
                preparedSkus.size(),
                preparedSkus.size(),
                preparedSuspects.size(),
                BulkImportSkus.MODE_COMMIT));

        for (PreparedSuspect s : preparedSuspects) {
            events.publish(
                    new DuplicateSuspected(s.suspectId(), s.skuA(), s.skuB(), s.score(), s.reasons(), ownerEntityId));
        }

        return new ImportReport(
                ImportReport.STATUS_ACCEPTED, preparedSkus.size(), preparedSkus.size(), 0, List.of(), reportSuspects);
    }

    private void batchInsertSkus(List<PreparedSku> skus) {
        if (skus.isEmpty()) return;
        String sql =
                """
                INSERT INTO catalogue.sku (
                    sku_id, sku_code, owner_entity_id, status,
                    short_name_en, short_name_si, short_name_ta,
                    description_en, description_si, description_ta,
                    base_uom_code, sold_by_weight, batch_tracked, expiry_tracked,
                    has_printed_mrp, expiry_warning_days, tax_category_id,
                    multi_mrp_policy, origin_kind, attributes
                ) VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO_LOWEST', 'PURCHASED', ?::jsonb)
                """;

        for (int i = 0; i < skus.size(); i += BATCH_SIZE) {
            List<PreparedSku> batch = skus.subList(i, Math.min(skus.size(), i + BATCH_SIZE));
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    PreparedSku s = batch.get(idx);
                    ps.setObject(1, s.skuId());
                    ps.setString(2, s.skuCode());
                    ps.setObject(3, s.ownerEntityId());
                    ps.setString(4, s.nameEn());
                    ps.setString(5, s.nameSi());
                    ps.setString(6, s.nameTa());
                    ps.setString(7, s.descEn());
                    ps.setString(8, s.descSi());
                    ps.setString(9, s.descTa());
                    ps.setString(10, s.baseUom());
                    ps.setBoolean(11, s.soldByWeight());
                    ps.setBoolean(12, s.batchTracked());
                    ps.setBoolean(13, s.expiryTracked());
                    ps.setBoolean(14, s.hasPrintedMrp());
                    if (s.expiryWarningDays() != null) ps.setShort(15, s.expiryWarningDays());
                    else ps.setNull(15, Types.SMALLINT);
                    ps.setObject(16, s.taxCategoryId());
                    ps.setString(17, "{}");
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
        }
    }

    private void batchInsertBarcodes(List<PreparedBarcode> barcodes) {
        if (barcodes.isEmpty()) return;
        String sql =
                """
                INSERT INTO catalogue.sku_barcode (
                    barcode, symbology, sku_id, uom_code, owner_entity_id, status
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (barcode, symbology, owner_entity_id) DO NOTHING
                """;

        for (int i = 0; i < barcodes.size(); i += BATCH_SIZE) {
            List<PreparedBarcode> batch = barcodes.subList(i, Math.min(barcodes.size(), i + BATCH_SIZE));
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    PreparedBarcode b = batch.get(idx);
                    ps.setString(1, b.barcode());
                    ps.setString(2, resolveSymbology(b.barcode()));
                    ps.setObject(3, b.skuId());
                    ps.setString(4, b.uomCode());
                    ps.setObject(5, b.ownerEntityId());
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
        }
    }

    private static String resolveSymbology(String barcode) {
        if (barcode == null) return "INTERNAL";
        int len = barcode.length();
        if (len == 13 && barcode.chars().allMatch(Character::isDigit)) return "EAN13";
        if (len == 8 && barcode.chars().allMatch(Character::isDigit)) return "EAN8";
        if (len == 12 && barcode.chars().allMatch(Character::isDigit)) return "UPCA";
        if (len == 14 && barcode.chars().allMatch(Character::isDigit)) return "GS1_128";
        return "INTERNAL";
    }

    private void batchInsertSuspects(List<PreparedSuspect> suspects) {
        if (suspects.isEmpty()) return;
        String sql =
                """
                INSERT INTO catalogue.duplicate_suspect (
                    suspect_id, sku_a, sku_b, score, reasons, status, owner_entity_id
                ) VALUES (?, ?, ?, ?, ?, 'OPEN', ?)
                ON CONFLICT (sku_a, sku_b) DO NOTHING
                """;

        for (int i = 0; i < suspects.size(); i += BATCH_SIZE) {
            List<PreparedSuspect> batch = suspects.subList(i, Math.min(suspects.size(), i + BATCH_SIZE));
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    PreparedSuspect s = batch.get(idx);
                    ps.setObject(1, s.suspectId());
                    ps.setObject(2, s.skuA());
                    ps.setObject(3, s.skuB());
                    ps.setBigDecimal(4, s.score());
                    Array reasonsArray =
                            ps.getConnection().createArrayOf("text", s.reasons().toArray());
                    ps.setArray(5, reasonsArray);
                    ps.setObject(6, s.ownerEntityId());
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
        }
    }

    private String generateUniqueSkuCode(Set<String> generatedCodes) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = codeGenerator.next();
            if (!generatedCodes.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private ValidationContext loadValidationContext(UUID ownerEntityId) {
        List<String> uoms = jdbc.query("SELECT uom_code FROM catalogue.uom", (rs, rowNum) -> rs.getString("uom_code"));
        Set<String> uomSet = new HashSet<>();
        for (String u : uoms) uomSet.add(u.toUpperCase(Locale.ROOT));

        Map<String, UUID> taxMap = new HashMap<>();
        jdbc.query("SELECT tax_category_id, code FROM catalogue.tax_category", rs -> {
            taxMap.put(rs.getString("code").toUpperCase(Locale.ROOT), (UUID) rs.getObject("tax_category_id"));
        });

        Set<String> existingCodes = new HashSet<>(jdbc.query(
                "SELECT lower(sku_code) FROM catalogue.sku WHERE owner_entity_id = ? OR status = 'SHARED'",
                (rs, rowNum) -> rs.getString(1),
                ownerEntityId));

        Set<String> existingBarcodes = new HashSet<>(jdbc.query(
                "SELECT barcode FROM catalogue.sku_barcode WHERE owner_entity_id = ? OR status = 'ACTIVE'",
                (rs, rowNum) -> rs.getString(1),
                ownerEntityId));

        return new ValidationContext(uomSet, taxMap, existingCodes, existingBarcodes);
    }

    private List<ItemProfile> loadExistingProfiles(UUID ownerEntityId) {
        return jdbc.query(
                """
                SELECT s.sku_id, s.sku_code, s.short_name_en, s.short_name_si, s.short_name_ta, s.base_uom_code,
                       array_agg(b.barcode) FILTER (WHERE b.barcode IS NOT NULL) AS barcodes
                FROM catalogue.sku s
                LEFT JOIN catalogue.sku_barcode b ON b.sku_id = s.sku_id AND b.status = 'ACTIVE'
                WHERE s.owner_entity_id = ? OR s.status = 'SHARED'
                GROUP BY s.sku_id, s.sku_code, s.short_name_en, s.short_name_si, s.short_name_ta, s.base_uom_code
                LIMIT 5000
                """,
                ps -> ps.setObject(1, ownerEntityId),
                (rs, rowNum) -> {
                    UUID id = (UUID) rs.getObject("sku_id");
                    String code = rs.getString("sku_code");
                    String en = rs.getString("short_name_en");
                    String si = rs.getString("short_name_si");
                    String ta = rs.getString("short_name_ta");
                    String uom = rs.getString("base_uom_code");
                    Array bArr = rs.getArray("barcodes");
                    List<String> bList = List.of();
                    if (bArr != null) {
                        String[] arr = (String[]) bArr.getArray();
                        bList = List.of(arr);
                    }
                    return new ItemProfile(id, code, en, si, ta, uom, null, bList);
                });
    }
}
