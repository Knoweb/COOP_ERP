package lk.coopfed.knoweb.m7customers.internal.loyalty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import lk.coopfed.knoweb.m7customers.api.LoyaltyCustomerEnrolled;
import lk.coopfed.knoweb.m7customers.api.RegisterLoyaltyCustomer;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.customer.NicCapture;
import lk.coopfed.knoweb.m7customers.internal.customer.PhoneNumbers;
import lk.coopfed.knoweb.m7customers.internal.customer.ReuseDetector;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles enrollment and regional registration of centralized loyalty customers (PGL-M3-01).
 * Strictly preserves owner and scope isolation without PII leakage in audit or events.
 */
@Service
@CommandHandler(permission = "cus.customer.register")
public class RegisterLoyaltyCustomerHandler implements Handles<RegisterLoyaltyCustomer, UUID> {

    public static final String AUDIT_LOYALTY_ENROLLED = "LOYALTY_CUSTOMER_ENROLLED";
    public static final Set<String> ALLOWED_TIERS = Set.of("STANDARD", "SILVER", "GOLD", "PLATINUM", "MEMBER");

    private final JdbcTemplate jdbc;
    private final ReuseDetector reuse;
    private final NicCapture nicCapture;
    private final CustomersClock clock;
    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;

    public RegisterLoyaltyCustomerHandler(
            JdbcTemplate jdbc,
            ReuseDetector reuse,
            NicCapture nicCapture,
            CustomersClock clock,
            ObjectMapper json,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.reuse = reuse;
        this.nicCapture = nicCapture;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterLoyaltyCustomer command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);

        UUID entityId = scope.entityId();
        UUID locationId = command.registeredLocationId();
        if (locationId == null && scope.locationId() != null) {
            locationId = scope.locationId();
        }
        if (locationId == null) {
            throw new ProblemException("m7.loyalty.location_required");
        }
        if (scope.locationId() != null && !scope.locationId().equals(locationId)) {
            throw new ProblemException("m7.scope.entity_required");
        }

        String regionCode = CustomerGuards.requiredText(command.regionCode(), "regionCode");
        String tier = command.loyaltyTier();
        if (!ALLOWED_TIERS.contains(tier)) {
            throw new ProblemException("m7.loyalty.tier_invalid", Map.of("tier", tier));
        }

        Instant nowInstant = clock.now();
        Timestamp now = Timestamp.from(nowInstant);
        UUID customerId = command.customerId();

        if (customerId != null) {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from customers.customer where customer_id = ? and owner_entity_id = ? and status = 'ACTIVE'",
                    Integer.class,
                    customerId,
                    entityId);
            if (exists == null || exists == 0) {
                throw new ProblemException("m7.customer.not_found");
            }
            Integer alreadyEnrolled = jdbc.queryForObject(
                    "select count(*) from customers.loyalty_customer where customer_id = ? and owner_entity_id = ?",
                    Integer.class,
                    customerId,
                    entityId);
            if (alreadyEnrolled != null && alreadyEnrolled > 0) {
                throw new ProblemException("m7.loyalty.already_enrolled");
            }
            if (command.nic() != null && !command.nic().isBlank()) {
                NicCapture.Captured captured = nicCapture.capture(command.nic(), customerId);
                jdbc.update(NicCapture.WRITE_SQL, captured.hash(), captured.last4(), captured.keyId(), customerId);
            }
        } else {
            String name = CustomerGuards.requiredText(command.displayName(), "displayName");
            String language = CustomerGuards.language(command.language());
            String phone = PhoneNumbers.normalise(command.phone())
                    .orElseThrow(() -> new ProblemException("m7.customer.phone_invalid"));

            reuse.guard(phone, null, true, scope);

            customerId = Ids.next();
            jdbc.update(
                    """
                    insert into customers.customer (customer_id, display_name, display_name_si, display_name_ta,
                        language, attributes, status, registered_by_entity_id, registered_at, owner_entity_id)
                    values (?, ?, ?, ?, ?, '{}'::jsonb, 'ACTIVE', ?, ?, ?)
                    """,
                    customerId,
                    name,
                    CustomerGuards.blankToNull(command.displayNameSi()),
                    CustomerGuards.blankToNull(command.displayNameTa()),
                    language,
                    entityId,
                    now,
                    entityId);

            jdbc.update(
                    """
                    insert into customers.customer_phone (phone_id, customer_id, phone, is_primary, valid_from,
                        changed_by, reason, owner_entity_id)
                    values (?, ?, ?, true, ?, ?, 'REGISTERED', ?)
                    """,
                    Ids.next(),
                    customerId,
                    phone,
                    now,
                    scope.userId(),
                    entityId);

            if (command.nic() != null && !command.nic().isBlank()) {
                NicCapture.Captured captured = nicCapture.capture(command.nic(), customerId);
                jdbc.update(NicCapture.WRITE_SQL, captured.hash(), captured.last4(), captured.keyId(), customerId);
            }
        }

        UUID loyaltyId = Ids.next();
        String attributesJson = toJson(command.regionalAttributes());

        jdbc.update(
                """
                insert into customers.loyalty_customer
                    (loyalty_id, customer_id, owner_entity_id, registered_location_id, region_code,
                     regional_member_no, loyalty_tier, status, all_locations_permitted, regional_attributes,
                     enrolled_at, enrolled_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?::jsonb, ?, ?, ?, ?)
                """,
                loyaltyId,
                customerId,
                entityId,
                locationId,
                regionCode,
                CustomerGuards.blankToNull(command.regionalMemberNo()),
                tier,
                command.allLocationsPermitted(),
                attributesJson,
                now,
                scope.userId(),
                now,
                now);

        if (!command.allLocationsPermitted()) {
            for (UUID loc : command.permittedLocationIds()) {
                jdbc.update(
                        """
                        insert into customers.loyalty_permitted_location
                            (loyalty_id, permitted_location_id, owner_entity_id, permitted_at, permitted_by)
                        values (?, ?, ?, ?, ?)
                        on conflict (loyalty_id, permitted_location_id) do update set revoked_at = null
                        """,
                        loyaltyId,
                        loc,
                        entityId,
                        now,
                        scope.userId());
            }
        }

        // Auditing strictly avoids PII (no phone, no name, no NIC)
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", customerId.toString());
        after.put("registeredLocationId", locationId.toString());
        after.put("regionCode", regionCode);
        if (command.regionalMemberNo() != null && !command.regionalMemberNo().isBlank()) {
            after.put("regionalMemberNo", command.regionalMemberNo().strip());
        }
        after.put("loyaltyTier", tier);
        after.put("allLocationsPermitted", command.allLocationsPermitted());
        after.put("permittedLocationsCount", command.permittedLocationIds().size());

        audit.record(AUDIT_LOYALTY_ENROLLED, Subject.of("loyalty_customer", loyaltyId), null, after, scope);

        events.publish(
                new LoyaltyCustomerEnrolled(loyaltyId, customerId, entityId, locationId, regionCode, tier, nowInstant));

        return loyaltyId;
    }

    private String toJson(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        try {
            return json.writeValueAsString(attributes);
        } catch (JsonProcessingException e) {
            throw new ProblemException("m7.customer.attributes_invalid");
        }
    }
}
