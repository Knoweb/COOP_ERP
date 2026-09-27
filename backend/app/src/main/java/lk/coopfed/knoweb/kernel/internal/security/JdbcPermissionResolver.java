package lk.coopfed.knoweb.kernel.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The resolver over M1's tables (19A section 3), by policy class (CR-19A-9, decided 27
 * September 2026):
 *
 * <ul>
 *   <li><b>OWN</b>: the union of the permissions of every ACTIVE role the user is assigned in
 *       the scope. An assignment with no location applies at every location of its entity; one
 *       with a location applies there alone. Read under the caller's own row-level security,
 *       inside the caller's transaction, so a user sees no assignment that is not theirs at
 *       that entity.
 *   <li><b>EXTERNAL_TIMEBOXED</b>: the union of the permissions of the user's ACTIVE roles of
 *       that class (the Regulator and Auditor templates, assigned by the Federation at the
 *       user's home entity, doc 21 flow 6.5), while the user holds a current grant; nothing
 *       once the grant ends. The grant says where (its entities, which row-level security
 *       applies through {@code ext_view}); the role says what. Read as the federation-wide
 *       viewer in a transaction of its own, because the caller's own policies do not show the
 *       Federation's assignment rows to an external caller.
 *   <li><b>FEDERATION_VIEW</b>: every read of every slice ({@link SliceOperations#readPermissions}):
 *       the class is "SELECT on everything, no writes" (doc 18 section 3.7), and 21A section
 *       3's federation-view template carries no permission of its own.
 *   <li>anything else: nothing.
 * </ul>
 *
 * <p>A command is refused for every class but OWN before this is asked ({@link PermissionGate}).
 *
 * <p>Cached per instance under (user, entity, location) for a minute; the instance that made
 * a change empties its own entries when the change commits, the others when the role and user
 * events reach them or the minute passes ({@link PermissionCacheInvalidator}).
 */
@Component
class JdbcPermissionResolver implements PermissionResolver {

    static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final SliceOperations slices;
    private final SystemScope system;
    private final Cache<Key, Set<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(20_000)
            .build();

    JdbcPermissionResolver(JdbcTemplate jdbc, SliceOperations slices, SystemScope system) {
        this.jdbc = jdbc;
        this.slices = slices;
        this.system = system;
    }

    @Override
    public Set<String> resolve(ScopeContext ctx) {
        if (ctx == null || ctx.userId() == null || ctx.entityId() == null) {
            return Set.of();
        }
        return switch (ctx.policyClass()) {
            case OWN ->
                cache.get(
                        new Key(ctx.userId(), ctx.entityId(), ctx.locationId()),
                        k -> loadOwn(k.userId(), k.entityId(), k.locationId()));
            case FEDERATION_VIEW -> slices.readPermissions();
            case EXTERNAL_TIMEBOXED ->
                ctx.grantedEntities().isEmpty()
                        ? Set.of()
                        : cache.get(new Key(ctx.userId(), ctx.homeEntityId(), null), k -> loadExternal(k.userId()));
            default -> Set.of();
        };
    }

    private Set<String> loadOwn(UUID userId, UUID entityId, UUID locationId) {
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

    /**
     * The external roles of the user wherever assigned (the Federation assigns them at its own
     * entity, the user's home). {@code inOwnTransaction}: a transaction of its own on a
     * connection of its own, so the federation-wide scope never lands on the caller's
     * connection, whether or not a transaction is running around this call.
     */
    private Set<String> loadExternal(UUID userId) {
        return system.inOwnTransaction(SystemScope.federationView(), () -> {
            List<String> codes = jdbc.queryForList(
                    """
                    select distinct rp.permission_code
                      from security.user_role ur
                      join security.role r on r.role_id = ur.role_id
                                          and r.status = 'ACTIVE'
                                          and r.role_class = 'EXTERNAL_TIMEBOXED'
                      join security.role_permission rp on rp.role_id = r.role_id
                      join security.app_user u on u.user_id = ur.user_id and u.status = 'ACTIVE'
                     where ur.user_id = ?
                    """,
                    String.class,
                    userId);
            return Set.copyOf(new HashSet<>(codes));
        });
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
