package lk.coopfed.knoweb.m2catalogue.internal.department;

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
import lk.coopfed.knoweb.m2catalogue.api.DepartmentCategoryUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates an existing merchandise department category in catalogue.department_category (PGL-M2-08).
 * Allows modifying names, description, display order, and status (ACTIVE / INACTIVE).
 * Only the Federation may update governed department categories (cat.department.manage).
 */
@Service
@CommandHandler(permission = "cat.department.manage")
public class UpdateDepartmentCategoryHandler implements Handles<UpdateDepartmentCategory, Void> {

    public static final String AUDIT_UPDATED = "DEPARTMENT_CATEGORY_UPDATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final FederationCaller federation;

    public UpdateDepartmentCategoryHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, FederationCaller federation) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.federation = federation;
    }

    @Override
    @Transactional
    public Void handle(UpdateDepartmentCategory command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        federation.require(scope);

        if (command.departmentId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "departmentId"));
        }
        if (command.nameEn() == null || command.nameEn().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "nameEn"));
        }

        UUID departmentId = command.departmentId();

        Map<String, Object> existing = jdbc.query(
                """
                        select code, name_en, name_si, name_ta, description, display_order, status
                        from catalogue.department_category
                        where department_id = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("code", rs.getString("code"));
                    map.put("nameEn", rs.getString("name_en"));
                    map.put("nameSi", rs.getString("name_si"));
                    map.put("nameTa", rs.getString("name_ta"));
                    map.put("description", rs.getString("description"));
                    map.put("displayOrder", rs.getInt("display_order"));
                    map.put("status", rs.getString("status"));
                    return map;
                },
                departmentId);

        if (existing == null) {
            throw new ProblemException("m2.department.not_found", Map.of("departmentId", departmentId));
        }

        String status = command.status() == null || command.status().isBlank()
                ? (String) existing.get("status")
                : command.status().strip().toUpperCase();

        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new ProblemException("request.field.invalid", Map.of("field", "status"));
        }

        String nameEn = command.nameEn().strip();
        String nameSi = (command.nameSi() == null || command.nameSi().isBlank())
                ? null
                : command.nameSi().strip();
        String nameTa = (command.nameTa() == null || command.nameTa().isBlank())
                ? null
                : command.nameTa().strip();
        String description =
                (command.description() == null || command.description().isBlank())
                        ? null
                        : command.description().strip();
        int displayOrder =
                command.displayOrder() == null ? (Integer) existing.get("displayOrder") : command.displayOrder();

        String code = (String) existing.get("code");

        jdbc.update(
                """
                update catalogue.department_category
                   set name_en = ?, name_si = ?, name_ta = ?, description = ?, display_order = ?, status = ?
                 where department_id = ?
                """,
                nameEn,
                nameSi,
                nameTa,
                description,
                displayOrder,
                status,
                departmentId);

        Map<String, Object> before = new LinkedHashMap<>(existing);
        before.put("departmentId", departmentId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("departmentId", departmentId);
        after.put("code", code);
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
        after.put("displayOrder", displayOrder);
        after.put("status", status);

        audit.record(
                AUDIT_UPDATED,
                Subject.of("department_category", departmentId),
                Collections.unmodifiableMap(before),
                Collections.unmodifiableMap(after),
                scope);

        events.publish(new DepartmentCategoryUpdated(departmentId, code, nameEn, nameSi, nameTa, status));

        return null;
    }
}
