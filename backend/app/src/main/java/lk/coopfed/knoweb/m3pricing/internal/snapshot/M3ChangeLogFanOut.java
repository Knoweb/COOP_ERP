package lk.coopfed.knoweb.m3pricing.internal.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceRescinded;
import lk.coopfed.knoweb.m3pricing.api.MrpPolicyChanged;
import lk.coopfed.knoweb.m3pricing.api.PriceListPublished;
import lk.coopfed.knoweb.m3pricing.api.RuleActivated;
import lk.coopfed.knoweb.m3pricing.api.RuleWithdrawn;
import lk.coopfed.knoweb.m3pricing.internal.list.Federation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Change-log producer for M3 Pricing & Rules (23A section 7.2; doc 32 section 5.2).
 *
 * <p>Listens to domain events for price lists, discount rules, control prices, and MRP policies,
 * and records delta rows into {@code kernel.change_log} for the appropriate shops:
 * <ul>
 *   <li>RETAIL price list publication fans out to active shops of the publishing MPCS society.
 *       New lines are upserted with {@code apply_from}; superseded previous version lines are deleted with {@code apply_from}.
 *   <li>Discount rule activation fans out to active shops of the society with {@code apply_from = valid_from}.
 *   <li>Discount rule withdrawal fans out urgently with {@code Change.delete} to immediately cease applying promotions.
 *   <li>Control price entry and rescission fan out urgently across the entire federation.
 *   <li>MRP policy overrides fan out to society shops and tombstone any superseded Federation default.
 * </ul>
 */
@Component
public class M3ChangeLogFanOut {

    static final String CONSUMER = "m3.snapshot";
    static final String PRICE_LIST_LINE = PricingSnapshotContributor.PRICE_LIST_LINE;
    static final String DISCOUNT_RULE = PricingSnapshotContributor.DISCOUNT_RULE;
    static final String CONTROL_PRICE = PricingSnapshotContributor.CONTROL_PRICE;
    static final String MRP_POLICY = PricingSnapshotContributor.MRP_POLICY;
    static final String KIND_RETAIL = "RETAIL";

    private final ChangeLog changeLog;
    private final PricingShops shops;
    private final Federation federation;
    private final JdbcTemplate jdbc;

    public M3ChangeLogFanOut(ChangeLog changeLog, PricingShops shops, Federation federation, JdbcTemplate jdbc) {
        this.changeLog = changeLog;
        this.shops = shops;
        this.federation = federation;
        this.jdbc = jdbc;
    }

    // ---- Price List Publication ---------------------------------------------------------------

