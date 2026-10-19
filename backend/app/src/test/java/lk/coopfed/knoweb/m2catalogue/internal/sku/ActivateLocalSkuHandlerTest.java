package lk.coopfed.knoweb.m2catalogue.internal.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuActivated;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActivateLocalSkuHandlerTest {

    private static final UUID MPCS_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000003");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private SkuRepository repository;
    private SkuReferenceData references;
    private ConfigRegistry config;
    private AuditFacade audit;
    private EventPublisher events;
    private ActivateLocalSkuHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        repository = mock(SkuRepository.class);
        references = mock(SkuReferenceData.class);
        config = mock(ConfigRegistry.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);

        handler = new ActivateLocalSkuHandler(repository, references, config, audit, events);

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
    void missingBarcodeWhenConfigRequiresBarcodeRefusesActivation() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, MPCS_ENTITY, "Local Rice", "ea");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(config.getBoolean("catalogue.local_sku_requires_barcode", scope, false))
                .thenReturn(true);
        when(references.hasActiveBarcode(skuId)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(new ActivateLocalSku(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.barcode_required"));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void activeBarcodePresentWhenConfigRequiresBarcodeAllowsActivation() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, MPCS_ENTITY, "Local Rice", "ea");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(config.getBoolean("catalogue.local_sku_requires_barcode", scope, false))
                .thenReturn(true);
        when(references.hasActiveBarcode(skuId)).thenReturn(true);

        UUID result = handler.handle(new ActivateLocalSku(skuId), scope);

        assertThat(result).isEqualTo(skuId);
        assertThat(sku.status()).isEqualTo(Sku.LOCAL);
        assertThat(sku.baseUomCode()).isEqualTo("EA");

        verify(repository).saveAndFlush(sku);
        verify(audit).record(eq("SKU_ACTIVATED"), eq(Subject.of("sku", skuId)), any(), any(), eq(scope));

        ArgumentCaptor<SkuActivated> captor = ArgumentCaptor.forClass(SkuActivated.class);
        verify(events).publish(captor.capture());
        SkuActivated published = captor.getValue();
        assertThat(published.skuId()).isEqualTo(skuId);
        assertThat(published.ownerEntityId()).isEqualTo(MPCS_ENTITY);
        assertThat(published.status()).isEqualTo("LOCAL");
    }

    @Test
    void activationSucceedsWithoutBarcodeWhenConfigDisabled() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, MPCS_ENTITY, "Local Rice", "ea");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));
        when(config.getBoolean("catalogue.local_sku_requires_barcode", scope, false))
                .thenReturn(false);
        when(references.hasActiveBarcode(skuId)).thenReturn(false);

        UUID result = handler.handle(new ActivateLocalSku(skuId), scope);

        assertThat(result).isEqualTo(skuId);
        assertThat(sku.status()).isEqualTo(Sku.LOCAL);

        verify(repository).saveAndFlush(sku);
        verify(audit).record(eq("SKU_ACTIVATED"), eq(Subject.of("sku", skuId)), any(), any(), eq(scope));
        verify(events).publish(any(SkuActivated.class));
    }

    @Test
    void activationRefusesWhenSkuNotOwned() {
        UUID skuId = UUID.randomUUID();
        Sku sku = createDraftSku(skuId, OTHER_ENTITY, "Other Rice", "ea");
        when(repository.findById(skuId)).thenReturn(Optional.of(sku));

        assertThatThrownBy(() -> handler.handle(new ActivateLocalSku(skuId), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.owner_mismatch"));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    private static Sku createDraftSku(UUID skuId, UUID owner, String en, String uom) {
        SkuDetails details = new SkuDetails(
                en,
                null,
                null,
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
}
