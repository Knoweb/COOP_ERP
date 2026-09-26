package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** supplier.registered.v1 (doc 22 section 5.3: "supplier id, owner"; the name stays in the table). */
public record SupplierRegistered(UUID supplierId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "supplier.registered.v1";
}
