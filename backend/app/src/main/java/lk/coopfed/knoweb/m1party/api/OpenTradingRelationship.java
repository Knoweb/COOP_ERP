package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Open a trading relationship in DRAFT (21A section 6, OpenTradingRelationship). The seller is
 * the caller's entity, never a field of the command: a client must not be able to name the
 * entity it sells for.
 *
 * @param buyerEntityId           the entity that will buy
 * @param priceListId             the seller's TRADE price list (M3); may be bound later, but
 *                                activation needs it
 * @param creditLimit             informative (ADR-12); null for none
 * @param paymentTermsDays        days to pay; activation needs it
 * @param discrepancyWindowDays   null for the default, 7
 * @param orderLockHoursBeforeEta null for the default, 24
 * @param allocationRule          FCFS, PRO_RATA or QUOTA; null for FCFS
 * @param effectiveFrom           the first day the terms apply
 * @param effectiveTo             the last day, or null for open-ended
 */
public record OpenTradingRelationship(
        UUID buyerEntityId,
        UUID priceListId,
        BigDecimal creditLimit,
        Integer paymentTermsDays,
        Integer discrepancyWindowDays,
        Integer orderLockHoursBeforeEta,
        String allocationRule,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
