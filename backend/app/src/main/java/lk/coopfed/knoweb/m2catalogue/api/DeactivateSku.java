package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

public record DeactivateSku(UUID skuId, String reasonCode, String reasonText) {}
