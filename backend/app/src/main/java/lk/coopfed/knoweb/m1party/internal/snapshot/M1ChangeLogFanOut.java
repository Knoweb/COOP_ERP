package lk.coopfed.knoweb.m1party.internal.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.LocationActivated;
import lk.coopfed.knoweb.m1party.api.LocationDormant;
import lk.coopfed.knoweb.m1party.api.LocationPrimaryChanged;
import lk.coopfed.knoweb.m1party.api.LocationRegistered;
import lk.coopfed.knoweb.m1party.api.LocationUpdated;
import lk.coopfed.knoweb.m1party.api.RoleAssigned;
import lk.coopfed.knoweb.m1party.api.RoleChanged;
import lk.coopfed.knoweb.m1party.api.RoleRevoked;
import lk.coopfed.knoweb.m1party.api.TillPositionRegistered;
import lk.coopfed.knoweb.m1party.api.TillPositionRetired;
import lk.coopfed.knoweb.m1party.api.UserActivated;
import lk.coopfed.knoweb.m1party.api.UserCreated;
import lk.coopfed.knoweb.m1party.api.UserCredentialReset;
import lk.coopfed.knoweb.m1party.api.UserDeactivated;
import lk.coopfed.knoweb.m1party.api.UserUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class M1ChangeLogFanOut {
    private static final Logger log = LoggerFactory.getLogger(M1ChangeLogFanOut.class);

    static final String CONSUMER = "m1.snapshot";

    private final ChangeLog changeLog;
    private final NamedParameterJdbcTemplate jdbc;

    M1ChangeLogFanOut(ChangeLog changeLog, JdbcTemplate jdbc) {
        this.changeLog = changeLog;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @EventConsumer(
            types = {
                LocationRegistered.TYPE,
                LocationUpdated.TYPE,
                LocationActivated.TYPE,
                LocationDormant.TYPE,
                LocationPrimaryChanged.TYPE
            },
            consumer = CONSUMER)
    public void onShopEvent(JsonNode payload, ScopeContext scope) {
        UUID locationId = uuid(payload, "locationId");
        if (locationId == null) locationId = uuid(payload, "registeredLocationId");
        if (locationId == null) locationId = uuid(payload, "activatedLocationId");
        if (locationId == null) locationId = uuid(payload, "updatedLocationId");
        if (locationId == null) locationId = uuid(payload, "dormantLocationId");
        if (locationId == null) locationId = uuid(payload, "changedLocationId");

        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        if (locationId == null || ownerEntityId == null) {
            throw new IllegalArgumentException("Shop event payload is missing required identifiers");
        }

        changeLog.append(
                List.of(new Target(ownerEntityId, locationId)),
                List.of(Change.upsert(ShopSnapshotContributor.LOCATION, locationId)),
                null,
                false,
                scope);
    }

    @EventConsumer(
            types = {TillPositionRegistered.TYPE, TillPositionRetired.TYPE},
            consumer = CONSUMER)
    public void onTillPositionEvent(JsonNode payload, ScopeContext scope) {
        UUID tillPositionId = uuid(payload, "tillPositionId");
        UUID locationId = uuid(payload, "locationId");
        UUID ownerEntityId = uuid(payload, "ownerEntityId");
        if (tillPositionId == null || locationId == null || ownerEntityId == null) {
            throw new IllegalArgumentException("Till position event payload is missing required identifiers");
        }
        changeLog.append(
                List.of(new Target(ownerEntityId, locationId)),
                List.of(Change.upsert(ShopSnapshotContributor.TILL_POSITION, tillPositionId)),
                null,
                false,
                scope);
    }

    @EventConsumer(
            types = {RoleChanged.TYPE},
            consumer = CONSUMER)
    public void onRoleChanged(JsonNode payload, ScopeContext scope) {
        UUID roleId = uuid(payload, "roleId");
        if (roleId == null) {
            throw new IllegalArgumentException("Role changed event payload is missing roleId");
        }
        // The holders of a Federation template are assigned in every society's own scope, which
        // security.user_role's own_read hides from this consumer (it runs in the Federation's OWN
        // scope). security.role_holder_shops (m1security V0021) answers the role's manager across
        // entities: the owner for its own role, the Federation for a template.
        Map<Target, List<Change>> changesPerTarget = new HashMap<>();
        jdbc.query(
                "select holder_user_id, shop_entity_id, shop_location_id from security.role_holder_shops(:roleId)",
                Map.of("roleId", roleId),
                rs -> {
                    Target t = new Target(
                            rs.getObject("shop_entity_id", UUID.class), rs.getObject("shop_location_id", UUID.class));
                    changesPerTarget
                            .computeIfAbsent(t, k -> new ArrayList<>())
                            .add(Change.upsert(
                                    OperatorSnapshotContributor.OPERATOR, rs.getObject("holder_user_id", UUID.class)));
                });
        for (Map.Entry<Target, List<Change>> entry : changesPerTarget.entrySet()) {
            changeLog.append(List.of(entry.getKey()), entry.getValue(), null, true, scope);
        }
    }

    @EventConsumer(
            types = {RoleAssigned.TYPE},
            consumer = CONSUMER)
    public void onRoleAssigned(JsonNode payload, ScopeContext scope) {
        fanOutRoleAssignment(payload, scope, false);
    }

    @EventConsumer(
            types = {RoleRevoked.TYPE},
            consumer = CONSUMER)
    public void onRoleRevoked(JsonNode payload, ScopeContext scope) {
        fanOutRoleAssignment(payload, scope, true);
    }

    private void fanOutRoleAssignment(JsonNode payload, ScopeContext scope, boolean urgent) {
        UUID userId = uuid(payload, "userId");
        UUID scopeEntityId = uuid(payload, "scopeEntityId");
        UUID scopeLocationId = uuid(payload, "scopeLocationId");
        if (userId == null || scopeEntityId == null) {
            throw new IllegalArgumentException("Role assignment event payload is missing required identifiers");
        }

        List<Target> targets;
        if (scopeLocationId != null) {
            targets = List.of(new Target(scopeEntityId, scopeLocationId));
        } else {
            targets = jdbc.query(
                    "select location_id from party.location where owner_entity_id = :entity and location_type = 'SHOP'",
                    Map.of("entity", scopeEntityId),
                    (rs, i) -> new Target(scopeEntityId, rs.getObject(1, UUID.class)));
        }
        if (!targets.isEmpty()) {
            changeLog.append(
                    targets, List.of(Change.upsert(OperatorSnapshotContributor.OPERATOR, userId)), null, urgent, scope);
        }
    }

    @EventConsumer(
            types = {UserDeactivated.TYPE, UserCredentialReset.TYPE},
            consumer = CONSUMER)
    public void onUrgentUserEvent(JsonNode payload, ScopeContext scope) {
        fanOutUser(payload, scope, true);
    }

    @EventConsumer(
            types = {UserCreated.TYPE, UserUpdated.TYPE, UserActivated.TYPE},
            consumer = CONSUMER)
    public void onNormalUserEvent(JsonNode payload, ScopeContext scope) {
        fanOutUser(payload, scope, false);
    }

    private void fanOutUser(JsonNode payload, ScopeContext scope, boolean urgent) {
        UUID userId = uuid(payload, "userId");
        if (userId == null) {
            throw new IllegalArgumentException("User event payload is missing userId");
        }
        List<Target> targets = jdbc.query(
                "select distinct l.owner_entity_id, l.location_id " + "  from security.user_role ur "
                        + "  join party.location l on l.owner_entity_id = ur.scope_entity_id "
                        + " where ur.user_id = :userId "
                        + "   and l.location_type = 'SHOP' "
                        + "   and (ur.scope_location_id is null or ur.scope_location_id = l.location_id)",
                Map.of("userId", userId),
                (rs, i) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        if (!targets.isEmpty()) {
            changeLog.append(
                    targets, List.of(Change.upsert(OperatorSnapshotContributor.OPERATOR, userId)), null, urgent, scope);
        }
    }

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? null : UUID.fromString(value.asText());
    }
}
