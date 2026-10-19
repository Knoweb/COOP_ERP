package lk.coopfed.knoweb.m2catalogue.internal.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspected;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateSku;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.ItemProfile;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

class UpdateLocalSkuHandlerTest {

    private static final UUID MPCS_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private SkuRepository repository;
    private SkuRules rules;
    private InventoryLotQuery inventory;
    private AuditFacade audit;
    private EventPublisher events;
    private SkuDuplicateDetector duplicateDetector;
    private JdbcTemplate jdbc;
    private UpdateLocalSkuHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        repository = mock(SkuRepository.class);
        rules = mock(SkuRules.class);
        inventory = mock(InventoryLotQuery.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        duplicateDetector = mock(SkuDuplicateDetector.class);
        jdbc = mock(JdbcTemplate.class);

        handler = new UpdateLocalSkuHandler(repository, rules, inventory, duplicateDetector, jdbc, audit, events);

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
    void updateSkuPublishesSkuUpdatedAndEmitsDuplicateSuspectedWhenMatchFound() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, MPCS_ENTITY, "Local Rice", "EA");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));

        SkuDetails updatedDetails = sampleDetails("Local Rice White");
        when(duplicateDetector.findDuplicates(eq(sku), eq(scope))).thenAnswer(invocation -> {
            ItemProfile candProfile =
                    new ItemProfile(sku.getId(), sku.skuCode(), sku.shortNameEn(), null, null, "EA", null, List.of());
            UUID existingSkuId = UUID.randomUUID();
            ItemProfile existingProfile =
                    new ItemProfile(existingSkuId, "SKU-99999999", "White Rice", null, null, "EA", null, List.of());
            return List.of(new SuspectMatch(
                    candProfile, existingProfile, new BigDecimal("0.8800"), List.of("NAME_SIMILARITY")));
        });

        UUID result = handler.handle(new UpdateSku(skuId, updatedDetails), scope);

        assertThat(result).isEqualTo(skuId);
        verify(repository).saveAndFlush(sku);
        verify(audit).record(eq("SKU_UPDATED"), eq(Subject.of("sku", skuId)), any(), any(), eq(scope));

        verify(jdbc).update(anyString(), any(PreparedStatementSetter.class));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, times(2)).publish(eventCaptor.capture());

        List<DomainEvent> published = eventCaptor.getAllValues();
        assertThat(published.get(0)).isInstanceOf(SkuUpdated.class);
        assertThat(published.get(1)).isInstanceOf(DuplicateSuspected.class);

        DuplicateSuspected suspect = (DuplicateSuspected) published.get(1);
        assertThat(suspect.score()).isEqualByComparingTo("0.8800");
        assertThat(suspect.reasons()).containsExactly("NAME_SIMILARITY");
        assertThat(suspect.ownerEntityId()).isEqualTo(MPCS_ENTITY);
    }

    @Test
    void updateSkuWithoutMatchesPublishesOnlySkuUpdated() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, MPCS_ENTITY, "Local Rice", "EA");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));

        SkuDetails updatedDetails = sampleDetails("Local Rice Brown");
        when(duplicateDetector.findDuplicates(eq(sku), eq(scope))).thenReturn(List.of());

        UUID result = handler.handle(new UpdateSku(skuId, updatedDetails), scope);

        assertThat(result).isEqualTo(skuId);
        verify(repository).saveAndFlush(sku);
        verify(jdbc, never()).update(anyString(), any(PreparedStatementSetter.class));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SkuUpdated.class);
    }

    private static Sku createSku(UUID skuId, UUID owner, String en, String uom) {
        SkuDetails details = sampleDetails(en);
        return Sku.create(skuId, "SKU-12345678", owner, details);
    }

    private static SkuDetails sampleDetails(String en) {
        return new SkuDetails(
                en,
                null,
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
                Map.of());
    }
}
