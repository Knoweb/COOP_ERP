package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/** The counter of a series moved to another device (21A: "kernel emits series.holder_changed.v1"). */
public record SeriesHolderChanged(UUID seriesId, UUID previousDeviceId, UUID deviceId, UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "series.holder_changed.v1";
}
