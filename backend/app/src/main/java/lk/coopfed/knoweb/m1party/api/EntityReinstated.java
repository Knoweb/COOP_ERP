package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

public record EntityReinstated(UUID entityId, String entityCode, String entityType, String status)
        implements DomainEvent {

    public static final String TYPE = "entity.reinstated.v1";
}
