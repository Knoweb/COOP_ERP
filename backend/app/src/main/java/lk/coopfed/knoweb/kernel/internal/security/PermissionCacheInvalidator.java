package lk.coopfed.knoweb.kernel.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.event.PublishedEventListener;
import org.springframework.stereotype.Component;

/**
 * Empties the permission and scope caches when the facts behind them change (doc 14 section
 * 1.3; 19A section 3: "invalidated by role.changed.v1, role.assigned/revoked.v1,
 * user.deactivated.v1 fanned out over the broker"), and the grant caches on the external grant
 * events (M1-09). Twice: on this instance the moment the change commits
 * ({@link PublishedEventListener}), so that a revoke usually bites on the next command here; on
 * the instances that consume the events when they arrive. It is not a guarantee: a cache load
 * that began before the commit can put the old permissions back after the entry was emptied.
 * The promise is "within a minute": the one-minute expiry bounds what any instance, one that
 * raced the change or one that heard nothing, still serves. A payload naming the user (userId, granteeUserId)
 * empties that user's entries, any other empties everything.
 */
@Component
class PermissionCacheInvalidator implements PublishedEventListener {

    static final String CONSUMER = "kernel-permission-cache";

    static final Set<String> TYPES = Set.of(
            "role.changed.v1",
            "role.assigned.v1",
            "role.revoked.v1",
            "user.deactivated.v1",
            "external_grant.issued.v1",
            "external_grant.revoked.v1",
            "external_grant.expired.v1");

    private final JdbcPermissionResolver resolver;
    private final JdbcUserScopes scopes;

    PermissionCacheInvalidator(JdbcPermissionResolver resolver, JdbcUserScopes scopes) {
        this.resolver = resolver;
        this.scopes = scopes;
    }

    @EventConsumer(
            types = {
                "role.changed.v1",
                "role.assigned.v1",
                "role.revoked.v1",
                "user.deactivated.v1",
                "external_grant.issued.v1",
                "external_grant.revoked.v1",
                "external_grant.expired.v1"
            },
            consumer = CONSUMER)
    public void onChange(JsonNode payload, ScopeContext scope) {
        invalidate(payload);
    }

    @Override
    public void published(String eventType, JsonNode payload) {
        if (TYPES.contains(eventType)) {
            invalidate(payload);
        }
    }

    void invalidate(JsonNode payload) {
        UUID user = userIn(payload, "userId");
        if (user == null) {
            user = userIn(payload, "granteeUserId");
        }
        if (user != null) {
            resolver.invalidateUser(user);
            scopes.invalidateUser(user);
            return;
        }
        resolver.invalidateAll();
        scopes.invalidateAll();
    }

    private static UUID userIn(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.path(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
