package lk.coopfed.knoweb.m2catalogue.internal.unit;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.UomUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateUom;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates an existing unit of measure in catalogue.uom (PGL-M2-04).
 * Allows modifying names, description, weight flag, and status (ACTIVE / INACTIVE).
 * Only the Federation may update units of measure (cat.uom.manage).
 */
@Service
@CommandHandler(permission = "cat.uom.manage")
public class UpdateUomHandler implements Handles<UpdateUom, Void> {

    public static final String AUDIT_UPDATED = "UOM_UPDATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final FederationCaller federation;

    public UpdateUomHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, FederationCaller federation) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.federation = federation;
    }

    @Override
    @Transactional
    public Void handle(UpdateUom command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        federation.require(scope);

        if (command.uomCode() == null || command.uomCode().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "uomCode"));
        }
        if (command.nameEn() == null || command.nameEn().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "nameEn"));
        }

        String code = command.uomCode().strip().toUpperCase();

        Map<String, Object> existing = jdbc.query(
                """
                select uom_code, name_en, name_si, name_ta, description, is_weight, status
                from catalogue.uom
                where uom_code = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("uomCode", rs.getString("uom_code"));
                    map.put("nameEn", rs.getString("name_en"));
                    map.put("nameSi", rs.getString("name_si"));
                    map.put("nameTa", rs.getString("name_ta"));
                    map.put("description", rs.getString("description"));
                    map.put("isWeight", rs.getBoolean("is_weight"));
                    map.put("status", rs.getString("status"));
                    return map;
                },
                code);

        if (existing == null) {
            throw new ProblemException("m2.uom.not_found", Map.of("uomCode", code));
        }

        String status = command.status() == null || command.status().isBlank()
                ? (String) existing.get("status")
                : command.status().strip().toUpperCase();

        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new ProblemException("request.field.invalid", Map.of("field", "status"));
        }

        boolean currentWeight = Boolean.TRUE.equals(existing.get("isWeight"));
        boolean newWeight = command.weight();

        if (newWeight != currentWeight) {
            Integer skuCount = jdbc.queryForObject(
                    "select count(*) from catalogue.sku where base_uom_code = ?", Integer.class, code);
            Integer conversionCount = jdbc.queryForObject(
                    "select count(*) from catalogue.sku_uom_conversion where uom_code = ?", Integer.class, code);
            if ((skuCount != null && skuCount > 0) || (conversionCount != null && conversionCount > 0)) {
                throw new ProblemException("m2.uom.in_use_cannot_change_weight", Map.of("uomCode", code));
            }
        }

        String nameEn = command.nameEn().strip();
        String nameSi = command.nameSi() == null
                ? (String) existing.get("nameSi")
                : (command.nameSi().isBlank() ? null : command.nameSi().strip());
        String nameTa = command.nameTa() == null
                ? (String) existing.get("nameTa")
                : (command.nameTa().isBlank() ? null : command.nameTa().strip());
        String description = command.description() == null
                ? (String) existing.get("description")
                : (command.description().isBlank()
                        ? null
                        : command.description().strip());

        jdbc.update(
                """
                update catalogue.uom
                set name_en = ?, name_si = ?, name_ta = ?, description = ?, is_weight = ?, status = ?
                where uom_code = ?
                """,
                nameEn,
                nameSi,
                nameTa,
                description,
                newWeight,
                status,
                code);

        Map<String, Object> before = Collections.unmodifiableMap(existing);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("uomCode", code);
        after.put("nameEn", nameEn);
        if (nameSi != null) {
            after.put("nameSi", nameSi);
        }
        if (nameTa != null) {
            after.put("nameTa", nameTa);
        }
        if (description != null) {
            after.put("description", description);
        }
        after.put("isWeight", newWeight);
        after.put("status", status);

        UUID uomId = UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8));
        audit.record(AUDIT_UPDATED, Subject.of("uom", uomId), before, Collections.unmodifiableMap(after), scope);

        events.publish(new UomUpdated(code, nameEn, nameSi, nameTa, newWeight, status));

        return null;
    }
}
