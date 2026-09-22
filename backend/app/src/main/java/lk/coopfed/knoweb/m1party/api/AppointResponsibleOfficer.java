package lk.coopfed.knoweb.m1party.api;

import java.time.LocalDate;
import java.util.UUID;

public record AppointResponsibleOfficer(UUID entityId, UUID userId, LocalDate dataGovernanceSignedOn) {}
