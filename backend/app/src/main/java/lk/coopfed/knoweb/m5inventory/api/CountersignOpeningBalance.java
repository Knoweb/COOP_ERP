package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * CountersignOpeningBalance (doc 25 section 4.7): the Federation's onboarding officer countersigns,
 * the OPB document is issued and the OPENING_BALANCE movements posted.
 */
public record CountersignOpeningBalance(UUID openingBalanceId) {}
