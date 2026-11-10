package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.event.DeadLetter;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerDispatcher;
import lk.coopfed.knoweb.kernel.internal.event.EventConsumerRegistry;
import lk.coopfed.knoweb.kernel.internal.event.InboxGuard;
import lk.coopfed.knoweb.kernel.internal.event.OutboxMessage;
import lk.coopfed.knoweb.m9integration.internal.einvoice.InvoiceSubmissionConsumer;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EInvoiceSubmissionPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = UUID.fromString("0190e9d0-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0190e9d0-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190e9d0-0000-7000-8000-000000000010");

    @Autowired
    EventConsumerRegistry registry;

    @Autowired
    AuditFacade audit;

    @Autowired
    InvoiceSubmissionConsumer consumer;

    @Autowired
    ConfigRegistry config;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        superuserJdbc().execute("truncate table integration.e_invoice_submission");

        // Previous tests may have enabled e-invoicing.
        // Reset both entity settings through the normal config API.
        for (UUID entity : List.of(SELLER, OTHER)) {
            inScope(entity, () -> {
                config.set(
                        "integration.einvoice.enabled",
                        ConfigScope.entity(entity),
                        "false",
                        scopeWithMfa(entity),
                        "ACC-10 test isolation");
                return null;
            });
        }
        kernel.reset();
    }

    @Test
    void disabledEntityDoesNotCreateSubmission() {
        UUID invoice = Ids.next();

        deliver(invoice, SELLER, SELLER);

        assertThat(countSubmissions(invoice)).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void enabledEntityCreatesPendingSubmissionOnlyOnce() {
        enable(SELLER);
        UUID invoice = Ids.next();

        deliver(invoice, SELLER, SELLER);

        assertThat(countSubmissions(invoice)).isEqualTo(1);
        assertThat(superuserJdbc()
                        .queryForMap(
                                """
                select owner_entity_id, status, attempts,
                       provider_ref, submitted_at
                  from integration.e_invoice_submission
                 where invoice_document_id = ?
                """,
                                invoice))
                .containsEntry("owner_entity_id", SELLER)
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0)
                .containsEntry("provider_ref", null)
                .containsEntry("submitted_at", null);

        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("EINVOICE_PENDING");

        kernel.reset();
        deliver(invoice, SELLER, SELLER);

        assertThat(countSubmissions(invoice)).isEqualTo(1);
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void differentEntityCannotSubmitSellersInvoice() {
        enable(OTHER);
        UUID invoice = Ids.next();

        assertThatThrownBy(() -> deliver(invoice, SELLER, OTHER)).isInstanceOf(ProblemException.class);

        assertThat(countSubmissions(invoice)).isZero();
    }

    @Test
    void otherEntityCannotReadSubmissionThroughRls() {
        enable(SELLER);
        UUID invoice = Ids.next();
        deliver(invoice, SELLER, SELLER);

        Integer sellerCount = inScope(
                SELLER,
                () -> jdbc.queryForObject("select count(*) from integration.e_invoice_submission", Integer.class));

        Integer otherCount = inScope(
                OTHER,
                () -> jdbc.queryForObject("select count(*) from integration.e_invoice_submission", Integer.class));

        assertThat(sellerCount).isEqualTo(1);
        assertThat(otherCount).isZero();
    }

    @Test
    void malformedInvoiceDoesNotCreateSubmission() {
        enable(SELLER);

        ObjectNode payload = json.createObjectNode();
        payload.put("invoiceId", Ids.next().toString());
        payload.put("sellerEntityId", SELLER.toString());

        // Required invoice fields are deliberately missing.
        assertThatThrownBy(() -> inScope(SELLER, () -> {
                    consumer.on(payload, scope(SELLER));
                    return null;
                }))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m9.einvoice.invoice_malformed");

        assertThat(superuserJdbc()
                        .queryForObject("select count(*) from integration.e_invoice_submission", Integer.class))
                .isZero();
    }

    @Test
    void issuedInvoiceReachesStubThroughKernelDispatcherOnce() throws Exception {
        enable(SELLER);
        UUID invoice = Ids.next();
        UUID eventId = Ids.next();

        ObjectNode payload = json.createObjectNode();
        payload.put("invoiceId", invoice.toString());
        payload.put("sellerEntityId", SELLER.toString());
        payload.put("buyerEntityId", OTHER.toString());
        payload.put("docNumberDisplay", "D101-INV-000099");
        payload.put("sellerVatNo", "VAT-SELLER");
        payload.put("buyerVatNo", "VAT-BUYER");
        payload.put("taxPointDate", "2026-11-10");
        payload.put("netAmount", "1000.00");
        payload.put("taxAmount", "180.00");
        payload.put("grossAmount", "1180.00");
        payload.put("contentHash", "test-immutable-hash");

        OutboxMessage message = new OutboxMessage(
                eventId,
                "invoice.issued.v1",
                Instant.now(),
                "central",
                1L,
                SELLER,
                null,
                "invoice",
                invoice,
                Ids.next(),
                null,
                null,
                null,
                json.writeValueAsString(payload));

        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher(
                registry, new InboxGuard(jdbc), new DeadLetter(ignored -> {}), audit, json, jdbc, transactionManager);

        assertThat(dispatcher.deliver("m9.einvoice.submission", message, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.APPLIED);

        assertThat(dispatcher.deliver("m9.einvoice.submission", message, 1))
                .isEqualTo(EventConsumerDispatcher.DeliveryResult.DUPLICATE);

        assertThat(countSubmissions(invoice)).isEqualTo(1);

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select status from integration.e_invoice_submission"
                                        + " where invoice_document_id = ?",
                                String.class,
                                invoice))
                .isEqualTo("PENDING");

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.event_inbox"
                                        + " where consumer = ? and event_id = ?"
                                        + " and outcome = 'APPLIED'",
                                Integer.class,
                                "m9.einvoice.submission",
                                eventId))
                .isEqualTo(1);

        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("EINVOICE_PENDING");
    }

    private void enable(UUID entity) {
        inScope(entity, () -> {
            config.set(
                    "integration.einvoice.enabled",
                    ConfigScope.entity(entity),
                    "true",
                    scopeWithMfa(entity),
                    "ACC-10 integration test");
            return null;
        });
        kernel.reset();
    }

    private void deliver(UUID invoice, UUID seller, UUID actingEntity) {
        ObjectNode payload = json.createObjectNode();
        payload.put("invoiceId", invoice.toString());
        payload.put("sellerEntityId", seller.toString());
        payload.put("buyerEntityId", Ids.next().toString());
        payload.put("docNumberDisplay", "D101-INV-000001");
        payload.put("sellerVatNo", "VAT-SELLER");
        payload.put("buyerVatNo", "VAT-BUYER");
        payload.put("taxPointDate", "2026-11-10");
        payload.put("netAmount", "1000.00");
        payload.put("taxAmount", "180.00");
        payload.put("grossAmount", "1180.00");
        payload.put("contentHash", "test-immutable-hash");

        inScope(actingEntity, () -> {
            consumer.on(payload, scope(actingEntity));
            return null;
        });
    }

    private int countSubmissions(UUID invoice) {
        return superuserJdbc()
                .queryForObject(
                        """
                select count(*)
                  from integration.e_invoice_submission
                 where invoice_document_id = ?
                """,
                        Integer.class,
                        invoice);
    }

    private static ScopeContext scopeWithMfa(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                java.time.Instant.now(),
                Locale.ENGLISH,
                Ids.next());
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
                    """
                            select set_config('app.user_id', ?, true),
                                   set_config('app.correlation_id', ?, true),
                                   set_config('app.scope_entity_id', ?, true),
                                   set_config('app.scope_location_id', '', true),
                                   set_config('app.scope_class', 'OWN', true),
                                   set_config('app.granted_entities', '{}', true)
                            """,
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString());
            return work.get();
        });
    }
}
