package lk.coopfed.knoweb.m1party.internal.security.role;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.junit.jupiter.api.Test;

/**
 * The property tests of 21A section 9 on the guardrail functions, a thousand generated cases
 * each. 21A names jqwik; it is not in the version catalogue, so these are plain JUnit tests
 * over generated role and pair sets: a fixed seed per case, printed in the failure message, so
 * that any failure can be replayed exactly. RoleGuardrailsPropertyPostgresIntegrationTest runs
 * the same two properties through the handlers and the database.
 */
class RoleRulesPropertyTest {

    private static final int CASES = 1000;
    private static final UUID ENTITY = Ids.next();

    private static List<String> catalogue(Random random) {
        int size = 4 + random.nextInt(12);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            codes.add("m" + random.nextInt(3) + ".thing" + i + ".act");
        }
        return codes;
    }

    private static List<RoleRules.SodRule> rules(Random random, List<String> codes) {
        List<RoleRules.SodRule> rules = new ArrayList<>();
        int count = random.nextInt(8);
        for (int i = 0; i < count; i++) {
            String a = codes.get(random.nextInt(codes.size()));
            String b = codes.get(random.nextInt(codes.size()));
            if (a.equals(b)) {
                continue;
            }
            List<String> ordered = RoleRules.ordered(a, b);
            rules.add(new RoleRules.SodRule(
                    Ids.next(),
                    ordered.get(0),
                    ordered.get(1),
                    random.nextBoolean() ? "ROLE" : "INSTANCE",
                    random.nextBoolean() ? null : ENTITY));
        }
        return rules;
    }

    private static Set<String> subset(Random random, List<String> codes) {
        Set<String> chosen = new HashSet<>();
        for (String code : codes) {
            if (random.nextInt(3) == 0) {
                chosen.add(code);
            }
        }
        return chosen;
    }

    private static boolean holdsARolePair(Set<String> role, List<RoleRules.SodRule> rules) {
        return rules.stream()
                .anyMatch(rule -> "ROLE".equals(rule.mode())
                        && role.contains(rule.permissionA())
                        && role.contains(rule.permissionB()));
    }

    @Test
    void randomRoleEditsNeverProduceARoleHoldingARoleModePair() {
        int accepted = 0;
        int refused = 0;
        for (long seed = 1; seed <= CASES; seed++) {
            Random random = new Random(seed);
            List<String> codes = catalogue(random);
            List<RoleRules.SodRule> rules = rules(random, codes);
            Set<String> role = new HashSet<>();

            for (int edit = 0; edit < 20; edit++) {
                Set<String> wanted = subset(random, codes);
                Optional<List<String>> conflict = RoleRules.roleModeConflict(wanted, rules);
                if (conflict.isEmpty()) {
                    role = wanted;
                    accepted++;
                } else {
                    refused++;
                    // A refusal names a real pair of the proposal, never an invented one.
                    assertThat(wanted).as("seed %d", seed).containsAll(conflict.get());
                    assertThat(RoleRules.modeInForce(
                                    conflict.get().get(0), conflict.get().get(1), rules))
                            .as("seed %d", seed)
                            .contains("ROLE");
                }
                assertThat(holdsARolePair(role, rules))
                        .as("seed %d, edit %d: the role holds a ROLE pair", seed, edit)
                        .isFalse();
            }
        }
        assertThat(accepted).as("edits accepted").isPositive();
        assertThat(refused).as("edits refused").isPositive();
    }

    @Test
    void randomGrantsNeverExceedWhatTheGrantorHolds() {
        for (long seed = 1; seed <= CASES; seed++) {
            Random random = new Random(seed);
            List<String> codes = catalogue(random);
            Set<String> grantorHolds = subset(random, codes);
            Set<String> wanted = subset(random, codes);

            List<String> notHeld = RoleRules.notHeld(wanted, grantorHolds);

            if (notHeld.isEmpty()) {
                assertThat(grantorHolds).as("seed %d", seed).containsAll(wanted);
            } else {
                assertThat(wanted).as("seed %d", seed).containsAll(notHeld);
                assertThat(grantorHolds).as("seed %d", seed).doesNotContainAnyElementsOf(notHeld);
            }
        }
    }

    @Test
    void theAnswerDoesNotDependOnTheOrderOfTheRowsAndAPairIsNeverLowered() {
        for (long seed = 1; seed <= CASES; seed++) {
            Random random = new Random(seed);
            List<String> codes = catalogue(random);
            List<RoleRules.SodRule> rules = rules(random, codes);
            Set<String> wanted = subset(random, codes);

            Optional<List<String>> before = RoleRules.roleModeConflict(wanted, rules);
            List<RoleRules.SodRule> shuffled = new ArrayList<>(rules);
            Collections.shuffle(shuffled, random);
            assertThat(RoleRules.roleModeConflict(wanted, shuffled))
                    .as("seed %d", seed)
                    .isEqualTo(before);

            for (RoleRules.SodRule rule : rules) {
                if ("ROLE".equals(rule.mode())) {
                    assertThat(RoleRules.modeInForce(rule.permissionA(), rule.permissionB(), shuffled))
                            .as("seed %d", seed)
                            .contains("ROLE");
                }
            }
        }
    }
}
