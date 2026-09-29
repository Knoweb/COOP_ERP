package lk.coopfed.knoweb.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureView;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentReceiptView;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.RepackView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.m5inventory.query.WriteOffView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DEMO-01: the loader against the test database, twice. The first run loads the demo through the
 * command handlers, as the demo users, with the permission check on (the users' roles decide, as in
 * compose) and the application connected as coop_app under row-level security; the second run
 * issues no command and changes no row.
 *
 * <p>Tagged {@code slow} (about 67s locally) so a pull request's integration run excludes it;
 * the stack-smoke job already loads the demo, through the real HTTP API, on every pull request.
 * Pushes to main and the nightly run include it here too, for the assertions this test makes
 * that stack-smoke does not (the second, no-op run; the query-side views).
 */
@Tag("slow")
class DemoDataLoaderIntegrationTest extends PostgresIntegrationTest {

    /** The Federation the demo users belong to: the one of the development seed and of compose. */
    @DynamicPropertySource
    static void theDemoFederation(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", DemoCast.FEDERATION::toString);
        registry.add("coop-erp.security.enforce-permissions", () -> "true");
        // DEMO-02: what the demo container sets, so the history is dated over eight weeks.
        registry.add("coop-erp.demo.historical-time", () -> "true");
    }

    @Autowired
    DemoDataLoader loader;

    @Autowired
    Clock clock;

    @Autowired
    PricingQueries pricing;

    @Autowired
    InvoiceQueries invoices;

    @Autowired
    PaymentQueries payments;

    @Autowired
    ExposureQueries exposures;

    @Autowired
    StockControlQueries control;

    @Autowired
    lk.coopfed.knoweb.m7customers.query.CustomerQueries customers;

    @Autowired
    lk.coopfed.knoweb.m7customers.query.AccountQueries accounts;

    @Autowired
    lk.coopfed.knoweb.m7customers.query.PrivacyQueries privacy;

