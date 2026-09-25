package lk.coopfed.knoweb.m1party.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The devices a caller may see (21A section 7, ListDevices: OWN and FEDERATION_VIEW). Row-level
 * security decides which rows: an entity-wide caller sees the entity's devices, a caller scoped
 * to one shop sees that shop's only, the Federation view sees every one.
 */
public interface DeviceQueries {

    Optional<DeviceView> getDevice(UUID deviceId, ScopeContext scope);

    /** Ordered by location, then serial. The register is small (doc 21 section 9: 15,000 in all). */
    List<DeviceView> listDevices(DeviceFilter filter, ScopeContext scope);
}
