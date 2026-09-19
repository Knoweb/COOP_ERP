package lk.coopfed.knoweb;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules every OpenAPI slice obeys (AGENTS.md; 17A sections 3 and 12), checked for every
 * file in resources/openapi. The generator turns a slice into an interface whatever it says;
 * these are the things it does not care about and the project does.
 *
 * <p>One test per slice, so a failure names the slice. A new slice is picked up by itself.
 * (That a handler's permission is one of its slice's x-permission values is checked by
 * tools/check-permissions.mjs, which reads the Java sources as well.)
 */
class OpenApiSliceRulesTest {

    private static final Path SLICES = Path.of("src/main/resources/openapi");
    private static final String SHARED = "common.yaml";

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");
    private static final Set<String> MUTATING = Set.of("put", "post", "delete", "patch");
    private static final String IDEMPOTENCY_REF = "common.yaml#/components/parameters/IdempotencyKey";

    @TestFactory
    Stream<DynamicTest> everySliceObeysTheRules() throws IOException {
        try (Stream<Path> files = Files.list(SLICES)) {
            List<Path> slices = files
                    .filter(f -> f.getFileName().toString().endsWith(".yaml"))
                    .filter(f -> !f.getFileName().toString().equals(SHARED))
                    .sorted()
                    .toList();
            assertThat(slices).as("slices in " + SLICES).isNotEmpty();
            return slices.stream()
                    .map(slice -> DynamicTest.dynamicTest(
                            slice.getFileName().toString(),
                            () -> assertThat(problemsOf(slice)).as("rule violations in " + slice).isEmpty()));
        }
    }

    @Test
    void theSharedFileHasNoPathsOfItsOwn() throws IOException {
        // common.yaml holds components only. A path in it would belong to no module: no
        // interface, no controller, no owner.
        Map<String, Object> shared = load(SLICES.resolve(SHARED));
        assertThat(map(shared.get("paths"))).isEmpty();

        List<String> problems = new ArrayList<>();
        keysWithoutAValue("", shared, problems);
        assertThat(problems).isEmpty();
    }

    /** Proof that the rules bite: a deliberately broken slice is reported, rule by rule. */
    @Test
    void aBrokenSliceIsReportedRuleByRule() throws IOException {
        List<String> problems = problemsOf(Path.of("src/test/resources/openapi-fixtures/broken.yaml"));

        assertThat(problems).anyMatch(p -> p.contains("openapi is 3.0.3"));
        assertThat(problems).anyMatch(p -> p.startsWith("/things: every path is versioned"));
        assertThat(problems).anyMatch(p -> p.startsWith("GET /things: no x-permission"));
        assertThat(problems).anyMatch(p -> p.startsWith("GET /things: needs exactly one tag"));
        assertThat(problems).anyMatch(p -> p.contains("operationId listThings is used twice"));
        assertThat(problems).anyMatch(p -> p.startsWith("POST /v1/party/things: no operationId"));
        assertThat(problems).anyMatch(p -> p.startsWith("POST /v1/party/things: needs exactly one tag"));
        assertThat(problems).anyMatch(p -> p.startsWith("POST /v1/party/things: a mutating operation must carry"));
        assertThat(problems).anyMatch(p -> p.startsWith("components.schemas.Thing.properties.name: the key \"or so it seems\" has no value"));
        assertThat(problems).hasSize(9);
    }

    /** Every violation in one slice, as sentences a developer can act on. */
    static List<String> problemsOf(Path slice) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, Object> document = load(slice);

        String version = String.valueOf(document.get("openapi"));
        if (!version.startsWith("3.1")) {
            problems.add("openapi is " + version + "; every slice is OpenAPI 3.1 (doc 14; doc 17)");
        }

        Set<String> operationIds = new TreeSet<>();
        map(document.get("paths")).forEach((path, item) -> {
            if (!path.startsWith("/v1/")) {
                problems.add(path + ": every path is versioned under /v1/ (doc 17)");
            }
            map(item).forEach((method, value) -> {
                if (!METHODS.contains(method)) {
                    return;     // "parameters", "summary" ... at path level
                }
                Map<String, Object> operation = map(value);
                String where = method.toUpperCase() + " " + path;

                Object id = operation.get("operationId");
                if (id == null) {
                    problems.add(where + ": no operationId; it is the method name in the generated interface");
                } else if (!operationIds.add(id.toString())) {
                    problems.add(where + ": operationId " + id + " is used twice in this slice");
                }
                if (!(operation.get("x-permission") instanceof String permission) || permission.isBlank()) {
                    problems.add(where + ": no x-permission; every operation names the permission it needs");
                }
                if (!(operation.get("tags") instanceof List<?> tags) || tags.size() != 1) {
                    problems.add(where + ": needs exactly one tag; the tag names the generated interface"
                            + " (Hello -> HelloApi), and two tags would put the operation in two interfaces");
                }
                if (MUTATING.contains(method) && !refersToIdempotencyKey(operation)) {
                    problems.add(where + ": a mutating operation must carry the Idempotency-Key header:"
                            + " parameters: - $ref: '" + IDEMPOTENCY_REF + "'");
                }
            });
        });
        keysWithoutAValue("", document, problems);
        return problems;
    }

    /**
     * A key with no value is never meant in a slice. It is what YAML makes of a comma inside an
     * inline map: in { type: string, description: A name, or so } the description ends at the
     * comma and "or so" becomes a key. Both generators accept it and cut the text short.
     */
    private static void keysWithoutAValue(String where, Object node, List<String> problems) {
        if (node instanceof Map<?, ?> entries) {
            entries.forEach((key, value) -> {
                String here = where.isEmpty() ? String.valueOf(key) : where + "." + key;
                if (value == null) {
                    problems.add(where + ": the key \"" + key + "\" has no value; a comma inside an inline"
                            + " { description: ... } ends the text there: write the description on its own line");
                } else {
                    keysWithoutAValue(here, value, problems);
                }
            });
        } else if (node instanceof List<?> items) {
            items.forEach(item -> keysWithoutAValue(where, item, problems));
        }
    }

    private static boolean refersToIdempotencyKey(Map<String, Object> operation) {
        return operation.get("parameters") instanceof List<?> parameters
                && parameters.stream().anyMatch(p -> IDEMPOTENCY_REF.equals(map(p).get("$ref")));
    }

    private static Map<String, Object> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return map(new Yaml().load(reader));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
