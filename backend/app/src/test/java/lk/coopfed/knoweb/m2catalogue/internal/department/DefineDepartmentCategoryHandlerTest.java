package lk.coopfed.knoweb.m2catalogue.internal.department;

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
import lk.coopfed.knoweb.m2catalogue.api.DefineDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.api.DepartmentCategoryDefined;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class DefineDepartmentCategoryHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private FederationCaller federation;
    private DefineDepartmentCategoryHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        federation = mock(FederationCaller.class);

        handler = new DefineDepartmentCategoryHandler(jdbc, audit, events, federation);
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
                        handler.handle(new DefineDepartmentCategory("GROCERY", "Grocery", null, null, null, 10), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void requiredFieldsValidated() {
        assertThatThrownBy(() ->
                        handler.handle(new DefineDepartmentCategory(null, "Grocery", null, null, null, 10), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(
                        () -> handler.handle(new DefineDepartmentCategory("", "Grocery", null, null, null, 10), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() ->
                        handler.handle(new DefineDepartmentCategory("GROCERY", null, null, null, null, 10), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() ->
                        handler.handle(new DefineDepartmentCategory("GROCERY", "   ", null, null, null, 10), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));
    }

    @Test
    void codeLengthValidated() {
        String longCode = "A".repeat(33);
        assertThatThrownBy(() ->
                        handler.handle(new DefineDepartmentCategory(longCode, "Grocery", null, null, null, 10), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.too_long"));
    }

    @Test
    void duplicateCodeThrows() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("GROCERY"))).thenReturn(1);

        assertThatThrownBy(() ->
                        handler.handle(new DefineDepartmentCategory("grocery", "Grocery", null, null, null, 10), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.department.code_exists"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void successDefinesCategoryAuditsAndPublishes() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("GROCERY"))).thenReturn(0);

        UUID departmentId = handler.handle(
                new DefineDepartmentCategory("grocery ", "Grocery", "වියලි ආහාර", "மளிகை", "Dry food", 10), scope);

        assertThat(departmentId).isNotNull();

        verify(jdbc)
                .update(
                        anyString(),
                        eq(departmentId),
                        eq("GROCERY"),
                        eq("Grocery"),
                        eq("වියලි ආහාර"),
                        eq("மளிகை"),
                        eq("Dry food"),
                        eq(10));

        verify(audit)
                .record(
                        eq(DefineDepartmentCategoryHandler.AUDIT_DEFINED),
                        eq(Subject.of("department_category", departmentId)),
                        eq(null),
                        any(),
                        eq(scope));

        ArgumentCaptor<DepartmentCategoryDefined> eventCaptor =
                ArgumentCaptor.forClass(DepartmentCategoryDefined.class);
        verify(events).publish(eventCaptor.capture());
        DepartmentCategoryDefined published = eventCaptor.getValue();
        assertThat(published.departmentId()).isEqualTo(departmentId);
        assertThat(published.code()).isEqualTo("GROCERY");
        assertThat(published.nameEn()).isEqualTo("Grocery");
        assertThat(published.nameSi()).isEqualTo("වියලි ආහාර");
        assertThat(published.nameTa()).isEqualTo("மளிகை");
        assertThat(published.status()).isEqualTo("ACTIVE");
    }
}
