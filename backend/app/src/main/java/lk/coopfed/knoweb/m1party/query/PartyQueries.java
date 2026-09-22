package lk.coopfed.knoweb.m1party.query;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

public interface PartyQueries {

    Optional<EntityView> getEntity(UUID entityId, ScopeContext scope);

    EntityPage listEntities(EntityFilter filter, ScopeContext scope);
}
