package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Records qualifying customer spend towards loyalty monthly reward evaluation (PGL-M3-03).
 */
public record RecordLoyaltySpend(
        UUID loyaltyId, UUID locationId, LocalDate spendDate, BigDecimal amount, String receiptNumber) {}
