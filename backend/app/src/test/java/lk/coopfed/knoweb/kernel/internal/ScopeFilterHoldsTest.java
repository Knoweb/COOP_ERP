package lk.coopfed.knoweb.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;

/** An entity-wide holder may act at a location of that entity, and at no other entity's location. */
class ScopeFilterHoldsTest {

    private static final UUID E = UUID.fromString("0190a700-0000-7000-8000-000000000001");
    private static final UUID F = UUID.fromString("0190a700-0000-7000-8000-000000000002");
    private static final UUID E_SHOP = UUID.fromString("0190a700-0000-7000-8000-000000000101");
    private static final UUID F_SHOP = UUID.fromString("0190a700-0000-7000-8000-000000000102");

    private static final Function<UUID, Optional<UUID>> OWNERS =
            location -> Optional.ofNullable(Map.of(E_SHOP, E, F_SHOP, F).get(location));

    private static ScopeContext holding(Scope... scopes) {
        return new ScopeContext(
                UUID.randomUUID(),
                null,
                E,
                List.of(scopes),
                null,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                UUID.randomUUID());
    }

    @Test
    void anEntityWideHolderActsAtItsOwnShop() {
        assertThat(ScopeFilter.holds(holding(new Scope(E, null)), new Scope(E, E_SHOP), OWNERS))
                .isTrue();
    }

    @Test
    void anEntityWideHolderMayNotNameAnotherEntitysShop() {
        assertThat(ScopeFilter.holds(holding(new Scope(E, null)), new Scope(E, F_SHOP), OWNERS))
                .isFalse();
        assertThat(ScopeFilter.holds(holding(new Scope(E, null)), new Scope(E, UUID.randomUUID()), OWNERS))
                .isFalse();
    }

    @Test
    void aShopHolderActsAtItsShopAndNowhereElse() {
        ScopeContext shopOnly = holding(new Scope(E, E_SHOP));
        assertThat(ScopeFilter.holds(shopOnly, new Scope(E, E_SHOP), OWNERS)).isTrue();
        assertThat(ScopeFilter.holds(shopOnly, new Scope(E, null), OWNERS)).isFalse();
        assertThat(ScopeFilter.holds(shopOnly, new Scope(F, F_SHOP), OWNERS)).isFalse();
    }
}
