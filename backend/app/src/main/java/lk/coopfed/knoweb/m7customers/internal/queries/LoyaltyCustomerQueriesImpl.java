package lk.coopfed.knoweb.m7customers.internal.queries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.internal.customer.NicHasher;
import lk.coopfed.knoweb.m7customers.internal.customer.NicNumbers;
import lk.coopfed.knoweb.m7customers.internal.customer.PhoneNumbers;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyEligibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of LoyaltyCustomerQueries protected by Row Level Security (PGL-M3-01).
 * Resolves centralized loyalty identities and evaluates branch eligibility.
 */
@Service
@Transactional(readOnly = true)
class LoyaltyCustomerQueriesImpl implements LoyaltyCustomerQueries {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final NicHasher nicHasher;
    private final ObjectMapper json;

    LoyaltyCustomerQueriesImpl(JdbcTemplate jdbc, NicHasher nicHasher, ObjectMapper json) {
        this.jdbc = jdbc;
        this.nicHasher = nicHasher;
        this.json = json;
    }

    @Override
    public Optional<LoyaltyCustomerView> lookupByPhone(String phone, ScopeContext scope) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        Optional<String> e164 = PhoneNumbers.normalise(phone);
        if (e164.isEmpty()) {
            return Optional.empty();
        }
        List<LoyaltyCustomerView> list = jdbc.query(
                baseSelect() + " WHERE ph.phone = ? ORDER BY lc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs),
                e164.get());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<LoyaltyCustomerView> lookupByMemberNo(String memberNo, ScopeContext scope) {
        if (memberNo == null || memberNo.isBlank()) {
            return Optional.empty();
        }
        String canonical = memberNo.strip();
        List<LoyaltyCustomerView> list = jdbc.query(
                baseSelect() + " WHERE lc.regional_member_no = ? ORDER BY lc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs),
                canonical);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<LoyaltyCustomerView> lookupByNic(String nic, ScopeContext scope) {
        if (nic == null || nic.isBlank()) {
            return Optional.empty();
        }
        Optional<String> canonical = NicNumbers.canonical(nic);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        List<String> candidates = nicHasher.candidates(canonical.get());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        String[] candidateArray = candidates.toArray(String[]::new);
        List<LoyaltyCustomerView> list = jdbc.query(
                baseSelect() + " WHERE c.nic_hash = ANY (cast(? as char(64)[])) ORDER BY lc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs),
                (Object) candidateArray);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<LoyaltyCustomerView> getLoyaltyCustomer(UUID customerId, ScopeContext scope) {
        if (customerId == null) {
            return Optional.empty();
        }
        List<LoyaltyCustomerView> list =
                jdbc.query(baseSelect() + " WHERE lc.customer_id = ? LIMIT 1", (rs, rowNum) -> mapRow(rs), customerId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public LoyaltyEligibility checkEligibility(UUID customerId, UUID locationId, ScopeContext scope) {
        if (customerId == null) {
            return LoyaltyEligibility.notFound(locationId);
        }
        Optional<LoyaltyCustomerView> view = getLoyaltyCustomer(customerId, scope);
        if (view.isEmpty()) {
            return LoyaltyEligibility.notFound(locationId);
        }
        LoyaltyCustomerView customer = view.get();
        if (!"ACTIVE".equalsIgnoreCase(customer.status())) {
            return LoyaltyEligibility.inactive(customer, locationId);
        }
        if (locationId != null) {
            boolean allowed = customer.allLocationsPermitted()
                    || locationId.equals(customer.registeredLocationId())
                    || customer.permittedLocationIds().contains(locationId);
            if (!allowed) {
                return LoyaltyEligibility.locationNotPermitted(customer, locationId);
            }
        }
        return LoyaltyEligibility.eligible(customer, locationId);
    }

    @Override
    public LoyaltyEligibility checkEligibility(String identifier, UUID locationId, ScopeContext scope) {
        if (identifier == null || identifier.isBlank()) {
            return LoyaltyEligibility.notFound(locationId);
        }
        String text = identifier.strip();
        try {
            UUID cid = UUID.fromString(text);
            LoyaltyEligibility res = checkEligibility(cid, locationId, scope);
            if (!"NOT_FOUND".equals(res.status())) {
                return res;
            }
        } catch (IllegalArgumentException ignored) {
        }

        Optional<LoyaltyCustomerView> byMember = lookupByMemberNo(text, scope);
        if (byMember.isPresent()) {
            return checkEligibility(byMember.get().customerId(), locationId, scope);
        }

        Optional<LoyaltyCustomerView> byPhone = lookupByPhone(text, scope);
        if (byPhone.isPresent()) {
            return checkEligibility(byPhone.get().customerId(), locationId, scope);
        }

        Optional<LoyaltyCustomerView> byNic = lookupByNic(text, scope);
        if (byNic.isPresent()) {
            return checkEligibility(byNic.get().customerId(), locationId, scope);
        }

        return LoyaltyEligibility.notFound(locationId);
    }

    @Override
    public List<LoyaltyCustomerView> listByRegion(String regionCode, ScopeContext scope) {
        if (regionCode == null || regionCode.isBlank()) {
            return List.of();
        }
        return jdbc.query(
                baseSelect() + " WHERE lc.region_code = ? ORDER BY lc.created_at DESC",
                (rs, rowNum) -> mapRow(rs),
                regionCode.strip());
    }

    private String baseSelect() {
        return """
            SELECT lc.loyalty_id, lc.customer_id, lc.owner_entity_id,
                   c.display_name, c.display_name_si, c.display_name_ta,
                   ph.phone, c.nic_last4,
                   lc.registered_location_id, lc.region_code, lc.regional_member_no,
                   lc.loyalty_tier, lc.status, lc.all_locations_permitted,
                   lc.regional_attributes, lc.enrolled_at, lc.updated_at
              FROM customers.loyalty_customer lc
              JOIN customers.customer c ON c.customer_id = lc.customer_id
              LEFT JOIN customers.customer_phone ph ON ph.customer_id = c.customer_id
                   AND ph.is_primary = true AND ph.valid_to IS NULL
            """;
    }

    private LoyaltyCustomerView mapRow(ResultSet rs) throws SQLException {
        UUID loyaltyId = rs.getObject("loyalty_id", UUID.class);
        Set<UUID> permittedLocations = loadPermittedLocations(loyaltyId);
        Map<String, String> attributes = parseAttributes(rs.getString("regional_attributes"));

        return new LoyaltyCustomerView(
                loyaltyId,
                rs.getObject("customer_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("display_name_si"),
                rs.getString("display_name_ta"),
                rs.getString("phone"),
                rs.getString("nic_last4"),
                rs.getObject("registered_location_id", UUID.class),
                rs.getString("region_code"),
                rs.getString("regional_member_no"),
                rs.getString("loyalty_tier"),
                rs.getString("status"),
                rs.getBoolean("all_locations_permitted"),
                permittedLocations,
                attributes,
                toInstant(rs.getTimestamp("enrolled_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Set<UUID> loadPermittedLocations(UUID loyaltyId) {
        List<UUID> locs = jdbc.query(
                "SELECT permitted_location_id FROM customers.loyalty_permitted_location WHERE loyalty_id = ? AND revoked_at IS NULL",
                (rs, rowNum) -> rs.getObject("permitted_location_id", UUID.class),
                loyaltyId);
        return new HashSet<>(locs);
    }

    private Map<String, String> parseAttributes(String jsonText) {
        if (jsonText == null || jsonText.isBlank() || "{}".equals(jsonText.trim())) {
            return Collections.emptyMap();
        }
        try {
            return json.readValue(jsonText, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
