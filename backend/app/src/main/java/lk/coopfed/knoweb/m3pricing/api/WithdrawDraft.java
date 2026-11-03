package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;

/** Withdraw an unpublished price-list draft with a reason. */
public record WithdrawDraft(UUID priceListId, String reason) {}