    @EventConsumer(
            types = {PriceListPublished.TYPE},
            consumer = CONSUMER)
    public void onPriceListPublished(JsonNode payload, ScopeContext scope) {
        String kind = str(payload, "kind");
        if (!KIND_RETAIL.equalsIgnoreCase(kind)) {
            // TRADE and ADVISORY lists do not resolve retail till prices and do not fan out
            return;
        }

        UUID priceListId = uuid(payload, "priceListId");
        UUID rootPriceListId = uuid(payload, "rootPriceListId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        LocalDate applyFrom = date(payload, "applyFrom");
        if (priceListId == null || ownerEntityId == null) {
            throw new IllegalArgumentException("PriceListPublished event missing required ids");
        }

        List<Target> targets = shops.of(ownerEntityId, scope);
        if (targets.isEmpty()) {
            return;
        }

        List<Change> changes = new ArrayList<>();
        // 1. Upsert new lines with apply_from
        List<UUID> newLines = jdbc.queryForList(
                "select line_id from pricing.price_list_line where price_list_id = ?", UUID.class, priceListId);
        for (UUID lineId : newLines) {
            changes.add(Change.upsert(PRICE_LIST_LINE, lineId));
        }

        int version = payload.path("version").asInt(1);

        // 2. Delete lines of the previous version of this list
        if (rootPriceListId != null) {
            List<UUID> oldLines = jdbc.queryForList(
                    """
                    select pll.line_id
                      from pricing.price_list_line pll
                      join (
                          select price_list_id
                            from pricing.price_list
                           where root_price_list_id = ?
                             and version < ?
                             and status = 'SUPERSEDED'
                           order by version desc
                           limit 1
                      ) prev on prev.price_list_id = pll.price_list_id
                    """,
                    UUID.class,
                    rootPriceListId,
                    version);
            for (UUID oldLineId : oldLines) {
                changes.add(Change.delete(PRICE_LIST_LINE, oldLineId));
            }
        }

        if (!changes.isEmpty()) {
            changeLog.append(targets, changes, applyFrom, false, scope);
        }
    }

    // ---- Discount Rules -----------------------------------------------------------------------

    @EventConsumer(
            types = {RuleActivated.TYPE},
            consumer = CONSUMER)
    public void onRuleActivated(JsonNode payload, ScopeContext scope) {
        UUID ruleId = uuid(payload, "ruleId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        LocalDate validFrom = date(payload, "validFrom");
        if (ruleId == null || ownerEntityId == null) {
            throw new IllegalArgumentException("RuleActivated event missing required ids");
        }

        List<Target> targets = shops.of(ownerEntityId, scope);
        if (!targets.isEmpty()) {
            changeLog.append(targets, List.of(Change.upsert(DISCOUNT_RULE, ruleId)), validFrom, false, scope);
        }
    }

    @EventConsumer(
            types = {RuleWithdrawn.TYPE},
            consumer = CONSUMER)
    public void onRuleWithdrawn(JsonNode payload, ScopeContext scope) {
        UUID ruleId = uuid(payload, "ruleId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        if (ruleId == null || ownerEntityId == null) {
            throw new IllegalArgumentException("RuleWithdrawn event missing required ids");
        }

        List<Target> targets = shops.of(ownerEntityId, scope);
        if (!targets.isEmpty()) {
            // Urgent delete: ceases applying promotion immediately
            changeLog.append(targets, List.of(Change.delete(DISCOUNT_RULE, ruleId)), null, true, scope);
        }
    }

    // ---- Control Prices -----------------------------------------------------------------------

    @EventConsumer(
            types = {ControlPriceEntered.TYPE},
            consumer = CONSUMER)
    public void onControlPriceEntered(JsonNode payload, ScopeContext scope) {
        UUID controlPriceId = uuid(payload, "controlPriceId");
        UUID closedControlPriceId = uuid(payload, "closedControlPriceId");
        LocalDate effectiveFrom = date(payload, "effectiveFrom");
        if (controlPriceId == null) {
            throw new IllegalArgumentException("ControlPriceEntered event missing controlPriceId");
        }

        List<Target> targets = shops.all(scope);
        if (targets.isEmpty()) {
            return;
        }

        List<Change> changes = new ArrayList<>();
        changes.add(Change.upsert(CONTROL_PRICE, controlPriceId));
        if (closedControlPriceId != null) {
            changes.add(Change.upsert(CONTROL_PRICE, closedControlPriceId));
        }

        // Urgent: legal ceiling entered downloads on next heartbeat (doc 32 §5.2)
        changeLog.append(targets, changes, effectiveFrom, true, scope);
    }

    @EventConsumer(
            types = {ControlPriceRescinded.TYPE},
            consumer = CONSUMER)
    public void onControlPriceRescinded(JsonNode payload, ScopeContext scope) {
        UUID controlPriceId = uuid(payload, "controlPriceId");
        if (controlPriceId == null) {
            throw new IllegalArgumentException("ControlPriceRescinded event missing controlPriceId");
        }

        List<Target> targets = shops.all(scope);
        if (!targets.isEmpty()) {
            // Urgent: ceiling rescinded (with updated effective_to = lastDay)
            changeLog.append(targets, List.of(Change.upsert(CONTROL_PRICE, controlPriceId)), null, true, scope);
        }
    }

    // ---- MRP Policy ---------------------------------------------------------------------------

    @EventConsumer(
            types = {MrpPolicyChanged.TYPE},
            consumer = CONSUMER)
    public void onMrpPolicyChanged(JsonNode payload, ScopeContext scope) {
        UUID policyId = uuid(payload, "policyId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        UUID skuId = uuid(payload, "skuId");
        if (policyId == null || ownerEntityId == null || skuId == null) {
            throw new IllegalArgumentException("MrpPolicyChanged event missing required fields");
        }

        boolean isFed = federation.entityId().map(f -> f.equals(ownerEntityId)).orElse(false);
        if (isFed) {
            // Federation default applies to all shops across the federation
            List<Target> targets = shops.all(scope);
            if (!targets.isEmpty()) {
                changeLog.append(targets, List.of(Change.upsert(MRP_POLICY, policyId)), null, false, scope);
            }
        } else {
            // MPCS override applies to its own shops
            List<Target> targets = shops.of(ownerEntityId, scope);
            if (!targets.isEmpty()) {
                List<Change> changes = new ArrayList<>();
                changes.add(Change.upsert(MRP_POLICY, policyId));

                // If Federation had a default policy for this SKU, delete it from this MPCS's shops
                federation.entityId().ifPresent(fedId -> {
                    List<UUID> fedPolicyIds = jdbc.queryForList(
                            "select policy_id from pricing.mrp_policy where sku_id = ? and owner_entity_id = ?",
                            UUID.class,
                            skuId,
                            fedId);
                    for (UUID fedPolicyId : fedPolicyIds) {
                        changes.add(Change.delete(MRP_POLICY, fedPolicyId));
                    }
                });

                changeLog.append(targets, changes, null, false, scope);
            }
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : UUID.fromString(node.asText());
    }

    private static String str(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static LocalDate date(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : LocalDate.parse(node.asText());
    }
}
