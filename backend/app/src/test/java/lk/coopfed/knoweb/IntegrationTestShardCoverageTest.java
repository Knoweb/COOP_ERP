package lk.coopfed.knoweb;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ci.yml shards the integration job by package, with COOP_ERP_TEST_SHARD_PACKAGES filtering
 * {@code :app:integrationTest} in each matrix leg (build.gradle.kts, task {@code
 * integrationTest}). This proves the shard list there still covers every class tagged {@code
 * integration}, and covers none of them twice: a class in neither, or in two, would either not
 * run in CI at all or run (and be timed) twice. When a new module or top-level test class
 * appears, this fails until ci.yml's three prefix lists are updated to include it in exactly
 * one shard.
 *
 * <p>The three lists below are the ones ci.yml's {@code integration-test} matrix passes as
 * {@code COOP_ERP_TEST_SHARD_PACKAGES}; keep them identical to ci.yml or this test proves the
 * wrong thing.
 */
class IntegrationTestShardCoverageTest {

    private static final Map<String, List<String>> SHARDS = Map.of(
            "kernel+m1party", List.of("lk.coopfed.knoweb.kernel.", "lk.coopfed.knoweb.m1party."),
            "m2catalogue+m3pricing+m4trading",
                    List.of(
                            "lk.coopfed.knoweb.m2catalogue.",
                            "lk.coopfed.knoweb.m3pricing.",
                            "lk.coopfed.knoweb.m4trading."),
            "m5inventory+m6pos+m8reporting+demo+others",
                    List.of(
                            "lk.coopfed.knoweb.m5inventory.",
                            "lk.coopfed.knoweb.m6pos.",
                            "lk.coopfed.knoweb.m7customers.",
                            "lk.coopfed.knoweb.m8reporting.",
                            "lk.coopfed.knoweb.demo.",
                            "lk.coopfed.knoweb.hello.",
                            "lk.coopfed.knoweb.config.",
                            "lk.coopfed.knoweb.testsupport.",
                            // Top-level classes (SchemaRulesIntegrationTest and friends) have no
                            // further package segment, so their own name is the prefix.
                            "lk.coopfed.knoweb.SchemaRulesIntegrationTest",
                            "lk.coopfed.knoweb.OwnPoliciesTestTheClassIntegrationTest",
                            "lk.coopfed.knoweb.RuntimeRolesIntegrationTest",
                            "lk.coopfed.knoweb.IntegrationTestShardCoverageTest"));

    @Test
    void everyIntegrationTestClassIsInExactlyOneShard() {
        // Test classes only: the shard rule is about integration test classes, and importing the
        // whole application as well ran the test JVM out of heap beside ArchitectureTests.
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.OnlyIncludeTests())
                .importPackages("lk.coopfed.knoweb");

        List<String> uncovered = new ArrayList<>();
        List<String> doubleCovered = new ArrayList<>();

        for (JavaClass clazz : classes) {
            if (!isTaggedIntegration(clazz)) {
                continue;
            }
            String name = clazz.getFullName();
            long matches = SHARDS.values().stream()
                    .filter(prefixes -> prefixes.stream().anyMatch(name::startsWith))
                    .count();
            if (matches == 0) {
                uncovered.add(name);
            } else if (matches > 1) {
                doubleCovered.add(name);
            }
        }

        assertThat(uncovered)
                .as("integration test classes matching no CI shard")
                .isEmpty();
        assertThat(doubleCovered)
                .as("integration test classes matching more than one CI shard")
                .isEmpty();
    }

    private static boolean isTaggedIntegration(JavaClass clazz) {
        if (!clazz.isAnnotatedWith(Tag.class)) {
            // A subclass (or the class itself) may inherit @Tag("integration") from a base
            // class such as PostgresIntegrationTest; ArchUnit's isAnnotatedWith checks the
            // class itself, so walk supertypes too.
            return clazz.getAllRawSuperclasses().stream()
                    .anyMatch(IntegrationTestShardCoverageTest::isTaggedIntegration);
        }
        boolean here = clazz.getAnnotationOfType(Tag.class).value().equals("integration");
        return here
                || clazz.getAllRawSuperclasses().stream()
                        .anyMatch(IntegrationTestShardCoverageTest::isTaggedIntegration);
    }
}
