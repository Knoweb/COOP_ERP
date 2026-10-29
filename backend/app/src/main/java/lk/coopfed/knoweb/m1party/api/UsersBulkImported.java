package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Published when users are successfully loaded in bulk (CAT-09).
 */
public record UsersBulkImported(UUID entityId, int count) implements DomainEvent {

    public static final String TYPE = "m1.users.bulk_imported.v1";
}
