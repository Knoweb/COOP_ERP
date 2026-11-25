package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;

/** ExpireRule: command to mark an ACTIVE rule past its validity window as EXPIRED. */
public record ExpireRule(UUID ruleId) {}
