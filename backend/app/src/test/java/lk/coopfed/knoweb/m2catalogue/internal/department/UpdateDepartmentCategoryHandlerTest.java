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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.DepartmentCategoryUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class UpdateDepartmentCategoryHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");
    private static final UUID DEPT_ID = UUID.fromString("0190e600-0000-7000-8000-000000000050");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private FederationCaller federation;
    private UpdateDepartmentCategoryHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        federation = mock(FederationCaller.class);

        handler = new UpdateDepartmentCategoryHandler(jdbc, audit, events, federation);
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

        assertThatThrownBy(() -> handler.handle(
                        new UpdateDepartmentCategory(DEPT_ID, "Beverages", null, null, null, 20, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void requiredFieldsValidated() {
        assertThatThrownBy(() -> handler.handle(
                        new UpdateDepartmentCategory(null, "Beverages", null, null, null, 20, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(
                        new UpdateDepartmentCategory(DEPT_ID, null, null, null, null, 20, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(
                        new UpdateDepartmentCategory(DEPT_ID, "   ", null, null, null, 20, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));
    }

    @Test
    void notFoundThrows() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(DEPT_ID)))
                .thenReturn(null);

        assertThatThrownBy(() -> handler.handle(
                        new UpdateDepartmentCategory(DEPT_ID, "Beverages", null, null, null, 20, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.department.not_found"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidStatusThrows() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("code", "BEV");
        existing.put("nameEn", "Beverage");
        existing.put("displayOrder", 10);
        existing.put("status", "ACTIVE");

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(DEPT_ID)))
                .thenReturn(existing);

        assertThatThrownBy(() -> handler.handle(
                        new UpdateDepartmentCategory(DEPT_ID, "Beverages", null, null, null, 20, "UNKNOWN"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.invalid"));
    }

    @Test
    void successUpdatesCategoryAuditsAndPublishes() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("code", "BEV");
        existing.put("nameEn", "Beverage");
        existing.put("nameSi", "බීම වර්ග");
        existing.put("nameTa", "பானங்கள்");
        existing.put("description", "Old desc");
        existing.put("displayOrder", 10);
        existing.put("status", "ACTIVE");

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(DEPT_ID)))
                .thenReturn(existing);

        handler.handle(
                new UpdateDepartmentCategory(
                        DEPT_ID, "Beverages Updated", "බීම", "பானம்", "Updated desc", 15, "INACTIVE"),
                scope);

        verify(jdbc)
                .update(
                        anyString(),
                        eq("Beverages Updated"),
                        eq("බීම"),
                        eq("பானம்"),
                        eq("Updated desc"),
                        eq(15),
                        eq("INACTIVE"),
                        eq(DEPT_ID));

        verify(audit)
                .record(
                        eq(UpdateDepartmentCategoryHandler.AUDIT_UPDATED),
                        eq(Subject.of("department_category", DEPT_ID)),
                        any(),
                        any(),
                        eq(scope));

        ArgumentCaptor<DepartmentCategoryUpdated> eventCaptor =
                ArgumentCaptor.forClass(DepartmentCategoryUpdated.class);
        verify(events).publish(eventCaptor.capture());
        DepartmentCategoryUpdated published = eventCaptor.getValue();
        assertThat(published.departmentId()).isEqualTo(DEPT_ID);
        assertThat(published.code()).isEqualTo("BEV");
        assertThat(published.nameEn()).isEqualTo("Beverages Updated");
        assertThat(published.nameSi()).isEqualTo("බීම");
        assertThat(published.nameTa()).isEqualTo("பானம்");
        assertThat(published.status()).isEqualTo("INACTIVE");
    }
}
