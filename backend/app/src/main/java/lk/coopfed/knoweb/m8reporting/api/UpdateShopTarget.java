package lk.coopfed.knoweb.m8reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A command to update an existing sales target. */
public record UpdateShopTarget(UUID targetId, LocalDate periodStart, LocalDate periodEnd, BigDecimal targetAmount) {}
