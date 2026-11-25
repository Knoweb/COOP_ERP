package lk.coopfed.knoweb.m2catalogue.internal.department;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.DefineDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.api.DepartmentCategoryDefined;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defines a new merchandise department category in catalogue.department_category (PGL-M2-08).
 * Only the Federation may define governed department categories (cat.department.manage).
 */
@Service
@CommandHandler(permission = "cat.department.manage")
public class DefineDepartmentCategoryHandler implements Handles<DefineDepartmentCategory, UUID> {

    public static final String AUDIT_DEFINED = "DEPARTMENT_CATEGORY_DEFINED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final FederationCaller federation;

    public DefineDepartmentCategoryHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, FederationCaller federation) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.federation = federation;
    }

    @Override
    @Transactional
    public UUID handle(DefineDepartmentCategory command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        federation.require(scope);

        if (command.code() == null || command.code().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "code"));
        }
        if (command.nameEn() == null || command.nameEn().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "nameEn"));
        }

        String code = command.code().strip().toUpperCase();
        if (code.length() > 32) {
            throw new ProblemException("request.field.too_long", Map.of("field", "code", "max", 32));
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
        int displayOrder = command.displayOrder() == null ? 0 : command.displayOrder();

        Integer taken = jdbc.queryForObject(
                "select count(*) from catalogue.department_category where code = ?", Integer.class, code);
        if (taken != null && taken > 0) {
            throw new ProblemException("m2.department.code_exists", Map.of("code", code));
        }

        UUID departmentId = Ids.next();
        jdbc.update(
                """
                insert into catalogue.department_category
                    (department_id, code, name_en, name_si, name_ta, description, display_order, status, owner_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NULL)
                """,
                departmentId,
                code,
                nameEn,
                nameSi,
                nameTa,
                description,
                displayOrder);

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
        after.put("status", "ACTIVE");

        audit.record(
                AUDIT_DEFINED,
                Subject.of("department_category", departmentId),
                null,
                Collections.unmodifiableMap(after),
                scope);

        events.publish(new DepartmentCategoryDefined(departmentId, code, nameEn, nameSi, nameTa, "ACTIVE"));

        return departmentId;
    }
}
