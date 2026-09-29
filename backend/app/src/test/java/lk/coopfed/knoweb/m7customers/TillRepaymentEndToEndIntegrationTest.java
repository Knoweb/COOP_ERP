package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerDispatcher;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerDispatcher.DeliveryResult;
import lk.coopfed.knoweb.kernel.internal.event.OutboxMessage;
import lk.coopfed.knoweb.kernel.internal.sync.SyncTestKeys;
import lk.coopfed.knoweb.m7customers.api.ChangeAccountStatus;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentRecorded;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A repayment taken at the till (27A section 7.3, doc 27 flow 6.4), end to end through the sync
 * contract: the till records its CPR from its own series ({@code customer_payment.issued.v1}) and
 * uploads it; the gateway puts it in the outbox with the device as its source; {@code
 * m7.repayments} applies it as the relay would deliver it: a PAYMENT posting allocated oldest
 * first, the till's number kept. A repayment on a CLOSED account is posted and flagged, one on an
 * account the society does not have is kept with no posting and flagged: the cash was taken, so
 * nothing is refused (AGENTS.md). A redelivery changes nothing.
 */
class TillRepaymentEndToEndIntegrationTest extends PostgresIntegrationTest {

    private static final UUID POSITION = UUID.fromString("0190f700-0000-7000-8000-000000000031");
    private static final UUID DEVICE = UUID.fromString("0190f700-0000-7000-8000-000000000032");
    private static final UUID SERIES = UUID.fromString("0190f700-0000-7000-8000-000000000033");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationContext context;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.event.EventConsumerRegistry registry;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.event.InboxGuard inbox;

    @Autowired
    lk.coopfed.knoweb.kernel.api.AuditFacade audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    Handles<RegisterCustomer, UUID> register;

    @Autowired
    Handles<OpenAccount, UUID> open;

    @Autowired
    Handles<PostAccountTender, UUID> tender;

    @Autowired
    Handles<ChangeAccountStatus, UUID> changeStatus;

    @Autowired
    AccountQueries accounts;

    @BeforeEach
    void anEnrolledTillAtTheSocietysShop() {
        JdbcTemplate db = superuserJdbc();
        forget(db);
        CustomersFixture.arrange(db);
        db.update(
                "insert into party.till_position (till_position_id, location_id, position_no, owner_entity_id) values (?, ?, 1, ?)",
                POSITION,
                SHOP,
                SOCIETY);
        db.update(
                """
                insert into party.device (device_id, hardware_serial, device_kind, owner_entity_id,
                                          current_till_position_id, status, enrolled_at, location_id)
                values (?, 'SN-M7-0001', 'POS_TERMINAL', ?, ?, 'ACTIVE', now(), ?)
                """,
                DEVICE,
                SOCIETY,
                POSITION,
                SHOP);
        db.update("insert into kernel.device_sync_cursor (device_id, owner_entity_id) values (?, ?)", DEVICE, SOCIETY);
        db.update(
                """
                insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id,
                                                     location_id, till_position_id, prefix, holder_device_id)
                values (?, 'CPR', 'TILL_POSITION', ?, ?, ?, 'M7S-S1-1-CPR', ?)
                """,
                SERIES,
                SOCIETY,
                SHOP,
                POSITION,
                DEVICE);
        kernel.reset();
    }

