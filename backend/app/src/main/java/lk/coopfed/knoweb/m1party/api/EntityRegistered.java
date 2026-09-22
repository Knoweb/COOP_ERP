package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

public record EntityRegistered(UUID entityId, String entityType) implements DomainEvent {

    public static final String TYPE = "entity.registered.v1";
}
