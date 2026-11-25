package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * A prerequisite owned by another module that must hold before a location first becomes active.
 * Implementations run in the activation command's transaction and caller scope and throw their
 * module's {@code ProblemException} when the prerequisite is not met.
 */
public interface LocationActivationPrerequisite {

    void requireSatisfied(UUID ownerEntityId, UUID locationId, String locationType);
}
