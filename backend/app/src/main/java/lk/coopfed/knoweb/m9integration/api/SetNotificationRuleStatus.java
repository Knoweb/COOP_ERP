package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;

/** ActivateRule / RetireRule (29A section 6) for a federation-wide rule: the status ACTIVE or RETIRED. */
public record SetNotificationRuleStatus(UUID ruleId, String status) {

    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";
}
