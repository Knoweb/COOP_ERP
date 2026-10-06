package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A multi-MRP policy (doc 23 section 3.4). As a stored row, {@code source} is OWN (the caller's
 * entity's row) or FEDERATION (the Federation's row for the SKU); as the effective policy of a SKU
 * it may also be DEFAULT (no row: the configured pricing.default_mrp_policy), and then policyId,
 * ownerEntityId, setBy and setAt are null. The gaps are those the till applies: a PICKER row's own
 * or, when it has none, the configured defaults.
 */
public record MrpPolicyView(
        UUID policyId,
        UUID skuId,
        String policy,
        BigDecimal gapAmount,
        BigDecimal gapPercent,
        UUID ownerEntityId,
        String source,
        UUID setBy,
        Instant setAt) {

    public static final String OWN = "OWN";
    public static final String FEDERATION = "FEDERATION";
    public static final String DEFAULT = "DEFAULT";
}
