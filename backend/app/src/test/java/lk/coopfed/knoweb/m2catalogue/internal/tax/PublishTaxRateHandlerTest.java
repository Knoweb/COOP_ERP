package lk.coopfed.knoweb.m2catalogue.internal.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.PublishTaxRate;
import lk.coopfed.knoweb.m2catalogue.api.TaxRatePublished;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PublishTaxRateHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");
    private static final UUID CATEGORY_ID = UUID.fromString("0190e600-0000-7000-8000-000000000020");

    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 10, 21);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private FederationCaller federation;
    private PublishTaxRateHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        federation = mock(FederationCaller.class);

        handler = new PublishTaxRateHandler(jdbc, audit, events, federation, FIXED_CLOCK);
        scope = ScopeContext.dev(USER_ID, FEDERATION_ENTITY, null);
    }

    @Test
    void nullCommandThrowsRequestInvalid() {
        assertThatThrownBy(() -> handler.handle(null, scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.invalid"));
    }

    @Test
    void nonFederationCallerRefused() {
        doThrow(new ProblemException("m2.sku.federation_only")).when(federation).require(scope);

        assertThatThrownBy(() ->
                        handler.handle(new PublishTaxRate(CATEGORY_ID, new BigDecimal("18.00"), FIXED_TODAY), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void requiredFieldsValidated() {
        assertThatThrownBy(() -> handler.handle(new PublishTaxRate(null, new BigDecimal("18.00"), FIXED_TODAY), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new PublishTaxRate(CATEGORY_ID, null, FIXED_TODAY), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new PublishTaxRate(CATEGORY_ID, new BigDecimal("18.00"), null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));
    }

    @Test
    void categoryNotFoundThrows() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID)))
                .thenReturn(0);

        assertThatThrownBy(() ->
                        handler.handle(new PublishTaxRate(CATEGORY_ID, new BigDecimal("18.00"), FIXED_TODAY), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.tax.category_not_found"));
    }

    @Test
    void invalidRatePercentThrows() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID)))
                .thenReturn(1);

        assertThatThrownBy(() ->
                        handler.handle(new PublishTaxRate(CATEGORY_ID, new BigDecimal("-0.01"), FIXED_TODAY), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.tax.rate_invalid"));

        assertThatThrownBy(() ->
                        handler.handle(new PublishTaxRate(CATEGORY_ID, new BigDecimal("100.01"), FIXED_TODAY), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.tax.rate_invalid"));
    }

    @Test
    void applyFromInPastThrows() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID)))
                .thenReturn(1);

        assertThatThrownBy(() -> handler.handle(
                        new PublishTaxRate(CATEGORY_ID, new BigDecimal("18.00"), FIXED_TODAY.minusDays(1)), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.tax.apply_from_in_past"));
    }

    @Test
    void rateConflictThrowsWhenExistingRateOnOrAfterApplyFrom() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID)))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID), eq(FIXED_TODAY)))
                .thenReturn(1);

        assertThatThrownBy(() ->
                        handler.handle(new PublishTaxRate(CATEGORY_ID, new BigDecimal("18.00"), FIXED_TODAY), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.tax.rate_conflict"));
    }

    @Test
    void successClosesPreviousRateInsertsNewRateAuditsAndPublishes() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID)))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID), eq(FIXED_TODAY)))
                .thenReturn(0);

        LocalDate futureApplyFrom = FIXED_TODAY.plusDays(7);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(CATEGORY_ID), eq(futureApplyFrom)))
                .thenReturn(0);

        handler.handle(new PublishTaxRate(CATEGORY_ID, new BigDecimal("18.00"), futureApplyFrom), scope);

        // Closes previous rate at applyFrom - 1
        LocalDate expectedCloseDate = futureApplyFrom.minusDays(1);
        verify(jdbc)
                .update(
                        anyString(),
                        eq(expectedCloseDate),
                        eq(CATEGORY_ID),
                        eq(expectedCloseDate),
                        eq(expectedCloseDate));

        // Inserts new rate
        verify(jdbc)
                .update(
                        anyString(),
                        eq(CATEGORY_ID),
                        eq(new BigDecimal("18.00")),
                        eq(futureApplyFrom),
                        eq(FEDERATION_ENTITY));

        // Audits
        verify(audit)
                .record(
                        eq(PublishTaxRateHandler.AUDIT_PUBLISHED),
                        eq(Subject.of("tax_category", CATEGORY_ID)),
                        eq(null),
                        any(),
                        eq(scope));

        // Publishes domain event
        ArgumentCaptor<TaxRatePublished> eventCaptor = ArgumentCaptor.forClass(TaxRatePublished.class);
        verify(events).publish(eventCaptor.capture());
        TaxRatePublished published = eventCaptor.getValue();
        assertThat(published.taxCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(published.ratePercent()).isEqualTo(new BigDecimal("18.00"));
        assertThat(published.applyFrom()).isEqualTo(futureApplyFrom);
    }
}
