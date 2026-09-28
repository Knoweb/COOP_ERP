package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A repack recipe was defined. */
public record RecipeDefined(UUID recipeId, UUID ownerEntityId, UUID inputSkuId, UUID outputSkuId)
        implements DomainEvent {

    public static final String TYPE = "recipe.defined.v1";
}
