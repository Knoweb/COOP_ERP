package lk.coopfed.knoweb.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
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
 * The last step of {@code make demo-data} (DEMO-02b, docs/DEMO.md): a history of till sales at the
 * four demo shops over the same eight weeks as the trading history, so that the receipts screen,
 * the shops' stock and the reports open with sales in them.
 *
 * <p>Every sale goes the way a real till's does, through the sync contract, as {@code make
 * demo-till-sale} does: the shop's manager registers the demo till {@code DEMO-TILL-<shop>} on
 * till position 1 when it is not there and issues it a one-time code; the till enrols, takes its
 * snapshot, and for each sale day opens a session, sells, closes and uploads. The till is the
 * party that writes receipts (AGENTS.md, idea 3), so central's loader cannot write them; that is
 * why this runs outside the loader, against the running stack, and not inside {@code
 * HistoricalTime}. The till's clock is set to each sale day ({@link TillSimulator#clockAt}), so the
 * sessions and receipts carry those dates and times; M5's stock movements for them are recorded
 * at the real time by M5's own listener, as for the trading history.
 *
 * <p>Idempotent: a day on which the shop's demo till already has a receipt is not sold again, so a
 * second run finds every day done, enrols nothing and changes nothing. Sales are made only of
 * items the shop holds ten or more of, in varied baskets, never more in all than the shop holds
 * (DemoTillHistory#salesOf), so the history does not drive the shops' stock negative.
 *
 * <p>Settings as {@link DemoTillSale}: {@code COOP_ERP_API}, {@code COOP_ERP_TOKEN_URL}.
 */
public final class DemoTillHistory {

    private static final String APP_VERSION = "1.0.0";
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    private static final BigDecimal FLOAT = new BigDecimal("2000.00");

    /** The day of the week (0 to 6 after each week's start) on which each shop sells. */
    private static final int[] SALE_DAYS_OF_WEEK = {0, 2, 4};

    /**
     * When each shop's history starts, in days before the load: the town shop had its stock at the
     * set-up (its first transfer), Hettipola a month later (its first transfer), the Pannala and
     * Point Pedro shops at the set-up too (their counted opening stock, DemoShopStock).
     */
    static final Map<UUID, Integer> FIRST_DAY_AGO = Map.of(
            DemoCast.M101_TOWN_SHOP, DemoCalendar.HISTORY_DAYS,
            DemoCast.M101_HETTIPOLA_SHOP, DemoCalendar.SETUP_DAYS_AGO / 2 - 1,
            DemoCast.M102_SHOP, DemoCalendar.HISTORY_DAYS,
            DemoCast.M103_SHOP, DemoCalendar.HISTORY_DAYS);

    /**
     * The days a shop sells on: three a week over the eight weeks of the trading history (the
     * first day {@link DemoCalendar#HISTORY_DAYS} ago), none before the shop had stock and none
     * today (today's sale is {@code make demo-till-sale}'s), oldest first.
     */
    static List<LocalDate> saleDays(LocalDate today, int firstDayAgo) {
        List<LocalDate> days = new ArrayList<>();
        for (int daysAgo = DemoCalendar.HISTORY_DAYS; daysAgo >= 1; daysAgo--) {
            int dayOfWeek = (DemoCalendar.HISTORY_DAYS - daysAgo) % 7;
            boolean sells = false;
            for (int d : SALE_DAYS_OF_WEEK) {
                sells |= d == dayOfWeek;
            }
            if (sells && daysAgo <= firstDayAgo) {
                days.add(today.minusDays(daysAgo));
            }
        }
        return days;
    }

    /** The sale days still to sell: those on which the demo till has no receipt yet. */
    static List<LocalDate> stillToSell(List<LocalDate> plan, Set<LocalDate> sold) {
        return plan.stream().filter(day -> !sold.contains(day)).toList();
    }

    /** Of each item the shop holds, this many are left on the shelf for {@code make demo-till-sale}. */
    static final int RESERVE = 2;

    /**
     * The sales of the days still to sell, per day (a day may have none left to sell): one sale on
     * odd days of the plan, two on even ones, each a basket of one to five different items drawn
     * from what the shop holds, one to three of each. The baskets are drawn from a random source
     * seeded by the shop, the day's place in the plan and the sale's index, so the same stock gives
     * the same history on every run. What the history sells of an item never goes beyond what the
     * shop holds less {@link #RESERVE}, spread evenly over the days, so the stock never goes negative
     * and the last days still sell.
     */
    static Map<LocalDate, List<List<Sale>>> salesOf(
            UUID shop, List<LocalDate> plan, List<LocalDate> todo, Map<Item, BigDecimal> held) {
        List<Item> items = new ArrayList<>(held.keySet());
        Map<Item, Integer> budget = new LinkedHashMap<>();
        Map<Item, Integer> used = new LinkedHashMap<>();
        held.forEach((item, qty) -> {
            budget.put(item, Math.max(0, qty.intValue() - RESERVE));
            used.put(item, 0);
        });
        Map<LocalDate, List<List<Sale>>> byDay = new LinkedHashMap<>();
        for (int k = 0; k < todo.size(); k++) {
            LocalDate day = todo.get(k);
            int dayIndex = plan.indexOf(day);
            int count = dayIndex % 2 == 0 ? 2 : 1;
            List<List<Sale>> sales = new ArrayList<>();
            for (int s = 0; s < count; s++) {
                SplittableRandom random = new SplittableRandom(shop.getMostSignificantBits()
                        ^ shop.getLeastSignificantBits()
                        ^ (dayIndex * 1_000_003L)
                        ^ (s * 7_919L));
                List<Item> shuffled = new ArrayList<>(items);
                for (int i = shuffled.size() - 1; i > 0; i--) {
                    java.util.Collections.swap(shuffled, i, random.nextInt(i + 1));
                }
                int lines = Math.min(1 + random.nextInt(5), shuffled.size());
                List<Sale> basket = new ArrayList<>();
                for (Item item : shuffled.subList(0, lines)) {
                    int wanted = 1 + random.nextInt(3);
                    int allowed = budget.get(item) * (k + 1) / todo.size() - used.get(item);
                    int qty = Math.min(wanted, allowed);
                    if (qty > 0) {
                        used.merge(item, qty, Integer::sum);
                        BigDecimal price = item.printedMrp() != null ? item.printedMrp() : item.distributorPrice();
                        basket.add(new Sale(item.barcode(), BigDecimal.valueOf(qty), price));
                    }
                }
                if (!basket.isEmpty()) {
                    sales.add(basket);
                }
            }
            byDay.put(day, sales);
        }
        return byDay;
    }

    private final ObjectMapper json = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final String api;
    private final String tokenUrl;
    private final TestRestTemplate http;

    private DemoTillHistory(String api, String tokenUrl) {
        this.api = api;
        this.tokenUrl = tokenUrl;
        this.http = new TestRestTemplate(new RestTemplateBuilder().rootUri(api));
    }

    public static void main(String[] args) throws Exception {
        String api = System.getenv().getOrDefault("COOP_ERP_API", "http://localhost:8080");
        String tokenUrl = System.getenv()
                .getOrDefault("COOP_ERP_TOKEN_URL", "http://localhost:8085/realms/coop/protocol/openid-connect/token");
        new DemoTillHistory(api, tokenUrl).run();
    }

    private void run() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        for (DemoCast.Shop shop : DemoCast.SHOPS) {
            sellAt(shop, today);
        }
    }

    private void sellAt(DemoCast.Shop shop, LocalDate today) throws Exception {
        UUID location = shop.locationId();
        String serial = serialOf(location);
        HttpHeaders manager = userHeaders(shop.manager());
        List<LocalDate> plan = saleDays(today, FIRST_DAY_AGO.get(location));

        JsonNode device = findDevice(location, serial, manager);
        List<LocalDate> todo = stillToSell(plan, device == null ? Set.of() : soldDays(location, device, manager));
        if (todo.isEmpty()) {
            System.out.println("Till history at " + location + ": all " + plan.size() + " sale days already there");
            return;
        }

        // The till at the shop, on position 1, with a one-time code (as DemoTillSale).
        UUID position = null;
        for (JsonNode p : call(HttpMethod.GET, "/v1/party/locations/" + location + "/positions", null, manager)) {
            if (p.path("positionNo").asInt() == 1) {
                position = UUID.fromString(p.path("tillPositionId").asText());
            }
        }
        if (position == null) {
            throw new IllegalStateException(
                    "Shop " + location + " has no till position 1: the demo loader has not run");
        }
        if (device == null) {
            Map<String, Object> enrol = new LinkedHashMap<>();
            enrol.put("hardwareSerial", serial);
            enrol.put("deviceKind", "POS_TERMINAL");
            enrol.put("locationId", location.toString());
            enrol.put("appVersion", APP_VERSION);
            enrol.put("stagingReference", "DEMO");
            device = call(HttpMethod.POST, "/v1/party/devices", enrol, manager);
        }
        UUID deviceId = UUID.fromString(device.path("deviceId").asText());
        if (device.path("tillPositionId").isMissingNode()
                || device.path("tillPositionId").isNull()) {
            call(
                    HttpMethod.POST,
                    "/v1/party/devices/" + deviceId + "/assign",
                    Map.of("tillPositionId", position.toString(), "reasonCode", "OPENING"),
                    manager);
        }
        String code = call(HttpMethod.POST, "/v1/sync/devices/" + deviceId + "/enrolment-codes", null, manager)
                .path("code")
                .asText();
        JsonNode enrolled = call(
                HttpMethod.POST,
                "/v1/sync/devices/" + deviceId + "/enrol",
                Map.of("enrolment_code", code, "hardware_serial", serial, "app_version", APP_VERSION),
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
        String deviceToken = token(Map.of(
                "grant_type", "client_credentials",
                "client_id", enrolled.path("credential").path("client_id").asText(),
                "client_secret",
                        enrolled.path("credential").path("client_secret").asText()));

        TillSimulator till = new TillSimulator(
                        http,
                        json,
                        deviceId,
                        location,
                        enrolled.path("signing_key").path("public_key").asText())
                .authenticatedBy(() -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setBearerAuth(deviceToken);
                    return headers;
                })
                .startingAt(enrolled.path("next_device_seq").asLong())
                .sellsAs(
                        shop.manager().entityId(),
                        position,
                        UUID.fromString(rct.path("series_id").asText()),
                        rct.path("prefix").asText())
                .numberingFrom(rct.path("next_number").asLong());
        till.refreshSnapshot();

        Map<Item, BigDecimal> candidates = candidates(location, till, manager);
        if (candidates.isEmpty()) {
            System.out.println("Till history at " + location + ": the shop holds no item to sell, skipped");
            return;
        }

        int receipts = 0;
        Map<LocalDate, List<List<Sale>>> planned = salesOf(location, plan, todo, candidates);
        List<LocalDate> selling =
                todo.stream().filter(day -> !planned.get(day).isEmpty()).toList();
        if (selling.size() < todo.size()) {
            System.out.println("Till history at " + location + ": " + (todo.size() - selling.size())
                    + " days have nothing left to sell (the shop's stock), no sale on them");
        }
        todo = selling;
        for (LocalDate day : todo) {
            till.dayOpen(day);
            till.clockAt(moment(day, LocalTime.of(8, 30)));
            till.openSession(FLOAT);
            BigDecimal taken = BigDecimal.ZERO;
            List<List<Sale>> sales = planned.get(day);
            for (int s = 0; s < sales.size(); s++) {
                till.clockAt(moment(day, LocalTime.of(10 + 3 * s, 15)));
                till.sell(sales.get(s));
                for (Sale line : sales.get(s)) {
                    taken = taken.add(line.qty().multiply(line.unitPrice()));
                }
                receipts++;
            }
            till.clockAt(moment(day, LocalTime.of(18, 0)));
            till.closeSession(FLOAT.add(taken));
        }
        till.clockAt(null);
        till.dayOpen(today);
        till.drain(50, Instant.now().plusSeconds(120));

        // Wait for the relay to hand the receipts to M6, so a second run sees them.
        Set<LocalDate> sold = Set.of();
        for (int attempt = 0; attempt < 60; attempt++) {
            sold = soldDays(location, device, manager);
            if (sold.containsAll(todo)) {
                break;
            }
            Thread.sleep(1000);
        }
        System.out.println("Till history at " + location + ": " + receipts + " receipts on " + todo.size()
                + " days uploaded; central holds receipts on " + sold.size() + " of " + plan.size() + " days");
        if (!sold.containsAll(todo)) {
            System.out.println("Central has not applied every sale yet: is the relay running (make up)?");
            System.exit(1);
        }
    }

    /** DEMO-TILL-S01 for Kuliyapitiya town shop, as make demo-till-sale; the others by their code. */
    private static String serialOf(UUID location) {
        if (location.equals(DemoCast.M101_TOWN_SHOP)) {
            return "DEMO-TILL-S01";
        }
        if (location.equals(DemoCast.M101_HETTIPOLA_SHOP)) {
            return "DEMO-TILL-M101-S02";
        }
        return location.equals(DemoCast.M102_SHOP) ? "DEMO-TILL-M102-S01" : "DEMO-TILL-M103-S01";
    }

    private JsonNode findDevice(UUID location, String serial, HttpHeaders manager) {
        for (JsonNode d : call(HttpMethod.GET, "/v1/party/devices?locationId=" + location, null, manager)) {
            if (serial.equals(d.path("hardwareSerial").asText())
                    && !"RETIRED".equals(d.path("status").asText())) {
                return d;
            }
        }
        return null;
    }

    /** The business dates on which the demo till already has a receipt at central. */
    private Set<LocalDate> soldDays(UUID location, JsonNode device, HttpHeaders manager) {
        String deviceId = device.path("deviceId").asText();
        Set<LocalDate> days = new HashSet<>();
        for (JsonNode r : call(HttpMethod.GET, "/v1/pos/receipts?locationId=" + location, null, manager)) {
            if (deviceId.equals(r.path("deviceId").asText()) && r.hasNonNull("businessDate")) {
                days.add(LocalDate.parse(r.path("businessDate").asText()));
            }
        }
        return days;
    }

    /** The catalogue's barcoded items the shop holds ten or more of, in catalogue order, with how many. */
    private Map<Item, BigDecimal> candidates(UUID location, TillSimulator till, HttpHeaders manager) {
        Map<String, BigDecimal> onHand = new LinkedHashMap<>();
        for (JsonNode lot : call(HttpMethod.GET, "/v1/inventory/locations/" + location + "/balances", null, manager)) {
            onHand.merge(lot.path("skuId").asText(), lot.path("qtyOnHand").decimalValue(), BigDecimal::add);
        }
        Map<String, String> skuOfBarcode = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, Object>> sku : till.table("sku").entrySet()) {
            if (sku.getValue().get("barcodes") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> row && row.get("barcode") != null) {
                        skuOfBarcode.put(
                                String.valueOf(row.get("barcode")), sku.getKey().toString());
                    }
                }
            }
        }
        Map<Item, BigDecimal> items = new LinkedHashMap<>();
        for (Item item : DemoCatalogue.load()) {
            String sku = item.barcode() == null ? null : skuOfBarcode.get(item.barcode());
            BigDecimal qty = sku == null ? BigDecimal.ZERO : onHand.getOrDefault(sku, BigDecimal.ZERO);
            if (qty.compareTo(BigDecimal.TEN) >= 0) {
                items.put(item, qty);
            }
        }
        return items;
    }

    private static Instant moment(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(ZONE).toInstant();
    }

    private HttpHeaders userHeaders(DemoCast.Actor actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(Map.of(
                "grant_type", "password",
                "client_id", "coop-erp-web",
                "username", actor.username(),
                "password", "demo",
                "scope", "openid")));
        headers.set("X-Scope-Entity", actor.entityId().toString());
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
