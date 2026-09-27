package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** SignOpeningBalance (doc 25 section 4.7): the entity's officer signs the counted stock. */
public record SignOpeningBalance(UUID openingBalanceId) {}
