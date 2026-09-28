package lk.coopfed.knoweb.m7customers.internal.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.AmendCustomer;
import lk.coopfed.knoweb.m7customers.api.CustomerUpdated;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AmendCustomer (27A section 6, UpdateAttributes / TagCustomer, with the names and language).
 * Guards, in order: the society's OWN scope; the customer registered by the caller's society
 * ("owner MPCS": another society that holds an account reads the identity but does not change it)
 * and ACTIVE; a name; a known language; tags and attributes well formed and within size.
 *
 * <p>Mutation: the names, language and attributes; the society's tags made the given list (a
 * removed tag keeps its row with removed_at, nothing is deleted). Audit CUSTOMER_UPDATED with the
 * language, attributes' keys and tags before and after (never a name); event customer.updated.v1.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
class AmendCustomerHandler implements Handles<AmendCustomer, UUID> {

    static final String AUDIT_UPDATED = "CUSTOMER_UPDATED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    AmendCustomerHandler(
            JdbcTemplate jdbc, ObjectMapper json, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AmendCustomer command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        UUID customerId = command.customerId();
        if (!"ACTIVE".equals(CustomerGuards.customerStatus(jdbc, customerId))) {
            throw new ProblemException("m7.customer.not_active");
        }
        String name = CustomerGuards.requiredText(command.displayName(), "displayName");
        String language = CustomerGuards.language(command.language());
        List<String> tags = CustomerGuards.tags(command.tags());
        String attributes = CustomerGuards.attributesJson(json, command.attributes());

        Map<String, Object> before = state(customerId, scope);
        jdbc.update(
                """
                update customers.customer
                   set display_name = ?, display_name_si = ?, display_name_ta = ?, language = ?, attributes = ?::jsonb
                 where customer_id = ?
                """,
                name,
                CustomerGuards.blankToNull(command.displayNameSi()),
                CustomerGuards.blankToNull(command.displayNameTa()),
                language,
                attributes,
                customerId);
        Timestamp now = Timestamp.from(clock.now());
        @SuppressWarnings("unchecked")
        List<String> current = (List<String>) before.get("tags");
        for (String tag : current) {
            if (!tags.contains(tag)) {
                jdbc.update(
                        """
                        update customers.customer_tag set removed_at = ?
                         where customer_id = ? and tag_code = ? and owner_entity_id = ?
                        """,
                        now,
                        customerId,
                        tag,
                        scope.entityId());
            }
        }
        for (String tag : tags) {
            if (!current.contains(tag)) {
                jdbc.update(
                        """
                        insert into customers.customer_tag (customer_id, tag_code, owner_entity_id, tagged_at)
                        values (?, ?, ?, ?)
                        on conflict (customer_id, tag_code, owner_entity_id)
                        do update set removed_at = null, tagged_at = excluded.tagged_at
                        """,
                        customerId,
                        tag,
                        scope.entityId(),
                        now);
            }
        }

        audit.record(AUDIT_UPDATED, Subject.of("customer", customerId), before, state(customerId, scope), scope);
        events.publish(new CustomerUpdated(customerId, scope.entityId()));
        return customerId;
    }

    /** What the audit record shows of a customer: never a name, a phone or the NIC. */
    private Map<String, Object> state(UUID customerId, ScopeContext scope) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(
                "language",
                jdbc.queryForObject(
                        "select language from customers.customer where customer_id = ?", String.class, customerId));
        state.put(
                "attributeKeys",
                new ArrayList<>(jdbc.queryForList(
                        "select jsonb_object_keys(attributes) from customers.customer where customer_id = ? order by 1",
                        String.class,
                        customerId)));
        state.put(
                "tags",
                new ArrayList<>(jdbc.queryForList(
                        """
                select tag_code from customers.customer_tag
                 where customer_id = ? and owner_entity_id = ? and removed_at is null order by tag_code
                """,
                        String.class,
                        customerId,
                        scope.entityId())));
        return state;
    }
}
