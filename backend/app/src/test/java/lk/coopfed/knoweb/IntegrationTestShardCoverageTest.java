package lk.coopfed.knoweb;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * ci.yml shards the integration job by package, with COOP_ERP_TEST_SHARD_PACKAGES filtering
 * {@code :app:integrationTest} in each matrix leg (build.gradle.kts, task {@code
 * integrationTest}). This proves the shard lists there still cover every class tagged {@code
 * integration}, and cover none of them twice: a class in neither, or in two, would either not
 * run in CI at all or run (and be timed) twice. When a new module or top-level test class
 * appears, this fails until a prefix for it is added to exactly one shard of ci.yml.
 *
 * <p>The lists are read from ci.yml itself (the {@code packages} of each leg of the {@code
 * integration-test} matrix), not copied here: a second copy drifted from the first once and
 * nothing compared them, so a module added to the copy alone was green here and never ran in CI
 * (wave 3, WCD-12). Each prefix must also match at least one class, because the Gradle filter
 * ({@code isFailOnNoMatchingTests = false}) says nothing about a prefix with a typo.
 */
class IntegrationTestShardCoverageTest {

    /** Relative to backend/app, where Gradle runs the tests (as ArchitectureTests reads docs/modules). */
    private static final Path CI_WORKFLOW = Path.of("../../.github/workflows/ci.yml");

    @Test
    void everyIntegrationTestClassIsInExactlyOneShard() throws IOException {
        Map<String, List<String>> shards = shardsOfTheCiWorkflow();
        assertThat(shards).as("the integration-test matrix of ci.yml").isNotEmpty();

        // Test classes only: the shard rule is about integration test classes, and importing the
        // whole application as well ran the test JVM out of heap beside ArchitectureTests.
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.OnlyIncludeTests())
                .importPackages("lk.coopfed.knoweb");

        List<String> uncovered = new ArrayList<>();
        List<String> doubleCovered = new ArrayList<>();
        List<String> integrationClasses = new ArrayList<>();

        for (JavaClass clazz : classes) {
            if (!isTaggedIntegration(clazz)) {
                continue;
            }
            String name = clazz.getFullName();
            integrationClasses.add(name);
            long matches = shards.values().stream()
                    .filter(prefixes -> prefixes.stream().anyMatch(name::startsWith))
                    .count();
            if (matches == 0) {
                uncovered.add(name);
            } else if (matches > 1) {
                doubleCovered.add(name);
            }
        }

        assertThat(uncovered)
                .as("integration test classes matching no CI shard (add a prefix to one shard in ci.yml)")
                .isEmpty();
        assertThat(doubleCovered)
                .as("integration test classes matching more than one CI shard")
                .isEmpty();
        List<String> matchingNothing = shards.values().stream()
                .flatMap(List::stream)
                .filter(prefix -> integrationClasses.stream().noneMatch(name -> name.startsWith(prefix)))
                .toList();
        assertThat(matchingNothing)
                .as("ci.yml shard prefixes that match no integration test class (a typo runs nothing)")
                .isEmpty();
    }

    /** Each leg's name and its prefixes, as ci.yml passes them in COOP_ERP_TEST_SHARD_PACKAGES. */
    @SuppressWarnings("unchecked")
    static Map<String, List<String>> shardsOfTheCiWorkflow() throws IOException {
        Map<String, Object> workflow = new Yaml().load(Files.readString(CI_WORKFLOW));
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        Map<String, Object> job = (Map<String, Object>) jobs.get("integration-test");
        Map<String, Object> strategy = (Map<String, Object>) job.get("strategy");
        Map<String, Object> matrix = (Map<String, Object>) strategy.get("matrix");
        List<Map<String, Object>> legs = (List<Map<String, Object>>) matrix.get("shard");
        Map<String, List<String>> shards = new LinkedHashMap<>();
        for (Map<String, Object> leg : legs) {
            // The same split as build.gradle.kts: commas, then trimmed (a folded >- list leaves ", ").
            List<String> prefixes = Arrays.stream(
                            String.valueOf(leg.get("packages")).split(","))
                    .map(String::trim)
                    .filter(prefix -> !prefix.isEmpty())
                    .toList();
            shards.put(String.valueOf(leg.get("name")), prefixes);
        }
        return shards;
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
