package lk.coopfed.knoweb.m1party.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ActivateEntity;
import lk.coopfed.knoweb.m1party.api.EntityActivated;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ActivateEntityHandlerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID FEDERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private static final UUID OFFICER_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

    @Mock
    private EntityRepository repository;

    @Mock
    private EntityActivationPrerequisites prerequisites;

    @Mock
    private AuditFacade audit;

    @Mock
    private EventPublisher events;

    private ActivateEntityHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        handler = new ActivateEntityHandler(repository, prerequisites, audit, events);

        // The caller of every lifecycle command is the Federation (FederationCaller).
        RegisterEntity federation = new RegisterEntity(
                "COOPFED",
                "FEDERATION",
                "Cooperative Federation",
                null,
                null,
                "REG-001",
                "VAT-001",
                "Colombo",
                "en",
                1);
        when(repository.findById(FEDERATION_ID))
                .thenReturn(Optional.of(
                        Entity.register(FEDERATION_ID, federation, "COOPFED", "FEDERATION", "en", (short) 1)));
    }

    @Test
    void anEntityCannotActivateItself() {
        Entity entity = onboardingEntity("VAT-301");
        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(
                        () -> handler.handle(new ActivateEntity(ENTITY_ID), ScopeContext.dev(USER_ID, ENTITY_ID, null)))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m1.entity.federation_required");

        verify(repository, never()).saveAndFlush(any());
        verify(events, never()).publish(any());
    }

    @Test
    void activatesEntityWhenAllPrerequisitesAreSatisfied() {
        Entity entity = onboardingEntity("VAT-301");

        entity.appointResponsibleOfficer(OFFICER_ID, LocalDate.of(2026, 9, 22));

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        when(prerequisites.hasUserManager(ENTITY_ID)).thenReturn(true);

        ScopeContext scope = federationScope();

        Map<String, Object> before = entity.auditState();

        UUID result = handler.handle(new ActivateEntity(ENTITY_ID), scope);

        assertThat(result).isEqualTo(ENTITY_ID);
        assertThat(entity.status()).isEqualTo(Entity.STATUS_ACTIVE);

        verify(repository).saveAndFlush(entity);

        Map<String, Object> after = entity.auditState();

        assertThat(before).containsEntry("status", Entity.STATUS_ONBOARDING);

        assertThat(after).containsEntry("status", Entity.STATUS_ACTIVE);

        verify(audit).record(eq(ActivateEntityHandler.AUDIT_ACTIVATED), any(), eq(before), eq(after), eq(scope));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);

        verify(events).publish(eventCaptor.capture());

        assertThat(eventCaptor.getValue()).isInstanceOf(EntityActivated.class);

        EntityActivated event = (EntityActivated) eventCaptor.getValue();

        assertThat(event.entityId()).isEqualTo(ENTITY_ID);

        assertThat(event.entityCode()).isEqualTo("M301");

        assertThat(event.entityType()).isEqualTo("MPCS");

        assertThat(event.status()).isEqualTo(Entity.STATUS_ACTIVE);

        assertThat(EntityActivated.TYPE).isEqualTo("entity.activated.v1");
    }

    @Test
    void missingScopeCannotActivateEntity() {
        assertThatThrownBy(() -> handler.handle(new ActivateEntity(ENTITY_ID), null))
                .isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void locationScopedCallerCannotActivateEntity() {
        ScopeContext scope =
                ScopeContext.dev(USER_ID, FEDERATION_ID, UUID.fromString("00000000-0000-0000-0000-000000000999"));

        assertThatThrownBy(() -> handler.handle(new ActivateEntity(ENTITY_ID), scope))
                .isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void missingEntityIsRejected() {
        when(repository.findById(ENTITY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new ActivateEntity(ENTITY_ID), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());

        verify(prerequisites, never()).hasUserManager(any());
    }

    @Test
    void onlyOnboardingEntityCanBeActivated() {
        Entity entity = onboardingEntity("VAT-301");

        entity.appointResponsibleOfficer(OFFICER_ID, LocalDate.of(2026, 9, 22));

        entity.activate();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> handler.handle(new ActivateEntity(ENTITY_ID), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(prerequisites, never()).hasUserManager(any());

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void responsibleOfficerIsRequiredBeforeAdminCheck() {
        Entity entity = onboardingEntity("VAT-301");

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> handler.handle(new ActivateEntity(ENTITY_ID), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(prerequisites, never()).hasUserManager(any());

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void userManagerIsRequiredBeforeVatCheck() {
        Entity entity = onboardingEntity("VAT-301");

        entity.appointResponsibleOfficer(OFFICER_ID, LocalDate.of(2026, 9, 22));

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        when(prerequisites.hasUserManager(ENTITY_ID)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(new ActivateEntity(ENTITY_ID), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(prerequisites).hasUserManager(ENTITY_ID);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void vatRegistrationIsRequired() {
        Entity entity = onboardingEntity(null);

        entity.appointResponsibleOfficer(OFFICER_ID, LocalDate.of(2026, 9, 22));

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        when(prerequisites.hasUserManager(ENTITY_ID)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(new ActivateEntity(ENTITY_ID), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(prerequisites).hasUserManager(ENTITY_ID);

        verify(repository, never()).saveAndFlush(any());

        verify(audit, never()).record(any(), any(), any(), any(), any());

        verify(events, never()).publish(any());
    }

    private ScopeContext federationScope() {
        return ScopeContext.dev(USER_ID, FEDERATION_ID, null);
    }

    private Entity onboardingEntity(String vatRegistrationNo) {

        RegisterEntity command = new RegisterEntity(
                "M301", "MPCS", "MPCS 301", null, null, "REG-301", vatRegistrationNo, "Colombo", "en", 1);

        return Entity.register(ENTITY_ID, command, "M301", "MPCS", "en", (short) 1);
    }
}
