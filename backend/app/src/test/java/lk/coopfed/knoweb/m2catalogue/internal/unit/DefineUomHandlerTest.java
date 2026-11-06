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
import lk.coopfed.knoweb.m2catalogue.api.DefineUom;
import lk.coopfed.knoweb.m2catalogue.api.UomDefined;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class DefineUomHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private FederationCaller federation;
    private DefineUomHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        federation = mock(FederationCaller.class);

        handler = new DefineUomHandler(jdbc, audit, events, federation);
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

        assertThatThrownBy(() -> handler.handle(new DefineUom("ROLL", "Roll", null, null, null, false), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.federation_only"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void requiredFieldsValidated() {
        assertThatThrownBy(() -> handler.handle(new DefineUom(null, "Roll", null, null, null, false), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new DefineUom("", "Roll", null, null, null, false), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new DefineUom("ROLL", null, null, null, null, false), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));

        assertThatThrownBy(() -> handler.handle(new DefineUom("ROLL", "   ", null, null, null, false), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.required"));
    }

    @Test
    void codeLengthValidated() {
        String longCode = "TOOLONGCODE1";
        assertThatThrownBy(() -> handler.handle(new DefineUom(longCode, "Roll", null, null, null, false), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.field.too_long"));
    }

    @Test
    void duplicateCodeRefused() {
        when(jdbc.queryForObject(
                        eq("select count(*) from catalogue.uom where uom_code = ?"), eq(Integer.class), eq("KG")))
                .thenReturn(1);

        assertThatThrownBy(() -> handler.handle(new DefineUom("kg", "Kilogram", null, null, null, true), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.uom.code_exists"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void definesUomSuccessfully() {
        when(jdbc.queryForObject(
                        eq("select count(*) from catalogue.uom where uom_code = ?"), eq(Integer.class), eq("PACK")))
                .thenReturn(0);

        String result = handler.handle(
                new DefineUom("pack", "Packet 10s", "පැකට්ටු", "பொட்டலம்", "Standard ten pack", false), scope);

        assertThat(result).isEqualTo("PACK");

        verify(jdbc)
                .update(
                        anyString(),
                        eq("PACK"),
                        eq("Packet 10s"),
                        eq("පැකට්ටු"),
                        eq("பொட்டலம்"),
                        eq("Standard ten pack"),
                        eq(false));

        UUID expectedSubjectId = UUID.nameUUIDFromBytes("PACK".getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(DefineUomHandler.AUDIT_DEFINED),
                        eq(Subject.of("uom", expectedSubjectId)),
                        eq(null),
                        auditCaptor.capture(),
                        eq(scope));

        Map<String, Object> auditPayload = auditCaptor.getValue();
        assertThat(auditPayload)
                .containsEntry("uomCode", "PACK")
                .containsEntry("nameEn", "Packet 10s")
                .containsEntry("nameSi", "පැකට්ටු")
                .containsEntry("nameTa", "பொட்டலம்")
                .containsEntry("description", "Standard ten pack")
                .containsEntry("isWeight", false)
                .containsEntry("status", "ACTIVE");

        ArgumentCaptor<UomDefined> eventCaptor = ArgumentCaptor.forClass(UomDefined.class);
        verify(events).publish(eventCaptor.capture());

        UomDefined published = eventCaptor.getValue();
        assertThat(published.uomId()).isEqualTo(expectedSubjectId);
        assertThat(published.uomCode()).isEqualTo("PACK");
        assertThat(published.nameEn()).isEqualTo("Packet 10s");
        assertThat(published.nameSi()).isEqualTo("පැකට්ටු");
        assertThat(published.nameTa()).isEqualTo("பொட்டலம்");
        assertThat(published.weight()).isFalse();
        assertThat(published.status()).isEqualTo("ACTIVE");
    }
}
