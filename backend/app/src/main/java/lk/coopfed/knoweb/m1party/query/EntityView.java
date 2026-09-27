package lk.coopfed.knoweb.m1party.query;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only entity projection.
 *
 * PARTY visibility intentionally returns only entityId, legal names and vatRegistrationNo (a
 * trading counterparty's VAT number, needed on a tax invoice; CR-21A-6). Fields that PARTY may
 * not see are null. An OWN-scoped reader of a counterparty (not its own entity) gets the same
 * projection; see PartyQueriesImpl.getEntity.
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
