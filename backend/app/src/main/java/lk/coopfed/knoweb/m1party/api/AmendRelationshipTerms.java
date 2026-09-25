package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Amend the terms of an ACTIVE relationship from a date (21A section 6.1): the current row is
 * closed the day before and a new ACTIVE row carries the new terms. A term left null keeps its
 * current value.
 *
 * @param relationshipId the ACTIVE row being amended
 * @param effectiveFrom  the first day of the new terms; after the current row's first day
 * @param reasonCode     required
 * @param reasonText     optional words beside the code
 */
public record AmendRelationshipTerms(
        UUID relationshipId,
        LocalDate effectiveFrom,
        UUID priceListId,
        BigDecimal creditLimit,
        Integer paymentTermsDays,
        Integer discrepancyWindowDays,
        Integer orderLockHoursBeforeEta,
        String allocationRule,
        String reasonCode,
        String reasonText) {}
