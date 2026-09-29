package lk.coopfed.knoweb.m1party.query;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The users the caller's scope may see (row-level security decides: an entity-wide scope its
 * entity's users, a shop-scoped one the users that work at that shop, m1security V0012).
 */
public interface UserQueries {

    Optional<UserView> getUser(UUID userId, ScopeContext scope);

    /**
     * The display name of the responsible officer appointed on an entity, for whoever read the
     * entity row (and so its officer id) under its own scope: the name is part of the register
     * (doc 21), the user record is not (m1security V0017). Empty when that user is not the
     * entity's officer, or the scope has no class.
     */
    Optional<String> appointedOfficerName(UUID entityId, UUID officerUserId, ScopeContext scope);

    /** Ordered by user id; the next page starts after {@link UserPage#nextCursor()}. */
    UserPage listUsers(UserFilter filter, ScopeContext scope);
}
