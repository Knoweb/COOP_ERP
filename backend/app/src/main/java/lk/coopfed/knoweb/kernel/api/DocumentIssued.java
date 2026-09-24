package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * A document took its number (19A section 7, step 5: "document.issued.v1 + type-specific
 * event"). The type-specific event is the owning module's to publish from its handler.
 */
public record DocumentIssued(
        UUID documentId, String docTypeCode, String docNumberDisplay, UUID ownerEntityId, UUID counterpartyEntityId)
        implements DomainEvent {

    public static final String TYPE = "document.issued.v1";
}
