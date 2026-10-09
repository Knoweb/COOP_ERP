package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;

/**
 * The one application-version floor of the till fleet (doc 31 section 6; doc 32 section 3.3
 * step 1; doc 21 section 3.4). The floor is the register's {@code sync.app_version_floor}; when
 * a release raises it, a device below it keeps syncing for the grace period
 * ({@code sync.app_version_floor.grace}, 14 days by default) with a notice, and only after the
 * grace is its upload refused (426) and may it not be assigned to a new position. The grace of a
 * version runs from the moment the floor last rose above it and stayed there, read from the
 * register's history, so raising the floor twice does not restart it for a device already below
 * the first raise.
 *
 * <p>Decided on the architect's delegation, 27 September 2026 (branch {@code feat/decisions-sync}):
 * one floor, owned by the sync gateway, instead of the gateway's {@code sync.app_version_floor}
 * and M1's {@code m1.device.version_floor} side by side.
 */
public interface AppVersionFloor {

    /**
     * Where one application version stands.
     *
     * @param floor       the floor in force
     * @param below       the version is below it (a version that is not dotted numbers is below
     *                    any floor above 0)
     * @param graceEndsAt when the grace of this version ends (or ended); null when not below
     * @param afterGrace  below, and the grace is over: uploads are refused and the device may not
     *                    be assigned to a new position
     */
    record Standing(String floor, boolean below, Instant graceEndsAt, boolean afterGrace) {}

    /**
     * @param appVersion the version the device reported, e.g. "1.4.2"
     * @param scope      the caller's scope (the floor is federation-wide; the scope is what the
     *                   register reads it in)
     */
    Standing standing(String appVersion, ScopeContext scope);
}
