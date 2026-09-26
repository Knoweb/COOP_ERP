package lk.coopfed.knoweb.kernel.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link UserScopes} over M1's tables, read as the federation-wide viewer (the
 * {@code fed_view} policy of the security schema): the caller's scope is what is being
 * resolved, so the caller's own row policy cannot be the one that reads it. Cached per
 * instance for a minute, like the permission cache, and emptied by the same events and the
 * grant events ({@link PermissionCacheInvalidator}). A grant entry lives no longer than the
 * earliest end of the grants in it, so an ended grant admits nothing past its end on any
 * instance, whether or not the expiry job or the invalidation has reached it (doc 21 section
 * 6.5: "revocation immediate"; the end of the window is the same promise by the clock).
 *
 * <p>19A section 2 puts these claims into the token through "a provider mapper that reads
 * M1's user_role through a small kernel endpoint at token issue time". The provider we run
 * has no mapper that calls an endpoint without a provider-specific extension, which AGENTS.md
 * forbids the kernel to depend on; resolving at request time, from the same table, gives the
 * same scopes without the extension, and a token that does carry the claims is honoured as it
 * is. Recorded in the K-02 pull request for the architect.
 */
@Component
public class JdbcUserScopes implements UserScopes {

    static final Duration TTL = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final SystemScope system;
    private final Clock clock;
    private final Cache<UUID, Set<Scope>> scopes =
            Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(50_000).build();
    private final Cache<UUID, Grants> grants;

    /** The granted entities and the earliest moment one of the grants ends (null when none). */
    record Grants(Set<UUID> entities, Instant earliestEnd) {}

    public JdbcUserScopes(JdbcTemplate jdbc, SystemScope system, Clock clock) {
        this.jdbc = jdbc;
        this.system = system;
        this.clock = clock;
        this.grants = Caffeine.newBuilder()
                .expireAfter(new Expiry<UUID, Grants>() {
                    @Override
                    public long expireAfterCreate(UUID key, Grants value, long currentTime) {
                        return lifetime(value);
                    }

                    @Override
                    public long expireAfterUpdate(UUID key, Grants value, long currentTime, long currentDuration) {
                        return lifetime(value);
                    }

                    @Override
                    public long expireAfterRead(UUID key, Grants value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(50_000)
                .build();
    }

    /** The minute, or the time left until the earliest grant ends, whichever comes first. */
    private long lifetime(Grants value) {
        Duration life = TTL;
        if (value.earliestEnd() != null) {
            Duration untilEnd = Duration.between(clock.instant(), value.earliestEnd());
            if (untilEnd.compareTo(life) < 0) {
                life = untilEnd.isNegative() ? Duration.ZERO : untilEnd;
            }
        }
        return life.toNanos();
    }

    @Override
    public Set<Scope> scopesOf(UUID userId) {
        return scopes.get(userId, this::loadScopes);
    }

    @Override
    public Set<UUID> grantsOf(UUID userId) {
        return grants.get(userId, this::loadGrants).entities();
    }

    public void invalidateUser(UUID userId) {
        scopes.invalidate(userId);
        grants.invalidate(userId);
    }

    public void invalidateAll() {
        scopes.invalidateAll();
        grants.invalidateAll();
    }

    private Set<Scope> loadScopes(UUID userId) {
        return system.inScope(SystemScope.federationView(), () -> {
            Set<Scope> found = new HashSet<>();
            jdbc.query(
                    """
                    select distinct ur.scope_entity_id, ur.scope_location_id
                      from security.user_role ur
                      join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
                      join security.app_user u on u.user_id = ur.user_id and u.status = 'ACTIVE'
                     where ur.user_id = ?
                    """,
                    rs -> {
                        found.add(new Scope(
                                rs.getObject("scope_entity_id", UUID.class),
                                rs.getObject("scope_location_id", UUID.class)));
                    },
                    userId);
            return Set.copyOf(found);
        });
    }

    /**
     * The entities of the user's ACTIVE grants whose window contains now, when the user is
     * ACTIVE: a deactivated grantee's grant stays in the register as it was (deactivation ends
     * it, M1's DeactivateUser) and resolves nothing, as the scopes of a deactivated user do.
     */
    private Grants loadGrants(UUID userId) {
        return system.inScope(SystemScope.federationView(), () -> {
            Set<UUID> found = new HashSet<>();
            Instant[] earliestEnd = new Instant[1];
            jdbc.query(
                    """
                    select unnest(g.scope_entity_ids) as entity_id, g.valid_until
                      from security.external_grant g
                      join security.app_user u on u.user_id = g.grantee_user_id and u.status = 'ACTIVE'
                     where g.grantee_user_id = ?
                       and g.status = 'ACTIVE'
                       and g.valid_from <= now()
                       and g.valid_until > now()
                    """,
                    rs -> {
                        found.add(rs.getObject("entity_id", UUID.class));
                        Instant end = rs.getObject("valid_until", OffsetDateTime.class)
                                .toInstant();
                        if (earliestEnd[0] == null || end.isBefore(earliestEnd[0])) {
                            earliestEnd[0] = end;
                        }
                    },
                    userId);
            return new Grants(Set.copyOf(found), earliestEnd[0]);
        });
    }
}
