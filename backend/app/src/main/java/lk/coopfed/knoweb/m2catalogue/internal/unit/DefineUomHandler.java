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
import lk.coopfed.knoweb.m2catalogue.api.DefineUom;
import lk.coopfed.knoweb.m2catalogue.api.UomDefined;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defines a new unit of measure in catalogue.uom (PGL-M2-04).
 * Only the Federation may define units of measure (cat.uom.manage).
 */
@Service
@CommandHandler(permission = "cat.uom.manage")
public class DefineUomHandler implements Handles<DefineUom, String> {

    public static final String AUDIT_DEFINED = "UOM_DEFINED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final FederationCaller federation;

    public DefineUomHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, FederationCaller federation) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.federation = federation;
    }

    @Override
    @Transactional
    public String handle(DefineUom command, ScopeContext scope) {
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
        if (code.length() > 10) {
            throw new ProblemException("request.field.too_long", Map.of("field", "uomCode", "max", 10));
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
        boolean weight = command.weight();

        Integer taken =
                jdbc.queryForObject("select count(*) from catalogue.uom where uom_code = ?", Integer.class, code);
        if (taken != null && taken > 0) {
            throw new ProblemException("m2.uom.code_exists", Map.of("uomCode", code));
        }

        jdbc.update(
                """
                insert into catalogue.uom
                    (uom_code, name_en, name_si, name_ta, description, is_weight, status)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """,
                code,
                nameEn,
                nameSi,
                nameTa,
                description,
                weight);

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
        after.put("isWeight", weight);
        after.put("status", "ACTIVE");

        UUID uomId = UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8));
        audit.record(AUDIT_DEFINED, Subject.of("uom", uomId), null, Collections.unmodifiableMap(after), scope);

        events.publish(new UomDefined(uomId, code, nameEn, nameSi, nameTa, weight, "ACTIVE"));

        return code;
    }
}
