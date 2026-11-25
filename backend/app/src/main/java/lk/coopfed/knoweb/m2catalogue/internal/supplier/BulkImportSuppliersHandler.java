package lk.coopfed.knoweb.m2catalogue.internal.supplier;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BulkImportSuppliers;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportReport;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportReport.SupplierValidationError;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportRow;
import lk.coopfed.knoweb.m2catalogue.api.SuppliersBulkImported;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes or validates bulk supplier migration batches (PGL-M2-05).
 *
 * <p>Requirements:
 * <ul>
 *   <li>Permission: {@code cat.supplier.manage}</li>
 *   <li>Guards: In-sheet collision detection, tenant database conflict checks</li>
 *   <li>Dry-run (VALIDATE): Zero DB writes, zero audit logs, zero events published</li>
 *   <li>Idempotent re-import: Skips identical existing suppliers without creating duplicate rows or events</li>
 *   <li>Audit & Domain event on commit: Emits {@code SUPPLIERS_BULK_IMPORTED} and {@link SuppliersBulkImported}</li>
 * </ul>
 */
@Component
@CommandHandler(permission = "cat.supplier.manage")
public class BulkImportSuppliersHandler implements Handles<BulkImportSuppliers, SupplierImportReport> {

    public static final String AUDIT_BULK_IMPORTED = "SUPPLIERS_BULK_IMPORTED";
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_CODE_LENGTH = 32;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    private record ExistingSupplier(UUID supplierId, String name, String supplierCode, String status) {}

    private record PreparedSupplier(UUID supplierId, UUID ownerEntityId, String name, String supplierCode) {}

    private final JdbcTemplate jdbc;
    private final SupplierCsvParser csvParser;
    private final AuditFacade audit;
    private final EventPublisher events;

