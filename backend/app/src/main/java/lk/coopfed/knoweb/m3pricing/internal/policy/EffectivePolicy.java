package lk.coopfed.knoweb.m3pricing.internal.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.engine.Money;
import lk.coopfed.knoweb.engine.Policy;
import lk.coopfed.knoweb.engine.PolicyKind;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.internal.list.Federation;
import lk.coopfed.knoweb.m3pricing.query.MrpPolicyView;
import org.springframework.stereotype.Component;

/**
 * EffectivePolicy (23A section 4, policy/: "sku beats tag; default"): the multi-MRP policy that
 * applies to a SKU at the caller's entity. In order: the entity's own row for the SKU; the
 * Federation's row for the SKU; the configured default (pricing.default_mrp_policy, AUTO_LOWEST in
 * the seed, doc 23 section 3.4). Tag rows are deferred (no governed-tag query in M2), so "sku beats
 * tag" has nothing to beat yet. A PICKER policy without its own gaps takes the configured ones
 * (pricing.picker_gap_amount and pricing.picker_gap_percent: Rs 20 or 5 %, doc 23 DR-4).
 */
@Component
public class EffectivePolicy {

    static final String DEFAULT_POLICY_KEY = "pricing.default_mrp_policy";
    static final String GAP_AMOUNT_KEY = "pricing.picker_gap_amount";
    static final String GAP_PERCENT_KEY = "pricing.picker_gap_percent";

    private final MrpPolicyStore store;
    private final Federation federation;
    private final ConfigRegistry config;

    EffectivePolicy(MrpPolicyStore store, Federation federation, ConfigRegistry config) {
        this.store = store;
        this.federation = federation;
        this.config = config;
    }

    /** The policy of the SKU at the owner's shops (normally the caller's entity), and where it came from. */
    public MrpPolicyView of(UUID skuId, UUID ownerEntityId, ScopeContext scope) {
        Optional<MrpPolicyStore.Row> own = store.forSku(skuId, ownerEntityId);
        if (own.isPresent()) {
            return view(own.get(), MrpPolicyView.OWN, scope);
        }
        Optional<MrpPolicyStore.Row> fed = federation.entityId().flatMap(f -> store.forSku(skuId, f));
        if (fed.isPresent()) {
            return view(fed.get(), MrpPolicyView.FEDERATION, scope);
        }
        String policy = config.getOrDefault(DEFAULT_POLICY_KEY, scope, MrpPolicyStore.AUTO_LOWEST);
        return withGaps(
                new MrpPolicyView(null, skuId, policy, null, null, null, MrpPolicyView.DEFAULT, null, null), scope);
    }

    /**
     * Every stored row that applies at the caller's entity: its own, and the Federation's for the
     * SKUs it has none of. The MRP policy screen lists these; a SKU not listed has the default.
     */
    public List<MrpPolicyView> rows(ScopeContext scope) {
        Map<UUID, MrpPolicyView> bySku = new LinkedHashMap<>();
        for (MrpPolicyStore.Row row : store.ofOwner(scope.entityId())) {
            bySku.put(row.skuId(), view(row, MrpPolicyView.OWN, scope));
        }
        Optional<UUID> fed = federation.entityId().filter(f -> !f.equals(scope.entityId()));
        if (fed.isPresent()) {
            for (MrpPolicyStore.Row row : store.ofOwner(fed.get())) {
                bySku.putIfAbsent(row.skuId(), view(row, MrpPolicyView.FEDERATION, scope));
            }
        }
        return new ArrayList<>(bySku.values());
    }

    /** The engine's form of a policy (23A section 6, Policy). */
    public static Policy toEngine(MrpPolicyView view) {
        return new Policy(
                PolicyKind.valueOf(view.policy()),
                view.gapAmount() == null ? null : Money.rounded(view.gapAmount()),
                view.gapPercent());
    }

    private MrpPolicyView view(MrpPolicyStore.Row row, String source, ScopeContext scope) {
        return withGaps(
                new MrpPolicyView(
                        row.policyId(),
                        row.skuId(),
                        row.policy(),
                        row.gapAmount(),
                        row.gapPercent(),
                        row.ownerEntityId(),
                        source,
                        row.setBy(),
                        row.setAt()),
                scope);
    }

    private MrpPolicyView withGaps(MrpPolicyView view, ScopeContext scope) {
        if (!MrpPolicyStore.PICKER.equals(view.policy()) || (view.gapAmount() != null || view.gapPercent() != null)) {
            return view;
        }
        return new MrpPolicyView(
                view.policyId(),
                view.skuId(),
                view.policy(),
                config.get(GAP_AMOUNT_KEY, scope).map(BigDecimal::new).orElse(null),
                config.get(GAP_PERCENT_KEY, scope).map(BigDecimal::new).orElse(null),
                view.ownerEntityId(),
                view.source(),
                view.setBy(),
                view.setAt());
    }
}
