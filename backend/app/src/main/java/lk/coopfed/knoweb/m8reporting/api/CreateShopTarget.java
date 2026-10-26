package lk.coopfed.knoweb.m8reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A command to define a sales target for a shop/location and period. */
public record CreateShopTarget(
        UUID locationId, LocalDate periodStart, LocalDate periodEnd, BigDecimal targetAmount, String currency) {

    public CreateShopTarget(UUID locationId, LocalDate periodStart, LocalDate periodEnd, BigDecimal targetAmount) {
        this(locationId, periodStart, periodEnd, targetAmount, "LKR");
    }
}
