package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.ConfirmCodSettlement;
import lk.coopfed.knoweb.m9integration.api.ConfirmPayment;
import lk.coopfed.knoweb.m9integration.api.InitiatePayment;
import lk.coopfed.knoweb.m9integration.api.PaymentCodPending;
import lk.coopfed.knoweb.m9integration.api.PaymentInitiated;
import lk.coopfed.knoweb.m9integration.api.PaymentOrchestrator;
import lk.coopfed.knoweb.m9integration.api.PaymentSettled;
import lk.coopfed.knoweb.m9integration.api.RetryPayment;
import lk.coopfed.knoweb.m9integration.internal.payment.ConfirmCodSettlementHandler;
import lk.coopfed.knoweb.m9integration.internal.payment.ConfirmPaymentHandler;
import lk.coopfed.knoweb.m9integration.internal.payment.InitiatePaymentHandler;
import lk.coopfed.knoweb.m9integration.internal.payment.RetryPaymentHandler;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import lk.coopfed.knoweb.m9integration.query.PaymentQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PostgreSQL integration test proving online payments, COD, and LankaQR payment orchestration (M3-07).
 */
@Tag("integration")
class PaymentOrchestrationPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER_ENTITY = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e9c0-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");

    @Autowired
    Handles<InitiatePayment, PaymentIntentView> initiatePayment;

    @Autowired
    Handles<ConfirmPayment, PaymentIntentView> confirmPayment;

    @Autowired
    Handles<ConfirmCodSettlement, PaymentIntentView> confirmCodPayment;

    @Autowired
    Handles<RetryPayment, PaymentIntentView> retryPayment;

    @Autowired
    PaymentQueries paymentQueries;

    @Autowired
    PaymentOrchestrator paymentOrchestrator;

    private ScopeContext ownerScope;
    private ScopeContext otherScope;

    @BeforeEach
    void setUpScope() {
        Scope s1 = new Scope(OWNER_ENTITY, null);
        ownerScope = new ScopeContext(
                USER_ID, null, OWNER_ENTITY, List.of(s1), s1, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);

        Scope s2 = new Scope(OTHER_ENTITY, null);
        otherScope = new ScopeContext(
                USER_ID, null, OTHER_ENTITY, List.of(s2), s2, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);

        superuserJdbc().update("DELETE FROM integration.payment_event_log");
        superuserJdbc().update("DELETE FROM integration.payment_intent");
        kernel.reset();
    }

    @Test
    void paymentMovesSeamlesslyFromInitiatedToPaidWithoutDuplicateSettlement() {
        UUID paymentId = Ids.next();
        InitiatePayment initCmd = new InitiatePayment(
                paymentId,
                "ORD-ONLINE-001",
                "CUST-ONL-101",
                new BigDecimal("5000.00"),
                "ONLINE",
                "mock",
                "Online retail order payment",
                "DEDUP-ONL-001",
                Map.of("channel", "WEB"));

        PaymentIntentView initiated = initiatePayment.handle(initCmd, ownerScope);
        assertThat(initiated.paymentId()).isEqualTo(paymentId);
        assertThat(initiated.status()).isEqualTo("PENDING");
        assertThat(initiated.provider()).isEqualTo("mock");

        // Verify audit & event for initiation
        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .contains(InitiatePaymentHandler.AUDIT_PAYMENT_INITIATED);
        assertThat(kernel.committedEvents()).hasAtLeastOneElementOfType(PaymentInitiated.class);

        // Confirm settlement from provider callback
        ConfirmPayment confirmCmd =
                new ConfirmPayment(paymentId, "PGW-TXN-998877", "PAID", null, Map.of("gatewayAuthCode", "AUTH-999"));

        PaymentIntentView confirmed = confirmPayment.handle(confirmCmd, ownerScope);
        assertThat(confirmed.status()).isEqualTo("PAID");
        assertThat(confirmed.providerRef()).isEqualTo("PGW-TXN-998877");
        assertThat(confirmed.settledAt()).isNotNull();

        long auditCountAfterFirstSettlement = kernel.committedAudit().stream()
                .filter(a -> ConfirmPaymentHandler.AUDIT_PAYMENT_SETTLED.equals(a.eventType()))
                .count();
        long eventsCountAfterFirstSettlement = kernel.committedEvents().stream()
                .filter(e -> e instanceof PaymentSettled)
                .count();

        assertThat(auditCountAfterFirstSettlement).isEqualTo(1);
        assertThat(eventsCountAfterFirstSettlement).isEqualTo(1);

        // Second confirmation (duplicate webhook/callback or retry): Must be idempotent with NO second settlement
        ConfirmPayment duplicateConfirmCmd =
                new ConfirmPayment(paymentId, "PGW-TXN-998877", "PAID", null, Map.of("gatewayAuthCode", "AUTH-999"));

        PaymentIntentView duplicateResult = confirmPayment.handle(duplicateConfirmCmd, ownerScope);
        assertThat(duplicateResult.status()).isEqualTo("PAID");

        long auditCountAfterDuplicate = kernel.committedAudit().stream()
                .filter(a -> ConfirmPaymentHandler.AUDIT_PAYMENT_SETTLED.equals(a.eventType()))
                .count();
        long eventsCountAfterDuplicate = kernel.committedEvents().stream()
                .filter(e -> e instanceof PaymentSettled)
                .count();

        // Proves NO duplicate settlement occurred
        assertThat(auditCountAfterDuplicate).isEqualTo(1);
        assertThat(eventsCountAfterDuplicate).isEqualTo(1);
    }

    @Test
    void lankaQrPaymentInitiationAndDynamicQrPayloadGeneration() {
        UUID paymentId = Ids.next();
        InitiatePayment initCmd = new InitiatePayment(
                paymentId,
                "ORD-LQR-7788",
                "CUST-LQR-202",
                new BigDecimal("3450.75"),
                "LANKA_QR",
                "lankaqr",
                "LankaQR bill settlement",
                "DEDUP-LQR-7788",
                Map.of("terminal", "POS-01"));

        PaymentIntentView view = initiatePayment.handle(initCmd, ownerScope);
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.qrPayload()).isNotNull();
        // Conforms to EMVCo Merchant-Presented LankaQR standard
        assertThat(view.qrPayload()).startsWith("000201010212");
        assertThat(view.qrPayload()).contains("lk.cbsl.lankaqr");
        assertThat(view.qrPayload()).contains("3450.75");
        assertThat(view.qrPayload()).contains("ORD-LQR-7788");

        // Confirm settlement via LankaQR callback
        ConfirmPayment confirmCmd = new ConfirmPayment(paymentId, "LQR-SETTLE-001", "PAID", null, Map.of());
        PaymentIntentView settled = confirmPayment.handle(confirmCmd, ownerScope);
        assertThat(settled.status()).isEqualTo("PAID");
        assertThat(settled.settledAt()).isNotNull();
    }

    @Test
    void cashOnDeliveryFlowFromInitiatedToCodPendingToPaid() {
        UUID paymentId = Ids.next();
        InitiatePayment initCmd = new InitiatePayment(
                paymentId,
                "ORD-COD-4455",
                "CUST-COD-303",
                new BigDecimal("6750.00"),
                "COD",
                "mock",
                "COD Grocery Drop",
                "DEDUP-COD-4455",
                Map.of("courier", "Internal Fleet"));

        PaymentIntentView initiated = initiatePayment.handle(initCmd, ownerScope);
        assertThat(initiated.status()).isEqualTo("COD_PENDING");
        assertThat(initiated.paymentMethod()).isEqualTo("COD");

        assertThat(kernel.committedEvents()).hasAtLeastOneElementOfType(PaymentCodPending.class);

        // Delivery agent collects cash and confirms settlement
        ConfirmCodSettlement codCmd =
                new ConfirmCodSettlement(paymentId, "CASH-RCP-9900", Map.of("cashier", "Agent-1"));
        PaymentIntentView settled = confirmCodPayment.handle(codCmd, ownerScope);

        assertThat(settled.status()).isEqualTo("PAID");
        assertThat(settled.providerRef()).isEqualTo("CASH-RCP-9900");
        assertThat(settled.settledAt()).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .contains(ConfirmCodSettlementHandler.AUDIT_COD_COLLECTED);

        // Idempotent second COD confirmation
        PaymentIntentView secondSettle = confirmCodPayment.handle(codCmd, ownerScope);
        assertThat(secondSettle.status()).isEqualTo("PAID");
    }

    @Test
    void paymentFailureAndRetryFlow() {
        UUID paymentId = Ids.next();
        InitiatePayment initCmd = new InitiatePayment(
                paymentId,
                "ORD-FAIL-1122",
                "CUST-FAIL-404",
                new BigDecimal("1200.00"),
                "ONLINE",
                "mock",
                "Card purchase",
                "DEDUP-FAIL-1122",
                Map.of());

        initiatePayment.handle(initCmd, ownerScope);

        // Fail payment
        ConfirmPayment failCmd = new ConfirmPayment(paymentId, "FAIL-TXN", "FAILED", "Card network declined", Map.of());
        PaymentIntentView failed = confirmPayment.handle(failCmd, ownerScope);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("Card network declined");

        // Retry payment
        RetryPayment retryCmd = new RetryPayment(paymentId, "mock");
        PaymentIntentView retried = retryPayment.handle(retryCmd, ownerScope);
        assertThat(retried.status()).isEqualTo("PENDING");
        assertThat(retried.metadata()).containsEntry("retryCount", 1);

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .contains(RetryPaymentHandler.AUDIT_PAYMENT_RETRIED);
    }

    @Test
    void crossTenantRlsIsolation() {
        UUID paymentId = Ids.next();
        InitiatePayment initCmd = new InitiatePayment(
                paymentId,
                "ORD-TENANT-A",
                "CUST-A",
                new BigDecimal("990.00"),
                "ONLINE",
                "mock",
                "Tenant A order",
                "DEDUP-A",
                Map.of());

        initiatePayment.handle(initCmd, ownerScope);

        // Tenant A can query it
        Optional<PaymentIntentView> foundByOwner = paymentQueries.findById(paymentId, ownerScope);
        assertThat(foundByOwner).isPresent();

        // Other tenant cannot query it under RLS
        Optional<PaymentIntentView> foundByOther = paymentQueries.findById(paymentId, otherScope);
        assertThat(foundByOther).isEmpty();

        // Other tenant cannot settle Tenant A's payment
        ConfirmPayment otherConfirm = new ConfirmPayment(paymentId, "HACK-TXN", "PAID", null, Map.of());
        assertThatThrownBy(() -> confirmPayment.handle(otherConfirm, otherScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.payment.not_found");
    }
}
