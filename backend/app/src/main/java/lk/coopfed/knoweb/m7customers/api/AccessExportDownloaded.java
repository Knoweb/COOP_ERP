package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** The export of a fulfilled ACCESS request was handed over by the responsible officer. Ids only. */
public record AccessExportDownloaded(UUID requestId, UUID customerId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "dsar.export_downloaded.v1";
}
