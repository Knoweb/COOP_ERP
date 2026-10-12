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
import lk.coopfed.knoweb.m1party.query.ApprovalLimit;
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
    static final String BAND1_LIMIT = ApprovalLimit.BAND1_LIMIT;
    static final String BAND2_LIMIT = "inventory.approval_band2_limit";
    static final String SINGLE_STAFF = "inventory.single_staff";
    static final String REMOTE_WITNESS_ALLOWED = "inventory.remote_witness_allowed";
    static final String PHOTO_CATEGORIES = "inventory.writeoff_photo_categories";
    static final String TOLERANCE_VALUE = "inventory.count_tolerance_value";
    static final String AUTOPOST_VALUE_CAP = "inventory.count_autopost_value_cap";
    static final String DISPATCH_MIN_SHELF_LIFE_DAYS = "inventory.dispatch_min_shelf_life_days";
    static final String REPACK_YIELD_TOLERANCE_PCT = "inventory.repack_yield_tolerance_pct";

    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

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
     * Whether a count variance is within the quantity tolerance (F-10): within the quantity, or
     * within the percentage of what the book expected, whichever is wider. Since wave 2 (M5-14) a
     * line must also be within {@link #withinValueTolerance}; {@code SubmitCountHandler} asks both.
     */
    public boolean withinTolerance(BigDecimal variance, BigDecimal expected, ScopeContext scope) {
        BigDecimal size = variance.abs();
        BigDecimal qty = decimal(TOLERANCE_QTY, scope, "2");
        BigDecimal pct = decimal(TOLERANCE_PCT, scope, "1");
        BigDecimal byPct = expected.abs().multiply(pct).divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        return size.compareTo(qty) <= 0 || size.compareTo(byPct) <= 0;
    }

    /**
     * Whether a count variance's value posts without approval (wave 2, M5-14; decided 6 October
     * 2026, {@code 2026-10-06-wave2-stock-approvals.md} (5)): at most {@code
     * inventory.count_tolerance_value} (Rs 1,000), so two units of an expensive item wait for a
     * second person even inside the quantity tolerance.
     */
    public boolean withinValueTolerance(BigDecimal value, ScopeContext scope) {
        return value.compareTo(decimal(TOLERANCE_VALUE, scope, "1000")) <= 0;
    }

    /** The most one count may post without approval ({@code inventory.count_autopost_value_cap}, Rs 10,000). */
    public BigDecimal autopostValueCap(ScopeContext scope) {
        return decimal(AUTOPOST_VALUE_CAP, scope, "10000");
    }

    /** The approval band of a value (E-07): 1 up to the first limit, 2 up to the second, 3 above (the chairman). */
    public int band(BigDecimal value, ScopeContext scope) {
        if (value.compareTo(decimal(BAND1_LIMIT, scope, "25000")) <= 0) {
            return 1;
        }
        return value.compareTo(decimal(BAND2_LIMIT, scope, "250000")) <= 0 ? 2 : 3;
    }

    /**
     * The band a decision routes to: the band of its value, and at least band 2 when a line has no
     * cost at all (free bonus goods, samples), so a value of zero is never approved within band 1
     * whatever the quantity (wave 2, M5-09).
     */
    public int band(BigDecimal value, boolean zeroCostLine, ScopeContext scope) {
        int band = band(value, scope);
        return zeroCostLine ? Math.max(band, 2) : band;
    }

    /**
     * The approver's own limit on the permission (25A section 3.1: "limits max_value"; doc 25
     * section 7: "writeoff_bands as role limits (M1)"), which <b>fails closed</b> since wave 2
     * (M5-09; decided 6 October 2026, {@code 2026-10-06-wave2-stock-approvals.md} (1)):
     * <ul>
     *   <li>the limit is the highest {@code max_value} of the permission over the approver's role
     *       assignments at the decision's entity that are entity-wide or at the decision's location
     *       (wave 3, M1M2M3M5-18: a limit granted at shop A does not approve at shop B, as the M1
     *       resolver reads it);
     *   <li>a grant without {@code max_value} (a seeded template, a role from before the permission
     *       took a limits schema) approves up to {@code inventory.approval_band1_limit} only; M1
     *       reads the same meaning when it compares a grant with the grantor's ({@link
     *       ApprovalLimit}; wave 3, M1M2M3M5-01);
     *   <li>no grant at the entity is no authority: the limit is zero;
     *   <li>a decision with a line of no cost needs more than the band-1 limit (band 2).
     * </ul>
     * Refuses {@code m5.approval.limit_exceeded} when the value is above the limit: the next band's
     * approver must act (doc 25 flow 6.3).
     *
     * @param locationId the location of the stock the decision moves
     */
    public void requireWithinLimit(
            BigDecimal value, boolean zeroCostLine, String permission, UUID locationId, ScopeContext scope) {
        BigDecimal band1 = ApprovalLimit.band1(config, scope);
        BigDecimal limit = BigDecimal.ZERO;
        for (SecurityQueries.AssignmentView assignment : security.listAssignments(scope.userId(), scope)) {
            if (!scope.entityId().equals(assignment.scopeEntityId())) {
                continue;
            }
            if (assignment.scopeLocationId() != null
                    && !assignment.scopeLocationId().equals(locationId)) {
                continue;
            }
            Optional<SecurityQueries.RoleView> role = security.getRole(assignment.roleId(), scope);
            for (SecurityQueries.RolePermissionView p :
                    role.map(SecurityQueries.RoleView::permissions).orElse(List.of())) {
                if (!permission.equals(p.permissionCode())) {
                    continue;
                }
                limit = limit.max(ApprovalLimit.of(p.limits(), band1));
            }
        }
        BigDecimal required = zeroCostLine ? value.max(band1.add(ONE_CENT)) : value;
        if (required.compareTo(limit) > 0 || limit.signum() == 0) {
            throw new ProblemException(
                    "m5.approval.limit_exceeded",
                    Map.of("value", value.toPlainString(), "limit", limit.toPlainString()));
        }
    }

    /**
     * The fewest days of shelf life a lot must have left to leave on a delivery note to another
     * entity ({@code inventory.dispatch_min_shelf_life_days}, the seller's; 0 by default: expiry
     * alone). Transfers inside the entity use expiry alone (wave 2, M5-01 (6)).
     */
    public int dispatchMinShelfLifeDays(ScopeContext scope) {
        return config.getInt(DISPATCH_MIN_SHELF_LIFE_DAYS, scope, 0);
    }

    /** How far, in per cent, a repack's output may stray from the recipe's ({@code inventory.repack_yield_tolerance_pct}, 2). */
    public BigDecimal repackYieldTolerancePct(ScopeContext scope) {
        return decimal(REPACK_YIELD_TOLERANCE_PCT, scope, "2");
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
