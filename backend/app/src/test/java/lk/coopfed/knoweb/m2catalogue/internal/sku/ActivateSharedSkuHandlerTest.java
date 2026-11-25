package lk.coopfed.knoweb.m2catalogue.internal.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuShared;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActivateSharedSkuHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private SkuRepository repository;
    private SkuReferenceData references;
    private FederationCaller federation;
    private AuditFacade audit;
    private EventPublisher events;
    private ActivateSharedSkuHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        repository = mock(SkuRepository.class);
        references = mock(SkuReferenceData.class);
        federation = mock(FederationCaller.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);

        handler = new ActivateSharedSkuHandler(repository, references, federation, audit, events);

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

        assertThatThrownBy(() -> handler.handle(new ActivateSharedSku(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo(FederationCaller.FEDERATION_ONLY));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void nonGovernedTagsRefusesActivation() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, FEDERATION_ENTITY, "Milk", "කිරි", "பால்", "ea", "auto_lowest", "purchased");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(references.hasNonGovernedTags(skuId)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(new ActivateSharedSku(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.governed_tags_only"));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void caseBarcodeWithoutConversionRefusesActivation() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, FEDERATION_ENTITY, "Milk", "කිරි", "பால்", "ea", "auto_lowest", "purchased");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(references.hasNonGovernedTags(skuId)).thenReturn(false);
        when(references.hasCaseBarcodeWithoutConversion(skuId)).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(new ActivateSharedSku(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.case_conversion_required"));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void missingTranslationsRefusesActivation() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, FEDERATION_ENTITY, "Milk", null, null, "ea", "auto_lowest", "purchased");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(references.hasNonGovernedTags(skuId)).thenReturn(false);
        when(references.hasCaseBarcodeWithoutConversion(skuId)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(new ActivateSharedSku(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId())
                        .isEqualTo("m2.sku.shared_translations_required"));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void validDraftActivatesNormalizesCaseAuditsAndPublishes() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, FEDERATION_ENTITY, "Milk", "කිරි", "பால்", "ea", "auto_lowest", "purchased");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(references.hasNonGovernedTags(skuId)).thenReturn(false);
        when(references.hasCaseBarcodeWithoutConversion(skuId)).thenReturn(false);

        UUID result = handler.handle(new ActivateSharedSku(skuId), scope);

        assertThat(result).isEqualTo(skuId);
        assertThat(sku.status()).isEqualTo(Sku.SHARED);
        // Verify case normalization
        assertThat(sku.baseUomCode()).isEqualTo("EA");
        assertThat(sku.multiMrpPolicy()).isEqualTo("AUTO_LOWEST");
        assertThat(sku.originKind()).isEqualTo("PURCHASED");

        verify(repository).saveAndFlush(sku);
        verify(audit).record(eq("SKU_SHARED"), eq(Subject.of("sku", skuId)), any(), any(), eq(scope));

        ArgumentCaptor<SkuShared> captor = ArgumentCaptor.forClass(SkuShared.class);
        verify(events).publish(captor.capture());
        SkuShared published = captor.getValue();
        assertThat(published.skuId()).isEqualTo(skuId);
        assertThat(published.ownerEntityId()).isEqualTo(FEDERATION_ENTITY);
        assertThat(published.status()).isEqualTo("SHARED");
    }

    private static Sku createDraftSku(
            UUID skuId, UUID owner, String en, String si, String ta, String uom, String multiMrp, String origin) {
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
                multiMrp,
                origin,
                Map.of());
        return Sku.create(skuId, "SKU-12345678", owner, details);
    }
}
