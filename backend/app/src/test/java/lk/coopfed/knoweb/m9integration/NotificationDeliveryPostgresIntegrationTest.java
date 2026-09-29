package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.NotificationRuleChanged;
import lk.coopfed.knoweb.m9integration.api.SetNotificationRuleStatus;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M9's side of notification delivery against the kernel's dispatcher, renderer, log and retry
 * sweep (29A sections 6.3 and 9): the seeded rules reach the contacts of the other party of a
 * trading event, in the contact's language (the three scripts rendered through ICU, amounts
 * grouped); the SMTP adapter against a relay that speaks SMTP, which is what Mailpit is locally;
 * a relay that refuses once is retried with backoff and then sent; SMS through the log provider;
 * a federation rule retired and activated again by the Federation only. The log holds hashes,
 * never an address.
 */
class NotificationDeliveryPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final FakeSmtpServer SMTP;

    static {
        try {
            SMTP = new FakeSmtpServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final UUID SELLER = UUID.fromString("0190e9b0-0000-7000-8000-000000000001");
    private static final UUID BUYER_SI = UUID.fromString("0190e9b0-0000-7000-8000-000000000002");
    private static final UUID BUYER_TA = UUID.fromString("0190e9b0-0000-7000-8000-000000000003");
    private static final UUID BUYER_EN = UUID.fromString("0190e9b0-0000-7000-8000-000000000004");
    private static final UUID USER = UUID.fromString("0190e9b0-0000-7000-8000-000000000010");
    private static final UUID INVOICE_RULE = UUID.fromString("0190f9a0-0000-7000-8000-000000000001");

    @DynamicPropertySource
    static void relay(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.integration.smtp.host", () -> "127.0.0.1");
        registry.add("coop-erp.integration.smtp.port", SMTP::port);
    }

    @AfterAll
    static void stopRelay() throws IOException {
        SMTP.close();
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ConfigRegistry config;

    @Autowired
    Handles<SetNotificationRuleStatus, java.util.UUID> setStatus;

    @Autowired
    IntegrationQueries queries;

    @BeforeEach
    void arrange() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from kernel.notification_pending");
        admin.execute("delete from kernel.notification_log");
        admin.execute("truncate table integration.notification_contact");
        admin.execute("update integration.notification_rule set status = 'ACTIVE' where owner_entity_id is null");
        contact(BUYER_SI, "EMAIL", "accounts@buyer-si.coop-erp.test", "si");
        contact(BUYER_SI, "SMS", "0700000901", "si");
        contact(BUYER_TA, "EMAIL", "accounts@buyer-ta.coop-erp.test", "ta");
        contact(BUYER_EN, "EMAIL", "accounts@buyer-en.coop-erp.test", "en");
        SMTP.messages.clear();
        SMTP.failNext.set(0);
        invoke("invalidateRules");
        // No quiet hours for the seller in this test: the clock is whatever it is.
        inScope(SELLER, () -> {
            config.set("notification.sms.quiet_hours", ConfigScope.entity(SELLER), "", scope(SELLER), "test");
            return null;
        });
        kernel.reset();
    }

    @Test
    void anIssuedInvoiceReachesTheBuyersAccountsDeskInItsLanguage() throws Exception {
        for (UUID buyer : List.of(BUYER_SI, BUYER_TA, BUYER_EN)) {
            dispatch("invoice.issued.v1", invoice(buyer));
        }

        assertThat(SMTP.messages).hasSize(3);
        Map<String, MimeMessage> byRecipient = new java.util.HashMap<>();
        for (String raw : SMTP.messages) {
            MimeMessage message = parse(raw);
            byRecipient.put(message.getAllRecipients()[0].toString(), message);
        }
        MimeMessage si = byRecipient.get("accounts@buyer-si.coop-erp.test");
        assertThat(si.getSubject()).isEqualTo("ඉන්වොයිසිය D101-INV-000123 නිකුත් කෙරිණි");
        assertThat(body(si)).contains("රු. 11,800.50").contains("2026-09-04");
        MimeMessage ta = byRecipient.get("accounts@buyer-ta.coop-erp.test");
        assertThat(ta.getSubject()).isEqualTo("விலைப்பட்டியல் D101-INV-000123 வழங்கப்பட்டது");
        assertThat(body(ta)).contains("ரூ. 11,800.50");
        MimeMessage en = byRecipient.get("accounts@buyer-en.coop-erp.test");
        assertThat(en.getSubject()).isEqualTo("Invoice D101-INV-000123 issued");
        assertThat(body(en))
                .isEqualTo("Invoice D101-INV-000123 has been issued to you for Rs 11,800.50, tax point 2026-08-05,"
                        + " due 2026-09-04.");

        List<Map<String, Object>> log = superuserJdbc()
                .queryForList(
                        "select status, language, channel, recipient_hash, owner_entity_id from kernel.notification_log"
                                + " order by language");
        assertThat(log)
                .extracting(row -> row.get("status") + " " + row.get("channel") + " " + row.get("language"))
                .containsExactly("SENT EMAIL en", "SENT EMAIL si", "SENT EMAIL ta");
        assertThat(log).allSatisfy(row -> {
            assertThat(String.valueOf(row.get("recipient_hash"))).hasSize(64).doesNotContain("@");
            assertThat(row.get("owner_entity_id")).isEqualTo(SELLER);
        });

        // The seller's delivery log on the screen: newest first, hashes cut short.
        assertThat(queries.log(null, 10, user(SELLER))).hasSize(3).allSatisfy(entry -> assertThat(entry.recipientHash())
                .hasSize(12));
        assertThat(queries.log("FAILED", 10, user(SELLER))).isEmpty();
        assertThat(queries.log(null, 10, user(BUYER_SI))).isEmpty();
    }

    @Test
    void aSellerCannotReadTheBuyersContactsYetItsInvoiceStillReachesThem() throws Exception {
        String count = "select count(*) from integration.notification_contact where owner_entity_id = ?";
        assertThat(inScope(SELLER, () -> jdbc.queryForObject(count, Long.class, BUYER_SI)))
                .isZero();
        assertThat(inScope(
                        SELLER,
                        () -> jdbc.queryForObject("select count(*) from integration.notification_contact", Long.class)))
                .isZero();
        // The buyer reads its own two.
        assertThat(inScope(BUYER_SI, () -> jdbc.queryForObject(count, Long.class, BUYER_SI)))
                .isEqualTo(2L);
        // And a seller session cannot write a contact for the buyer.
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> inScope(
                        SELLER,
                        () -> jdbc.update(
                                "insert into integration.notification_contact"
                                        + " (contact_id, owner_entity_id, role_code, channel, address, language)"
                                        + " values (?, ?, 'ACCOUNTS', 'EMAIL', 'x@buyer-si.coop-erp.test', 'si')",
                                Ids.next(),
                                BUYER_SI)));

        // The dispatcher, in the seller's scope, still reaches them through the resolver.
        dispatch("invoice.issued.v1", invoice(BUYER_SI));
        assertThat(SMTP.messages).hasSize(1);
        assertThat(parse(SMTP.messages.get(0)).getAllRecipients()[0].toString())
                .isEqualTo("accounts@buyer-si.coop-erp.test");
    }

    @Test
    void aRelayThatRefusesIsRetriedWithBackoffAndThenSends() throws Exception {
        SMTP.failNext.set(1);
        ObjectNode receipt = json.createObjectNode();
        receipt.put("receiptId", Ids.next().toString());
        receipt.put("docNumberDisplay", "D101-PRC-000044");
        receipt.put("sellerEntityId", SELLER.toString());
        receipt.put("buyerEntityId", BUYER_EN.toString());
        receipt.put("method", "CASH");
        receipt.put("amount", new BigDecimal("2500.00"));
        receipt.put("receivedOn", "2026-08-12");
        receipt.put("unappliedAmount", new BigDecimal("0.00"));
        dispatch("payment_receipt.recorded.v1", receipt);

        Map<String, Object> failed = superuserJdbc()
                .queryForMap("select notification_id, status, attempts, last_error, last_attempt_at, next_attempt_at"
                        + " from kernel.notification_log");
        assertThat(failed.get("status")).isEqualTo("QUEUED");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(failed.get("last_error")))
                .contains("SMTP relay")
                .doesNotContain("buyer-en");
        // The kernel's first backoff (19A section 10): a minute after the failed attempt.
        assertThat(((Timestamp) failed.get("next_attempt_at")).toInstant())
                .isAfter(((Timestamp) failed.get("last_attempt_at")).toInstant().plusSeconds(30));
        assertThat(SMTP.messages).isEmpty();

        superuserJdbc()
                .update(
                        "update kernel.notification_log set next_attempt_at = now() - interval '1 minute'"
                                + " where notification_id = ?",
                        failed.get("notification_id"));
        Object sweep = context.getBean(Class.forName("lk.coopfed.knoweb.kernel.internal.notification.RetrySweepJob"));
        Method retryDue = sweep.getClass().getMethod("retryDue");
        retryDue.setAccessible(true);
        retryDue.invoke(sweep);

        Map<String, Object> sent =
                superuserJdbc().queryForMap("select status, attempts, provider_ref from kernel.notification_log");
        assertThat(sent.get("status")).isEqualTo("SENT");
        assertThat(((Number) sent.get("attempts")).intValue()).isEqualTo(2);
        assertThat(String.valueOf(sent.get("provider_ref"))).isNotBlank();
        assertThat(SMTP.messages).hasSize(1);
        assertThat(parse(SMTP.messages.get(0)).getSubject()).isEqualTo("Payment D101-PRC-000044 received");
    }

    @Test
    void aBouncedChequeGoesByEmailAndBySmsThroughTheLogProvider() {
        ObjectNode bounced = json.createObjectNode();
        bounced.put("receiptId", Ids.next().toString());
        bounced.put("reversalId", Ids.next().toString());
        bounced.put("sellerEntityId", SELLER.toString());
        bounced.put("buyerEntityId", BUYER_SI.toString());
        bounced.put("amount", new BigDecimal("4000.00"));
        bounced.put("reason", "Refer to drawer");
        dispatch("cheque.bounced.v1", bounced);

        List<Map<String, Object>> log = superuserJdbc()
                .queryForList("select channel, status, provider_ref, template_id from kernel.notification_log"
                        + " order by channel");
        assertThat(log)
                .extracting(row -> row.get("channel") + " " + row.get("status") + " " + row.get("template_id"))
                .containsExactly("EMAIL SENT cheque-bounced-email", "SMS SENT cheque-bounced-sms");
        assertThat(String.valueOf(log.get(1).get("provider_ref"))).startsWith("log-");
        assertThat(SMTP.messages).hasSize(1);
    }

    @Test
    void theFederationRetiresAndActivatesItsRules() {
        UUID ruleId = setStatus.handle(
                new SetNotificationRuleStatus(INVOICE_RULE, SetNotificationRuleStatus.RETIRED), user(TEST_FEDERATION));
        assertThat(ruleId).isEqualTo(INVOICE_RULE);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select status from integration.notification_rule where rule_id = ?",
                                String.class,
                                INVOICE_RULE))
                .isEqualTo("RETIRED");
        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly("NOTIFICATION_RULE_STATUS_CHANGED");
        assertThat(kernel.committedEvents())
                .containsExactly(new NotificationRuleChanged(INVOICE_RULE, "invoice.issued.v1", "RETIRED"));

        // Retired, the rule sends nothing.
        invoke("invalidateRules");
        dispatch("invoice.issued.v1", invoice(BUYER_EN));
        assertThat(SMTP.messages).isEmpty();

        kernel.reset();
        ProblemException unchanged = assertThrows(
                ProblemException.class,
                () -> setStatus.handle(
                        new SetNotificationRuleStatus(INVOICE_RULE, SetNotificationRuleStatus.RETIRED),
                        user(TEST_FEDERATION)));
        assertThat(unchanged.messageId()).isEqualTo("m9.rule.status_unchanged");

        ProblemException notTheFederation = assertThrows(
                ProblemException.class,
                () -> setStatus.handle(
                        new SetNotificationRuleStatus(INVOICE_RULE, SetNotificationRuleStatus.ACTIVE), user(SELLER)));
        assertThat(notTheFederation.messageId()).isEqualTo("m9.rule.federation_required");

        ProblemException unknown = assertThrows(
                ProblemException.class,
                () -> setStatus.handle(
                        new SetNotificationRuleStatus(Ids.next(), SetNotificationRuleStatus.ACTIVE),
                        user(TEST_FEDERATION)));
        assertThat(unknown.messageId()).isEqualTo("m9.rule.not_found");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        setStatus.handle(
                new SetNotificationRuleStatus(INVOICE_RULE, SetNotificationRuleStatus.ACTIVE), user(TEST_FEDERATION));
        invoke("invalidateRules");
        dispatch("invoice.issued.v1", invoice(BUYER_EN));
        assertThat(SMTP.messages).hasSize(1);
    }

    @Test
    void everySeededTemplateHasItsThreeLanguagesAndEveryRuleAnActiveTemplate() {
        List<IntegrationQueries.TemplateView> templates = queries.templates(user(SELLER));
        assertThat(templates).isNotEmpty().allSatisfy(t -> {
            assertThat(t.bodyEn()).isNotBlank();
            assertThat(t.bodySi()).isNotBlank();
            assertThat(t.bodyTa()).isNotBlank();
            assertThat(t.status()).isEqualTo("ACTIVE");
            assertThat(t.placeholders()).isNotEmpty();
        });
        Set<String> ids = templates.stream()
                .map(IntegrationQueries.TemplateView::templateId)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(queries.rules(user(SELLER))).isNotEmpty().allSatisfy(rule -> assertThat(ids)
                .contains(rule.templateId()));
    }

    // ---------------------------------------------------------------------------------------

    private ObjectNode invoice(UUID buyer) {
        ObjectNode invoice = json.createObjectNode();
        invoice.put("invoiceId", Ids.next().toString());
        invoice.put("docNumberDisplay", "D101-INV-000123");
        invoice.put("sellerEntityId", SELLER.toString());
        invoice.put("buyerEntityId", buyer.toString());
        invoice.put("taxPointDate", "2026-08-05");
        invoice.put("dueDate", "2026-09-04");
        invoice.put("netAmount", new BigDecimal("10000.50"));
        invoice.put("taxAmount", new BigDecimal("1800.00"));
        invoice.put("grossAmount", new BigDecimal("11800.50"));
        return invoice;
    }

    /** The kernel's dispatcher on an event of the seller's, as the consumer framework hands it over. */
    private void dispatch(String eventType, ObjectNode payload) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventType", eventType);
        envelope.put("eventId", Ids.next().toString());
        envelope.put("ownerEntityId", SELLER.toString());
        envelope.put("occurredAt", "2026-08-05T04:00:00Z");
        envelope.set("payload", payload);
        inScope(SELLER, () -> {
            invoke("onEvent", envelope, scope(SELLER));
            return null;
        });
    }

    /** A method of the kernel's dispatcher, a class of the kernel's internals that this module never imports. */
    private void invoke(String name, Object... arguments) {
        try {
            Object dispatcher = context.getBean(
                    Class.forName("lk.coopfed.knoweb.kernel.internal.notification.NotificationDispatcher"));
            for (Method method : dispatcher.getClass().getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                    method.setAccessible(true);
                    method.invoke(dispatcher, arguments);
                    return;
                }
            }
            throw new IllegalStateException("The dispatcher has no method " + name);
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        }
    }

    private void contact(UUID entity, String channel, String address, String language) {
        superuserJdbc()
                .update(
                        "insert into integration.notification_contact"
                                + " (contact_id, owner_entity_id, role_code, channel, address, language)"
                                + " values (?, ?, 'ACCOUNTS', ?, ?, ?)",
                        Ids.next(),
                        entity,
                        channel,
                        address,
                        language);
    }

    private static MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(
                Session.getInstance(new Properties()), new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static String body(MimeMessage message) throws Exception {
        return String.valueOf(message.getContent()).strip();
    }

    private static ScopeContext user(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private static ScopeContext scope(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    private <T> T inScope(UUID entity, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString());
            return work.get();
        });
    }
}
