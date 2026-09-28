package lk.coopfed.knoweb.m5inventory.internal.repack;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.RecipeRetired;
import lk.coopfed.knoweb.m5inventory.api.RetireRecipe;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RetireRecipe (25A section 6.3): a recipe no longer offered for repacks; the repacks done with it
 * stay as they are.
 *
 * <p>Guards, in order: an OWN scope; the recipe visible ({@code m5.recipe.not_found}); ACTIVE
 * ({@code m5.recipe.retired}).
 *
 * <p>Mutation: RETIRED. Audit {@code RECIPE_RETIRED}; event {@code recipe.retired.v1}.
 */
@Service
@CommandHandler(permission = "inv.recipe.manage")
class RetireRecipeHandler implements Handles<RetireRecipe, UUID> {

    static final String AUDIT_RETIRED = "RECIPE_RETIRED";

    private final RepackStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RetireRecipeHandler(RepackStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RetireRecipe command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        RepackStore.Recipe recipe = store.lockRecipe(command.recipeId());
        if (!"ACTIVE".equals(recipe.status())) {
            throw new ProblemException("m5.recipe.retired");
        }
        jdbc.update("update inventory.repack_recipe set status = 'RETIRED' where recipe_id = ?", recipe.recipeId());

        audit.record(
                AUDIT_RETIRED,
                Subject.of("repack_recipe", recipe.recipeId()),
                Map.of("status", "ACTIVE"),
                Map.of("status", "RETIRED"),
                scope);
        events.publish(new RecipeRetired(recipe.recipeId(), recipe.ownerEntityId()));
        return recipe.recipeId();
    }
}
