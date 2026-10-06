package lk.coopfed.knoweb.m8reporting.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness.Delivery;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shop's sales (shop_sale_fact, shop_sale_line_fact) from the till's receipt.issued.v1
 * bundles: rebuild equivalence over random receipts with redeliveries first (28A section 9), then
 * what one receipt leaves, its business date the till's own.
 */
class ShopSaleProjectionPostgresIntegrationTest extends PostgresIntegrationTest {

    static final List<String> TABLES =
            List.of("reporting.projection_state", "reporting.shop_sale_fact", "reporting.shop_sale_line_fact");

    @Autowired
    ShopSaleProjection projection;

    @Autowired
    ObjectMapper mapper;

    private ProjectionHarness harness;
    private JdbcTemplate admin;

    @BeforeEach
    void arrange() {
        admin = superuserJdbc();
        harness = new ProjectionHarness(mapper, admin);
        harness.empty(TABLES);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        harness.empty(TABLES);
    }

    @Test
    void aRebuildFromZeroGivesTheRowsTheLiveConsumerGaveForRandomReceipts() {
        for (long seed = 1; seed <= 5; seed++) {
            Random random = new Random(seed);
            UUID[] owners = {Ids.next(), Ids.next()};
            UUID[][] shops = {{Ids.next(), Ids.next()}, {Ids.next(), Ids.next()}};
            UUID[] skus = {Ids.next(), Ids.next(), Ids.next()};
            Instant t = Instant.parse("2026-09-20T03:00:00Z");
            List<Delivery> events = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                t = t.plusSeconds(600 + random.nextInt(20_000));
                int owner = random.nextInt(2);
                events.add(harness.event(
                        ShopSaleProjection.RECEIPT_ISSUED,
                        owners[owner],
                        t,
                        receipt(shops[owner][random.nextInt(2)], skus, random, t, "2026-09-2" + (i % 9))));
            }

            ProjectionHarness.Result result = harness.liveThenRebuild(events, projection::on, TABLES, seed);

            assertThat(result.live().get("reporting.shop_sale_fact")).hasSize(40);
            assertThat(result.rebuilt()).as("seed %d", seed).isEqualTo(result.live());
        }
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aReceiptLeavesItsHeaderAndLinesOnTheTillsBusinessDate() {
        UUID owner = Ids.next();
        UUID shop = Ids.next();
        UUID sku = Ids.next();
        // Issued at 23:50 in Colombo on the 26th (18:20 UTC): the till's business date is the 26th.
        Instant t = Instant.parse("2026-09-26T18:20:00Z");
        Map<String, Object> bundle = receipt(shop, new UUID[] {sku}, new Random(7), t, "2026-09-26");
        Delivery event = harness.event(ShopSaleProjection.RECEIPT_ISSUED, owner, t, bundle);
        harness.deliver(event, projection::on);
        harness.deliver(event, projection::on);

        List<Map<String, Object>> receipts = admin.queryForList("select * from reporting.shop_sale_fact");
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).get("owner_entity_id")).isEqualTo(owner);
        assertThat(receipts.get(0).get("location_id")).isEqualTo(shop);
        assertThat(receipts.get(0).get("business_date").toString()).isEqualTo("2026-09-26");
        List<Map<String, Object>> lines = admin.queryForList("select * from reporting.shop_sale_line_fact");
        assertThat(lines).hasSize((Integer) receipts.get(0).get("lines"));
        BigDecimal total =
                lines.stream().map(l -> (BigDecimal) l.get("line_total")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo((BigDecimal) receipts.get(0).get("gross"));
    }

    /**
     * Wave 2, M8-04: the shipping till sends no line_id. Its lines are kept, keyed by their number,
     * and the receipt is the device's shop's (the scope the dispatcher gives a till's event), not
     * the location the till's payload names, as M6 files it.
     */
    @Test
    void aTillReceiptWithoutLineIdsKeepsItsLinesByNumberAtTheDevicesShop() {
        UUID owner = Ids.next();
        UUID deviceShop = Ids.next();
        UUID namedShop = Ids.next();
        UUID sku = Ids.next();
        Instant t = Instant.parse("2026-09-26T05:00:00Z");
        Map<String, Object> bundle = receipt(namedShop, new UUID[] {sku}, new Random(11), t, "2026-09-26");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) bundle.get("lines");
        lines.forEach(line -> line.remove("line_id"));
        ProjectionHarness.Delivery event = harness.event(ShopSaleProjection.RECEIPT_ISSUED, owner, t, bundle);
        ProjectionHarness.Delivery fromTheDevice =
                new ProjectionHarness.Delivery(event.envelope(), ProjectionHarness.system(owner, deviceShop));
        harness.deliver(fromTheDevice, projection::on);
        harness.deliver(fromTheDevice, projection::on);

        Map<String, Object> receipt = admin.queryForMap("select * from reporting.shop_sale_fact");
        assertThat(receipt.get("location_id")).isEqualTo(deviceShop);
        assertThat(receipt.get("lines")).isEqualTo(lines.size());
        List<Map<String, Object>> rows =
                admin.queryForList("select * from reporting.shop_sale_line_fact order by line_no");
        assertThat(rows).hasSize(lines.size());
        assertThat(rows)
                .extracting(r -> r.get("line_no"))
                .containsExactlyElementsOf(
                        lines.stream().map(l -> l.get("line_no")).toList());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("line_id")).isNull();
            assertThat(r.get("location_id")).isEqualTo(deviceShop);
            assertThat(r.get("sku_id")).isEqualTo(sku);
        });
    }

    /** Wave 2, M8-02: a till clock a day ahead does not hold the projection's freshness in the future. */
    @Test
    void theLastEventTimeIsNeverAheadOfCentralsClock() {
        UUID owner = Ids.next();
        Instant ahead = Instant.now().plusSeconds(86_400);
        harness.deliver(
                harness.event(
                        ShopSaleProjection.RECEIPT_ISSUED,
                        owner,
                        ahead,
                        receipt(Ids.next(), new UUID[] {Ids.next()}, new Random(3), ahead, "2026-09-26")),
                projection::on);

        java.sql.Timestamp stored = admin.queryForObject(
                "select last_event_at from reporting.projection_state where name = ? and owner_entity_id = ?",
                java.sql.Timestamp.class,
                ShopSaleProjection.NAME,
                owner);
        assertThat(stored.toInstant()).isBefore(ahead).isBeforeOrEqualTo(Instant.now());
    }

    /** A till's bundle (doc 32 section 3.1), doc 18's column names, one to three lines. */
    private static Map<String, Object> receipt(UUID shop, UUID[] skus, Random random, Instant at, String day) {
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        int count = 1 + random.nextInt(3);
        for (int n = 1; n <= count; n++) {
            BigDecimal total = new BigDecimal(10 + random.nextInt(500) + ".50");
            gross = gross.add(total);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("line_id", Ids.next().toString());
            line.put("line_no", n);
            line.put("sku_id", skus[random.nextInt(skus.length)].toString());
            line.put("qty", String.valueOf(1 + random.nextInt(4)));
            line.put("line_total", total.toPlainString());
            lines.add(line);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("document_id", Ids.next().toString());
        document.put("location_id", shop.toString());
        document.put("business_date", day);
        document.put("issued_at", at.toString());
        document.put("doc_number_display", "S01-" + random.nextInt(1000));
        document.put("gross_amount", gross.toPlainString());
        return Map.of("document", document, "lines", lines);
    }
}
