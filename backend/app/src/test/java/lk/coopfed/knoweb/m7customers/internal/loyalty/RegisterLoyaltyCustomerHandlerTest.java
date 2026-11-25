package lk.coopfed.knoweb.m7customers.internal.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.LoyaltyCustomerEnrolled;
import lk.coopfed.knoweb.m7customers.api.RegisterLoyaltyCustomer;
import lk.coopfed.knoweb.m7customers.internal.customer.NicCapture;
import lk.coopfed.knoweb.m7customers.internal.customer.ReuseDetector;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class RegisterLoyaltyCustomerHandlerTest {

    private static final UUID SOCIETY = UUID.fromString("0190f700-0000-7000-8000-000000000001");
    private static final UUID SHOP = UUID.fromString("0190f700-0000-7000-8000-000000000011");
    private static final UUID USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");

    private JdbcTemplate jdbc;
    private ReuseDetector reuse;
    private NicCapture nicCapture;
    private CustomersClock clock;
    private ObjectMapper json;
    private AuditFacade audit;
    private EventPublisher events;
    private RegisterLoyaltyCustomerHandler handler;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        reuse = mock(ReuseDetector.class);
        nicCapture = mock(NicCapture.class);
        clock = mock(CustomersClock.class);
        when(clock.now()).thenReturn(Instant.parse("2026-10-28T10:00:00Z"));
        json = new ObjectMapper();
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        handler = new RegisterLoyaltyCustomerHandler(jdbc, reuse, nicCapture, clock, json, audit, events);
    }

    private ScopeContext scope(UUID locationId) {
        return ScopeContext.dev(USER, SOCIETY, locationId);
    }

    @Test
    void scopeMustBeOwn() {
        ScopeContext fed = new ScopeContext(
                USER,
                null,
                SOCIETY,
                List.of(new Scope(SOCIETY, null)),
                new Scope(SOCIETY, null),
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Name",
                null,
                null,
                "en",
                "0771234567",
                null,
                SHOP,
                "POL-01",
                null,
                "STANDARD",
                true,
                Set.of(),
                Map.of());

        assertThatThrownBy(() -> handler.handle(cmd, fed))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.scope.own_required");
    }

    @Test
    void locationRequired() {
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Name",
                null,
                null,
                "en",
                "0771234567",
                null,
                null,
                "POL-01",
                null,
                "STANDARD",
                true,
                Set.of(),
                Map.of());

        assertThatThrownBy(() -> handler.handle(cmd, scope(null)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.location_required");
    }

    @Test
    void regionCodeRequired() {
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Name",
                null,
                null,
                "en",
                "0771234567",
                null,
                SHOP,
                "",
                null,
                "STANDARD",
                true,
                Set.of(),
                Map.of());

        assertThatThrownBy(() -> handler.handle(cmd, scope(SHOP)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.field.required");
    }

    @Test
    void invalidTierRefused() {
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Name",
                null,
                null,
                "en",
                "0771234567",
                null,
                SHOP,
                "POL-01",
                null,
                "SUPER_VIP",
                true,
                Set.of(),
                Map.of());

        assertThatThrownBy(() -> handler.handle(cmd, scope(SHOP)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.tier_invalid");
    }

    @Test
    void alreadyEnrolledRefused() {
        UUID customerId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(customerId), eq(SOCIETY)))
                .thenReturn(1) // exists in customer table
                .thenReturn(1); // already in loyalty_customer

        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                customerId, null, null, null, null, null, null, SHOP, "POL-01", null, "GOLD", true, Set.of(), Map.of());

        assertThatThrownBy(() -> handler.handle(cmd, scope(SHOP)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.already_enrolled");
    }

    @Test
    void enrollsExistingCustomerWithoutPiiInAuditOrEvent() {
        UUID customerId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(customerId), eq(SOCIETY)))
                .thenReturn(1) // exists in customer
                .thenReturn(0); // not yet in loyalty_customer

        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                customerId,
                null,
                null,
                null,
                null,
                null,
                null,
                SHOP,
                "PGL-ZONE-1",
                "REG-888",
                "GOLD",
                true,
                Set.of(),
                Map.of("cluster", "A"));

        UUID loyaltyId = handler.handle(cmd, scope(SHOP));
        assertThat(loyaltyId).isNotNull();

        // Verify audit contains no PII
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(RegisterLoyaltyCustomerHandler.AUDIT_LOYALTY_ENROLLED),
                        eq(Subject.of("loyalty_customer", loyaltyId)),
                        eq(null),
                        auditCaptor.capture(),
                        any());

        Map<String, Object> auditPayload = auditCaptor.getValue();
        assertThat(auditPayload)
                .containsEntry("customerId", customerId.toString())
                .containsEntry("registeredLocationId", SHOP.toString())
                .containsEntry("regionCode", "PGL-ZONE-1")
                .containsEntry("regionalMemberNo", "REG-888")
                .containsEntry("loyaltyTier", "GOLD");
        assertThat(auditPayload.keySet()).doesNotContain("phone", "nic", "name", "displayName");

        // Verify event contains no PII
        ArgumentCaptor<LoyaltyCustomerEnrolled> eventCaptor = ArgumentCaptor.forClass(LoyaltyCustomerEnrolled.class);
        verify(events).publish(eventCaptor.capture());
        LoyaltyCustomerEnrolled event = eventCaptor.getValue();
        assertThat(event.loyaltyId()).isEqualTo(loyaltyId);
        assertThat(event.customerId()).isEqualTo(customerId);
        assertThat(event.registeredLocationId()).isEqualTo(SHOP);
        assertThat(event.regionCode()).isEqualTo("PGL-ZONE-1");
        assertThat(event.loyaltyTier()).isEqualTo("GOLD");
    }
}
