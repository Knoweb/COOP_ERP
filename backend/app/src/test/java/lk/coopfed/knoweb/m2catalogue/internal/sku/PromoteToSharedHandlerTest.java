package lk.coopfed.knoweb.m2catalogue.internal.sku;

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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.PromoteToShared;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuPromoted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PromoteToSharedHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID MPCS_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private SkuRepository repository;
    private SkuReferenceData references;
    private FederationCaller federation;
    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private PromoteToSharedHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        repository = mock(SkuRepository.class);
        references = mock(SkuReferenceData.class);
        federation = mock(FederationCaller.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);

        handler = new PromoteToSharedHandler(repository, references, federation, jdbc, audit, events, clock);

        Scope fedScope = new Scope(FEDERATION_ENTITY, null);
        scope = new ScopeContext(
                USER_ID,
                null,
                FEDERATION_ENTITY,
                List.of(fedScope),
                fedScope,
                PolicyClass.OWN,
                Set.of(),
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

        assertThatThrownBy(() -> handler.handle(new PromoteToShared(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo(FederationCaller.FEDERATION_ONLY));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void nullCommandOrSkuIdRefuses() {
        assertThatThrownBy(() -> handler.handle(null, scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.invalid"));

        assertThatThrownBy(() -> handler.handle(new PromoteToShared(null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.invalid"));
    }

    @Test
    void skuNotFoundRefuses() {
        UUID skuId = UUID.randomUUID();
        when(repository.findById(skuId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new PromoteToShared(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.not_found"));
    }

    @Test
    void nonLocalSkuRefuses() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, MPCS_ENTITY, "Tea", "තේ", "தே");
        // Status is DRAFT, not LOCAL
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));

        assertThatThrownBy(() -> handler.handle(new PromoteToShared(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.not_local"));
    }

    @Test
    void missingTranslationsRefuses() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, MPCS_ENTITY, "Tea", null, "தே");
        sku.activateLocal();
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));

        assertThatThrownBy(() -> handler.handle(new PromoteToShared(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId())
                        .isEqualTo("m2.sku.shared_translations_required"));
    }

    @Test
    void nonGovernedTagsRefuses() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, MPCS_ENTITY, "Tea", "තේ", "தே");
        sku.activateLocal();
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(references.hasNonGovernedTags(skuId)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(new PromoteToShared(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.governed_tags_only"));
    }

    @Test
    void caseBarcodeWithoutConversionRefuses() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, MPCS_ENTITY, "Tea", "තේ", "தே");
        sku.activateLocal();
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(references.hasNonGovernedTags(skuId)).thenReturn(false);
        when(references.hasCaseBarcodeWithoutConversion(skuId)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(new PromoteToShared(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.case_conversion_required"));
    }

    @Test
    void successfulPromotionUpdatesSkuInsertsAliasAuditsAndPublishes() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createSku(skuId, MPCS_ENTITY, "Tea", "තේ", "தே");
        sku.activateLocal();
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(references.hasNonGovernedTags(skuId)).thenReturn(false);
        when(references.hasCaseBarcodeWithoutConversion(skuId)).thenReturn(false);

        UUID result = handler.handle(new PromoteToShared(skuId), scope);

        assertThat(result).isEqualTo(skuId);
        assertThat(sku.status()).isEqualTo(Sku.SHARED);
        assertThat(sku.ownerEntityId()).isEqualTo(FEDERATION_ENTITY);

        verify(repository).saveAndFlush(sku);
        verify(jdbc).update(anyString(), eq(skuId), eq(skuId), any(), eq(MPCS_ENTITY));
        verify(audit).record(eq("SKU_PROMOTED"), eq(Subject.of("sku", skuId)), any(), any(), eq(scope));

        ArgumentCaptor<SkuPromoted> captor = ArgumentCaptor.forClass(SkuPromoted.class);
        verify(events).publish(captor.capture());
        SkuPromoted event = captor.getValue();
        assertThat(event.skuId()).isEqualTo(skuId);
        assertThat(event.originEntityId()).isEqualTo(MPCS_ENTITY);
        assertThat(event.status()).isEqualTo("SHARED");
    }

    private static Sku createSku(UUID skuId, UUID owner, String en, String si, String ta) {
        SkuDetails details = new SkuDetails(
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
        return Sku.create(skuId, "SKU-PROMO1", owner, details);
    }
}
