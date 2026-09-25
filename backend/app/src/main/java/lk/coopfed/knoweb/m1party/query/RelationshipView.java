package lk.coopfed.knoweb.m1party.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a trading relationship with its terms (doc 18 part A, entity_relationship). A
 * relationship's history is several rows of one seller and buyer, each effective-dated; an
 * amendment never edits a row's terms, it closes the row and opens the next.
 *
 * @param status DRAFT, ACTIVE or SUSPENDED
 */
public record RelationshipView(
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        UUID priceListId,
        BigDecimal creditLimit,
        Integer paymentTermsDays,
        int discrepancyWindowDays,
        int orderLockHoursBeforeEta,
        String allocationRule,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
