package lk.coopfed.knoweb.m1party.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.EntityRegistered;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RegisterEntityHandlerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID FEDERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private static final UUID MPCS_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Mock
    private EntityRepository repository;

    @Mock
    private AuditFacade audit;

    @Mock
    private EventPublisher events;

    private RegisterEntityHandler handler;

    private Entity federation;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        handler = new RegisterEntityHandler(repository, audit, events);

        federation = Entity.register(
                FEDERATION_ID,
                command("COOPFED", "FEDERATION", "Cooperative Federation", "en", 1),
                "COOPFED",
                "FEDERATION",
                "en",
                (short) 1);
    }

    @Test
    void federationCanRegisterMpcsInOnboardingState() {
        ScopeContext scope = ScopeContext.dev(USER_ID, FEDERATION_ID, null);

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("M301")).thenReturn(false);

        RegisterEntity command = command("M301", "MPCS", "MPCS 301", "en", 4);

        UUID createdId = handler.handle(command, scope);

        ArgumentCaptor<Entity> entityCaptor = ArgumentCaptor.forClass(Entity.class);

        verify(repository).saveAndFlush(entityCaptor.capture());

        Entity saved = entityCaptor.getValue();

        assertThat(saved.getId()).isEqualTo(createdId);
        assertThat(saved.auditState())
                .containsEntry("entityCode", "M301")
                .containsEntry("entityType", "MPCS")
                .containsEntry("legalNameEn", "MPCS 301")
                .containsEntry("defaultLanguage", "en")
                .containsEntry("financialYearStartMonth", (short) 4)
                .containsEntry("status", "ONBOARDING");

        verify(audit)
                .record(eq(RegisterEntityHandler.AUDIT_REGISTERED), any(), eq(null), eq(saved.auditState()), eq(scope));

        ArgumentCaptor<lk.coopfed.knoweb.kernel.api.DomainEvent> eventCaptor =
                ArgumentCaptor.forClass(lk.coopfed.knoweb.kernel.api.DomainEvent.class);

        verify(events).publish(eventCaptor.capture());

        assertThat(eventCaptor.getValue()).isInstanceOf(EntityRegistered.class);

        EntityRegistered event = (EntityRegistered) eventCaptor.getValue();

        assertThat(event.entityId()).isEqualTo(createdId);
        assertThat(event.entityType()).isEqualTo("MPCS");
        assertThat(EntityRegistered.TYPE).isEqualTo("entity.registered.v1");
    }

    @Test
    void missingScopeCannotRegisterEntity() {
        assertThatThrownBy(() -> handler.handle(validMpcsCommand(), null)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void locationScopedCallerCannotRegisterEntity() {
        ScopeContext scope =
                ScopeContext.dev(USER_ID, FEDERATION_ID, UUID.fromString("00000000-0000-0000-0000-000000000999"));

        assertThatThrownBy(() -> handler.handle(validMpcsCommand(), scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void nonFederationEntityCannotRegisterAnotherEntity() {
        Entity mpcs =
                Entity.register(MPCS_ID, command("M001", "MPCS", "MPCS One", "en", 1), "M001", "MPCS", "en", (short) 1);

        ScopeContext scope = ScopeContext.dev(USER_ID, MPCS_ID, null);

        when(repository.findById(MPCS_ID)).thenReturn(Optional.of(mpcs));

        assertThatThrownBy(() -> handler.handle(validMpcsCommand(), scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).existsByEntityCode(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateCodeIsRejectedBeforeMutation() {
        ScopeContext scope = federationScope();

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("M301")).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(validMpcsCommand(), scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void invalidEntityTypeIsRejected() {
        ScopeContext scope = federationScope();

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("X301")).thenReturn(false);

        RegisterEntity command = command("X301", "SHOP", "Invalid Entity", "en", 1);

        assertThatThrownBy(() -> handler.handle(command, scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void secondFederationIsRejected() {
        ScopeContext scope = federationScope();

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("FED002")).thenReturn(false);

        RegisterEntity command = command("FED002", "FEDERATION", "Second Federation", "en", 1);

        assertThatThrownBy(() -> handler.handle(command, scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void englishLegalNameIsRequired() {
        ScopeContext scope = federationScope();

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("M302")).thenReturn(false);

        RegisterEntity command = command("M302", "MPCS", " ", "en", 1);

        assertThatThrownBy(() -> handler.handle(command, scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void invalidDefaultLanguageIsRejected() {
        ScopeContext scope = federationScope();

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("M303")).thenReturn(false);

        RegisterEntity command = command("M303", "MPCS", "MPCS 303", "fr", 1);

        assertThatThrownBy(() -> handler.handle(command, scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void financialYearStartMonthMustBeBetweenOneAndTwelve() {
        ScopeContext scope = federationScope();

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("M304")).thenReturn(false);

        RegisterEntity command = command("M304", "MPCS", "MPCS 304", "en", 13);

        assertThatThrownBy(() -> handler.handle(command, scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void defaultsLanguageAndFinancialYearMonth() {
        ScopeContext scope = federationScope();

        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation));

        when(repository.existsByEntityCode("M305")).thenReturn(false);

        RegisterEntity command = command("M305", "MPCS", "MPCS 305", null, null);

        handler.handle(command, scope);

        ArgumentCaptor<Entity> entityCaptor = ArgumentCaptor.forClass(Entity.class);

        verify(repository).saveAndFlush(entityCaptor.capture());

        Map<String, Object> state = entityCaptor.getValue().auditState();

        assertThat(state)
                .containsEntry("defaultLanguage", "en")
                .containsEntry("financialYearStartMonth", (short) 1)
                .containsEntry("status", "ONBOARDING");
    }

    private ScopeContext federationScope() {
        return ScopeContext.dev(USER_ID, FEDERATION_ID, null);
    }

    private RegisterEntity validMpcsCommand() {
        return command("M301", "MPCS", "MPCS 301", "en", 1);
    }

    private static RegisterEntity command(
            String code, String type, String legalNameEn, String language, Integer financialYearStartMonth) {

        return new RegisterEntity(
                code,
                type,
                legalNameEn,
                null,
                null,
                "REG-001",
                "VAT-001",
                "Colombo",
                language,
                financialYearStartMonth);
    }
}
