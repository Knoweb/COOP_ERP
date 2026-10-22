package lk.coopfed.knoweb.m8reporting.internal.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m8reporting.api.CreateShopTarget;
import lk.coopfed.knoweb.m8reporting.api.ShopTargetCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CreateShopTargetHandlerTest {

    private static final UUID ENTITY_ID = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID SHOP_ID = UUID.fromString("0190e888-0000-7000-8000-000000000020");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private PartyQueries party;
    private Clock clock;
    private CreateShopTargetHandler handler;
    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        party = mock(PartyQueries.class);
        clock = Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneId.of("UTC"));

        handler = new CreateShopTargetHandler(jdbc, audit, events, party, clock);

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

        when(party.getLocation(eq(SHOP_ID), any())).thenReturn(Optional.of(location(SHOP_ID, ENTITY_ID)));
    }

    private static LocationView location(UUID id, UUID owner) {
        return new LocationView(
                id,
                owner,
                "SHOP-01",
                "SHOP",
                "Branch Shop",
                null,
                null,
                null,
                null,
                null,
                null,
                "en",
                List.of(),
                null,
                false,
                null,
                "ACTIVE");
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

        CreateShopTarget command = new CreateShopTarget(
                SHOP_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("100000.00"));

        assertThatThrownBy(() -> handler.handle(command, fedScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.own_required");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsInvalidDateRange() {
        CreateShopTarget command = new CreateShopTarget(
                SHOP_ID, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1), new BigDecimal("100000.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.period_invalid");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsNegativeTargetAmount() {
        CreateShopTarget command = new CreateShopTarget(
                SHOP_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("-50.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.amount_invalid");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsUnknownLocation() {
        when(party.getLocation(eq(SHOP_ID), any())).thenReturn(Optional.empty());

        CreateShopTarget command = new CreateShopTarget(
                SHOP_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("100000.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.location_unknown");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsLocationBelongingToAnotherEntity() {
        UUID otherEntity = UUID.randomUUID();
        when(party.getLocation(eq(SHOP_ID), any())).thenReturn(Optional.of(location(SHOP_ID, otherEntity)));

        CreateShopTarget command = new CreateShopTarget(
                SHOP_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("100000.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.location_scope_mismatch");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsDuplicateTargetForSameShopAndPeriod() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(ENTITY_ID), eq(SHOP_ID), any(), any()))
                .thenReturn(1);

        CreateShopTarget command = new CreateShopTarget(
                SHOP_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("100000.00"));

        assertThatThrownBy(() -> handler.handle(command, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.target.duplicate");

        verifyNoInteractions(audit, events);
    }

    @Test
    void createsTargetSuccessfullyAndAuditsAndPublishesEvent() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(ENTITY_ID), eq(SHOP_ID), any(), any()))
                .thenReturn(0);

        CreateShopTarget command = new CreateShopTarget(
                SHOP_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("150000.00"));

        UUID targetId = handler.handle(command, scope);

        assertThat(targetId).isNotNull();
        verify(jdbc)
                .update(
                        anyString(),
                        eq(targetId),
                        eq(ENTITY_ID),
                        eq(SHOP_ID),
                        any(),
                        any(),
                        eq(new BigDecimal("150000.00")),
                        eq("LKR"),
                        any(),
                        eq(USER_ID),
                        any(),
                        eq(USER_ID));
        verify(audit)
                .record(eq("SHOP_TARGET_CREATED"), eq(Subject.of("shop_target", targetId)), isNull(), any(), eq(scope));
        verify(events).publish(any(ShopTargetCreated.class));
    }
}
