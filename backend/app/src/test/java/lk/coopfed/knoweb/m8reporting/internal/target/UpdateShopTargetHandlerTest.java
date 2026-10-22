package lk.coopfed.knoweb.m8reporting.internal.target;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
import lk.coopfed.knoweb.m8reporting.api.ShopTargetUpdated;
import lk.coopfed.knoweb.m8reporting.api.UpdateShopTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class UpdateShopTargetHandlerTest {

    private static final UUID ENTITY_ID = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID TARGET_ID = UUID.fromString("0190e888-0000-7000-8000-000000000030");
    private static final UUID SHOP_ID = UUID.fromString("0190e888-0000-7000-8000-000000000020");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private UpdateShopTargetHandler handler;
    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneId.of("UTC"));

        handler = new UpdateShopTargetHandler(jdbc, audit, events, clock);

        Scope s = new Scope(ENTITY_ID, null);
        scope = new ScopeContext(
                USER_ID,
                null,
                ENTITY_ID,
                List.of(s),
                s,
                PolicyClass.OWN,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    @Test
    void rejectsCallerWithoutEntityScope() {
        ScopeContext fedScope = new ScopeContext(
                USER_ID,
                null,
                null,
                List.of(),
                null,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);

        UpdateShopTarget command = new UpdateShopTarget(
                TARGET_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("200000.00"));

        assertThatThrownBy(() -> handler.handle(command, fedScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.own_required");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsInvalidPeriod() {
        when(jdbc.queryForMap(anyString(), eq(TARGET_ID)))
                .thenReturn(Map.of(
                        "target_id",
                        TARGET_ID,
                        "owner_entity_id",
                        ENTITY_ID,
                        "location_id",
                        SHOP_ID,
                        "period_start",
                        java.sql.Date.valueOf("2026-10-01"),
                        "period_end",
                        java.sql.Date.valueOf("2026-10-31"),
                        "target_amount",
                        new BigDecimal("100000.00"),
                        "currency",
                        "LKR"));

        UpdateShopTarget command = new UpdateShopTarget(
                TARGET_ID, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1), new BigDecimal("200000.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.period_invalid");

        verifyNoInteractions(audit, events);
    }

    @Test
    void rejectsNegativeAmount() {
        when(jdbc.queryForMap(anyString(), eq(TARGET_ID)))
                .thenReturn(Map.of(
                        "target_id",
                        TARGET_ID,
                        "owner_entity_id",
                        ENTITY_ID,
                        "location_id",
                        SHOP_ID,
                        "period_start",
                        java.sql.Date.valueOf("2026-10-01"),
                        "period_end",
                        java.sql.Date.valueOf("2026-10-31"),
                        "target_amount",
                        new BigDecimal("100000.00"),
                        "currency",
                        "LKR"));

        UpdateShopTarget command = new UpdateShopTarget(
                TARGET_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("-1.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.amount_invalid");

        verifyNoInteractions(audit, events);
    }

    @Test
    void rejectsTargetNotFound() {
        when(jdbc.queryForMap(anyString(), eq(TARGET_ID))).thenThrow(new EmptyResultDataAccessException(1));

        UpdateShopTarget command = new UpdateShopTarget(
                TARGET_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("200000.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.not_found");

        verifyNoInteractions(audit, events);
    }

    @Test
    void rejectsTargetWhenUserScopedToDifferentLocation() {
        UUID otherLocation = UUID.randomUUID();
        Scope locScope = new Scope(ENTITY_ID, otherLocation);
        ScopeContext userWithLocScope = new ScopeContext(
                USER_ID,
                otherLocation,
                ENTITY_ID,
                List.of(locScope),
                locScope,
                PolicyClass.OWN,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);

        when(jdbc.queryForMap(anyString(), eq(TARGET_ID)))
                .thenReturn(Map.of(
                        "target_id",
                        TARGET_ID,
                        "owner_entity_id",
                        ENTITY_ID,
                        "location_id",
                        SHOP_ID,
                        "period_start",
                        java.sql.Date.valueOf("2026-10-01"),
                        "period_end",
                        java.sql.Date.valueOf("2026-10-31"),
                        "target_amount",
                        new BigDecimal("100000.00"),
                        "currency",
                        "LKR"));

        UpdateShopTarget command = new UpdateShopTarget(
                TARGET_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("200000.00"));

        assertThatThrownBy(() -> handler.handle(command, userWithLocScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.not_found");

        verifyNoInteractions(audit, events);
    }

    @Test
    void rejectsDuplicateTargetForAnotherRecordWithSameShopAndPeriod() {
        when(jdbc.queryForMap(anyString(), eq(TARGET_ID)))
                .thenReturn(Map.of(
                        "target_id",
                        TARGET_ID,
                        "owner_entity_id",
                        ENTITY_ID,
                        "location_id",
                        SHOP_ID,
                        "period_start",
                        java.sql.Date.valueOf("2026-10-01"),
                        "period_end",
                        java.sql.Date.valueOf("2026-10-31"),
                        "target_amount",
                        new BigDecimal("100000.00"),
                        "currency",
                        "LKR"));
        when(jdbc.queryForObject(
                        anyString(), eq(Integer.class), eq(ENTITY_ID), eq(SHOP_ID), any(), any(), eq(TARGET_ID)))
                .thenReturn(1);

        UpdateShopTarget command = new UpdateShopTarget(
                TARGET_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("200000.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.duplicate");

        verifyNoInteractions(audit, events);
    }

    @Test
    void updatesTargetSuccessfullyAndAuditsAndPublishesEvent() {
        when(jdbc.queryForMap(anyString(), eq(TARGET_ID)))
                .thenReturn(Map.of(
                        "target_id",
                        TARGET_ID,
                        "owner_entity_id",
                        ENTITY_ID,
                        "location_id",
                        SHOP_ID,
                        "period_start",
                        java.sql.Date.valueOf("2026-10-01"),
                        "period_end",
                        java.sql.Date.valueOf("2026-10-31"),
                        "target_amount",
                        new BigDecimal("100000.00"),
                        "currency",
                        "LKR"));
        when(jdbc.queryForObject(
                        anyString(), eq(Integer.class), eq(ENTITY_ID), eq(SHOP_ID), any(), any(), eq(TARGET_ID)))
                .thenReturn(0);

        UpdateShopTarget command = new UpdateShopTarget(
                TARGET_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("250000.00"));

        handler.handle(command, scope);

        verify(jdbc)
                .update(anyString(), eq(new BigDecimal("250000.00")), any(), any(), any(), eq(USER_ID), eq(TARGET_ID));
        verify(audit)
                .record(eq("SHOP_TARGET_UPDATED"), eq(Subject.of("shop_target", TARGET_ID)), any(), any(), eq(scope));
        verify(events).publish(any(ShopTargetUpdated.class));
    }
}