    @BeforeEach
    void theSeedTheDemoStartsFrom() throws Exception {
        JdbcTemplate admin = superuserJdbc();
        // The Federation row of entities.dev.sql, under a code of its own: other test classes of this
        // run register a Federation with the code FED.
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status, vat_registration_no)
                values (?, 'DEMOFED', 'FEDERATION', 'Cooperative Federation (demo test)', 'Colombo', 1, 'en', 'ACTIVE',
                    'VAT-DEMOFED')
                on conflict (entity_id) do nothing
                """,
                DemoCast.FEDERATION);
        try (var connection = admin.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("seed/m1party/demo-parties.demo.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("seed/m1security/demo-users.demo.sql"));
        }
    }

    /**
     * The trading history's documents go again after the test: the classes of one run share the
     * database, and a class that clears {@code kernel.document} (AttachmentsPostgresIntegrationTest)
     * must not find M4's rows pointing at it.
     */
    @AfterEach
    void removeTheTradingHistory() {
        JdbcTemplate admin = superuserJdbc();
        lk.coopfed.knoweb.m4trading.TradingFixture.cleanAllTrading(admin);
        lk.coopfed.knoweb.m7customers.CustomersFixture.cleanAllCustomers(admin);
    }

    @Test
    void loadsTheDemoOnceAndASecondRunChangesNothing() {
        DemoDataLoader.Report first = loader.load();
        Map<String, Long> afterFirst = counts();

        assertThat(afterFirst)
                .containsEntry("shared SKUs", 40L)
                .containsEntry("conversions", 37L)
                .containsEntry("barcodes", 37L)
                .containsEntry("till positions", 5L)
                .containsEntry("primary tills", 4L)
                .containsEntry("published trade lists", 3L)
                .containsEntry("control prices", 3L)
                .containsEntry("published shelf lists of M101", 1L)
                .containsEntry("shelf prices of M101", 40L)
                .containsEntry("MRP policies of M101", 1L)
                .containsEntry("active relationships", 5L)
                .containsEntry("posted opening balances", 4L)
                // 200, 29 at the Hettipola shop (DEMO-02) and the society's own 5 kg packs (the repack)
                .containsEntry("lots", 230L)
                .containsEntry("received transfers to the town shop", 1L)
                .containsEntry("received transfers to the Hettipola shop", 1L)
                .containsEntry("lots with stock at the town shop", 40L)
                .containsEntry("orders of the history", 44L)
                // M7: 36 members, every other one with an account (DemoCustomers).
                .containsEntry("members of M101", 36L)
                .containsEntry("credit accounts", 18L);
        assertThat(afterFirst.get("account postings")).isGreaterThan(100L);
        if (first.total() > 0) {
            // A fresh database: the loader went through the handlers, which audited and published.
            assertThat(kernel.committedAudit()).isNotEmpty();
            assertThat(kernel.committedEvents()).isNotEmpty();
            // DEMO-02: 44 orders over five relationships; the last four of each stop at
            // SUBMITTED, ACCEPTED, DISPATCHED and RECEIVED, the others are invoiced.
            assertThat(first.commands())
                    .containsEntry("CreateOrder", 44)
                    .containsEntry("SubmitOrder", 44)
                    .containsEntry("AcceptOrder", 39)
                    .containsEntry("CreateDeliveryNote", 34)
                    .containsEntry("IssueDeliveryNote", 34)
                    .containsEntry("DispatchDeliveryNote", 34)
                    .containsEntry("CaptureGrn", 29)
                    .containsEntry("ConfirmGrn", 29)
                    .containsEntry("IssueInvoice", 24)
                    // M4-07: eight payments, three cheque outcomes (two cleared, one bounced).
                    .containsEntry("RecordPaymentReceipt", 8)
                    .containsEntry("RecordChequeOutcome", 3)
                    // M5-11, M5-13: a count approved, a write-off witnessed and approved, a repack.
                    .containsEntry("SubmitCount", 1)
                    .containsEntry("ApproveAdjustment", 1)
                    .containsEntry("WitnessWriteOff", 1)
                    .containsEntry("ApproveWriteOff", 1)
                    .containsEntry("DefineRecipe", 1)
                    .containsEntry("ExecuteRepack", 1)
                    // M4-06, M4-10: a claim raised and approved, a transfer request asked and approved.
                    .containsEntry("RaiseClaim", 1)
                    .containsEntry("ApproveClaim", 1)
                    .containsEntry("RequestTransfer", 1)
                    .containsEntry("ApproveTransferRequest", 1);
            theHistorySpreadsOverEightWeeks();
        }
        theTownShopSellsTheGazettedRiceAtItsControlPrice();
        thePaymentsAndTheCreditLimit();
        theStockOperationsAtTheSocietysStores();
        theSocietysCreditBook();

        kernel.reset();
        DemoDataLoader.Report second = loader.load();

        assertThat(second.total()).isZero();
        assertThat(counts()).isEqualTo(afterFirst);
    }

    /**
     * A demo user who can see the Stock tab (any role permission {@code inv.*}) must also be able
     * to list locations for the picker (bug seen live 28 Sep 2026: fed-steward and fed-accounts held
     * inv.stock.view but not prt.location.view, so /v1/party/locations answered 403 and the Stock and
     * Transfers screens showed an empty dropdown). Every demo role with an inv.* permission holds
     * prt.location.view too.
     */
    @Test
    void everyDemoRoleThatSeesInventoryCanAlsoListItsLocations() {
        JdbcTemplate admin = superuserJdbc();
        Long offenders = admin.queryForObject(
                """
                        select count(*)
                        from security.role r
                        where r.role_id::text like '0190f0de-%'
                          and exists (
                              select 1 from security.role_permission rp
                              where rp.role_id = r.role_id and rp.permission_code like 'inv.%')
                          and not exists (
                              select 1 from security.role_permission rp
                              where rp.role_id = r.role_id and rp.permission_code = 'prt.location.view')
                        """,
                Long.class);
        assertThat(offenders).isZero();
    }

    /**
     * DEMO-02: the history's orders, delivery notes, GRNs and invoices carry business dates over the
     * eight weeks before today, each kind later than the one before it, and every numbering series
     * the demo used numbers its documents in the order of their dates.
     */
    private void theHistorySpreadsOverEightWeeks() {
        JdbcTemplate admin = superuserJdbc();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"));
        String demo = "(owner_entity_id = '" + DemoCast.FEDERATION + "' or owner_entity_id::text like '0190f0de-%')";

        LocalDate firstOrder = admin.queryForObject(
                "select min(business_date) from kernel.document where notes like 'Demo history %'", LocalDate.class);
        LocalDate lastOrder = admin.queryForObject(
                "select max(business_date) from kernel.document where notes like 'Demo history %'", LocalDate.class);
        assertThat(firstOrder).isEqualTo(today.minusDays(DemoCalendar.HISTORY_DAYS));
        assertThat(lastOrder).isEqualTo(today);

        // Every document of the history is dated within the eight weeks, on at least 20 different days.
        List<String> types = List.of("ORD", "DN", "GRN", "INV");
        LocalDate previousFirst = null;
        for (String type : types) {
            Map<String, Object> range = admin.queryForMap(
                    "select min(business_date) as first, max(business_date) as last,"
                            + " count(distinct business_date) as days from kernel.document"
                            + " where doc_type_code = ? and issued_at is not null and " + demo,
                    type);
            LocalDate first = ((java.sql.Date) range.get("first")).toLocalDate();
            LocalDate last = ((java.sql.Date) range.get("last")).toLocalDate();
            assertThat(first).as(type).isAfterOrEqualTo(today.minusDays(DemoCalendar.HISTORY_DAYS));
            assertThat(last).as(type).isBeforeOrEqualTo(today);
            assertThat((Long) range.get("days")).as(type).isGreaterThanOrEqualTo(10L);
            if (previousFirst != null) {
                assertThat(first).as(type + " after the step before it").isAfter(previousFirst);
            }
            previousFirst = first;
        }

        // Per series, a later number never carries an earlier date.
        Long outOfOrder = admin.queryForObject(
                "select count(*) from (select business_date, lag(business_date) over"
                        + " (partition by series_id order by doc_number) as before"
                        + " from kernel.document where series_id is not null and doc_number is not null and "
                        + demo + ") numbered where business_date < before",
                Long.class);
        assertThat(outOfOrder).isZero();

        // And each document of an invoiced order is dated after the one it follows.
        Long backwards = admin.queryForObject(
                """
                select count(*) from kernel.document inv
                  join kernel.document grn on grn.document_id = inv.reference_document_id
                 where inv.doc_type_code = 'INV' and grn.doc_type_code = 'GRN' and inv.business_date <= grn.business_date
                """,
                Long.class);
        assertThat(backwards).isZero();
    }

    /**
     * M3-06: the shelf price of Nadu rice 5 kg at the town shop, as the shop's staff reads it. The
     * pack's MRP is Rs 1,150 and the gazette caps it at Rs 1,100, so the society's list carries the
     * control price; the engine resolves it, with the MRP of the batch on the shelf as its batch term.
     * The date is the set-up day plus one, well inside the list's validity, never "today" (a run
     * that crosses midnight in Colombo reads the same answer).
     */
    private void theTownShopSellsTheGazettedRiceAtItsControlPrice() {
        UUID rice = superuserJdbc()
                .queryForObject(
                        "select sku_id from catalogue.sku where short_name_en = 'Nadu rice 5 kg'"
                                + " and attributes ->> 'demo' = 'true'",
                        UUID.class);
        LocalDate shelfDay = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"))
                .minusDays(DemoCalendar.SETUP_DAYS_AGO - 1);
        Scope shop = new Scope(DemoCast.M101, DemoCast.M101_TOWN_SHOP);
        ScopeContext staff = new ScopeContext(
                DemoCast.M101_SHOP_STAFF.userId(),
                null,
                DemoCast.M101,
                List.of(shop),
                shop,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);

        assertThat(pricing.resolveRetailPrice(DemoCast.M101_TOWN_SHOP, rice, "EA", BigDecimal.ONE, shelfDay, staff))
                .get()
                .satisfies(price -> {
                    assertThat(price.sellable()).isTrue();
                    assertThat(price.listPrice()).isEqualByComparingTo("1100.00");
                    assertThat(price.controlPrice()).isEqualByComparingTo("1100.00");
                    assertThat(price.mrpApplied()).isEqualByComparingTo("1150.00");
                    assertThat(price.unitPrice()).isEqualByComparingTo("1100.00");
                });
    }

    /**
     * M4-07 and M4-09: of D101's invoices from the Federation (oldest first) the first two are
     * settled, the third part-paid, and the fourth open again after its cheque bounced; and
     * Point Pedro MPCS (M103) owes D102 past the first warning threshold of its credit limit, but
     * not past the limit.
     */
    private void thePaymentsAndTheCreditLimit() {
        ScopeContext fedAccounts = scopeOf(DemoCast.FED_ACCOUNTS);
        List<InvoiceView> d101 = invoices.listInvoices(OrderQueries.Role.SELLER, fedAccounts).stream()
                .filter(invoice -> DemoCast.D101.equals(invoice.buyerEntityId()))
                .sorted(Comparator.comparing(InvoiceView::taxPointDate).thenComparing(InvoiceView::docNumberDisplay))
                .toList();
        assertThat(d101).hasSizeGreaterThanOrEqualTo(4);
        assertThat(d101.stream()
                        .limit(4)
                        .map(invoice -> invoices.balance(invoice.invoiceId(), fedAccounts)
                                .orElseThrow()
                                .paymentState())
                        .toList())
                .containsExactly(
                        InvoiceBalance.SETTLED, InvoiceBalance.SETTLED, InvoiceBalance.PART_PAID, InvoiceBalance.OPEN);
        List<PaymentReceiptView> receipts = payments.listReceipts(OrderQueries.Role.SELLER, fedAccounts);
        assertThat(receipts)
                .extracting(PaymentReceiptView::status)
                .contains(PaymentReceiptView.REVERSED, PaymentReceiptView.REVERSAL);
        // d101-accounts reads the same payments, read only.
        assertThat(payments.listReceipts(OrderQueries.Role.BUYER, scopeOf(DemoCast.D101_ACCOUNTS)))
                .isNotEmpty();

        ExposureView m103 = exposures
                .exposure(DemoCast.D102, DemoCast.M103, scopeOf(DemoCast.D102_BUYER))
                .orElseThrow();
        assertThat(m103.creditLimit()).isEqualByComparingTo(DemoDataLoader.M103_CREDIT_LIMIT);
        assertThat(m103.amount())
                .as("M103's exposure %s against its limit", m103.amount())
                .isGreaterThanOrEqualTo(m103.creditLimit().multiply(new BigDecimal("0.80")))
                .isLessThan(m103.creditLimit());
        assertThat(m103.warnThresholdPercent()).isEqualTo(80);
    }

    /**
     * M5-11, M5-13 (DemoStockOperations): at Kuliyapitiya stores, a count closed with a small
     * shortfall approved by the manager three weeks ago, a write-off of damaged flour witnessed and
     * posted twelve days ago, and loose samba rice repacked into the society's own 5 kg packs six
     * days ago; each dated in the history.
     */
    private void theStockOperationsAtTheSocietysStores() {
        ScopeContext manager = scopeOf(DemoCast.M101_MANAGER);
        // Dated in the history, and never against today: the load and the test may straddle midnight.
        ZoneId colombo = ZoneId.of("Asia/Colombo");

        List<CountView> counts = control.counts(DemoCast.M101_WAREHOUSE, manager);
        assertThat(counts).isNotEmpty();
        CountView count = counts.get(counts.size() - 1);
        assertThat(count.status()).isEqualTo("CLOSED");
        assertThat(count.outcome()).isEqualTo("APPROVED");
        assertThat(count.reviewedBy()).isEqualTo(DemoCast.M101_MANAGER.userId());
        assertThat(count.submittedBy()).isEqualTo(DemoCast.M101_BUYER.userId());
        assertThat(count.lines())
                .extracting(line -> line.varianceQty().stripTrailingZeros().toPlainString())
                .contains("-1", "-3");
        assertThat(LocalDate.ofInstant(count.submittedAt(), colombo)).isEqualTo(count.scheduledFor());
        assertThat(count.submittedAt()).isBefore(clock.instant().minus(java.time.Duration.ofDays(19)));

        List<WriteOffView> writeOffs = control.writeOffs(DemoCast.M101_WAREHOUSE, manager);
        assertThat(writeOffs).singleElement().satisfies(w -> {
            assertThat(w.status()).isEqualTo("POSTED");
            assertThat(w.category()).isEqualTo("DAMAGED_IN_STORE");
            assertThat(w.witnessUserId()).isEqualTo(DemoCast.M101_MANAGER.userId());
            assertThat(w.documentNo()).isNotBlank();
            assertThat(LocalDate.ofInstant(w.decidedAt(), colombo))
                    .isEqualTo(LocalDate.ofInstant(w.requestedAt(), colombo));
            assertThat(w.decidedAt()).isBefore(clock.instant().minus(java.time.Duration.ofDays(11)));
        });

        List<RepackView> repacks = control.repacks(DemoCast.M101_WAREHOUSE, manager);
        assertThat(repacks).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("EXECUTED");
            assertThat(r.actualOutputQty()).isEqualByComparingTo("2");
            assertThat(r.inputQty()).isEqualByComparingTo("10");
            assertThat(r.varianceQty()).isEqualByComparingTo("0");
        });
    }

    /**
     * M7: the office reads its members; the first account stands near its limit (95 %), the others
     * have charges spread over the eight weeks and repayments recorded at the office, each a CPR.
     */
    private void theSocietysCreditBook() {
        ScopeContext office = scopeOf(DemoCustomers.M101_OFFICE);
        List<lk.coopfed.knoweb.m7customers.query.CustomerSummary> members = customers.search(null, null, 200, office);
        assertThat(members).hasSize(36);
        assertThat(members).anySatisfy(m -> assertThat(m.language()).isEqualTo("ta"));
        lk.coopfed.knoweb.m7customers.query.CustomerSummary first =
                customers.search(null, DemoCustomers.phone(0), 1, office).get(0);
        var account = accounts.account(first.accountId(), office).orElseThrow();
        assertThat(account.balance()).isEqualByComparingTo("14200.00");
        assertThat(account.creditLimit()).isEqualByComparingTo("15000.00");
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"));
        var statement = accounts.statement(
                        first.accountId(), today.minusDays(DemoCustomers.REGISTERED_DAYS_AGO), today, office)
                .orElseThrow();
        assertThat(statement.lines())
                .filteredOn(line -> "CHARGE".equals(line.kind()))
                .hasSize(8);
        assertThat(statement.lines())
                .filteredOn(line -> "PAYMENT".equals(line.kind()))
                .singleElement()
                .satisfies(line -> assertThat(line.documentNumber()).contains("-CPR-"));
        assertThat(statement.lines().stream()
                        .map(line -> line.businessDate())
                        .distinct()
                        .count())
                .isGreaterThan(5);

        // One account suspended, with its reason in the history; one member erased by the officer.
        var second = customers.search(null, DemoCustomers.phone(2), 1, office).get(0);
        assertThat(accounts.account(second.accountId(), office).orElseThrow().status())
                .isEqualTo("SUSPENDED");
        assertThat(accounts.history(second.accountId(), office)).singleElement().satisfies(h -> assertThat(h.reason())
                .isEqualTo(DemoCustomers.SUSPENSION_REASON));
        assertThat(customers.search(null, DemoCustomers.phone(DemoCustomers.ERASED_MEMBER), 1, office))
                .isEmpty();
        assertThat(privacy.requests(null, office)).singleElement().satisfies(request -> {
            assertThat(request.kind()).isEqualTo("ERASURE");
            assertThat(request.status()).isEqualTo("FULFILLED");
            assertThat(request.customerName()).isEqualTo("Customer");
            assertThat(request.answeredBy()).isEqualTo(DemoCast.M101_MANAGER.userId());
        });
    }

    private ScopeContext scopeOf(DemoCast.Actor actor) {
        Scope scope = new Scope(actor.entityId(), actor.locationId());
        return new ScopeContext(
                actor.userId(),
                null,
                actor.entityId(),
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                clock.instant(),
                Locale.ENGLISH,
                null);
    }

    /** What the demo consists of, counted as the superuser over the demo's own rows. */
    private static Map<String, Long> counts() {
        JdbcTemplate admin = superuserJdbc();
        String fed = DemoCast.FEDERATION.toString();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(
                "shared SKUs",
                count(
                        admin,
                        "select count(*) from catalogue.sku where owner_entity_id = ?::uuid"
                                + " and status = 'SHARED' and attributes ->> 'demo' = 'true'",
                        fed));
        counts.put(
                "conversions",
                count(
                        admin,
                        "select count(*) from catalogue.sku_uom_conversion c join catalogue.sku s using (sku_id)"
                                + " where s.attributes ->> 'demo' = 'true'"));
        counts.put(
                "barcodes",
                count(
                        admin,
                        "select count(*) from catalogue.sku_barcode b join catalogue.sku s using (sku_id)"
                                + " where s.attributes ->> 'demo' = 'true'"));
        counts.put(
                "till positions",
                count(
                        admin,
                        "select count(*) from party.till_position p join party.location l using (location_id)"
                                + " where l.location_id::text like '0190f0de-%'"));
        counts.put(
                "primary tills",
                count(
                        admin,
                        "select count(*) from party.location where location_id::text like '0190f0de-%'"
                                + " and primary_till_position_id is not null"));
        counts.put(
                "published trade lists",
                count(
                        admin,
                        "select count(*) from pricing.price_list where status = 'PUBLISHED' and kind = 'TRADE'"
                                + " and (owner_entity_id = ?::uuid or owner_entity_id::text like '0190f0de-%')"
                                + " and name like '%trade list for%'",
                        fed));
        counts.put(
                "control prices",
                count(
                        admin,
                        "select count(*) from pricing.control_price c join catalogue.sku s using (sku_id)"
                                + " where s.attributes ->> 'demo' = 'true'"));
        counts.put(
                "published shelf lists of M101",
                count(
                        admin,
                        "select count(*) from pricing.price_list where status = 'PUBLISHED' and kind = 'RETAIL'"
                                + " and owner_entity_id = ?::uuid",
                        DemoCast.M101.toString()));
        counts.put(
                "shelf prices of M101",
                count(
                        admin,
                        "select count(*) from pricing.price_list_line l join pricing.price_list p using (price_list_id)"
                                + " where p.kind = 'RETAIL' and p.status = 'PUBLISHED' and p.owner_entity_id = ?::uuid",
                        DemoCast.M101.toString()));
        counts.put(
                "MRP policies of M101",
                count(
                        admin,
                        "select count(*) from pricing.mrp_policy where owner_entity_id = ?::uuid",
                        DemoCast.M101.toString()));
        counts.put(
                "active relationships",
                count(
                        admin,
                        "select count(*) from party.entity_relationship where status = 'ACTIVE'"
                                + " and buyer_entity_id::text like '0190f0de-%'"));
        counts.put(
                "posted opening balances",
                count(
                        admin,
                        "select count(*) from inventory.opening_balance where status = 'POSTED'"
                                + " and location_id::text like '0190f0de-%'"));
        counts.put(
                "lots",
                count(admin, "select count(*) from inventory.stock_lot where location_id::text like '0190f0de-%'"));
        counts.put(
                "received transfers to the town shop",
                count(
                        admin,
                        "select count(*) from inventory.transfer_receipt where location_id = ?::uuid",
                        DemoCast.M101_TOWN_SHOP.toString()));
        counts.put(
                "received transfers to the Hettipola shop",
                count(
                        admin,
                        "select count(*) from inventory.transfer_receipt where location_id = ?::uuid",
                        DemoCast.M101_HETTIPOLA_SHOP.toString()));
        counts.put(
                "lots with stock at the town shop",
                count(
                        admin,
                        "select count(*) from inventory.stock_lot where location_id = ?::uuid and qty_on_hand > 0",
                        DemoCast.M101_TOWN_SHOP.toString()));
        counts.put(
                "orders of the history",
                count(admin, "select count(*) from kernel.document where notes like 'Demo history %'"));
        counts.put(
                "members of M101",
                count(
                        admin,
                        "select count(*) from customers.customer where owner_entity_id = ?::uuid",
                        DemoCast.M101.toString()));
        counts.put("credit accounts", count(admin, "select count(*) from customers.customer_account"));
        counts.put("account postings", count(admin, "select count(*) from customers.account_posting"));
        counts.put(
                "audit of the demo users",
                count(admin, "select count(*) from kernel.audit_event where actor_user_id::text like '0190f0de-%'"));
        return counts;
    }

    private static long count(JdbcTemplate admin, String sql, Object... args) {
        Long value = admin.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
