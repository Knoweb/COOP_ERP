package lk.coopfed.knoweb.m1party.query;

import java.math.BigDecimal;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * What one holding of an approval permission ({@code inv.writeoff.approve}, {@code
 * inv.adjust.approve}: any permission whose limits carry {@code max_value}) lets its holder
 * approve. M1 compares a grant with the grantor's holdings by it, and M5 checks an approver's
 * decision by it, so the two can never disagree (wave 3, M1M2M3M5-01).
 *
 * <p>The meaning is the one decided on 6 October 2026 for M5 ({@code
 * 2026-10-06-wave2-stock-approvals.md} (1)): a holding with {@code max_value} approves up to that
 * value; a holding without it (a role from before the permission took a limits schema, or one
 * written by hand) approves up to {@code inventory.approval_band1_limit} only, never without a
 * ceiling.
 */
public final class ApprovalLimit {

    /** The limits property that carries the ceiling. */
    public static final String MAX_VALUE = "max_value";

    /** The configuration item a holding without {@code max_value} is worth (seed/kernel/config-items.yaml). */
    public static final String BAND1_LIMIT = "inventory.approval_band1_limit";

    /** The federation default of {@link #BAND1_LIMIT} (25A section 3.1), when the register has no value. */
    public static final String BAND1_LIMIT_DEFAULT = "25000";

    private ApprovalLimit() {}

    /** The band-1 limit in force for the scope's entity. */
    public static BigDecimal band1(ConfigRegistry config, ScopeContext scope) {
        return new BigDecimal(
                config.getOrDefault(BAND1_LIMIT, scope, BAND1_LIMIT_DEFAULT).trim());
    }

    /**
     * The ceiling one holding grants.
     *
     * @param limits the holding's limits, null when it carries none
     * @param band1  the band-1 limit in force ({@link #band1})
     */
    public static BigDecimal of(Map<String, Object> limits, BigDecimal band1) {
        Object max = limits == null ? null : limits.get(MAX_VALUE);
        return max instanceof Number number ? new BigDecimal(number.toString()) : band1;
    }
}
