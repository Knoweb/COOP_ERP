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

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.DefineTaxCategory;
import lk.coopfed.knoweb.m2catalogue.api.TaxCategoryDefined;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class DefineTaxCategoryHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private FederationCaller federation;
    private DefineTaxCategoryHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        federation = mock(FederationCaller.class);

        handler = new DefineTaxCategoryHandler(jdbc, audit, events, federation);
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

        assertThatThrownBy(() -> handler.handle(new DefineTaxCategory("VAT", "VAT", null, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void requiredFieldsValidated() {
        assertThatThrownBy(() -> handler.handle(new DefineTaxCategory(null, "VAT", null, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new DefineTaxCategory("", "VAT", null, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new DefineTaxCategory("VAT", null, null, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new DefineTaxCategory("VAT", "   ", null, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));
    }

    @Test
    void codeLengthValidated() {
        assertThatThrownBy(() -> handler.handle(new DefineTaxCategory("TOOLONGFORCODE123", "VAT", null, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.too_long"));
    }

    @Test
    void duplicateCodeThrows() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("VAT"))).thenReturn(1);

        assertThatThrownBy(() -> handler.handle(new DefineTaxCategory("vat", "VAT", null, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(
                        e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.tax.category_code_exists"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void successDefinesCategoryAuditsAndPublishes() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("VAT"))).thenReturn(0);

        UUID categoryId = handler.handle(new DefineTaxCategory("vat ", "Value Added Tax", "වැට්", "வாட்"), scope);

        assertThat(categoryId).isNotNull();

        verify(jdbc)
                .update(
                        anyString(),
                        eq(categoryId),
                        eq("VAT"),
                        eq("Value Added Tax"),
                        eq("වැට්"),
                        eq("வாட்"),
                        eq(FEDERATION_ENTITY));

        verify(audit)
                .record(
                        eq(DefineTaxCategoryHandler.AUDIT_DEFINED),
                        eq(Subject.of("tax_category", categoryId)),
                        eq(null),
                        any(),
                        eq(scope));

        ArgumentCaptor<TaxCategoryDefined> eventCaptor = ArgumentCaptor.forClass(TaxCategoryDefined.class);
        verify(events).publish(eventCaptor.capture());
        TaxCategoryDefined published = eventCaptor.getValue();
        assertThat(published.taxCategoryId()).isEqualTo(categoryId);
        assertThat(published.ownerEntityId()).isEqualTo(FEDERATION_ENTITY);
        assertThat(published.code()).isEqualTo("VAT");
    }
}
