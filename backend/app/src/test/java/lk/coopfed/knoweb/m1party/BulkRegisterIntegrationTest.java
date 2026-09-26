package lk.coopfed.knoweb.m1party;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * M1-11 end to end: a CSV file through {@code POST /v1/party/bulk-register} as the Federation.
 * The proof rows of 21A section 11: a file with a bad row registers nothing and says which row
 * and why; a good file registers every row with one audit record and one event each, in one
 * transaction; forty societies go in well under the sixty seconds the guide allows.
 */
class BulkRegisterIntegrationTest extends PostgresIntegrationTest {

    private static final String URL = "/v1/party/bulk-register";
    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final String HEADER = "entity_code,entity_type,legal_name_en,legal_name_si,default_language\n";

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void theFederationExists() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table party.entity_relationship, party.entity_party_directory,"
                + " party.federation_identity, party.entity cascade");
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, ?, ?)",
                FEDERATION,
                "FED001",
                "FEDERATION",
                "Cooperative Federation");
    }

    @Test
    void aFileWithABadRowRegistersNothingAndSaysWhichRowAndWhy() {
        String csv = HEADER
                + "M001,MPCS,Society One,,si\n"
                + ",MPCS,No code,,en\n" // line 3: code missing
                + "M001,MPCS,Same code again,,en\n" // line 4: duplicate in file
                + "M003,SHOP,Wrong type,,en\n"; // line 5: type not allowed

        ResponseEntity<JsonNode> response = upload(csv);

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        JsonNode report = response.getBody();
        assertThat(report.get("status").asText()).isEqualTo("REJECTED");
        assertThat(report.get("rows").asInt()).isEqualTo(4);
        assertThat(report.get("registered").asInt()).isZero();
        assertThat(report.get("rejected").asInt()).isEqualTo(3);
        assertThat(report.get("results").get(0).get("status").asText()).isEqualTo("OK");
        assertThat(report.get("results").get(1).get("line").asInt()).isEqualTo(3);
        assertThat(report.get("results")
                        .get(1)
                        .get("problems")
                        .get(0)
                        .get("code")
                        .asText())
                .isEqualTo("bulk.row.value_required");
        assertThat(report.get("results")
                        .get(2)
                        .get("problems")
                        .get(0)
                        .get("code")
                        .asText())
                .isEqualTo("bulk.row.duplicate_in_file");
        assertThat(report.get("results")
                        .get(3)
                        .get("problems")
                        .get(0)
                        .get("field")
                        .asText())
                .isEqualTo("entity_type");

        assertThat(entitiesRegistered())
                .as("all or none: the good row is not in either")
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aGoodFileRegistersEveryRowWithAnAuditRecordAndAnEventEach() {
        String csv = HEADER + "M001,MPCS,Society One,සමිතිය එක,si\n" + "D001,DISTRIBUTOR,Distributor One,,en\n";

        ResponseEntity<JsonNode> response = upload(csv);

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        JsonNode report = response.getBody();
        assertThat(report.get("status").asText()).isEqualTo("REGISTERED");
        assertThat(report.get("registered").asInt()).isEqualTo(2);
        assertThat(report.get("results").get(0).get("entityId").asText()).isNotBlank();

        assertThat(entitiesRegistered()).isEqualTo(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select legal_name_si from party.entity where entity_code = 'M001'", String.class))
                .isEqualTo("සමිතිය එක");
        assertThat(kernel.committedAudit()).hasSize(2);
        assertThat(kernel.committedEvents()).hasSize(2);
    }

    @Test
    void aCodeAlreadyInTheRegisterIsReported() {
        upload(HEADER + "M001,MPCS,Society One,,en\n");

        JsonNode report = upload(HEADER + "M001,MPCS,Society One again,,en\n").getBody();

        assertThat(report.get("status").asText()).isEqualTo("REJECTED");
        assertThat(report.get("results")
                        .get(0)
                        .get("problems")
                        .get(0)
                        .get("code")
                        .asText())
                .isEqualTo("bulk.row.code_exists");
        assertThat(entitiesRegistered()).isEqualTo(1);
    }

    @Test
    void aFileWithoutTheRequiredColumnsIsRefused() {
        ResponseEntity<JsonNode> response = upload("code,name\nM001,One\n");

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("bulk.file.header_invalid");
    }

    @Test
    void anEmptyFileIsRefused() {
        ResponseEntity<JsonNode> response = upload(HEADER);

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("bulk.file.empty");
    }

    @Test
    void anMpcsCannotBulkRegister() {
        UUID mpcs = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        superuserJdbc()
                .update(
                        "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, ?, ?)",
                        mpcs,
                        "M000",
                        "MPCS",
                        "An MPCS");

        ResponseEntity<JsonNode> response = upload(HEADER + "M001,MPCS,Society One,,en\n", mpcs);

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("m1.entity.federation_required");
    }

    @Test
    void fortySocietiesGoInWellUnderTheMinuteTheGuideAllows() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 40; i++) {
            csv.append(String.format("M%03d,MPCS,Society %d,,en%n", i, i));
        }

        Instant before = Instant.now();
        JsonNode report = upload(csv.toString()).getBody();
        Duration took = Duration.between(before, Instant.now());

        assertThat(report.get("status").asText()).isEqualTo("REGISTERED");
        assertThat(report.get("registered").asInt()).isEqualTo(40);
        assertThat(entitiesRegistered()).isEqualTo(40);
        // 21A section 11: 40 shops, 120 users, 60 devices under 60 s; the entities alone take a
        // small fraction of that. The bound here is loose on purpose: a CI machine is slow and
        // the point is the order of magnitude, not a benchmark.
        assertThat(took).isLessThan(Duration.ofSeconds(20));
    }

    // ---- helpers ----

    private ResponseEntity<JsonNode> upload(String csv) {
        return upload(csv, FEDERATION);
    }

    private ResponseEntity<JsonNode> upload(String csv, UUID asEntity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(USER, asEntity));
        headers.set("X-Scope-Entity", asEntity.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource file = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "societies.csv";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);

        return http.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private int entitiesRegistered() {
        return superuserJdbc()
                .queryForObject("select count(*) from party.entity where entity_type <> 'FEDERATION'", Integer.class);
    }
}
