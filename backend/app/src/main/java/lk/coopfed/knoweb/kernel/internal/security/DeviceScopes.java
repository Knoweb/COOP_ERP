package lk.coopfed.knoweb.kernel.internal.security;

import java.util.Locale;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The scope of a device token (cls = DEVICE): the device's entity and shop from M1's records,
 * once its status has been checked (19A section 2, DeviceAuth). The claims mapper asks this for
 * a device token instead of reading a user from it.
 */
@FunctionalInterface
public interface DeviceScopes {

    /** The device's scope, or a ProblemException that refuses the device (unknown, suspended ...). */
    ScopeContext scopeOf(UUID deviceId, UUID correlationId, Locale locale);
}
