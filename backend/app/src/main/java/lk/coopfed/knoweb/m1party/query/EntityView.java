package lk.coopfed.knoweb.m1party.query;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only entity projection.
 *
 * PARTY visibility intentionally returns only entityId and legal names.
 * Fields that PARTY may not see are null.
 */
public record EntityView(
        UUID entityId,
        String entityCode,
        String entityType,
        String legalNameEn,
        String legalNameSi,
        String legalNameTa,
        String registrationNo,
        String vatRegistrationNo,
        String district,
        Integer financialYearStartMonth,
        String defaultLanguage,
        UUID responsibleOfficerUserId,
        LocalDate dataGovernanceSignedOn,
        String status) {}
