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
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.EntityReinstated;
import lk.coopfed.knoweb.m1party.api.EntitySuspended;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import lk.coopfed.knoweb.m1party.api.ReinstateEntity;
import lk.coopfed.knoweb.m1party.api.SuspendEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class EntityStatusLifecycleHandlerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID FEDERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Mock
    private EntityRepository repository;

    @Mock
    private AuditFacade audit;

    @Mock
    private EventPublisher events;

    private SuspendEntityHandler suspendHandler;
    private ReinstateEntityHandler reinstateHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        suspendHandler = new SuspendEntityHandler(repository, audit, events);

        reinstateHandler = new ReinstateEntityHandler(repository, audit, events);

        // The caller of every lifecycle command is the Federation (FederationCaller).
        when(repository.findById(FEDERATION_ID)).thenReturn(Optional.of(federation()));
    }

    @Test
    void anEntityCannotSuspendOrReinstateItself() {
        // The scope is entity-wide OWN, as the guard requires, but the caller is an MPCS.
        Entity mpcs = activeEntity();
        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(mpcs));
        ScopeContext ownScope = ScopeContext.dev(USER_ID, ENTITY_ID, null);

        assertThatThrownBy(
                        () -> suspendHandler.handle(new SuspendEntity(ENTITY_ID, "GOVERNANCE_REVIEW", null), ownScope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m1.entity.federation_required");
        assertThatThrownBy(() ->
                        reinstateHandler.handle(new ReinstateEntity(ENTITY_ID, "GOVERNANCE_REVIEW", null), ownScope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m1.entity.federation_required");

        verify(repository, never()).saveAndFlush(any());
        verify(events, never()).publish(any());
    }

    private static Entity federation() {
        RegisterEntity command = new RegisterEntity(
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
        return Entity.register(FEDERATION_ID, command, "COOPFED", "FEDERATION", "en", (short) 1);
    }

    @Test
    void activeEntityCanBeSuspendedWithReason() {
        Entity entity = activeEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        ScopeContext scope = federationScope();

        Map<String, Object> before = entity.auditState();

        UUID result = suspendHandler.handle(
                new SuspendEntity(ENTITY_ID, "GOVERNANCE_REVIEW", "Temporary governance review"), scope);

        assertThat(result).isEqualTo(ENTITY_ID);

        assertThat(entity.status()).isEqualTo(Entity.STATUS_SUSPENDED);

        verify(repository).saveAndFlush(entity);

        Map<String, Object> after = entity.auditState();

        verify(audit)
                .record(
                        eq(SuspendEntityHandler.AUDIT_SUSPENDED),
                        any(),
                        eq(before),
                        eq(after),
                        eq(scope),
                        eq("GOVERNANCE_REVIEW: " + "Temporary governance review"));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);

        verify(events).publish(eventCaptor.capture());

        assertThat(eventCaptor.getValue()).isInstanceOf(EntitySuspended.class);

        EntitySuspended event = (EntitySuspended) eventCaptor.getValue();

        assertThat(event.entityId()).isEqualTo(ENTITY_ID);

        assertThat(event.status()).isEqualTo(Entity.STATUS_SUSPENDED);

        assertThat(EntitySuspended.TYPE).isEqualTo("entity.suspended.v1");
    }

    @Test
    void onboardingEntityCannotBeSuspended() {
        Entity entity = onboardingEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> suspendHandler.handle(
                        new SuspendEntity(ENTITY_ID, "GOVERNANCE_REVIEW", null), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());

        verify(events, never()).publish(any());
    }

    @Test
    void suspensionRequiresReasonAfterStateCheck() {
        Entity entity = activeEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> suspendHandler.handle(new SuspendEntity(ENTITY_ID, " ", null), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());

        verify(events, never()).publish(any());
    }

    @Test
    void suspendedEntityCanBeReinstatedWithReason() {
        Entity entity = suspendedEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        ScopeContext scope = federationScope();

        Map<String, Object> before = entity.auditState();

        UUID result = reinstateHandler.handle(
                new ReinstateEntity(ENTITY_ID, "REVIEW_COMPLETE", "Governance review completed"), scope);

        assertThat(result).isEqualTo(ENTITY_ID);

        assertThat(entity.status()).isEqualTo(Entity.STATUS_ACTIVE);

        verify(repository).saveAndFlush(entity);

        Map<String, Object> after = entity.auditState();

        verify(audit)
                .record(
                        eq(ReinstateEntityHandler.AUDIT_REINSTATED),
                        any(),
                        eq(before),
                        eq(after),
                        eq(scope),
                        eq("REVIEW_COMPLETE: " + "Governance review completed"));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);

        verify(events).publish(eventCaptor.capture());

        assertThat(eventCaptor.getValue()).isInstanceOf(EntityReinstated.class);

        EntityReinstated event = (EntityReinstated) eventCaptor.getValue();

        assertThat(event.entityId()).isEqualTo(ENTITY_ID);

        assertThat(event.status()).isEqualTo(Entity.STATUS_ACTIVE);

        assertThat(EntityReinstated.TYPE).isEqualTo("entity.reinstated.v1");
    }

    @Test
    void activeEntityCannotBeReinstated() {
        Entity entity = activeEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> reinstateHandler.handle(
                        new ReinstateEntity(ENTITY_ID, "REVIEW_COMPLETE", null), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());

        verify(events, never()).publish(any());
    }

    @Test
    void reinstatementRequiresReasonAfterStateCheck() {
        Entity entity = suspendedEntity();

        when(repository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> reinstateHandler.handle(new ReinstateEntity(ENTITY_ID, null, null), federationScope()))
                .isInstanceOf(ProblemException.class);

        verify(repository, never()).saveAndFlush(any());

        verify(events, never()).publish(any());
    }

    private ScopeContext federationScope() {
        return ScopeContext.dev(USER_ID, FEDERATION_ID, null);
    }

    private Entity onboardingEntity() {
        RegisterEntity command =
                new RegisterEntity("M301", "MPCS", "MPCS 301", null, null, "REG-301", "VAT-301", "Colombo", "en", 1);

        return Entity.register(ENTITY_ID, command, "M301", "MPCS", "en", (short) 1);
    }

    private Entity activeEntity() {
        Entity entity = onboardingEntity();

        entity.activate();

        return entity;
    }

    private Entity suspendedEntity() {
        Entity entity = activeEntity();

        entity.suspend();

        return entity;
    }
}
