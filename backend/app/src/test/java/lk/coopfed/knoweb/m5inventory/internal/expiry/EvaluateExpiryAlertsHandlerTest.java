package lk.coopfed.knoweb.m5inventory.internal.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.EvaluateExpiryAlerts;
import lk.coopfed.knoweb.m5inventory.api.ExpiryAlertScanResult;
import lk.coopfed.knoweb.m5inventory.api.NearExpiryDetected;
import lk.coopfed.knoweb.m5inventory.api.SlowMovingDetected;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.query.NearExpiryLotView;
import lk.coopfed.knoweb.m5inventory.query.SlowMovingStockView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EvaluateExpiryAlertsHandlerTest {

    private static final UUID ENTITY_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("0190e67c-0000-7000-8000-000000000002");
    private static final UUID OTHER_LOCATION = UUID.fromString("0190e67c-0000-7000-8000-000000000003");

    private ExpiryAlertStore store;
    private ControlPolicy policy;
    private AuditFacade audit;
    private EventPublisher events;
    private JdbcTemplate jdbc;
    private Clock clock;
    private EvaluateExpiryAlertsHandler handler;

    @BeforeEach
    void setUp() {
        store = mock(ExpiryAlertStore.class);
        policy = mock(ControlPolicy.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        jdbc = mock(JdbcTemplate.class);
        clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Asia/Colombo"));

        handler = new EvaluateExpiryAlertsHandler(store, policy, jdbc, clock, "Asia/Colombo", audit, events);
    }

    private ScopeContext ownScope(UUID entityId, UUID locationId) {
        return ScopeContext.dev(UUID.randomUUID(), entityId, locationId);
    }

    private ScopeContext sharedScope() {
        UUID user = UUID.randomUUID();
        return new ScopeContext(
                user,
                null,
                ENTITY_ID,
                List.of(new Scope(ENTITY_ID, LOCATION_ID)),
                new Scope(ENTITY_ID, LOCATION_ID),
                PolicyClass.FEDERATION_VIEW,
                null,
                null,
                Locale.ENGLISH,
                null);
    }

    @Test
    void rejectsNonOwnScope() {
        EvaluateExpiryAlerts command = new EvaluateExpiryAlerts(LOCATION_ID);
        assertThatThrownBy(() -> handler.handle(command, sharedScope())).isInstanceOf(ProblemException.class);
    }

    @Test
    void rejectsNullLocationId() {
        EvaluateExpiryAlerts command = new EvaluateExpiryAlerts(null);
        assertThatThrownBy(() -> handler.handle(command, ownScope(ENTITY_ID, LOCATION_ID)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m5.expiry.location_required");
    }

    @Test
    void rejectsLocationMismatchWhenScopeIsNarrowedToLocation() {
        EvaluateExpiryAlerts command = new EvaluateExpiryAlerts(OTHER_LOCATION);
        assertThatThrownBy(() -> handler.handle(command, ownScope(ENTITY_ID, LOCATION_ID)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m5.location.not_in_scope");
    }

    @Test
    void evaluatesNearExpiryAndSlowMovingAlertsAndPublishesEvents() {
        ScopeContext scope = ownScope(ENTITY_ID, LOCATION_ID);

        when(policy.nearExpiryCriticalDays(scope)).thenReturn(7);
        when(policy.expiryWarningDays(scope)).thenReturn(30);
        when(policy.slowMovingDays(scope)).thenReturn(30);
        when(policy.slowMovingThresholdDos(scope)).thenReturn(90);

        UUID lotId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        NearExpiryLotView nearExpiryLot = new NearExpiryLotView(
                lotId,
                LOCATION_ID,
                skuId,
                batchId,
                "BATCH-01",
                LocalDate.parse("2026-11-05"),
                4,
                new BigDecimal("20.000"),
                new BigDecimal("100.0000"),
                "CRITICAL",
                false);

        SlowMovingStockView slowMovingItem = new SlowMovingStockView(
                LOCATION_ID,
                skuId,
                new BigDecimal("50.000"),
                new BigDecimal("120.0000"),
                35,
                LocalDate.parse("2026-09-25"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("999.00"),
                "STAGNANT",
                false);

        when(store.findNearExpiryLots(eq(LOCATION_ID), eq(30), any(), any(), eq(7), eq(30), eq(scope)))
                .thenReturn(List.of(nearExpiryLot));
        when(store.findActiveAlerts(eq(LOCATION_ID), eq("NEAR_EXPIRY"), eq(scope)))
                .thenReturn(List.of());

        when(store.findSlowMovingStock(
                        eq(LOCATION_ID), eq(30), eq(90), any(LocalDate.class), any(ZoneId.class), eq(scope)))
                .thenReturn(List.of(slowMovingItem));
        when(store.findActiveAlerts(eq(LOCATION_ID), eq("SLOW_MOVING"), eq(scope)))
                .thenReturn(List.of());

        ExpiryAlertScanResult result = handler.handle(new EvaluateExpiryAlerts(LOCATION_ID), scope);

        assertThat(result.nearExpiryAlertsEmitted()).isEqualTo(1);
        assertThat(result.slowMovingAlertsEmitted()).isEqualTo(1);
        assertThat(result.totalEvaluated()).isEqualTo(2);

        verify(events).publish(any(NearExpiryDetected.class));
        verify(events).publish(any(SlowMovingDetected.class));
        verify(audit).record(eq(EvaluateExpiryAlertsHandler.AUDIT_NEAR_EXPIRY), any(), any(), any(), eq(scope));
        verify(audit).record(eq(EvaluateExpiryAlertsHandler.AUDIT_SLOW_MOVING), any(), any(), any(), eq(scope));
    }
}
