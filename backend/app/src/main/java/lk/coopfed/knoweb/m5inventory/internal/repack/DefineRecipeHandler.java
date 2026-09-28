package lk.coopfed.knoweb.m5inventory.internal.repack;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.api.DefineRecipe;
import lk.coopfed.knoweb.m5inventory.api.RecipeDefined;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DefineRecipe (25A section 6.3: "input ≠ output; both SKUs active; output origin REPACK_OUTPUT
 * (M2)"; doc 25 section 3.6, ADR-07).
 *
 * <p>Guards, in order: an OWN scope; a name ({@code m5.recipe.name_required}); quantities above
 * zero with three decimals at most and an expected loss from 0 to below 100 % with two
 * ({@code m5.recipe.invalid}); two different items ({@code m5.recipe.same_sku}); both in use and
 * readable in M2 ({@code m5.recipe.sku_inactive}); the output an item M2 knows as a repack output
 * ({@code m5.recipe.output_not_repack}: a pack the entity makes is not the supplier's product); no
 * other active recipe of that name ({@code m5.recipe.name_taken}).
 *
 * <p>Mutation: the recipe, ACTIVE. Audit {@code RECIPE_DEFINED}; event {@code recipe.defined.v1}.
 */
@Service
@CommandHandler(permission = "inv.recipe.manage")
class DefineRecipeHandler implements Handles<DefineRecipe, UUID> {

    static final String AUDIT_DEFINED = "RECIPE_DEFINED";
    private static final Set<String> IN_USE = Set.of("LOCAL", "SHARED");

    private final RepackStore store;
    private final CatalogueQueries catalogue;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    DefineRecipeHandler(
            RepackStore store,
            CatalogueQueries catalogue,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.catalogue = catalogue;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(DefineRecipe command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (command.name() == null || command.name().isBlank()) {
            throw new ProblemException("m5.recipe.name_required");
        }
        BigDecimal loss = command.expectedLossPct() == null ? BigDecimal.ZERO : command.expectedLossPct();
        if (!quantity(command.inputQty())
                || !quantity(command.outputQty())
                || loss.signum() < 0
                || loss.compareTo(BigDecimal.valueOf(100)) >= 0
                || loss.stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m5.recipe.invalid");
        }
        if (command.inputSkuId() == null || command.inputSkuId().equals(command.outputSkuId())) {
            throw new ProblemException("m5.recipe.same_sku");
        }
        Optional<SkuView> input = catalogue.getSku(command.inputSkuId(), scope);
        Optional<SkuView> output =
                Optional.ofNullable(command.outputSkuId()).flatMap(id -> catalogue.getSku(id, scope));
        if (input.filter(s -> IN_USE.contains(s.status())).isEmpty()
                || output.filter(s -> IN_USE.contains(s.status())).isEmpty()) {
            throw new ProblemException("m5.recipe.sku_inactive");
        }
        if (!"REPACK_OUTPUT".equals(output.get().originKind())) {
            throw new ProblemException("m5.recipe.output_not_repack");
        }
        String name = command.name().strip();
        if (store.activeNameTaken(name)) {
            throw new ProblemException("m5.recipe.name_taken");
        }

        UUID id = Ids.next();
        jdbc.update(
                """
                insert into inventory.repack_recipe
                    (recipe_id, owner_entity_id, name, input_sku_id, input_qty, output_sku_id, output_qty,
                     expected_loss_pct, defined_by, defined_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                scope.entityId(),
                name,
                command.inputSkuId(),
                command.inputQty(),
                command.outputSkuId(),
                command.outputQty(),
                loss,
                scope.userId(),
                Timestamp.from(clock.instant()));

        audit.record(
                AUDIT_DEFINED,
                Subject.of("repack_recipe", id),
                null,
                Map.of(
                        "name", name,
                        "inputSkuId", command.inputSkuId(),
                        "inputQty", command.inputQty().toPlainString(),
                        "outputSkuId", command.outputSkuId(),
                        "outputQty", command.outputQty().toPlainString(),
                        "expectedLossPct", loss.toPlainString()),
                scope);
        events.publish(new RecipeDefined(id, scope.entityId(), command.inputSkuId(), command.outputSkuId()));
        return id;
    }

    private static boolean quantity(BigDecimal qty) {
        return qty != null && qty.signum() > 0 && qty.stripTrailingZeros().scale() <= 3;
    }
}
