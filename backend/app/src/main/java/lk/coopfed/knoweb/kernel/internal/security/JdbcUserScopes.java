package lk.coopfed.knoweb.kernel.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
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
 * grant events ({@link PermissionCacheInvalidator}).
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
    private final Cache<UUID, Set<Scope>> scopes =
            Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(50_000).build();
    private final Cache<UUID, Set<UUID>> grants =
            Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(50_000).build();

    public JdbcUserScopes(JdbcTemplate jdbc, SystemScope system) {
        this.jdbc = jdbc;
        this.system = system;
    }

    @Override
    public Set<Scope> scopesOf(UUID userId) {
        return scopes.get(userId, this::loadScopes);
    }

    @Override
    public Set<UUID> grantsOf(UUID userId) {
        return grants.get(userId, this::loadGrants);
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

    private Set<UUID> loadGrants(UUID userId) {
        return system.inScope(SystemScope.federationView(), () -> {
            Set<UUID> found = new HashSet<>();
            jdbc.query(
                    """
                    select unnest(scope_entity_ids) as entity_id
                      from security.external_grant
                     where grantee_user_id = ?
                       and status = 'ACTIVE'
                       and valid_from <= now()
                       and valid_until > now()
                    """,
                    rs -> {
                        found.add(rs.getObject("entity_id", UUID.class));
                    },
                    userId);
            return Set.copyOf(found);
        });
    }
}
