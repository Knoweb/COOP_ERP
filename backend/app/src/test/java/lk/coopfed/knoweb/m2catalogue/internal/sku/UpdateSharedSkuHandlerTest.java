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
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
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

class UpdateSharedSkuHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private SkuRepository repository;
    private SkuRules rules;
    private FederationCaller federation;
    private InventoryLotQuery inventory;
    private AuditFacade audit;
    private EventPublisher events;
    private SkuDuplicateDetector duplicateDetector;
    private JdbcTemplate jdbc;
    private UpdateSharedSkuHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        repository = mock(SkuRepository.class);
        rules = mock(SkuRules.class);
        federation = mock(FederationCaller.class);
        inventory = mock(InventoryLotQuery.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        duplicateDetector = mock(SkuDuplicateDetector.class);
        jdbc = mock(JdbcTemplate.class);

        handler = new UpdateSharedSkuHandler(
                repository, rules, federation, inventory, duplicateDetector, jdbc, audit, events);

        lk.coopfed.knoweb.kernel.api.Scope fedScope = new lk.coopfed.knoweb.kernel.api.Scope(FEDERATION_ENTITY, null);
        scope = new ScopeContext(
                USER_ID,
                null,
                FEDERATION_ENTITY,
                List.of(fedScope),
                fedScope,
                PolicyClass.OWN,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    @Test
    void nonFederationCallerIsRefused() {
        UUID skuId = UUID.randomUUID();
        doThrow(new ProblemException(FederationCaller.FEDERATION_ONLY))
                .when(federation)
                .require(scope);

        assertThatThrownBy(() -> handler.handle(new UpdateSku(skuId, sampleDetails("Rice", "සහල්", "அரிசி")), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo(FederationCaller.FEDERATION_ONLY));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void updateSharedSkuPublishesSkuUpdatedAndEmitsDuplicateSuspectedWhenMatchFound() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, FEDERATION_ENTITY, "Sugar", "සීනි", "சர்க்கரை", "EA");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));

        SkuDetails updatedDetails = sampleDetails("Sugar White", "සුදු සීනි", "வெள்ளை சர்க்கரை");
        when(duplicateDetector.findDuplicates(eq(sku), eq(scope))).thenAnswer(invocation -> {
            ItemProfile candProfile = new ItemProfile(
                    sku.getId(),
                    sku.skuCode(),
                    sku.shortNameEn(),
                    sku.shortNameSi(),
                    sku.shortNameTa(),
                    "EA",
                    null,
                    List.of());
            UUID existingSkuId = UUID.randomUUID();
            ItemProfile existingProfile =
                    new ItemProfile(existingSkuId, "SKU-88888888", "White Sugar", null, null, "EA", null, List.of());
            return List.of(new SuspectMatch(
                    candProfile, existingProfile, new BigDecimal("0.9100"), List.of("NAME_SIMILARITY", "SAME_UOM")));
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
        assertThat(suspect.score()).isEqualByComparingTo("0.9100");
        assertThat(suspect.reasons()).containsExactly("NAME_SIMILARITY", "SAME_UOM");
        assertThat(suspect.ownerEntityId()).isEqualTo(FEDERATION_ENTITY);
    }

    private static Sku createSku(UUID skuId, UUID owner, String en, String si, String ta, String uom) {
        SkuDetails details = new SkuDetails(
                en,
                si,
                ta,
                null,
                null,
                null,
                uom,
                false,
                false,
                false,
                false,
                null,
                UUID.randomUUID(),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of());
        return Sku.create(skuId, "SKU-12345678", owner, details);
    }

    private static SkuDetails sampleDetails(String en, String si, String ta) {
        return new SkuDetails(
                en,
                si,
                ta,
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
