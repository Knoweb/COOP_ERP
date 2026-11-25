package lk.coopfed.knoweb.m2catalogue.internal.tax;

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
import lk.coopfed.knoweb.m2catalogue.api.DefineTaxCategory;
import lk.coopfed.knoweb.m2catalogue.api.TaxCategoryDefined;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DefineTaxCategory (22A section 6; doc 22 section 3.6): defines a tax category in catalogue.tax_category.
 * Only the Federation may define tax categories (cat.tax.publish).
 */
@Service
@CommandHandler(permission = "cat.tax.publish")
public class DefineTaxCategoryHandler implements Handles<DefineTaxCategory, UUID> {

    static final String AUDIT_DEFINED = "TAX_CATEGORY_DEFINED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final FederationCaller federation;

    public DefineTaxCategoryHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, FederationCaller federation) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.federation = federation;
    }

    @Override
    @Transactional
    public UUID handle(DefineTaxCategory command, ScopeContext scope) {
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
        if (code.length() > 12) {
            throw new ProblemException("request.field.too_long", Map.of("field", "code", "max", 12));
        }

        String nameEn = command.nameEn().strip();
        String nameSi = (command.nameSi() == null || command.nameSi().isBlank())
                ? null
                : command.nameSi().strip();
        String nameTa = (command.nameTa() == null || command.nameTa().isBlank())
                ? null
                : command.nameTa().strip();

        Integer taken =
                jdbc.queryForObject("select count(*) from catalogue.tax_category where code = ?", Integer.class, code);
        if (taken != null && taken > 0) {
            throw new ProblemException("m2.tax.category_code_exists", Map.of("code", code));
        }

        UUID taxCategoryId = Ids.next();
        jdbc.update(
                """
                insert into catalogue.tax_category
                    (tax_category_id, code, name_en, name_si, name_ta, owner_entity_id)
                values (?, ?, ?, ?, ?, ?)
                """,
                taxCategoryId,
                code,
                nameEn,
                nameSi,
                nameTa,
                scope.entityId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("taxCategoryId", taxCategoryId);
        after.put("code", code);
        after.put("nameEn", nameEn);
        if (nameSi != null) {
            after.put("nameSi", nameSi);
        }
        if (nameTa != null) {
            after.put("nameTa", nameTa);
        }
        after.put("ownerEntityId", scope.entityId());

        audit.record(
                AUDIT_DEFINED,
                Subject.of("tax_category", taxCategoryId),
                null,
                Collections.unmodifiableMap(after),
                scope);

        events.publish(new TaxCategoryDefined(taxCategoryId, scope.entityId(), code));

        return taxCategoryId;
    }
}
