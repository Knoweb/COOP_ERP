package lk.coopfed.knoweb.m5inventory.internal.snapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.stereotype.Component;

/**
 * Snapshot contributor for inventory policy rules applicable to a shop location (doc 25A section 7.3,
 * doc 26 section 3.1, README Deviation 15).
 *
 * <p>Serves the {@code inventory_policy} snapshot table:
 * <ul>
 *   <li>The row ID is the shop's location ID.
 *   <li>Resolves location &gt; entity &gt; Federation &gt; default precedence via {@link ConfigRegistry}.
 *   <li>Delivers the 11 verified stock-control configuration items (count tolerances, auto-post caps,
 *       approval bands, single-staff status, photo categories, repack yield tolerance, dispatch shelf life).
 * </ul>
 */
@Component
public class PolicySnapshotContributor implements SnapshotContributor {

    public static final String TABLE = "inventory_policy";

    public static final String COUNT_TOLERANCE_PCT = "inventory.count_tolerance_pct";
    public static final String COUNT_TOLERANCE_QTY = "inventory.count_tolerance_qty";
    public static final String COUNT_TOLERANCE_VALUE = "inventory.count_tolerance_value";
    public static final String COUNT_AUTOPOST_VALUE_CAP = "inventory.count_autopost_value_cap";
    public static final String APPROVAL_BAND1_LIMIT = "inventory.approval_band1_limit";
    public static final String APPROVAL_BAND2_LIMIT = "inventory.approval_band2_limit";
    public static final String SINGLE_STAFF = "inventory.single_staff";
    public static final String REMOTE_WITNESS_ALLOWED = "inventory.remote_witness_allowed";
    public static final String WRITEOFF_PHOTO_CATEGORIES = "inventory.writeoff_photo_categories";
    public static final String REPACK_YIELD_TOLERANCE_PCT = "inventory.repack_yield_tolerance_pct";
    public static final String DISPATCH_MIN_SHELF_LIFE_DAYS = "inventory.dispatch_min_shelf_life_days";

    private final ConfigRegistry config;
    private final PartyQueries party;
    private final ObjectMapper json;

    PolicySnapshotContributor(ConfigRegistry config, PartyQueries party, ObjectMapper json) {
        this.config = config;
        this.party = party;
        this.json = json;
    }

    @Override
    public Set<String> tables() {
        return Set.of(TABLE);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (!TABLE.equals(table) || shop == null || rowIds == null || !rowIds.contains(shop.locationId())) {
            return Map.of();
        }
        return read(shop);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        if (!TABLE.equals(table) || shop == null) {
            return Map.of();
        }
        return read(shop);
    }

    private Map<UUID, Map<String, Object>> read(Shop shop) {
        Scope active = new Scope(shop.ownerEntityId(), shop.locationId());
        ScopeContext scope = new ScopeContext(
                null,
                null,
                shop.ownerEntityId(),
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);

        String countTolerancePct = config.getOrDefault(COUNT_TOLERANCE_PCT, scope, "1");
        String countToleranceQty = config.getOrDefault(COUNT_TOLERANCE_QTY, scope, "2");
        String countToleranceValue = config.getOrDefault(COUNT_TOLERANCE_VALUE, scope, "1000");
        String countAutopostValueCap = config.getOrDefault(COUNT_AUTOPOST_VALUE_CAP, scope, "10000");
        String approvalBand1Limit = config.getOrDefault(APPROVAL_BAND1_LIMIT, scope, "25000");
        String approvalBand2Limit = config.getOrDefault(APPROVAL_BAND2_LIMIT, scope, "250000");

        boolean singleStaff = party.getLocation(shop.locationId(), scope)
                        .map(loc -> "S".equalsIgnoreCase(loc.sizeBand()))
                        .orElse(false)
                || config.getBoolean(SINGLE_STAFF, scope, false);

        boolean remoteWitnessAllowed = config.getBoolean(REMOTE_WITNESS_ALLOWED, scope, true);

        String photoCategoriesStr =
                config.getOrDefault(WRITEOFF_PHOTO_CATEGORIES, scope, "[\"THEFT\", \"SHRINKAGE_UNEXPLAINED\"]");
        List<String> photoCategories;
        try {
            photoCategories = json.readValue(photoCategoriesStr, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            photoCategories = List.of("THEFT", "SHRINKAGE_UNEXPLAINED");
        }

        String repackYieldTolerancePct = config.getOrDefault(REPACK_YIELD_TOLERANCE_PCT, scope, "2");
        int dispatchMinShelfLifeDays = config.getInt(DISPATCH_MIN_SHELF_LIFE_DAYS, scope, 0);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("location_id", shop.locationId().toString());
        row.put("count_tolerance_pct", countTolerancePct);
        row.put("count_tolerance_qty", countToleranceQty);
        row.put("count_tolerance_value", countToleranceValue);
        row.put("count_autopost_value_cap", countAutopostValueCap);
        row.put("approval_band1_limit", approvalBand1Limit);
        row.put("approval_band2_limit", approvalBand2Limit);
        row.put("single_staff", singleStaff);
        row.put("remote_witness_allowed", remoteWitnessAllowed);
        row.put("writeoff_photo_categories", photoCategories);
        row.put("repack_yield_tolerance_pct", repackYieldTolerancePct);
        row.put("dispatch_min_shelf_life_days", dispatchMinShelfLifeDays);

        return Map.of(shop.locationId(), row);
    }
}
