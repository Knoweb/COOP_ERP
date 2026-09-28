package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;

/** ActivateRule (23A section 7): a DRAFT rule of the caller's becomes ACTIVE. */
public record ActivateRule(UUID ruleId) {}
