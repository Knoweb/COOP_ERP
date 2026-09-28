package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;

/** WithdrawRule (23A section 7): an ACTIVE rule of the caller's is withdrawn, for a reason. */
public record WithdrawRule(UUID ruleId, String reason) {}
