package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A location is ACTIVE: from ONBOARDING (ActivateLocation) or from DORMANT (ReactivateLocation),
 * which doc 21 section 4.3 records under the same event; {@code previousStatus} tells them apart.
 */
public record LocationActivated(
        UUID activatedLocationId, UUID ownerEntityId, String locationType, String previousStatus)
        implements DomainEvent {

    public static final String TYPE = "location.activated.v1";
}
