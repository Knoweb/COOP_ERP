package lk.coopfed.knoweb.m2catalogue.internal.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspected;
import lk.coopfed.knoweb.m2catalogue.api.SkuCreated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.ItemProfile;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

class CreateLocalSkuHandlerTest {

    private static final UUID MPCS_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private SkuRepository repository;
    private SkuRules rules;
    private SkuCodeGenerator codes;
    private AuditFacade audit;
    private EventPublisher events;
    private SkuDuplicateDetector duplicateDetector;
    private JdbcTemplate jdbc;
    private CreateLocalSkuHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        repository = mock(SkuRepository.class);
        rules = mock(SkuRules.class);
        codes = mock(SkuCodeGenerator.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        duplicateDetector = mock(SkuDuplicateDetector.class);
        jdbc = mock(JdbcTemplate.class);

        handler = new CreateLocalSkuHandler(repository, rules, codes, duplicateDetector, jdbc, audit, events);

        when(codes.next()).thenReturn("SKU-12345678");

        lk.coopfed.knoweb.kernel.api.Scope mpcsScope = new lk.coopfed.knoweb.kernel.api.Scope(MPCS_ENTITY, null);
        scope = new ScopeContext(
                USER_ID,
                null,
                MPCS_ENTITY,
                List.of(mpcsScope),
                mpcsScope,
                PolicyClass.OWN,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    @Test
    void createSkuPublishesSkuCreatedAndEmitsDuplicateSuspectedWhenMatchFound() {
        SkuDetails details = sampleDetails();
        when(duplicateDetector.findDuplicates(any(Sku.class), eq(scope))).thenAnswer(invocation -> {
            Sku created = invocation.getArgument(0);
            ItemProfile candProfile = new ItemProfile(
                    created.getId(), created.skuCode(), created.shortNameEn(), null, null, "EA", "Anchor", List.of());
            UUID existingSkuId = UUID.randomUUID();
            ItemProfile existingProfile = new ItemProfile(
                    existingSkuId, "SKU-99999999", "Anchor Butter", null, null, "EA", "Anchor", List.of());
            return List.of(new SuspectMatch(
                    candProfile, existingProfile, new BigDecimal("0.9200"), List.of("NAME_SIMILARITY", "SAME_UOM")));
        });

        UUID createdId = handler.handle(new CreateSku(details), scope);

        assertThat(createdId).isNotNull();
        verify(repository).saveAndFlush(any(Sku.class));
        verify(audit).record(eq("SKU_CREATED"), eq(Subject.of("sku", createdId)), eq(null), any(), eq(scope));

        verify(jdbc).update(anyString(), any(PreparedStatementSetter.class));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, times(2)).publish(eventCaptor.capture());

        List<DomainEvent> published = eventCaptor.getAllValues();
        assertThat(published.get(0)).isInstanceOf(SkuCreated.class);
        assertThat(published.get(1)).isInstanceOf(DuplicateSuspected.class);

        DuplicateSuspected suspect = (DuplicateSuspected) published.get(1);
        assertThat(suspect.score()).isEqualByComparingTo("0.9200");
        assertThat(suspect.reasons()).containsExactly("NAME_SIMILARITY", "SAME_UOM");
        assertThat(suspect.ownerEntityId()).isEqualTo(MPCS_ENTITY);
    }

    @Test
    void createSkuWithoutDuplicateMatchesEmitsOnlySkuCreated() {
        SkuDetails details = sampleDetails();
        when(duplicateDetector.findDuplicates(any(Sku.class), eq(scope))).thenReturn(List.of());

        UUID createdId = handler.handle(new CreateSku(details), scope);

        assertThat(createdId).isNotNull();
        verify(repository).saveAndFlush(any(Sku.class));
        verify(jdbc, never()).update(anyString(), any(PreparedStatementSetter.class));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SkuCreated.class);
    }

    @Test
    void invalidDetailsRefusesCreationWithoutAuditOrEvents() {
        SkuDetails details = sampleDetails();
        doThrow(new ProblemException("m2.sku.weight_requires_kilograms"))
                .when(rules)
                .validate(details);

        assertThatThrownBy(() -> handler.handle(new CreateSku(details), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.weight_requires_kilograms"));

        verify(repository, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    private static SkuDetails sampleDetails() {
        return new SkuDetails(
                "Anchor Butter 200g",
                "ඇන්කර් බටර් 200g",
                null,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                false,
                null,
                UUID.randomUUID(),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of("brand", "Anchor"));
    }
}
