package lk.coopfed.knoweb.kernel.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Doc 19 §1 and 19A §1: the active scope decides entity and location; no scope fails closed. */
class ScopeContextTest {

    private final UUID user = Ids.next();
    private final UUID entityA = Ids.next();
    private final UUID entityB = Ids.next();
    private final UUID shop = Ids.next();

    @Test
    void devContextIsASingleActiveOwnScope() {
        ScopeContext ctx = ScopeContext.dev(user, entityA, shop);

        assertTrue(ctx.hasActiveScope());
        assertEquals(entityA, ctx.entityId());
        assertEquals(shop, ctx.locationId());
        assertEquals(entityA, ctx.homeEntityId());
        assertEquals(PolicyClass.OWN, ctx.policyClass());
        assertEquals("en", ctx.lang());
        assertNotNull(ctx.correlationId());
    }

    @Test
    void theOnlyScopeBecomesActiveWhenNoneIsChosen() {
        Scope only = new Scope(entityA, null);

        ScopeContext ctx = new ScopeContext(
                user, null, entityA, List.of(only), null,
                PolicyClass.OWN, null, null, null, null);

        assertEquals(only, ctx.activeScope());
        assertEquals(entityA, ctx.entityId());
        assertNull(ctx.locationId(), "an entity-wide scope has no location");
    }

    @Test
    void severalScopesWithoutAChoiceLeaveNoActiveScope() {
        ScopeContext ctx = new ScopeContext(
                user, null, entityA,
                List.of(new Scope(entityA, null), new Scope(entityB, null)),
                null, PolicyClass.OWN, null, null, null, null);

        assertFalse(ctx.hasActiveScope());
        assertNull(ctx.entityId(), "no entity means row-level security returns nothing");
        assertNull(ctx.locationId());
    }

    @Test
    void missingValuesFallToSafeDefaults() {
        ScopeContext ctx = new ScopeContext(
                null, null, null, null, null, null, null, null, null, null);

        assertEquals(PolicyClass.NONE, ctx.policyClass());
        assertEquals(Locale.ENGLISH, ctx.locale());
        assertNotNull(ctx.correlationId());
        assertTrue(ctx.scopes().isEmpty());
        assertTrue(ctx.grantedEntities().isEmpty());
    }

    @Test
    void aScopeAlwaysNamesAnEntity() {
        assertThrows(IllegalArgumentException.class, () -> new Scope(null, shop));
    }
}
