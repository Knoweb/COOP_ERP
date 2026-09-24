package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/** A series was closed and issues nothing from now on. */
public record SeriesClosed(UUID seriesId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "series.closed.v1";
}
