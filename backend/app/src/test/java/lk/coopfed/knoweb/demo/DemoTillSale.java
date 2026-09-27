package lk.coopfed.knoweb.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.demo.DemoCatalogue.Item;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import lk.coopfed.knoweb.testsupport.TillSimulator.Sale;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * {@code make demo-till-sale}: a sale at Kuliyapitiya town shop (M101 S01) without an Android
 * device (docs/DEMO.md, phase 3). Against the local stack, with the demo users, it does what a
 * shop's administrator and its till do:
 *
 * <ol>
 *   <li>signs in as {@code m101-manager} (password {@code demo}); registers the demo till
 *       {@code DEMO-TILL-S01} at the shop and puts it on till position 1 when it is not there yet
 *       (M1), and issues it a one-time enrolment code (K-08);
 *   <li>enrols the till with the code: its credential at the identity provider, its next device
 *       sequence, its receipt series and central's signing key; takes a device token;
 *   <li>runs the {@link TillSimulator}: takes the snapshot, opens a session, sells three items by
 *       barcode, closes the session and uploads through the sync contract;
 *   <li>waits for the relay to deliver the facts to M6 and M5, and prints the shop's stock of
 *       those items before and after, and the receipt as central keeps it.
 * </ol>
 *
 * Each run enrols the till again (a new secret, the same device and sequence) and makes one more
 * sale. Settings: {@code COOP_ERP_API} (default http://localhost:8080) and
 * {@code COOP_ERP_TOKEN_URL} (default the coop realm of the local Keycloak on :8085).
 */
public final class DemoTillSale {

    private static final String SERIAL = "DEMO-TILL-S01";
    private static final String APP_VERSION = "1.0.0";

    private final ObjectMapper json = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final String api;
    private final String tokenUrl;
    private final TestRestTemplate http;

    private DemoTillSale(String api, String tokenUrl) {
        this.api = api;
        this.tokenUrl = tokenUrl;
        this.http = new TestRestTemplate(new RestTemplateBuilder().rootUri(api));
    }

    public static void main(String[] args) throws Exception {
        String api = System.getenv().getOrDefault("COOP_ERP_API", "http://localhost:8080");
        String tokenUrl = System.getenv()
                .getOrDefault("COOP_ERP_TOKEN_URL", "http://localhost:8085/realms/coop/protocol/openid-connect/token");
        new DemoTillSale(api, tokenUrl).run();
    }

    private void run() throws Exception {
        UUID shop = DemoCast.M101_TOWN_SHOP;
        HttpHeaders manager = userHeaders("m101-manager", "demo");

        // 1. The till at the shop, on position 1, with a one-time code.
        UUID position = null;
        for (JsonNode p : call(HttpMethod.GET, "/v1/party/locations/" + shop + "/positions", null, manager)) {
            if (p.path("positionNo").asInt() == 1) {
                position = UUID.fromString(p.path("tillPositionId").asText());
            }
        }
        if (position == null) {
            throw new IllegalStateException("Kuliyapitiya town shop has no till position 1: run make demo-data first");
        }
        JsonNode device = null;
        for (JsonNode d : call(HttpMethod.GET, "/v1/party/devices?locationId=" + shop, null, manager)) {
            if (SERIAL.equals(d.path("hardwareSerial").asText())
                    && !"RETIRED".equals(d.path("status").asText())) {
                device = d;
            }
        }
        if (device == null) {
            Map<String, Object> enrol = new LinkedHashMap<>();
            enrol.put("hardwareSerial", SERIAL);
            enrol.put("deviceKind", "POS_TERMINAL");
            enrol.put("locationId", shop.toString());
            enrol.put("appVersion", APP_VERSION);
            enrol.put("stagingReference", "DEMO");
            device = call(HttpMethod.POST, "/v1/party/devices", enrol, manager);
            System.out.println("Registered the demo till " + SERIAL + " at Kuliyapitiya town shop");
        }
        UUID deviceId = UUID.fromString(device.path("deviceId").asText());
        if (device.path("tillPositionId").isMissingNode()
                || device.path("tillPositionId").isNull()) {
            call(
                    HttpMethod.POST,
                    "/v1/party/devices/" + deviceId + "/assign",
                    Map.of("tillPositionId", position.toString(), "reasonCode", "OPENING"),
                    manager);
            System.out.println("Put the demo till on till position 1");
        }
        String code = call(HttpMethod.POST, "/v1/sync/devices/" + deviceId + "/enrolment-codes", null, manager)
                .path("code")
                .asText();

        // 2. The till enrols: credential, sequence, series, signing key.
        JsonNode enrolled = call(
                HttpMethod.POST,
                "/v1/sync/devices/" + deviceId + "/enrol",
                Map.of("enrolment_code", code, "hardware_serial", SERIAL, "app_version", APP_VERSION),
                new HttpHeaders());
        JsonNode rct = null;
        for (JsonNode series : enrolled.path("series")) {
            if ("RCT".equals(series.path("doc_type_code").asText())) {
                rct = series;
            }
        }
        if (rct == null) {
            throw new IllegalStateException("The enrolment answer carries no RCT series for the till position");
        }
        // Central raises the series' next number from every receipt it applies (doc 32 section 8),
        // so each run's fresh till goes on after the last run's receipt: 1, 2, 3 ...
        System.out.println("The till numbers its receipts from "
                + rct.path("prefix").asText() + "-" + rct.path("next_number").asLong() + ", as central reports");
        String deviceToken = token(Map.of(
                "grant_type", "client_credentials",
                "client_id", enrolled.path("credential").path("client_id").asText(),
                "client_secret",
                        enrolled.path("credential").path("client_secret").asText()));

        // 3. The sale.
        List<Item> items = new ArrayList<>();
        for (Item item : DemoCatalogue.load()) {
            if (item.barcode() != null && items.size() < 3) {
                items.add(item);
            }
        }
        Map<String, BigDecimal> before = onHand(shop, manager);
        TillSimulator till = new TillSimulator(
                        http,
                        json,
                        deviceId,
                        shop,
                        enrolled.path("signing_key").path("public_key").asText())
                .authenticatedBy(() -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setBearerAuth(deviceToken);
                    return headers;
                })
                .startingAt(enrolled.path("next_device_seq").asLong())
                .sellsAs(
                        DemoCast.M101,
                        position,
                        UUID.fromString(rct.path("series_id").asText()),
                        rct.path("prefix").asText())
                .numberingFrom(rct.path("next_number").asLong());
        till.refreshSnapshot();
        BigDecimal floatAmount = new BigDecimal("2000.00");
        till.openSession(floatAmount);
        List<Sale> sales = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        int qty = 1;
        for (Item item : items) {
            BigDecimal price = item.printedMrp() != null ? item.printedMrp() : item.distributorPrice();
            sales.add(new Sale(item.barcode(), BigDecimal.valueOf(qty), price));
            gross = gross.add(price.multiply(BigDecimal.valueOf(qty)));
            qty++;
        }
        UUID receipt = till.sell(sales);
        till.closeSession(floatAmount.add(gross));
        till.drain(50, Instant.now().plusSeconds(60));
        System.out.println("The till sold " + items.size() + " items by barcode (receipt " + receipt
                + ") and uploaded; waiting for central to apply it ...");

        // 4. After sync: the stock went down and the receipt is central.
        Map<String, BigDecimal> after = before;
        for (int attempt = 0; attempt < 30 && after.equals(before); attempt++) {
            Thread.sleep(1000);
            after = onHand(shop, manager);
        }
        System.out.println();
        System.out.println("Kuliyapitiya town shop, on hand   before -> after");
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            String sku = skuOf(item, manager);
            System.out.printf(
                    "  %-32s %8s -> %s  (sold %d)%n",
                    item.nameEn(),
                    before.getOrDefault(sku, BigDecimal.ZERO)
                            .stripTrailingZeros()
                            .toPlainString(),
                    after.getOrDefault(sku, BigDecimal.ZERO)
                            .stripTrailingZeros()
                            .toPlainString(),
                    i + 1);
        }
        for (JsonNode r : call(HttpMethod.GET, "/v1/pos/receipts?locationId=" + shop, null, manager)) {
            if (receipt.toString().equals(r.path("documentId").asText())) {
                System.out.println("Receipt " + r.path("docNumberDisplay").asText() + ", LKR "
                        + r.path("grossAmount").asText() + ", flags " + r.path("flags"));
            }
        }
        if (after.equals(before)) {
            System.out.println("Central has not applied the sale yet: is the relay running (make up)?");
            System.exit(1);
        }
    }

    private Map<String, BigDecimal> onHand(UUID location, HttpHeaders headers) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (JsonNode lot : call(HttpMethod.GET, "/v1/inventory/locations/" + location + "/balances", null, headers)) {
            totals.merge(lot.path("skuId").asText(), lot.path("qtyOnHand").decimalValue(), BigDecimal::add);
        }
        return totals;
    }

    private final Map<String, String> skus = new LinkedHashMap<>();

    private String skuOf(Item item, HttpHeaders headers) {
        return skus.computeIfAbsent(item.barcode(), barcode -> {
            JsonNode page = call(HttpMethod.GET, "/v1/catalogue/skus?q=" + barcode + "&limit=1", null, headers);
            JsonNode first = page.path("items").path(0);
            return first.path("skuId").asText();
        });
    }

    private HttpHeaders userHeaders(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(Map.of(
                "grant_type", "password",
                "client_id", "coop-erp-web",
                "username", username,
                "password", password,
                "scope", "openid")));
        headers.set("X-Scope-Entity", DemoCast.M101.toString());
        return headers;
    }

    private String token(Map<String, String> form) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<JsonNode> answer =
                new TestRestTemplate().postForEntity(tokenUrl, new HttpEntity<>(body, headers), JsonNode.class);
        if (!answer.getStatusCode().is2xxSuccessful() || answer.getBody() == null) {
            throw new IllegalStateException("The identity provider at " + tokenUrl + " answered "
                    + answer.getStatusCode() + ": " + answer.getBody());
        }
        return answer.getBody().path("access_token").asText();
    }

    private JsonNode call(HttpMethod method, String path, Object body, HttpHeaders headers) {
        HttpHeaders sent = new HttpHeaders();
        sent.addAll(headers);
        sent.setContentType(MediaType.APPLICATION_JSON);
        if (method == HttpMethod.POST) {
            sent.set("Idempotency-Key", UUID.randomUUID().toString());
        }
        ResponseEntity<JsonNode> answer = http.exchange(path, method, new HttpEntity<>(body, sent), JsonNode.class);
        if (!answer.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    method + " " + api + path + " answered " + answer.getStatusCode() + ": " + answer.getBody());
        }
        return answer.getBody() == null ? json.createObjectNode() : answer.getBody();
    }
}
