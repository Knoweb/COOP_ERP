package lk.coopfed.knoweb.m3pricing.internal.markdown;

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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import lk.coopfed.knoweb.m3pricing.api.EvaluateExpirySlowMovingDiscounts;
import lk.coopfed.knoweb.m3pricing.api.ExpirySlowMovingDiscountsEvaluated;
import lk.coopfed.knoweb.m3pricing.api.ExpirySlowMovingEvaluationResult;
import lk.coopfed.knoweb.m3pricing.query.ExpirySlowMovingDiscountView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvaluateExpirySlowMovingDiscountsHandlerTest {

    private ExpirySlowMovingDiscountCalculator calculator;
    private PartyQueries party;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private EvaluateExpirySlowMovingDiscountsHandler handler;

    private final UUID entityId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-02T08:00:00Z");

    @BeforeEach
    void setUp() {
        calculator = mock(ExpirySlowMovingDiscountCalculator.class);
        party = mock(PartyQueries.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(now, ZoneOffset.UTC);

        handler = new EvaluateExpirySlowMovingDiscountsHandler(calculator, party, audit, events, clock, "UTC");
    }

    @Test
    @DisplayName("Guard: null command or null locationId throws request.invalid")
    void nullCommandThrowsRequestInvalid() {
        ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), entityId, locationId);
        assertThatThrownBy(() -> handler.handle(null, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");

        assertThatThrownBy(() -> handler.handle(new EvaluateExpirySlowMovingDiscounts(null), scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("request.invalid");
    }

    @Test
    @DisplayName("Guard: non-OWN scope or missing entity throws scope.invalid")
    void nonOwnScopeThrowsScopeInvalid() {
        EvaluateExpirySlowMovingDiscounts cmd = new EvaluateExpirySlowMovingDiscounts(locationId);

        assertThatThrownBy(() -> handler.handle(cmd, null))
                .isInstanceOf(ProblemException.class)
                .hasMessage("scope.invalid");

        Scope s = new Scope(entityId, locationId);
        ScopeContext partyScope = new ScopeContext(
                UUID.randomUUID(),
                null,
                entityId,
                List.of(s),
                s,
                PolicyClass.PARTY,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);

        assertThatThrownBy(() -> handler.handle(cmd, partyScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("scope.invalid");
    }

    @Test
    @DisplayName("Guard: location not owned by entity throws m3.label.location_not_owned")
    void locationNotOwnedThrowsException() {
        EvaluateExpirySlowMovingDiscounts cmd = new EvaluateExpirySlowMovingDiscounts(locationId);
        ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), entityId, locationId);

        // Location belongs to different entity
        UUID otherEntity = UUID.randomUUID();
        when(party.getLocation(eq(locationId), any())).thenReturn(Optional.of(mockLocation(locationId, otherEntity)));

        assertThatThrownBy(() -> handler.handle(cmd, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m3.label.location_not_owned");
    }

    @Test
    @DisplayName("Successful evaluation executes calculation, audits, publishes event, and returns summary")
    void successfulEvaluation() {
        EvaluateExpirySlowMovingDiscounts cmd = new EvaluateExpirySlowMovingDiscounts(locationId);
        ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), entityId, locationId);

        when(party.getLocation(eq(locationId), any())).thenReturn(Optional.of(mockLocation(locationId, entityId)));

        ExpirySlowMovingDiscountView d1 = new ExpirySlowMovingDiscountView(
                UUID.randomUUID(),
                "SKU-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "B-1",
                locationId,
                "NEAR_EXPIRY",
                "CRITICAL",
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                new BigDecimal("70.00"),
                new BigDecimal("10.000"),
                LocalDate.of(2026, 11, 5),
                3,
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 5),
                "ACTIVE");

        ExpirySlowMovingDiscountView d2 = new ExpirySlowMovingDiscountView(
                UUID.randomUUID(),
                "SKU-2",
                null,
                null,
                null,
                locationId,
                "SLOW_MOVING",
                "STAGNANT",
                new BigDecimal("200.00"),
                new BigDecimal("20.00"),
                new BigDecimal("160.00"),
                new BigDecimal("50.000"),
                null,
                null,
                LocalDate.of(2026, 11, 2),
                null,
                "ACTIVE");

        when(calculator.calculate(eq(locationId), any(), any(), eq(scope))).thenReturn(List.of(d1, d2));

        ExpirySlowMovingEvaluationResult result = handler.handle(cmd, scope);

        assertThat(result.locationId()).isEqualTo(locationId);
        assertThat(result.totalEvaluated()).isEqualTo(2);
        assertThat(result.markdownsActive()).isEqualTo(2);
        assertThat(result.nearExpiryCount()).isEqualTo(1);
        assertThat(result.slowMovingCount()).isEqualTo(1);
        assertThat(result.evaluatedAt()).isEqualTo(now);

        verify(audit)
                .record(
                        eq(EvaluateExpirySlowMovingDiscountsHandler.AUDIT_EVALUATED),
                        eq(Subject.of("location", locationId)),
                        eq(null),
                        any(Map.class),
                        eq(scope));

        ArgumentCaptor<ExpirySlowMovingDiscountsEvaluated> eventCaptor =
                ArgumentCaptor.forClass(ExpirySlowMovingDiscountsEvaluated.class);
        verify(events).publish(eventCaptor.capture());

        ExpirySlowMovingDiscountsEvaluated event = eventCaptor.getValue();
        assertThat(event.locationId()).isEqualTo(locationId);
        assertThat(event.ownerEntityId()).isEqualTo(entityId);
        assertThat(event.totalEvaluated()).isEqualTo(2);
        assertThat(event.markdownsActive()).isEqualTo(2);
        assertThat(event.nearExpiryCount()).isEqualTo(1);
        assertThat(event.slowMovingCount()).isEqualTo(1);
        assertThat(event.evaluatedAt()).isEqualTo(now);
    }

    private LocationView mockLocation(UUID locId, UUID owner) {
        return new LocationView(
                locId,
                owner,
                "LOC-01",
                "SHOP",
                "Main Shop",
                null,
                null,
                null,
                null,
                null,
                null,
                "en",
                List.of(),
                null,
                true,
                null,
                "ACTIVE");
    }
}
