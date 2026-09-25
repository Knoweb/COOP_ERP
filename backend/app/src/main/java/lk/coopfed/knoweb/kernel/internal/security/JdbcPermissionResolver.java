package lk.coopfed.knoweb.kernel.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The resolver over M1's tables (19A section 3): the union of the permissions of every ACTIVE
 * role the user is assigned in the scope. An assignment with no location applies at every
 * location of its entity; one with a location applies there alone. Read under the caller's
 * own row-level security, inside the handler's transaction, so a user sees no assignment
 * that is not theirs at that entity.
 *
 * <p>Cached per instance under (user, entity, location) for ten minutes and emptied by the
 * role and user events ({@link PermissionCacheInvalidator}).
 */
@Component
class JdbcPermissionResolver implements PermissionResolver {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Cache<Key, Set<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(20_000)
            .build();

    JdbcPermissionResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> resolve(ScopeContext ctx) {
        if (ctx == null || ctx.userId() == null || ctx.entityId() == null || ctx.policyClass() != PolicyClass.OWN) {
            return Set.of();
        }
        Key key = new Key(ctx.userId(), ctx.entityId(), ctx.locationId());
        return cache.get(key, k -> load(k.userId(), k.entityId(), k.locationId()));
    }

    private Set<String> load(UUID userId, UUID entityId, UUID locationId) {
        List<String> codes = jdbc.queryForList(
                """
                select distinct rp.permission_code
                  from security.user_role ur
                  join security.role r on r.role_id = ur.role_id and r.status = 'ACTIVE'
                  join security.role_permission rp on rp.role_id = r.role_id
                  join security.app_user u on u.user_id = ur.user_id and u.status = 'ACTIVE'
                 where ur.user_id = ?
                   and ur.scope_entity_id = ?
                   and (ur.scope_location_id is null or ur.scope_location_id = ?)
                """,
                String.class,
                userId,
                entityId,
                locationId);
        return Set.copyOf(new HashSet<>(codes));
    }

    @Override
    public boolean requiresMfa(String permission) {
        if (permission == null) {
            return false;
        }
        List<Boolean> found = jdbc.queryForList(
                "select requires_mfa from security.permission where permission_code = ?", Boolean.class, permission);
        return !found.isEmpty() && Boolean.TRUE.equals(found.get(0));
    }

    /** Empties every resolution: a role changed, an assignment was made or taken, a user left. */
    void invalidateAll() {
        cache.invalidateAll();
    }

    void invalidateUser(UUID userId) {
        cache.asMap().keySet().removeIf(key -> key.userId().equals(userId));
    }

    private record Key(UUID userId, UUID entityId, UUID locationId) {}
}
