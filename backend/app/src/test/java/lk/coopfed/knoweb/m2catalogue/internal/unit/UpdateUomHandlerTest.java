package lk.coopfed.knoweb.m2catalogue.internal.unit;

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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.UomUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateUom;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class UpdateUomHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private FederationCaller federation;
    private UpdateUomHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        federation = mock(FederationCaller.class);

        handler = new UpdateUomHandler(jdbc, audit, events, federation);
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

        assertThatThrownBy(
                        () -> handler.handle(new UpdateUom("ROLL", "Roll", null, null, null, false, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void requiredFieldsValidated() {
        assertThatThrownBy(() -> handler.handle(new UpdateUom(null, "Roll", null, null, null, false, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new UpdateUom("ROLL", null, null, null, null, false, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));
    }

    @Test
    void uomNotFoundThrowsProblem() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("UNKNOWN")))
                .thenReturn(null);

        assertThatThrownBy(() -> handler.handle(
                        new UpdateUom("UNKNOWN", "Unknown Unit", null, null, null, false, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.uom.not_found"));
    }

    @Test
    void invalidStatusRefused() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("EA")))
                .thenReturn(Map.of(
                        "uomCode", "EA",
                        "nameEn", "Each",
                        "isWeight", false,
                        "status", "ACTIVE"));

        assertThatThrownBy(() ->
                        handler.handle(new UpdateUom("EA", "Each", null, null, null, false, "DISCONTINUED"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.invalid"));
    }

    @Test
    void changingWeightWhenInUseRefused() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("EA")))
                .thenReturn(Map.of(
                        "uomCode", "EA",
                        "nameEn", "Each",
                        "isWeight", false,
                        "status", "ACTIVE"));

        when(jdbc.queryForObject(
                        eq("select count(*) from catalogue.sku where base_uom_code = ?"), eq(Integer.class), eq("EA")))
                .thenReturn(15);

        assertThatThrownBy(() -> handler.handle(new UpdateUom("EA", "Each", null, null, null, true, "ACTIVE"), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m2.uom.in_use_cannot_change_weight"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updatesUomSuccessfully() {
        Map<String, Object> existing = Map.of(
                "uomCode", "BOX",
                "nameEn", "Box",
                "nameSi", "පෙට්ටිය",
                "nameTa", "பெட்டி",
                "description", "Old box description",
                "isWeight", false,
                "status", "ACTIVE");

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("BOX"))).thenReturn(existing);

        handler.handle(
                new UpdateUom(
                        "box", "Box (Master Carton)", "පෙට්ටිය", "பெட்டி", "New master carton", false, "INACTIVE"),
                scope);

        verify(jdbc)
                .update(
                        anyString(),
                        eq("Box (Master Carton)"),
                        eq("පෙට්ටිය"),
                        eq("பெட்டி"),
                        eq("New master carton"),
                        eq(false),
                        eq("INACTIVE"),
                        eq("BOX"));

        UUID expectedSubjectId = UUID.nameUUIDFromBytes("BOX".getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(UpdateUomHandler.AUDIT_UPDATED),
                        eq(Subject.of("uom", expectedSubjectId)),
                        eq(existing),
                        afterCaptor.capture(),
                        eq(scope));

        Map<String, Object> after = afterCaptor.getValue();
        assertThat(after)
                .containsEntry("uomCode", "BOX")
                .containsEntry("nameEn", "Box (Master Carton)")
                .containsEntry("status", "INACTIVE");

        ArgumentCaptor<UomUpdated> eventCaptor = ArgumentCaptor.forClass(UomUpdated.class);
        verify(events).publish(eventCaptor.capture());

        UomUpdated event = eventCaptor.getValue();
        assertThat(event.uomCode()).isEqualTo("BOX");
        assertThat(event.nameEn()).isEqualTo("Box (Master Carton)");
        assertThat(event.status()).isEqualTo("INACTIVE");
    }
}
