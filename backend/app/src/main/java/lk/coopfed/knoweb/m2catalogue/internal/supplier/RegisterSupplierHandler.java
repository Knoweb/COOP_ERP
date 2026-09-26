package lk.coopfed.knoweb.m2catalogue.internal.supplier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.RegisterSupplier;
import lk.coopfed.knoweb.m2catalogue.api.SupplierRegistered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegisterSupplier (22A section 6; doc 22 section 3.9): the owner is the caller's entity, in an
 * entity-wide OWN scope; a name, unique within the entity (the table's key). Mutation: one ACTIVE
 * supplier row. The minimal table until M10 takes it over.
 */
@Service
@CommandHandler(permission = "cat.supplier.manage")
public class RegisterSupplierHandler implements Handles<RegisterSupplier, UUID> {

    static final String AUDIT_REGISTERED = "SUPPLIER_REGISTERED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterSupplierHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterSupplier command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.locationId() != null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "name"));
        }
        String name = command.name().strip();

        Integer taken = jdbc.queryForObject(
                "select count(*) from catalogue.supplier where owner_entity_id = ? and name = ?",
                Integer.class,
                scope.entityId(),
                name);
        if (taken != null && taken > 0) {
            throw new ProblemException("m2.supplier.name_taken");
        }

        UUID supplierId = Ids.next();
        jdbc.update(
                "insert into catalogue.supplier (supplier_id, owner_entity_id, name) values (?, ?, ?)",
                supplierId,
                scope.entityId(),
                name);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("supplierId", supplierId);
        after.put("ownerEntityId", scope.entityId());
        after.put("name", name);
        after.put("status", "ACTIVE");

        audit.record(
                AUDIT_REGISTERED, Subject.of("supplier", supplierId), null, Collections.unmodifiableMap(after), scope);

        events.publish(new SupplierRegistered(supplierId, scope.entityId()));

        return supplierId;
    }
}
