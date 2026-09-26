package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.junit.jupiter.api.Test;

/** The guardrails of role authoring as plain functions (RoleRules), one case per rule and edge. */
class RoleRulesTest {

    private static final UUID ENTITY = Ids.next();

    private static RoleRules.SodRule rule(String a, String b, String mode, UUID owner) {
        List<String> ordered = RoleRules.ordered(a, b);
        return new RoleRules.SodRule(Ids.next(), ordered.get(0), ordered.get(1), mode, owner);
    }

    @Test
    void theTwoCodesOfAPairAreKeptInOrder() {
        assertThat(RoleRules.ordered("b.x", "a.y")).containsExactly("a.y", "b.x");
        assertThat(RoleRules.ordered("a.y", "b.x")).containsExactly("a.y", "b.x");
    }

    @Test
    void aRolePairConflictsOnlyWhenBothHalvesAreHeld() {
        List<RoleRules.SodRule> rules = List.of(rule("inv.writeoff.request", "inv.writeoff.approve", "ROLE", ENTITY));

        assertThat(RoleRules.roleModeConflict(Set.of("inv.writeoff.request"), rules))
                .isEmpty();
        assertThat(RoleRules.roleModeConflict(Set.of("inv.writeoff.request", "inv.writeoff.approve"), rules))
                .contains(List.of("inv.writeoff.approve", "inv.writeoff.request"));
    }

    @Test
    void anInstancePairIsNoConflictForARole() {
        List<RoleRules.SodRule> rules = List.of(rule("a.one", "b.two", "INSTANCE", null));
        assertThat(RoleRules.roleModeConflict(Set.of("a.one", "b.two"), rules)).isEmpty();
    }

    @Test
    void theFirstConflictInCodeOrderIsNamed() {
        List<RoleRules.SodRule> rules =
                List.of(rule("c.three", "d.four", "ROLE", ENTITY), rule("a.one", "b.two", "ROLE", null));
        assertThat(RoleRules.roleModeConflict(Set.of("a.one", "b.two", "c.three", "d.four"), rules))
                .contains(List.of("a.one", "b.two"));
    }

    @Test
    void theModeInForceIsRoleWhenAnyRowSaysSo() {
        List<RoleRules.SodRule> rules =
                List.of(rule("a.one", "b.two", "INSTANCE", null), rule("a.one", "b.two", "ROLE", ENTITY));
        assertThat(RoleRules.modeInForce("a.one", "b.two", rules)).contains("ROLE");
        assertThat(RoleRules.modeInForce("a.one", "b.two", rules.subList(0, 1))).contains("INSTANCE");
        assertThat(RoleRules.modeInForce("a.one", "c.three", rules)).isEmpty();
    }

    @Test
    void theCodesTheGrantorDoesNotHoldAreListedInOrder() {
        assertThat(RoleRules.notHeld(List.of("z.a", "a.b", "m.c"), Set.of("m.c")))
                .containsExactly("a.b", "z.a");
        assertThat(RoleRules.notHeld(List.of("m.c"), Set.of("m.c"))).isEmpty();
    }

    @Test
    void federationCodesAreRefusedOutsideAFederationOwnedRole() {
        List<SecurityRecords.CatalogueEntry> entries = List.of(
                new SecurityRecords.CatalogueEntry("gov.entity.view", "FEDERATION", null),
                new SecurityRecords.CatalogueEntry("prt.location.view", "ENTITY", null));
        assertThat(RoleRules.federationOnly(entries, false)).containsExactly("gov.entity.view");
        assertThat(RoleRules.federationOnly(entries, true)).isEmpty();
    }

    @Test
    void theLastUserManagerIsNotLostButAnEntityWithoutOneIsNotRefused() {
        UUID first = Ids.next();
        UUID second = Ids.next();
        UUID role = Ids.next();
        SecurityRecords.Assignment a = new SecurityRecords.Assignment(first, role, ENTITY, null);
        SecurityRecords.Assignment b = new SecurityRecords.Assignment(second, role, ENTITY, null);

        assertThat(RoleRules.losesLastUserManager(List.of(a), List.of())).isTrue();
        assertThat(RoleRules.losesLastUserManager(List.of(a, b), List.of(b))).isFalse();
        assertThat(RoleRules.losesLastUserManager(List.of(), List.of())).isFalse();
    }

    @Test
    void limitsAreReadAgainstTheSubsetOfJsonSchemaTheCatalogueUses() throws Exception {
        JsonNode schema = new ObjectMapper()
                .readTree("{\"properties\":{\"max_value\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100000},"
                        + "\"count\":{\"type\":\"integer\"},\"note\":{\"type\":\"string\"},"
                        + "\"strict\":{\"type\":\"boolean\"}},\"required\":[\"max_value\"]}");

        // No limits at all is refused when the schema requires one, else an approval permission
        // could be granted without its ceiling; a schema that requires nothing accepts none.
        assertThat(RoleRules.limitsProblem(null, schema)).contains("max_value");
        assertThat(RoleRules.limitsProblem(null, null)).isEmpty();
        assertThat(RoleRules.limitsProblem(Map.of("max_value", 25000), schema)).isEmpty();
        assertThat(RoleRules.limitsProblem(Map.of("max_value", 2.5, "count", 3, "note", "x", "strict", true), schema))
                .isEmpty();
        assertThat(RoleRules.limitsProblem(Map.of("max_value", -1), schema)).contains("max_value");
        assertThat(RoleRules.limitsProblem(Map.of("max_value", 100001), schema)).contains("max_value");
        assertThat(RoleRules.limitsProblem(Map.of("max_value", "lots"), schema)).contains("max_value");
        assertThat(RoleRules.limitsProblem(Map.of("max_value", 1, "count", 1.5), schema))
                .contains("count");
        assertThat(RoleRules.limitsProblem(Map.of("max_value", 1, "other", 1), schema))
                .contains("other");
        assertThat(RoleRules.limitsProblem(Map.of("count", 1), schema)).contains("max_value");
        // A permission with no schema takes no limits at all.
        assertThat(RoleRules.limitsProblem(Map.of("max_value", 1), null)).contains("limits");
    }

    @Test
    void theDiffOfTwoSetsIsWhatWasAddedAndWhatWasRemoved() {
        Map<String, List<String>> diff = RoleRules.diff(List.of("a", "b"), List.of("b", "c"));
        assertThat(diff.get("added")).containsExactly("c");
        assertThat(diff.get("removed")).containsExactly("a");
    }
}
