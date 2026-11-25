package lk.coopfed.knoweb.m7customers.internal.loyalty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.RegionalRegistrationUpdated;
import lk.coopfed.knoweb.m7customers.api.UpdateRegionalRegistration;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles updates to regional registration attributes and allowed branch locations (PGL-M3-01).
 * Auditing and event payloads strictly omit PII.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
public class UpdateRegionalRegistrationHandler implements Handles<UpdateRegionalRegistration, Void> {

    public static final String AUDIT_REGIONAL_UPDATED = "REGIONAL_REGISTRATION_UPDATED";
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "SUSPENDED", "INACTIVE");

    private final JdbcTemplate jdbc;
    private final CustomersClock clock;
    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;

    public UpdateRegionalRegistrationHandler(
            JdbcTemplate jdbc, CustomersClock clock, ObjectMapper json, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(UpdateRegionalRegistration command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);

        UUID entityId = scope.entityId();
        UUID customerId = command.customerId();
        String regionCode = CustomerGuards.requiredText(command.regionCode(), "regionCode");
        UUID locationId = command.registeredLocationId();
        if (locationId == null) {
            throw new ProblemException("m7.loyalty.location_required");
        }

        String tier = command.loyaltyTier();
        if (!RegisterLoyaltyCustomerHandler.ALLOWED_TIERS.contains(tier)) {
            throw new ProblemException("m7.loyalty.tier_invalid", Map.of("tier", tier));
        }

        String status = command.status();
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new ProblemException("m7.account.status_invalid", Map.of("status", status));
        }

        List<Map<String, Object>> existing = jdbc.queryForList(
                """
                select loyalty_id, registered_location_id, region_code, regional_member_no,
                       loyalty_tier, status, all_locations_permitted
                  from customers.loyalty_customer
                 where customer_id = ? and owner_entity_id = ?
                """,
                customerId,
                entityId);

        if (existing.isEmpty()) {
            throw new ProblemException("m7.loyalty.not_found");
        }

        Map<String, Object> current = existing.get(0);
        UUID loyaltyId = (UUID) current.get("loyalty_id");
        Instant nowInstant = clock.now();
        Timestamp now = Timestamp.from(nowInstant);

        String attributesJson = toJson(command.regionalAttributes());

        jdbc.update(
                """
                update customers.loyalty_customer
                   set registered_location_id = ?,
                       region_code = ?,
                       regional_member_no = ?,
                       loyalty_tier = ?,
                       status = ?,
                       all_locations_permitted = ?,
                       regional_attributes = ?::jsonb,
                       updated_at = ?
                 where loyalty_id = ?
                """,
                locationId,
                regionCode,
                CustomerGuards.blankToNull(command.regionalMemberNo()),
                tier,
                status,
                command.allLocationsPermitted(),
                attributesJson,
                now,
                loyaltyId);

        if (!command.allLocationsPermitted()) {
            // Revoke active locations not in the requested set
            for (UUID loc : command.permittedLocationIds()) {
                jdbc.update(
                        """
                        insert into customers.loyalty_permitted_location
                            (loyalty_id, permitted_location_id, owner_entity_id, permitted_at, permitted_by, revoked_at)
                        values (?, ?, ?, ?, ?, null)
                        on conflict (loyalty_id, permitted_location_id) do update set revoked_at = null
                        """,
                        loyaltyId,
                        loc,
                        entityId,
                        now,
                        scope.userId());
            }
            if (!command.permittedLocationIds().isEmpty()) {
                jdbc.update(
                        """
                        update customers.loyalty_permitted_location
                           set revoked_at = ?
                         where loyalty_id = ? and revoked_at is null and permitted_location_id != ALL (?)
                        """,
                        now,
                        loyaltyId,
                        command.permittedLocationIds().toArray(UUID[]::new));
            } else {
                jdbc.update(
                        "update customers.loyalty_permitted_location set revoked_at = ? where loyalty_id = ? and revoked_at is null",
                        now,
                        loyaltyId);
            }
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("registeredLocationId", current.get("registered_location_id").toString());
        before.put("regionCode", current.get("region_code"));
        before.put("regionalMemberNo", current.get("regional_member_no"));
        before.put("loyaltyTier", current.get("loyalty_tier"));
        before.put("status", current.get("status"));
        before.put("allLocationsPermitted", current.get("all_locations_permitted"));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("registeredLocationId", locationId.toString());
        after.put("regionCode", regionCode);
        after.put("regionalMemberNo", command.regionalMemberNo());
        after.put("loyaltyTier", tier);
        after.put("status", status);
        after.put("allLocationsPermitted", command.allLocationsPermitted());
        after.put("permittedLocationsCount", command.permittedLocationIds().size());

        audit.record(AUDIT_REGIONAL_UPDATED, Subject.of("loyalty_customer", loyaltyId), before, after, scope);

        events.publish(new RegionalRegistrationUpdated(
                loyaltyId, customerId, entityId, locationId, regionCode, tier, status, nowInstant));

        return null;
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
