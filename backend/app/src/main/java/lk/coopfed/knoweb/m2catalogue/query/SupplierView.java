package lk.coopfed.knoweb.m2catalogue.query;

import java.util.UUID;

/** One supplier (doc 22 section 3.9: supplier_id, owner_entity, name, status). */
public record SupplierView(UUID supplierId, UUID ownerEntityId, String name, String status) {}
