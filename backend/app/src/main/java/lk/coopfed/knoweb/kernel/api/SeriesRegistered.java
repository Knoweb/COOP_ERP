package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/** A numbering series exists from now on (19A section 7). */
public record SeriesRegistered(UUID seriesId, String docTypeCode, SeriesScope scope, UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "series.registered.v1";
}
