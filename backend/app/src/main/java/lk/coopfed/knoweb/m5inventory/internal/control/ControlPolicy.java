package lk.coopfed.knoweb.m5inventory.internal.control;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.SecurityQueries;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import org.springframework.stereotype.Component;

/**
 * The rules stock control shares (doc 25 section 7; 25A sections 3.1 and 6.3): the count
 * tolerance (F-10), the value bands of an adjustment or a write-off (E-07), the approver's own
 * limit, the single-staff location (DR-4) and the photographs a write-off needs. Every figure is a
 * configuration item of the register (seed/kernel/config-items.yaml), never a constant in code.
 *
 * <p>Accepted on the architect's delegation: the per-SKU and per-tag tables of 25A
 * (variance_threshold, loss_tolerance) are not built yet; the entity's configuration items give
 * the one tolerance and the two band limits for every item (the federation defaults of 25A
 * section 3.1: 1 % or 2 units; Rs 25,000 and Rs 250,000).
 */
@Component
public class ControlPolicy {

    static final String TOLERANCE_PCT = "inventory.count_tolerance_pct";
    static final String TOLERANCE_QTY = "inventory.count_tolerance_qty";
    static final String BAND1_LIMIT = "inventory.approval_band1_limit";
    static final String BAND2_LIMIT = "inventory.approval_band2_limit";
    static final String SINGLE_STAFF = "inventory.single_staff";
    static final String REMOTE_WITNESS_ALLOWED = "inventory.remote_witness_allowed";
    static final String PHOTO_CATEGORIES = "inventory.writeoff_photo_categories";

    private final ConfigRegistry config;
    private final PartyQueries party;
    private final SecurityQueries security;

    ControlPolicy(ConfigRegistry config, PartyQueries party, SecurityQueries security) {
        this.config = config;
        this.party = party;
        this.security = security;
    }

    /** An OWN scope with an entity, which every stock command needs ({@code m5.scope.own_required}). */
    public static void requireOwn(ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
    }

    /** A location of the scope entity that the scope reads (M1), or {@code m5.location.not_in_scope}. */
    public LocationView requireEntityLocation(UUID locationId, ScopeContext scope) {
        return Optional.ofNullable(locationId)
                .flatMap(id -> party.getLocation(id, scope))
                .filter(l -> scope.entityId().equals(l.ownerEntityId()))
                .orElseThrow(() -> new ProblemException(
                        "m5.location.not_in_scope", Map.of("locationId", String.valueOf(locationId))));
    }

    /**
     * Whether a count variance posts without approval (F-10): within the quantity tolerance, or
     * within the percentage of what the book expected, whichever is wider.
     */
    public boolean withinTolerance(BigDecimal variance, BigDecimal expected, ScopeContext scope) {
        BigDecimal size = variance.abs();
        BigDecimal qty = decimal(TOLERANCE_QTY, scope, "2");
        BigDecimal pct = decimal(TOLERANCE_PCT, scope, "1");
        BigDecimal byPct = expected.abs().multiply(pct).divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        return size.compareTo(qty) <= 0 || size.compareTo(byPct) <= 0;
    }

    /** The approval band of a value (E-07): 1 up to the first limit, 2 up to the second, 3 above (the chairman). */
    public int band(BigDecimal value, ScopeContext scope) {
        if (value.compareTo(decimal(BAND1_LIMIT, scope, "25000")) <= 0) {
            return 1;
        }
        return value.compareTo(decimal(BAND2_LIMIT, scope, "250000")) <= 0 ? 2 : 3;
    }

    /**
     * The approver's own limit on the permission (25A section 3.1: "limits max_value"), read from
     * the {@code max_value} of the permission on the approver's roles at the entity: the highest of
     * them. A grant without a limit is no limit. Refuses {@code m5.approval.limit_exceeded} when
     * the value is above it: the next band's approver must act (doc 25 flow 6.3).
     */
    public void requireWithinLimit(BigDecimal value, String permission, ScopeContext scope) {
        BigDecimal limit = null;
        boolean unlimited = false;
        for (SecurityQueries.AssignmentView assignment : security.listAssignments(scope.userId(), scope)) {
            if (!scope.entityId().equals(assignment.scopeEntityId())) {
                continue;
            }
            Optional<SecurityQueries.RoleView> role = security.getRole(assignment.roleId(), scope);
            for (SecurityQueries.RolePermissionView p :
                    role.map(SecurityQueries.RoleView::permissions).orElse(List.of())) {
                if (!permission.equals(p.permissionCode())) {
                    continue;
                }
                Object max = p.limits() == null ? null : p.limits().get("max_value");
                if (max == null) {
                    unlimited = true;
                } else {
                    BigDecimal found = new BigDecimal(max.toString());
                    limit = limit == null ? found : limit.max(found);
                }
            }
        }
        if (!unlimited && limit != null && value.compareTo(limit) > 0) {
            throw new ProblemException(
                    "m5.approval.limit_exceeded",
                    Map.of("value", value.toPlainString(), "limit", limit.toPlainString()));
        }
    }

    /**
     * A single-staff location (25A section 11: "location size band S or config flag"): nobody is
     * there to witness in person, so the witness may act remotely and photographs are required.
     */
    public boolean singleStaff(LocationView location, ScopeContext scope) {
        if ("S".equalsIgnoreCase(location.sizeBand())) {
            return true;
        }
        return config.getBoolean(SINGLE_STAFF, at(scope, location.locationId()), false);
    }

    public boolean remoteWitnessAllowed(ScopeContext scope) {
        return config.getBoolean(REMOTE_WITNESS_ALLOWED, scope, true);
    }

    /** Photographs are required for this category (THEFT, SHRINKAGE_UNEXPLAINED by default) or at a single-staff location. */
    public boolean photosRequired(LossCategory category, LocationView location, ScopeContext scope) {
        String categories = config.getOrDefault(PHOTO_CATEGORIES, scope, "[\"THEFT\", \"SHRINKAGE_UNEXPLAINED\"]");
        Set<String> named = Set.of(categories
                .replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .toUpperCase(Locale.ROOT)
                .split("\\s*,\\s*"));
        return named.contains(category.name()) || singleStaff(location, scope);
    }

    private BigDecimal decimal(String key, ScopeContext scope, String fallback) {
        return new BigDecimal(config.getOrDefault(key, scope, fallback).trim());
    }

    /** The same principal at one location of its entity, for a configuration value set per location. */
    private static ScopeContext at(ScopeContext scope, UUID locationId) {
        if (Objects.equals(scope.locationId(), locationId)) {
            return scope;
        }
        Scope located = new Scope(scope.entityId(), locationId);
        return new ScopeContext(
                scope.userId(),
                scope.deviceId(),
                scope.homeEntityId(),
                List.of(located),
                located,
                scope.policyClass(),
                scope.grantedEntities(),
                scope.mfaAt(),
                scope.locale(),
                scope.correlationId());
    }
}
