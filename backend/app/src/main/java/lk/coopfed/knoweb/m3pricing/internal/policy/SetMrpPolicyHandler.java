package lk.coopfed.knoweb.m3pricing.internal.policy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.MrpPolicyChanged;
import lk.coopfed.knoweb.m3pricing.api.SetMrpPolicy;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SetMrpPolicy (23A section 7; doc 23 section 3.4). Guards: the owner in an entity-wide OWN scope;
 * the SKU is active and visible to the caller; the policy is one of the three; the gap fields only
 * for PICKER (their bounds are the slice's). Mutation: the caller's row for the SKU is replaced, or
 * inserted when there is none; the audit record keeps what it was. Event: mrp_policy.changed.v1.
 *
 * <p>Not built (deferred): tag scope (Federation only in 23A; M2 publishes no governed-tag query);
 * M2's consumer that keeps sku.multi_mrp_policy (the effective value is computed by EffectivePolicy
 * instead); the snapshot rows (M3-09).
 */
@Service
@CommandHandler(permission = "prc.mrp_policy.set")
public class SetMrpPolicyHandler implements Handles<SetMrpPolicy, UUID> {

    static final String AUDIT_SET = "MRP_POLICY_SET";

    private static final Set<String> POLICIES = Set.of("AUTO_LOWEST", "BARCODE_RESOLVED", MrpPolicyStore.PICKER);
    private static final Set<String> ACTIVE = Set.of("LOCAL", "SHARED");

    private final JdbcTemplate jdbc;
    private final MrpPolicyStore store;
    private final CatalogueQueries catalogue;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    SetMrpPolicyHandler(
            JdbcTemplate jdbc,
            MrpPolicyStore store,
            CatalogueQueries catalogue,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.catalogue = catalogue;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(SetMrpPolicy command, ScopeContext scope) {
        if (command == null || command.skuId() == null || command.policy() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        Optional<SkuView> sku = catalogue.getSku(command.skuId(), scope);
        if (sku.isEmpty() || !ACTIVE.contains(sku.get().status())) {
            throw new ProblemException("m3.mrp_policy.sku_not_active");
        }
        if (!POLICIES.contains(command.policy())) {
            throw new ProblemException("m3.mrp_policy.policy_invalid", Map.of("policy", command.policy()));
        }
        boolean picker = MrpPolicyStore.PICKER.equals(command.policy());
        if (!picker && (command.gapAmount() != null || command.gapPercent() != null)) {
            throw new ProblemException("m3.mrp_policy.gap_not_allowed");
        }
        BigDecimal gapAmount = command.gapAmount();
        BigDecimal gapPercent = command.gapPercent();
        if ((gapAmount != null
                        && (gapAmount.signum() < 0
                                || gapAmount.stripTrailingZeros().scale() > 2))
                || (gapPercent != null
                        && (gapPercent.signum() < 0
                                || gapPercent.compareTo(BigDecimal.valueOf(100)) > 0
                                || gapPercent.stripTrailingZeros().scale() > 2))) {
            throw new ProblemException("m3.mrp_policy.gap_invalid");
        }

        Optional<MrpPolicyStore.Row> previous = store.forSku(command.skuId(), scope.entityId());
        Timestamp now = Timestamp.from(clock.instant());
        UUID policyId;
        if (previous.isPresent()) {
            policyId = previous.get().policyId();
            jdbc.update(
                    "update pricing.mrp_policy set policy = ?, picker_gap_amount = ?, picker_gap_percent = ?,"
                            + " set_by = ?, set_at = ? where policy_id = ?",
                    command.policy(),
                    gapAmount,
                    gapPercent,
                    scope.userId(),
                    now,
                    policyId);
        } else {
            policyId = Ids.next();
            jdbc.update(
                    "insert into pricing.mrp_policy (policy_id, sku_id, policy, picker_gap_amount, picker_gap_percent,"
                            + " owner_entity_id, set_by, set_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    policyId,
                    command.skuId(),
                    command.policy(),
                    gapAmount,
                    gapPercent,
                    scope.entityId(),
                    scope.userId(),
                    now);
        }

        Map<String, Object> before = previous.map(row -> {
                    Map<String, Object> was = new LinkedHashMap<>();
                    was.put("policy", row.policy());
                    was.put("gapAmount", row.gapAmount());
                    was.put("gapPercent", row.gapPercent());
                    return was;
                })
                .orElse(null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("skuId", command.skuId());
        after.put("policy", command.policy());
        after.put("gapAmount", gapAmount);
        after.put("gapPercent", gapPercent);
        audit.record(AUDIT_SET, Subject.of("mrp_policy", policyId), before, after, scope);

        events.publish(new MrpPolicyChanged(
                policyId, scope.entityId(), command.skuId(), command.policy(), gapAmount, gapPercent));
        return policyId;
    }
}
