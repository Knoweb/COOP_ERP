package lk.coopfed.knoweb.kernel.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * Empties the permission cache when the facts behind it change (doc 14 section 1.3; 19A
 * section 3: "invalidated by role.changed.v1, role.assigned/revoked.v1, user.deactivated.v1
 * fanned out over the broker"). The event types are M1's (21A); a payload with a userId
 * empties that user's entries, any other payload empties everything. Where the consumer
 * runtime does not run, the ten-minute expiry bounds the staleness.
 */
@Component
class PermissionCacheInvalidator {

    static final String CONSUMER = "kernel-permission-cache";

    private final JdbcPermissionResolver resolver;
    private final JdbcUserScopes scopes;

    PermissionCacheInvalidator(JdbcPermissionResolver resolver, JdbcUserScopes scopes) {
        this.resolver = resolver;
        this.scopes = scopes;
    }

    @EventConsumer(
            types = {"role.changed.v1", "role.assigned.v1", "role.revoked.v1", "user.deactivated.v1"},
            consumer = CONSUMER)
    public void onChange(JsonNode payload, ScopeContext scope) {
        JsonNode user = payload.path("userId");
        if (user.isTextual()) {
            try {
                UUID userId = UUID.fromString(user.asText());
                resolver.invalidateUser(userId);
                scopes.invalidateUser(userId);
                return;
            } catch (IllegalArgumentException notAUuid) {
                // fall through: empty everything
            }
        }
        resolver.invalidateAll();
        scopes.invalidateAll();
    }
}
