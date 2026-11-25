package lk.coopfed.knoweb.m8reporting.query;

import java.time.LocalDate;
import java.util.UUID;

public record ShopTargetFilter(UUID locationId, LocalDate from, LocalDate to) {}
