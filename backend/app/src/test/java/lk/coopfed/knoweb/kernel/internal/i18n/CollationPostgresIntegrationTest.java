package lk.coopfed.knoweb.kernel.internal.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.TextNormaliser;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

/**
 * Doc 19 section 5.4 against PostgreSQL: the ICU collations of the baseline sort the curated
 * test set ({@code i18n/sort-test-set.yaml}) in its known order, equivalent spellings sort
 * together once normalised, and text written through a handler reaches the table in NFC (the
 * kernel's converter at the write boundary).
 */
class CollationPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190b600-0000-7000-8000-000000000001");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Test
    void theCollationsSortTheCuratedNamesInTheirKnownOrder() throws Exception {
        Map<String, Object> set = testSet();
        assertSorted("si", listOf(set, "sinhala_in_order"));
        assertSorted("ta", listOf(set, "tamil_in_order"));
        assertSorted("en", listOf(set, "english_in_order"));
    }

    @Test
    void equivalentSpellingsAreOneNameOnceNormalised() throws Exception {
        Map<String, Object> set = testSet();
        @SuppressWarnings("unchecked")
        List<List<String>> pairs = (List<List<String>>) set.get("equivalent_spellings");
        assertThat(pairs).isNotEmpty();
        for (List<String> pair : pairs) {
            assertThat(pair.get(0)).isNotEqualTo(pair.get(1));
            assertThat(TextNormaliser.nfc(pair.get(0))).isEqualTo(TextNormaliser.nfc(pair.get(1)));

            // PostgreSQL itself: the ICU collations of the baseline are deterministic, so the two
            // raw spellings are two values that sort next to each other (equal at every ICU
            // level, told apart by their bytes), and one value once normalised. That is why the
            // write boundary normalises and a lookup parameter must be normalised too.
            String language = pair.get(0).codePoints().anyMatch(cp -> cp >= 0x0D80 && cp <= 0x0DFF)
                    ? "si"
                    : pair.get(0).codePoints().anyMatch(cp -> cp >= 0x0B80 && cp <= 0x0BFF) ? "ta" : "en";
            String collation = "kernel." + language + "_icu";
            assertThat(jdbc.queryForObject(
                            "select count(distinct n collate " + collation + ") from (values (?), (?)) as t(n)",
                            Long.class,
                            pair.get(0),
                            pair.get(1)))
                    .as("raw spellings, %s", language)
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                            "select count(distinct n collate " + collation + ") from (values (?), (?)) as t(n)",
                            Long.class,
                            TextNormaliser.nfc(pair.get(0)),
                            TextNormaliser.nfc(pair.get(1))))
                    .as("normalised spellings, %s", language)
                    .isEqualTo(1L);
            // Adjacent: with a name that sorts before and one after, the pair stays together.
            List<String> sorted = jdbc.queryForList(
                    "select n from (values (?), (?), (?), (?)) as t(n) order by n collate " + collation,
                    String.class,
                    pair.get(1),
                    "\u0001",
                    pair.get(0),
                    "\uFFFF");
            int first = sorted.indexOf(pair.get(0));
            int second = sorted.indexOf(pair.get(1));
            assertThat(Math.abs(first - second)).as("adjacent, %s", language).isEqualTo(1);
        }
    }

    @Test
    void theCollationVersionsOfTheDatabaseMatchTheOnesTheyWereCreatedWith() {
        // After a PostgreSQL image upgrade with another libicu, an index on a collated column is
        // silently wrong until REINDEX and ALTER COLLATION ... REFRESH VERSION; PostgreSQL only
        // warns. This makes the pipeline fail instead.
        List<Map<String, Object>> collations = superuserJdbc()
                .queryForList("select collname, collversion, pg_collation_actual_version(oid) as actual"
                        + " from pg_collation where collname in ('en_icu', 'si_icu', 'ta_icu')");
        assertThat(collations).hasSize(3);
        for (Map<String, Object> collation : collations) {
            assertThat(collation.get("collversion"))
                    .as("%s", collation.get("collname"))
                    .isNotNull()
                    .isEqualTo(collation.get("actual"));
        }
    }

    @Test
    void textWrittenThroughAHandlerReachesTheTableInNfc() {
        String decomposed = "Café " + UUID.randomUUID();
        assertThat(Normalizer.isNormalized(decomposed, Normalizer.Form.NFC)).isFalse();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(UUID.randomUUID(), ENTITY));
        headers.set("X-Scope-Entity", ENTITY.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/hello/greetings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("textEn", decomposed), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String stored = superuserJdbc()
                .queryForObject(
                        "select text_en from hello.greeting where id = ?",
                        String.class,
                        UUID.fromString(response.getBody().get("id").asText()));
        assertThat(Normalizer.isNormalized(stored, Normalizer.Form.NFC)).isTrue();
        assertThat(stored).isEqualTo(TextNormaliser.nfc(decomposed));
    }

    private void assertSorted(String language, List<String> expected) {
        // Shuffle by reversing, let PostgreSQL sort with the language's collation, compare.
        List<String> reversed = new java.util.ArrayList<>(expected);
        java.util.Collections.reverse(reversed);
        String values = String.join(", ", java.util.Collections.nCopies(reversed.size(), "(?)"));
        List<String> sorted = jdbc.queryForList(
                "select n from (values " + values + ") as t(n) order by n collate kernel." + language + "_icu",
                String.class,
                reversed.toArray());
        assertThat(sorted).as(language).isEqualTo(expected);
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Map<String, Object> set, String key) {
        return (List<String>) set.get(key);
    }

    private static Map<String, Object> testSet() throws Exception {
        try (InputStream in = new ClassPathResource("i18n/sort-test-set.yaml").getInputStream()) {
            return new Yaml().load(in);
        }
    }
}