    public BulkImportSuppliersHandler(
            JdbcTemplate jdbc, SupplierCsvParser csvParser, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.csvParser = csvParser;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public SupplierImportReport handle(BulkImportSuppliers command, ScopeContext scope) {
        UUID ownerEntityId = scope.homeEntityId();
        if (ownerEntityId == null && scope.activeScope() != null) {
            ownerEntityId = scope.activeScope().entityId();
        }
        if (ownerEntityId == null) {
            throw new IllegalArgumentException("Scope must contain an entity");
        }

        List<SupplierImportRow> rows = command.rows();
        if ((rows == null || rows.isEmpty())
                && command.csvContent() != null
                && !command.csvContent().isBlank()) {
            try {
                rows = csvParser.parse(command.csvContent());
            } catch (Exception e) {
                return new SupplierImportReport(
                        SupplierImportReport.STATUS_REJECTED,
                        0,
                        0,
                        0,
                        1,
                        List.of(new SupplierValidationError(
                                1,
                                "csvContent",
                                "m2.supplier.csv_parse_failed",
                                "Failed to parse CSV: " + e.getMessage(),
                                "")));
            }
        }

        if (rows == null || rows.isEmpty()) {
            return new SupplierImportReport(
                    SupplierImportReport.STATUS_REJECTED,
                    0,
                    0,
                    0,
                    1,
                    List.of(new SupplierValidationError(
                            1,
                            "rows",
                            "m2.supplier.empty_payload",
                            "The supplier import file contains no data rows",
                            "")));
        }

        String mode = command.mode() != null && command.mode().equalsIgnoreCase(BulkImportSuppliers.MODE_COMMIT)
                ? BulkImportSuppliers.MODE_COMMIT
                : BulkImportSuppliers.MODE_VALIDATE;

        // 1. Load existing suppliers for caller's entity
        Map<String, ExistingSupplier> existingByName = new HashMap<>();
        Map<String, ExistingSupplier> existingByCode = new HashMap<>();
        jdbc.query(
                "SELECT supplier_id, name, supplier_code, status FROM catalogue.supplier WHERE owner_entity_id = ?",
                rs -> {
                    ExistingSupplier sup = new ExistingSupplier(
                            (UUID) rs.getObject("supplier_id"),
                            rs.getString("name"),
                            rs.getString("supplier_code"),
                            rs.getString("status"));
                    if (sup.name() != null) {
                        existingByName.put(normalizeKey(sup.name()), sup);
                    }
                    if (sup.supplierCode() != null) {
                        existingByCode.put(normalizeKey(sup.supplierCode()), sup);
                    }
                },
                ownerEntityId);

        // 2. Validate rows, tracking in-sheet duplicates and identical vs conflict statuses
        List<SupplierValidationError> errors = new ArrayList<>();
        Map<String, SupplierImportRow> sheetByCode = new HashMap<>();
        Map<String, SupplierImportRow> sheetByName = new HashMap<>();
        List<PreparedSupplier> newSuppliers = new ArrayList<>();
        int skippedCount = 0;

        for (SupplierImportRow row : rows) {
            int rowNum =
                    row.rowNumber() > 0 ? row.rowNumber() : (newSuppliers.size() + skippedCount + errors.size() + 1);
            String rawName = row.name();
            String rawCode = row.supplierCode();

            // Name validation
            if (rawName == null || rawName.isBlank()) {
                errors.add(new SupplierValidationError(
                        rowNum, "name", "m2.supplier.name_required", "Supplier name is required", ""));
                continue;
            }
            String name = rawName.strip();
            if (name.length() > MAX_NAME_LENGTH) {
                errors.add(new SupplierValidationError(
                        rowNum, "name", "m2.supplier.name_too_long", "Supplier name exceeds maximum length", name));
                continue;
            }

            // Code validation
            String code = (rawCode != null && !rawCode.isBlank()) ? rawCode.strip() : null;
            if (code != null) {
                if (code.length() > MAX_CODE_LENGTH
                        || !CODE_PATTERN.matcher(code).matches()) {
                    errors.add(new SupplierValidationError(
                            rowNum, "supplierCode", "m2.supplier.code_invalid", "Supplier code format invalid", code));
                    continue;
                }
            }

            String normName = normalizeKey(name);
            String normCode = code != null ? normalizeKey(code) : null;

            // In-sheet collision check
            boolean sheetConflict = false;
            if (normCode != null) {
                SupplierImportRow prevWithCode = sheetByCode.get(normCode);
                if (prevWithCode != null && !normalizeKey(prevWithCode.name()).equals(normName)) {
                    errors.add(new SupplierValidationError(
                            rowNum,
                            "supplierCode",
                            "m2.supplier.code_conflict_in_sheet",
                            "Supplier code " + code + " maps to multiple distinct names in sheet",
                            code));
                    sheetConflict = true;
                } else {
                    sheetByCode.put(normCode, row);
                }
            }

            SupplierImportRow prevWithName = sheetByName.get(normName);
            if (prevWithName != null) {
                String prevCode =
                        prevWithName.supplierCode() != null ? normalizeKey(prevWithName.supplierCode()) : null;
                if (normCode != null && prevCode != null && !normCode.equals(prevCode)) {
                    errors.add(new SupplierValidationError(
                            rowNum,
                            "name",
                            "m2.supplier.name_conflict_in_sheet",
                            "Supplier name " + name + " maps to multiple distinct codes in sheet",
                            name));
                    sheetConflict = true;
                }
            } else {
                sheetByName.put(normName, row);
            }

            if (sheetConflict) {
                continue;
            }

            // Database collision check
            boolean dbConflict = false;
            boolean isIdentical = false;

            ExistingSupplier matchByCode = normCode != null ? existingByCode.get(normCode) : null;
            ExistingSupplier matchByName = existingByName.get(normName);

            if (matchByCode != null) {
                if (normalizeKey(matchByCode.name()).equals(normName)) {
                    isIdentical = true;
                } else {
                    errors.add(new SupplierValidationError(
                            rowNum,
                            "supplierCode",
                            "m2.supplier.code_conflict",
                            "Supplier code " + code + " already belongs to existing supplier: " + matchByCode.name(),
                            code));
                    dbConflict = true;
                }
            }

            if (matchByName != null && !dbConflict) {
                if (matchByName.supplierCode() != null && normCode != null) {
                    if (normalizeKey(matchByName.supplierCode()).equals(normCode)) {
                        isIdentical = true;
                    } else {
                        errors.add(new SupplierValidationError(
                                rowNum,
                                "name",
                                "m2.supplier.name_conflict",
                                "Supplier name " + name + " already exists under different code: "
                                        + matchByName.supplierCode(),
                                name));
                        dbConflict = true;
                    }
                } else {
                    isIdentical = true;
                }
            }

            if (dbConflict) {
                continue;
            }

            if (isIdentical) {
                skippedCount++;
            } else {
                // New supplier to insert
                UUID newId = Ids.next();
                newSuppliers.add(new PreparedSupplier(newId, ownerEntityId, name, code));
                // Add to transient cache so later rows in same batch recognize it as identical
                ExistingSupplier transientSup = new ExistingSupplier(newId, name, code, "ACTIVE");
                existingByName.put(normName, transientSup);
                if (normCode != null) {
                    existingByCode.put(normCode, transientSup);
                }
            }
        }

        int totalCount = rows.size();
        int createdCount = newSuppliers.size();

        // 3. VALIDATE Mode
        if (BulkImportSuppliers.MODE_VALIDATE.equals(mode)) {
            String status =
                    errors.isEmpty() ? SupplierImportReport.STATUS_VALIDATED : SupplierImportReport.STATUS_REJECTED;
            return new SupplierImportReport(status, totalCount, createdCount, skippedCount, errors.size(), errors);
        }

        // 4. COMMIT Mode
        if (!errors.isEmpty()) {
            return new SupplierImportReport(
                    SupplierImportReport.STATUS_REJECTED, totalCount, 0, 0, errors.size(), errors);
        }

        if (!newSuppliers.isEmpty()) {
            batchInsertSuppliers(newSuppliers);
        }

        // 5. Audit & Domain Event (committed mode only)
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ownerEntityId", ownerEntityId.toString());
        summary.put("totalRows", totalCount);
        summary.put("createdCount", createdCount);
        summary.put("skippedCount", skippedCount);

        UUID importId = Ids.next();
        audit.record(AUDIT_BULK_IMPORTED, Subject.of("entity", ownerEntityId), null, summary, scope);
        if (createdCount > 0) {
            events.publish(new SuppliersBulkImported(
                    importId, ownerEntityId, totalCount, createdCount, skippedCount, BulkImportSuppliers.MODE_COMMIT));
        }

        return new SupplierImportReport(
                SupplierImportReport.STATUS_ACCEPTED, totalCount, createdCount, skippedCount, 0, List.of());
    }

    private void batchInsertSuppliers(List<PreparedSupplier> suppliers) {
        String sql =
                """
                INSERT INTO catalogue.supplier (
                    supplier_id, owner_entity_id, name, supplier_code, status
                ) VALUES (?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """;

        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PreparedSupplier s = suppliers.get(i);
                ps.setObject(1, s.supplierId());
                ps.setObject(2, s.ownerEntityId());
                ps.setString(3, s.name());
                ps.setString(4, s.supplierCode());
            }

            @Override
            public int getBatchSize() {
                return suppliers.size();
            }
        });
    }

    private static String normalizeKey(String val) {
        return val == null ? "" : val.strip().toLowerCase(Locale.ROOT);
    }
}
