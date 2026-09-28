package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** RetireRecipe (25A section 6.3): the recipe is no longer used; repacks done with it stay. */
public record RetireRecipe(UUID recipeId) {}
