package lk.coopfed.knoweb.kernel.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The entity that owns a location, from M1's {@code party.location}, read as the federation-wide
 * viewer (its {@code fed_view} policy) like {@link JdbcUserScopes}: the scope filter asks before
 * the caller's scope is settled, so the caller's own row policy cannot be the one that reads it.
 * A location's owner never changes (M1 keeps it immutable), so an answer is cached for an hour;
 * an unknown location is not cached, so a new shop is found as soon as it is saved.
 */
@Component
public class LocationOwners {

    static final Duration TTL = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final Cache<UUID, UUID> owners =
            Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(50_000).build();

    public LocationOwners(JdbcTemplate jdbc, SystemScope system) {
        this.jdbc = jdbc;
        this.system = system;
    }

    /** The owning entity of the location, or empty when there is no such location. */
    public Optional<UUID> ownerOf(UUID locationId) {
        UUID cached = owners.getIfPresent(locationId);
        if (cached != null) {
            return Optional.of(cached);
        }
        List<UUID> found = system.inScope(
                SystemScope.federationView(),
                () -> jdbc.queryForList(
                        "select owner_entity_id from party.location where location_id = ?", UUID.class, locationId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        owners.put(locationId, found.get(0));
        return Optional.of(found.get(0));
    }
}