    @AfterEach
    void forgetAfterwards() {
        forget(superuserJdbc());
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void theTillsRepaymentIsPostedOldestFirstAndOddOnesAreFlaggedNotRefused() throws Exception {
        UUID accountId = account("0700000701", "190000000701");
        UUID older = charge(accountId, "1500.00", LocalDate.of(2026, 9, 1));
        UUID newer = charge(accountId, "1000.00", LocalDate.of(2026, 9, 8));
        UUID closedAccount = account("0700000702", "190000000702");
        changeStatus.handle(new ChangeAccountStatus(closedAccount, ChangeAccountStatus.CLOSE, "Left"), office());
        kernel.reset();

        TillSimulator till = new TillSimulator(http, json, DEVICE, SHOP, SyncTestKeys.signingKey(context));
        UUID repayment = cpr(till, 1, accountId, "2000.00");
        UUID onClosed = cpr(till, 2, closedAccount, "300.00");
        UUID unknown = cpr(till, 3, UUID.randomUUID(), "100.00");
        till.drain(50, Instant.now().plusSeconds(30));
        assertThat(till.pending()).isZero();
        deliver();

        // Oldest first: the older charge settled in full, 500 of the newer; the till's number kept.
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("500");
        AccountQueries.Statement statement = accounts.statement(
                        accountId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), office())
                .orElseThrow();
        assertThat(settled(statement, older)).isEqualByComparingTo("1500");
        assertThat(settled(statement, newer)).isEqualByComparingTo("500");
        assertThat(statement.lines())
                .filteredOn(line -> "PAYMENT".equals(line.kind()))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.documentId()).isEqualTo(repayment);
                    assertThat(line.documentNumber()).isEqualTo("M7S-S1-1-CPR-1");
                    assertThat(line.businessDate()).isEqualTo(LocalDate.of(2026, 9, 10));
                });
        assertThat(accounts.payment(repayment, office()).orElseThrow().origin()).isEqualTo("TILL");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select next_number from kernel.numbering_series where series_id = ?",
                                Long.class,
                                SERIES))
                .isGreaterThanOrEqualTo(4L);

        // A CLOSED account: posted and flagged. An unknown one: kept, not posted, flagged.
        assertThat(accounts.account(closedAccount, office()).orElseThrow().balance())
                .isEqualByComparingTo("-300");
        assertThat(flags(onClosed)).containsExactly("CLOSED_ACCOUNT");
        assertThat(flags(unknown)).containsExactly("UNKNOWN_ACCOUNT");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from customers.account_posting where document_id = ?",
                                Integer.class,
                                unknown))
                .isZero();
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("CUSTOMER_PAYMENT_RECORDED"))
                .hasSize(3);
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("TILL_PAYMENT_FLAGGED"))
                .hasSize(2);
        assertThat(kernel.committedEvents())
                .filteredOn(CustomerPaymentRecorded.class::isInstance)
                .hasSize(3);

        // A redelivery of every event changes nothing.
        kernel.reset();
        deliver();
        assertThat(kernel.committedAudit())
                .extracting(AuditRecord::eventType)
                .doesNotContain("CUSTOMER_PAYMENT_RECORDED", "TILL_PAYMENT_FLAGGED");
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo("500");
    }

    /** The till's CPR bundle (27A section 7.3), as the till track writes it (CR-30-1). */
    private UUID cpr(TillSimulator till, long number, UUID accountId, String amount) {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("document_id", documentId.toString());
        document.put("doc_type_code", "CPR");
        document.put("series_id", SERIES.toString());
        document.put("doc_number", number);
        document.put("doc_number_display", "M7S-S1-1-CPR-" + number);
        document.put("location_id", SHOP.toString());
        document.put("till_position_id", POSITION.toString());
        document.put("device_id", DEVICE.toString());
        document.put("business_date", "2026-09-10");
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("customer_account_id", accountId.toString());
        payment.put("method", "CASH");
        payment.put("amount", amount);
        payment.put("allocation_mode", "OLDEST_FIRST");
        till.record("customer_payment.issued.v1", Map.of("document", document, "payment", payment));
        return documentId;
    }

    private void deliver() {
        List<OutboxMessage> messages = superuserJdbc()
                .query(
                        """
                        select event_id, event_type, occurred_at, source, source_seq, owner_entity_id, location_id,
                               aggregate_type, aggregate_id, correlation_id, causation_id, actor_user_id,
                               engine_version, payload::text as payload
                          from kernel.event_outbox
                         where source = ? and event_type = 'customer_payment.issued.v1'
                         order by source_seq
                        """,
                        (rs, i) -> new OutboxMessage(
                                rs.getObject("event_id", UUID.class),
                                rs.getString("event_type"),
                                rs.getTimestamp("occurred_at").toInstant(),
                                rs.getString("source"),
                                rs.getLong("source_seq"),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getString("aggregate_type"),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getObject("correlation_id", UUID.class),
                                rs.getObject("causation_id", UUID.class),
                                rs.getObject("actor_user_id", UUID.class),
                                rs.getString("engine_version"),
                                rs.getString("payload")),
                        DEVICE.toString());
        assertThat(messages).hasSize(3);
        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher(
                registry,
                inbox,
                new lk.coopfed.knoweb.kernel.internal.event.DeadLetter(message -> {}),
                audit,
                json,
                jdbc,
                transactionManager);
        for (OutboxMessage message : messages) {
            assertThat(dispatcher.deliver("m7.repayments", message, 1))
                    .isIn(DeliveryResult.APPLIED, DeliveryResult.DUPLICATE);
        }
    }

    private UUID account(String phone, String nic) {
        UUID id = register.handle(
                new RegisterCustomer(
                        "Member " + phone,
                        null,
                        null,
                        "si",
                        phone,
                        List.of("CREDIT_ACCOUNT"),
                        "PAPER",
                        Map.of(),
                        List.of(),
                        false),
                office());
        return open.handle(new OpenAccount(id, new BigDecimal("10000"), null, null, nic), office());
    }

    private UUID charge(UUID accountId, String amount, LocalDate day) {
        return tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        accountId,
                        new BigDecimal(amount),
                        UUID.randomUUID(),
                        "RCT-" + day,
                        1,
                        SHOP,
                        day,
                        null,
                        false),
                till());
    }

    private static BigDecimal settled(AccountQueries.Statement statement, UUID posting) {
        return statement.lines().stream()
                .filter(line -> line.postingId().equals(posting))
                .findFirst()
                .orElseThrow()
                .settled();
    }

    private List<String> flags(UUID documentId) {
        return superuserJdbc()
                .queryForList(
                        "select unnest(flags) from customers.doc_customer_payment where document_id = ?",
                        String.class,
                        documentId);
    }

    private static void forget(JdbcTemplate db) {
        db.update("delete from kernel.event_outbox where owner_entity_id = ?", SOCIETY);
        for (String table : List.of(
                "kernel.sync_event",
                "kernel.sync_quarantine",
                "kernel.device_heartbeat",
                "kernel.device_sync_cursor",
                "kernel.change_log",
                "kernel.location_snapshot_version")) {
            db.update("delete from " + table + " where owner_entity_id = ?", SOCIETY);
        }
        db.update("delete from kernel.numbering_series where series_id = ?", SERIES);
        db.update("delete from party.device where owner_entity_id = ?", SOCIETY);
        db.update("delete from party.till_position where owner_entity_id = ?", SOCIETY);
    }
}
