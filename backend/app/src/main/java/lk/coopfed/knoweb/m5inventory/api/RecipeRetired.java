package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A repack recipe was retired. */
public record RecipeRetired(UUID recipeId, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "recipe.retired.v1";
}
