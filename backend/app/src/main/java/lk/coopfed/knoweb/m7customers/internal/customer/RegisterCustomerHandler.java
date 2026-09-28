package lk.coopfed.knoweb.m7customers.internal.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
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
import lk.coopfed.knoweb.m7customers.api.ConsentRecorded;
import lk.coopfed.knoweb.m7customers.api.CustomerRegistered;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegisterCustomer (27A section 6). Guards, in order: the society's OWN scope; a name; a known
 * language and channel; the phone a Sri Lankan number ({@code m7.customer.phone_invalid}); the
 * reuse detection of section 6.1 passed or confirmed; the CREDIT_ACCOUNT consent present (27A:
 * a customer is registered to buy on credit); tags and attributes well formed.
 *
 * <p>"Online only" (27A: the command is not offline_allowed) holds by construction: nothing but
 * the web calls this handler, and the till's registration is its web mode.
 *
 * <p>Mutation: the customer, its primary phone, one consent row per purpose, the tags. Audit
 * CUSTOMER_REGISTERED with no name, phone or NIC (the previous holders of a reused number by id
 * when the officer confirmed); events customer.registered.v1 and consent.recorded.v1 per consent.
 */
@Service
@CommandHandler(permission = "cus.customer.register")
class RegisterCustomerHandler implements Handles<RegisterCustomer, UUID> {

    static final String AUDIT_REGISTERED = "CUSTOMER_REGISTERED";
    static final String CREDIT_ACCOUNT = "CREDIT_ACCOUNT";
    static final Set<String> PURPOSES = Set.of(CREDIT_ACCOUNT, "STATEMENTS_NOTIFICATIONS");
    static final Set<String> CHANNELS = Set.of("TILL", "WEB", "PAPER");

    private final JdbcTemplate jdbc;
    private final ReuseDetector reuse;
    private final CustomersClock clock;
    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterCustomerHandler(
            JdbcTemplate jdbc,
            ReuseDetector reuse,
            CustomersClock clock,
            ObjectMapper json,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.reuse = reuse;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterCustomer command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        String name = CustomerGuards.requiredText(command.displayName(), "displayName");
        String language = CustomerGuards.language(command.language());
        String via = command.via() == null ? "WEB" : command.via();
        if (!CHANNELS.contains(via)) {
            throw new ProblemException("m7.customer.via_invalid", Map.of("via", via));
        }
        String phone = PhoneNumbers.normalise(command.phone())
                .orElseThrow(() -> new ProblemException("m7.customer.phone_invalid"));
        List<UUID> previousHolders = reuse.guard(phone, null, command.confirmedIdentity(), scope);
        List<String> consents = command.consents().stream().distinct().toList();
        for (String purpose : consents) {
            if (!PURPOSES.contains(purpose)) {
                throw new ProblemException("m7.customer.consent_invalid", Map.of("purpose", String.valueOf(purpose)));
            }
        }
        if (!consents.contains(CREDIT_ACCOUNT)) {
            throw new ProblemException("m7.customer.consent_required");
        }
        List<String> tags = CustomerGuards.tags(command.tags());
        CustomerGuards.attributes(command.attributes());

        UUID customerId = Ids.next();
        UUID society = scope.entityId();
        Timestamp now = Timestamp.from(clock.now());
        jdbc.update(
                """
                insert into customers.customer (customer_id, display_name, display_name_si, display_name_ta, language,
                    attributes, status, registered_by_entity_id, registered_at, owner_entity_id)
                values (?, ?, ?, ?, ?, ?::jsonb, 'ACTIVE', ?, ?, ?)
                """,
                customerId,
                name,
                CustomerGuards.blankToNull(command.displayNameSi()),
                CustomerGuards.blankToNull(command.displayNameTa()),
                language,
                toJson(command.attributes()),
                society,
                now,
                society);
        jdbc.update(
                """
                insert into customers.customer_phone (phone_id, customer_id, phone, is_primary, valid_from, changed_by,
                    reason, owner_entity_id)
                values (?, ?, ?, true, ?, ?, 'REGISTERED', ?)
                """,
                Ids.next(),
                customerId,
                phone,
                now,
                scope.userId(),
                society);
        for (String purpose : consents) {
            jdbc.update(
                    """
                    insert into customers.customer_consent (consent_id, customer_id, purpose, granted_at, granted_via,
                        entity_id, owner_entity_id)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    customerId,
                    purpose,
                    now,
                    via,
                    society,
                    society);
        }
        for (String tag : tags) {
            jdbc.update(
                    "insert into customers.customer_tag (customer_id, tag_code, owner_entity_id, tagged_at) values (?, ?, ?, ?)",
                    customerId,
                    tag,
                    society,
                    now);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("language", language);
        after.put("via", via);
        after.put("consents", consents);
        after.put("tags", tags);
        after.put("status", "ACTIVE");
        if (!previousHolders.isEmpty()) {
            after.put("previousHolders", previousHolders);
        }
        audit.record(AUDIT_REGISTERED, Subject.of("customer", customerId), null, after, scope);
        events.publish(new CustomerRegistered(customerId, society));
        for (String purpose : consents) {
            events.publish(new ConsentRecorded(customerId, society, purpose));
        }
        return customerId;
    }

    private String toJson(Map<String, String> attributes) {
        return CustomerGuards.attributesJson(json, attributes);
    }
}
