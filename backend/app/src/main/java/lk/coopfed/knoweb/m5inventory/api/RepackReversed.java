package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A repack was reversed: its output taken back and its input lot restored. */
public record RepackReversed(UUID repackId, UUID ownerEntityId, UUID locationId) implements DomainEvent {

    public static final String TYPE = "repack.reversed.v1";
}
