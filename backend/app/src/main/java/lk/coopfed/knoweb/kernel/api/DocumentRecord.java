package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

public record DocumentRecord(
        UUID id,
        String documentType,
        UUID ownerEntityId,
        UUID locationId,
        String status) {
}