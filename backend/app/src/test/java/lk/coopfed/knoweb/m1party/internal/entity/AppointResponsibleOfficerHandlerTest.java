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
import lk.coopfed.knoweb.m1party.api.AppointResponsibleOfficer;
import lk.coopfed.knoweb.m1party.api.EntityUpdated;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AppointResponsibleOfficerHandlerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private static final UUID OFFICER_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");

    private static final LocalDate SIGNED_ON = LocalDate.of(2026, 9, 23);

    @Mock
    private EntityRepository repository;

    @Mock
    private EntityOfficerOwnership ownership;

    @Mock
    private AuditFacade audit;

    @Mock
    private EventPublisher events;

    private AppointResponsibleOfficerHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        handler = new AppointResponsibleOfficerHandler(repository, ownership, audit, events);
    }

    @Test
    void appointsResponsibleOfficerAndPublishesEntityUpdated() {

        Entity entity = onboardingEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        when(ownership.userBelongsToEntity(OFFICER_ID, ENTITY_ID)).thenReturn(true);

        ScopeContext scope = entityScope();

        Map<String, Object> before = entity.auditState();

        UUID result = handler.handle(new AppointResponsibleOfficer(ENTITY_ID, OFFICER_ID, SIGNED_ON), scope);

        assertThat(result).isEqualTo(ENTITY_ID);

        Map<String, Object> after = entity.auditState();

        assertThat(before)
                .containsEntry("responsibleOfficerUserId", null)
                .containsEntry("dataGovernanceSignedOn", null);

        assertThat(after)
                .containsEntry("responsibleOfficerUserId", OFFICER_ID)
                .containsEntry("dataGovernanceSignedOn", SIGNED_ON);

        verify(repository).saveAndFlush(entity);

        verify(audit)
                .record(eq(AppointResponsibleOfficerHandler.AUDIT_UPDATED), any(), eq(before), eq(after), eq(scope));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);

        verify(events).publish(eventCaptor.capture());

        assertThat(eventCaptor.getValue()).isInstanceOf(EntityUpdated.class);

        EntityUpdated event = (EntityUpdated) eventCaptor.getValue();

        assertThat(event.entityId()).isEqualTo(ENTITY_ID);
        assertThat(event.entityCode()).isEqualTo("M301");
        assertThat(event.entityType()).isEqualTo("MPCS");
        assertThat(event.status()).isEqualTo(Entity.STATUS_ONBOARDING);

        assertThat(EntityUpdated.TYPE).isEqualTo("entity.updated.v1");
    }

    @Test
    void missingScopeIsRejected() {

        assertThatThrownBy(() -> handler.handle(validCommand(), null)).isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());
        verify(repository, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void locationScopeIsRejected() {

        ScopeContext scope = ScopeContext.dev(USER_ID, ENTITY_ID, LOCATION_ID);

        assertThatThrownBy(() -> handler.handle(validCommand(), scope)).isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void nullCommandIsRejected() {

        assertThatThrownBy(() -> handler.handle(null, entityScope())).isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void missingEntityIdIsRejected() {

        AppointResponsibleOfficer command = new AppointResponsibleOfficer(null, OFFICER_ID, SIGNED_ON);

        assertThatThrownBy(() -> handler.handle(command, entityScope())).isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void missingOfficerUserIdIsRejected() {

        AppointResponsibleOfficer command = new AppointResponsibleOfficer(ENTITY_ID, null, SIGNED_ON);

        assertThatThrownBy(() -> handler.handle(command, entityScope())).isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void missingGovernanceSignedDateIsRejected() {

        AppointResponsibleOfficer command = new AppointResponsibleOfficer(ENTITY_ID, OFFICER_ID, null);

        assertThatThrownBy(() -> handler.handle(command, entityScope())).isInstanceOf(ProblemException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void missingEntityIsRejected() {

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(validCommand(), entityScope())).isInstanceOf(ProblemException.class);

        verify(ownership, never()).userBelongsToEntity(any(), any());

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void responsibleOfficerCanOnlyBeChangedDuringOnboarding() {

        Entity entity = onboardingEntity();

        entity.activate();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> handler.handle(validCommand(), entityScope())).isInstanceOf(ProblemException.class);

        verify(ownership, never()).userBelongsToEntity(any(), any());

        verify(repository, never()).saveAndFlush(any());

        verify(audit, never()).record(any(), any(), any(), any(), any());

        verify(events, never()).publish(any());
    }

    @Test
    void officerMustBelongToTargetEntity() {

        Entity entity = onboardingEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        when(ownership.userBelongsToEntity(OFFICER_ID, ENTITY_ID)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(validCommand(), entityScope())).isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());

        verify(audit, never()).record(any(), any(), any(), any(), any());

        verify(events, never()).publish(any());
    }

    private ScopeContext entityScope() {
        return ScopeContext.dev(USER_ID, ENTITY_ID, null);
    }

    private AppointResponsibleOfficer validCommand() {
        return new AppointResponsibleOfficer(ENTITY_ID, OFFICER_ID, SIGNED_ON);
    }

    private Entity onboardingEntity() {

        RegisterEntity command =
                new RegisterEntity("M301", "MPCS", "MPCS 301", null, null, "REG-301", "VAT-301", "Colombo", "en", 1);

        return Entity.register(ENTITY_ID, command, "M301", "MPCS", "en", (short) 1);
    }
}
