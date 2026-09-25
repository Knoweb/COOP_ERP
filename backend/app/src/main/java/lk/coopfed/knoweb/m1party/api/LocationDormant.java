package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** An ACTIVE location stopped trading (doc 21 section 4.3, MarkDormant). */
public record LocationDormant(UUID dormantLocationId, UUID ownerEntityId, String locationType, String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "location.dormant.v1";
}
