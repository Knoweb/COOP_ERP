package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.OFFICE_USER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.OTHER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.OTHER_USER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
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
 * The slice over HTTP (27A section 5, back office): the office registers a member, confirms a
 * reused number, opens the account, the till's charge arrives, the office records a repayment and
 * the statement shows it; the kernel refuses a malformed request (400) and another society sees
 * nothing (404). The statement's period is read from the account's own postings, not from today.
 */
class CustomerHttpIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    Handles<PostAccountTender, UUID> tender;

    @BeforeEach
    void arrange() {
        CustomersFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void registerOpenChargeRepayAndTheStatementShowsIt() {
        ResponseEntity<JsonNode> registered = post(
                "/v1/customers",
                Map.of(
                        "displayName", "K. Perera",
                        "displayNameSi", "කේ. පෙරේරා",
                        "language", "si",
                        "phone", "070 000 0201",
                        "consents", List.of("CREDIT_ACCOUNT", "STATEMENTS_NOTIFICATIONS"),
                        "via", "PAPER",
                        "tags", List.of("regular")),
                OFFICE_USER,
                SOCIETY);
        assertThat(registered.getStatusCode())
                .as(String.valueOf(registered.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String customerId = registered.getBody().get("customerId").asText();
        assertThat(registered.getBody().get("phone").asText()).isEqualTo("+94700000201");
        assertThat(registered.getBody().get("consents")).hasSize(2);

        JsonNode found = get("/v1/customers?q=perera", OFFICE_USER, SOCIETY).getBody();
        assertThat(found)
                .singleElement()
                .satisfies(row -> assertThat(row.get("customerId").asText()).isEqualTo(customerId));
        assertThat(get("/v1/customers?phone=0700000201", OFFICE_USER, SOCIETY).getBody())
                .hasSize(1);

        ResponseEntity<JsonNode> opened = post(
                "/v1/customers/" + customerId + "/accounts",
                Map.of("creditLimit", 15000, "nic", "190000000101"),
                OFFICE_USER,
                SOCIETY);
        assertThat(opened.getStatusCode()).as(String.valueOf(opened.getBody())).isEqualTo(HttpStatus.CREATED);
        String accountId = opened.getBody().get("accountId").asText();
        assertThat(opened.getBody().get("available").decimalValue()).isEqualByComparingTo("15000");

        LocalDate saleDay = LocalDate.of(2026, 9, 1);
        tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        UUID.fromString(accountId),
                        new BigDecimal("3500.00"),
                        UUID.randomUUID(),
                        "M7S-S1-T1-0000001",
                        1,
                        SHOP,
                        saleDay,
                        null,
                        false),
                CustomersFixture.till());

        ResponseEntity<JsonNode> paid = post(
                "/v1/accounts/" + accountId + "/payments",
                Map.of("method", "CASH", "amount", 2000, "reference", "Paid at the office"),
                OFFICE_USER,
                SOCIETY);
        assertThat(paid.getStatusCode()).as(String.valueOf(paid.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(paid.getBody().get("docNumber").asText()).isEqualTo("M7S-CPR-0000001");
        assertThat(paid.getBody().get("allocated").decimalValue()).isEqualByComparingTo("2000");
        assertThat(paid.getBody().get("balance").decimalValue()).isEqualByComparingTo("1500");

        JsonNode card = get("/v1/customers/" + customerId, OFFICE_USER, SOCIETY).getBody();
        assertThat(card.get("account").get("balance").decimalValue()).isEqualByComparingTo("1500");
        assertThat(card.get("nicLast4").asText()).isEqualTo("0101");
        assertThat(card.toString()).doesNotContain("190000000101");

        // The period from the sale day to the payment's own business date, whatever today is.
        String paymentDay = superuserJdbc()
                .queryForObject(
                        "select business_date::text from customers.account_posting where kind = 'PAYMENT' and account_id = ?::uuid",
                        String.class,
                        accountId);
        JsonNode statement = get(
                        "/v1/accounts/" + accountId + "/statement?from=" + saleDay + "&to=" + paymentDay,
                        OFFICE_USER,
                        SOCIETY)
                .getBody();
        assertThat(statement.get("openingBalance").decimalValue()).isEqualByComparingTo("0");
        assertThat(statement.get("closingBalance").decimalValue()).isEqualByComparingTo("1500");
        JsonNode lines = statement.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("kind").asText()).isEqualTo("CHARGE");
        assertThat(lines.get(0).get("settled").decimalValue()).isEqualByComparingTo("2000");
        assertThat(lines.get(1).get("kind").asText()).isEqualTo("PAYMENT");
        assertThat(lines.get(1).get("documentNumber").asText()).isEqualTo("M7S-CPR-0000001");
        assertThat(lines.get(1).get("runningBalance").decimalValue()).isEqualByComparingTo("1500");

        // Another society sees neither the customer nor the account.
        assertThat(get("/v1/customers/" + customerId, OTHER_USER, OTHER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/accounts/" + accountId, OTHER_USER, OTHER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/customers?q=perera", OTHER_USER, OTHER).getBody()).isEmpty();
    }

    @Test
    void limitsStatesAdjustmentsReversalAndPrivacyOverHttp() {
        String customerId = post(
                        "/v1/customers",
                        Map.of(
                                "displayName", "Gamini Ekanayake",
                                "phone", "0700000211",
                                "consents", List.of("CREDIT_ACCOUNT"),
                                "via", "PAPER"),
                        OFFICE_USER,
                        SOCIETY)
                .getBody()
                .get("customerId")
                .asText();
        String accountId = post(
                        "/v1/customers/" + customerId + "/accounts",
                        Map.of("creditLimit", 10000, "nic", "190000000211"),
                        OFFICE_USER,
                        SOCIETY)
                .getBody()
                .get("accountId")
                .asText();

        // A lower limit and a hard block: no second factor. A higher limit asks for one (401).
        ResponseEntity<JsonNode> lowered = post(
                "/v1/accounts/" + accountId + "/limits",
                Map.of("creditLimit", 8000, "hardBlock", true, "reason", "Missed a month"),
                OFFICE_USER,
                SOCIETY);
        assertThat(lowered.getStatusCode())
                .as(String.valueOf(lowered.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(lowered.getBody().get("hardBlock").asBoolean()).isTrue();
        ResponseEntity<JsonNode> raised = post(
                "/v1/accounts/" + accountId + "/limits",
                Map.of("creditLimit", 20000, "reason", "Pays on time"),
                OFFICE_USER,
                SOCIETY);
        assertThat(raised.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(String.valueOf(raised.getBody())).contains("mfa.required");
        assertThat(post("/v1/accounts/" + accountId + "/limits", Map.of("creditLimit", 1), OFFICE_USER, SOCIETY)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> suspended =
                post("/v1/accounts/" + accountId + "/suspend", Map.of("reason", "Overdue"), OFFICE_USER, SOCIETY);
        assertThat(suspended.getBody().get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(post("/v1/accounts/" + accountId + "/suspend", Map.of("reason", "Again"), OFFICE_USER, SOCIETY)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post("/v1/accounts/" + accountId + "/reinstate", Map.of("reason", "Paid"), OFFICE_USER, SOCIETY)
                        .getBody()
                        .get("status")
                        .asText())
                .isEqualTo("OPEN");
        JsonNode history = get("/v1/accounts/" + accountId + "/history", OFFICE_USER, SOCIETY)
                .getBody();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("action").asText()).isEqualTo("REINSTATED");
        assertThat(get("/v1/accounts/" + accountId + "/history", OTHER_USER, OTHER)
                        .getBody())
                .isEmpty();

        // A repayment recorded in error, reversed.
        String paymentId = post(
                        "/v1/accounts/" + accountId + "/payments",
                        Map.of("method", "DEPOSIT", "amount", 400),
                        OFFICE_USER,
                        SOCIETY)
                .getBody()
                .get("documentId")
                .asText();
        ResponseEntity<JsonNode> reversed = post(
                "/v1/customer-payments/" + paymentId + "/reverse",
                Map.of("reason", "Deposit bounced"),
                OFFICE_USER,
                SOCIETY);
        assertThat(reversed.getStatusCode())
                .as(String.valueOf(reversed.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        assertThat(reversed.getBody().get("docNumber").asText()).isEqualTo("M7S-CPR-0000002");
        assertThat(reversed.getBody().get("balance").decimalValue()).isEqualByComparingTo("0");

        // An adjustment: asked by the office, approved by another clerk.
        ResponseEntity<JsonNode> asked = post(
                "/v1/accounts/" + accountId + "/adjustments",
                Map.of("amount", 150, "reason", "Bank charge on the bounced deposit"),
                OFFICE_USER,
                SOCIETY);
        assertThat(asked.getStatusCode()).as(String.valueOf(asked.getBody())).isEqualTo(HttpStatus.CREATED);
        String adjustmentId = asked.getBody().get("adjustmentId").asText();
        String approve = "/v1/accounts/" + accountId + "/adjustments/" + adjustmentId + "/approve";
        assertThat(post(approve, null, OFFICE_USER, SOCIETY).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ResponseEntity<JsonNode> approved = post(approve, null, CustomersFixture.SECOND_CLERK, SOCIETY);
        assertThat(approved.getStatusCode())
                .as(String.valueOf(approved.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asText()).isEqualTo("APPROVED");
        assertThat(get("/v1/accounts/" + accountId, OFFICE_USER, SOCIETY)
                        .getBody()
                        .get("balance")
                        .decimalValue())
                .isEqualByComparingTo("150");

        // An access request: recorded by the office, answered by the responsible officer only.
        ResponseEntity<JsonNode> recorded =
                post("/v1/privacy/requests", Map.of("customerId", customerId, "kind", "ACCESS"), OFFICE_USER, SOCIETY);
        assertThat(recorded.getStatusCode())
                .as(String.valueOf(recorded.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String requestId = recorded.getBody().get("requestId").asText();
        assertThat(recorded.getBody().get("customerName").asText()).isEqualTo("Gamini Ekanayake");
        CustomersFixture.appointOfficer(superuserJdbc());
        ResponseEntity<JsonNode> notTheOfficer =
                post("/v1/privacy/requests/" + requestId + "/fulfil", Map.of(), OFFICE_USER, SOCIETY);
        assertThat(String.valueOf(notTheOfficer.getBody())).contains("m7.privacy.officer_only");
        ResponseEntity<JsonNode> fulfilled = post(
                "/v1/privacy/requests/" + requestId + "/fulfil",
                Map.of("outcome", "Printed and handed over"),
                CustomersFixture.OFFICER_USER,
                SOCIETY);
        assertThat(fulfilled.getStatusCode())
                .as(String.valueOf(fulfilled.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(fulfilled.getBody().get("status").asText()).isEqualTo("FULFILLED");
        JsonNode export = get("/v1/privacy/requests/" + requestId + "/export", CustomersFixture.OFFICER_USER, SOCIETY)
                .getBody();
        assertThat(export.get("customer").get(0).get("display_name").asText()).isEqualTo("Gamini Ekanayake");
        assertThat(export.get("postings")).hasSize(3);
        assertThat(get("/v1/privacy/requests?status=FULFILLED", OFFICE_USER, SOCIETY)
                        .getBody())
                .hasSize(1);
        assertThat(get("/v1/privacy/requests", OTHER_USER, OTHER).getBody()).isEmpty();
        assertThat(get("/v1/privacy/requests/" + requestId + "/export", OTHER_USER, OTHER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theShapeOfARequestIsTheSlicesToRefuseAndARuleIsTheHandlers() {
        ResponseEntity<JsonNode> noPhone =
                post("/v1/customers", Map.of("displayName", "No phone", "consents", List.of()), OFFICE_USER, SOCIETY);
        assertThat(noPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<JsonNode> barter = post(
                "/v1/accounts/" + UUID.randomUUID() + "/payments",
                Map.of("method", "BARTER", "amount", 10),
                OFFICE_USER,
                SOCIETY);
        assertThat(barter.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<JsonNode> noConsent = post(
                "/v1/customers",
                Map.of("displayName", "No consent", "phone", "0700000202", "consents", List.of()),
                OFFICE_USER,
                SOCIETY);
        assertThat(noConsent.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(noConsent.getBody())).contains("m7.customer.consent_required");
    }

    private ResponseEntity<JsonNode> post(String path, Object body, UUID user, UUID entity) {
        HttpHeaders headers = headers(user, entity);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, UUID user, UUID entity) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(user, entity)), JsonNode.class);
    }

    private static HttpHeaders headers(UUID user, UUID entity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(user, entity));
        headers.set("X-Scope-Entity", entity.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
