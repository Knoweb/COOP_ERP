package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The contract of the slice's operations (29A section 9, "Contract: every operation"), through
 * HTTP as the web client calls them: an export requested, listed, read line by line, downloaded
 * as the journal file and reconciled; what waits for the next export; another entity answered
 * 404; the notification templates, rules, the rule toggle of the Federation and the delivery log.
 */
class IntegrationHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0190e9c0-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final String RULE = "0190f9a0-0000-7000-8000-000000000006";

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void arrange() {
        superuserJdbc()
                .execute("truncate table integration.journal_line, integration.journal_export,"
                        + " integration.journal_posting");
        superuserJdbc()
                .update("update integration.notification_rule set status = 'ACTIVE' where rule_id = ?::uuid", RULE);
        UUID invoice = Ids.next();
        posting(invoice, 1, "INV", "RECEIVABLE", "REVENUE", "2000.00", LocalDate.of(2026, 8, 3));
        posting(invoice, 2, "INV", "RECEIVABLE", "VAT_OUTPUT", "360.00", LocalDate.of(2026, 8, 3));
        posting(Ids.next(), 1, "PRC", "BANK_OR_CASH", "RECEIVABLE", "1000.00", LocalDate.of(2026, 8, 9));
    }

    @Test
    void anExportFromRequestToFileAndReconciliation() {
        ResponseEntity<JsonNode> pending =
                get("/v1/integration/journal-postings/pending?from=2026-08-01&to=2026-08-31", as(SELLER));
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody().path("postings").asInt()).isEqualTo(3);
        assertThat(pending.getBody().path("amount").decimalValue()).isEqualByComparingTo("3360.00");

        ResponseEntity<JsonNode> created = post(
                "/v1/integration/journal-exports",
                Map.of("periodFrom", "2026-08-01", "periodTo", "2026-08-31"),
                as(SELLER));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String exportId = created.getBody().path("exportId").asText();
        assertThat(created.getHeaders().getLocation()).hasToString("/v1/integration/journal-exports/" + exportId);
        assertThat(created.getBody().path("status").asText()).isEqualTo("GENERATED");
        assertThat(created.getBody().path("lineCount").asInt()).isEqualTo(3);
        assertThat(created.getBody().path("totalDebit").decimalValue()).isEqualByComparingTo("3360.00");
        assertThat(created.getBody().path("contentHash").asText()).hasSize(64);

        ResponseEntity<JsonNode> list = get("/v1/integration/journal-exports", as(SELLER));
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<JsonNode> lines = get("/v1/integration/journal-exports/" + exportId + "/lines", as(SELLER));
        assertThat(lines.getBody()).hasSize(3);
        assertThat(lines.getBody().get(0).path("docType").asText()).isEqualTo("INV");
        assertThat(lines.getBody().get(0).path("side").asText()).isEqualTo("SELLER");

        ResponseEntity<String> file = http.exchange(
                "/v1/integration/journal-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(as(SELLER)),
                String.class);
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(file.getHeaders().getContentType().isCompatibleWith(MediaType.parseMediaType("text/csv")))
                .isTrue();
        assertThat(file.getHeaders().getContentDisposition().getFilename())
                .startsWith("journal_2026-08-01_2026-08-31_")
                .endsWith(".csv");
        assertThat(file.getBody().lines()).hasSize(7);

        ResponseEntity<JsonNode> reconciliation =
                get("/v1/integration/journal-exports/" + exportId + "/reconciliation", as(SELLER));
        assertThat(reconciliation.getBody().path("balanced").asBoolean()).isTrue();
        assertThat(reconciliation.getBody().path("matchesRecordedTotals").asBoolean())
                .isTrue();
        assertThat(reconciliation.getBody().path("matchesRecordedHash").asBoolean())
                .isTrue();
        assertThat(reconciliation.getBody().path("accounts")).hasSize(4);

        // Another entity learns nothing, not even that the export exists.
        assertThat(get("/v1/integration/journal-exports/" + exportId, as(OTHER)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/integration/journal-exports/" + exportId + "/lines", as(OTHER))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange(
                                "/v1/integration/journal-exports/" + exportId + "/file",
                                HttpMethod.GET,
                                new HttpEntity<>(as(OTHER)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/integration/journal-exports", as(OTHER)).getBody()).isEmpty();

        // The same period again: nothing is left to export.
        ResponseEntity<JsonNode> again = post(
                "/v1/integration/journal-exports",
                Map.of("periodFrom", "2026-08-01", "periodTo", "2026-08-31"),
                as(SELLER));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(again.getBody().path("code").asText()).isEqualTo("m9.journal.nothing_to_export");
    }

    @Test
    void aRequestWithoutAPeriodIsRefusedByItsShape() {
        ResponseEntity<JsonNode> refused =
                post("/v1/integration/journal-exports", Map.of("periodFrom", "2026-08-01"), as(SELLER));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("request.invalid");
    }

    @Test
    void theNotificationScreensReadTemplatesRulesAndTheLog() {
        ResponseEntity<JsonNode> templates = get("/v1/integration/templates", as(SELLER));
        assertThat(templates.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(templates.getBody().size()).isGreaterThanOrEqualTo(6);
        assertThat(templates.getBody().get(0).path("bodySi").asText()).isNotBlank();

        ResponseEntity<JsonNode> rules = get("/v1/integration/rules", as(SELLER));
        assertThat(rules.getBody().size()).isGreaterThanOrEqualTo(6);
        assertThat(rules.getBody().get(0).path("federationWide").asBoolean()).isTrue();

        ResponseEntity<JsonNode> log = get("/v1/integration/notifications/log?status=SENT&limit=5", as(SELLER));
        assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(log.getBody().isArray()).isTrue();

        ResponseEntity<JsonNode> badStatus = get("/v1/integration/notifications/log?status=LOST", as(SELLER));
        assertThat(badStatus.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void theFederationRetiresARuleAndOthersMayNot() {
        ResponseEntity<JsonNode> refused = post("/v1/integration/rules/" + RULE + "/retire", null, as(SELLER));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("m9.rule.federation_required");

        ResponseEntity<JsonNode> retired = post("/v1/integration/rules/" + RULE + "/retire", null, as(TEST_FEDERATION));
        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retired.getBody().path("status").asText()).isEqualTo("RETIRED");

        ResponseEntity<JsonNode> active =
                post("/v1/integration/rules/" + RULE + "/activate", null, as(TEST_FEDERATION));
        assertThat(active.getBody().path("status").asText()).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------------------------------

    private void posting(
            UUID document, int seq, String type, String debit, String credit, String amount, LocalDate day) {
        superuserJdbc()
                .update(
                        """
                        insert into integration.journal_posting
                               (posting_id, owner_entity_id, document_id, seq, doc_type_code, doc_number_display,
                                line_kind, side, debit_role, credit_role, amount_source, amount, business_date,
                                recorded_at)
                        values (?, ?, ?, ?, ?, ?, 'GOODS', 'SELLER', ?, ?, 'net', ?, ?, ?)
                        """,
                        Ids.next(),
                        SELLER,
                        document,
                        seq,
                        type,
                        "D101-" + type + "-" + document.toString().substring(24),
                        debit,
                        credit,
                        new BigDecimal(amount),
                        Date.valueOf(day),
                        Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")));
    }

    private ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String url, Object body, HttpHeaders headers) {
        HttpHeaders withKey = new HttpHeaders();
        withKey.addAll(headers);
        withKey.set("Idempotency-Key", Ids.next().toString());
        withKey.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, withKey), JsonNode.class);
    }

    private static HttpHeaders as(UUID entity) {
        return TestIdentityProvider.entityWideHeaders(USER, entity);
    }
}
