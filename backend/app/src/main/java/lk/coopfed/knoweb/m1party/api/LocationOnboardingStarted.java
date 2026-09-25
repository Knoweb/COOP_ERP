package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A PLANNED location moved to ONBOARDING (doc 21 section 4.3). */
public record LocationOnboardingStarted(UUID onboardingLocationId, UUID ownerEntityId, String locationType)
        implements DomainEvent {

    public static final String TYPE = "location.onboarding.v1";
}
