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
import lk.coopfed.knoweb.m2catalogue.api.MergeIntoShared;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuMerged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class MergeIntoSharedHandlerTest {

    private static final UUID FEDERATION_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID MPCS_ENTITY = UUID.fromString("0190e600-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private SkuRepository repository;
    private FederationCaller federation;
    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private MergeIntoSharedHandler handler;

    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        repository = mock(SkuRepository.class);
        federation = mock(FederationCaller.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);

        handler = new MergeIntoSharedHandler(repository, federation, jdbc, audit, events, clock);

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
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        doThrow(new ProblemException(FederationCaller.FEDERATION_ONLY))
                .when(federation)
                .require(scope);

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(source, target), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo(FederationCaller.FEDERATION_ONLY));

        verify(audit, never()).record(any(), any(), any(), any(), any());
        verify(events, never()).publish(any());
    }

    @Test
    void nullCommandOrIdsRefuses() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> handler.handle(null, scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.invalid"));

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(null, id), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.invalid"));

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(id, null), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("request.invalid"));
    }

    @Test
    void mergeWithSelfRefuses() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(id, id), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(
                        e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.cannot_merge_with_self"));
    }

    @Test
    void sourceNotFoundRefuses() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(repository.findById(source)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(source, target), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.not_found"));
    }

    @Test
    void sourceNotLocalRefuses() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Sku sourceSku = createSku(source, MPCS_ENTITY, "Tea Local", "EA", false, false, false);
        // DRAFT, not LOCAL
        when(repository.findById(source)).thenReturn(Optional.of(sourceSku));

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(source, target), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.not_local"));
    }

    @Test
    void targetNotFoundRefuses() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Sku sourceSku = createSku(source, MPCS_ENTITY, "Tea Local", "EA", false, false, false);
        sourceSku.activateLocal();
        when(repository.findById(source)).thenReturn(Optional.of(sourceSku));
        when(repository.findById(target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(source, target), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.not_found"));
    }

    @Test
    void targetNotSharedRefuses() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Sku sourceSku = createSku(source, MPCS_ENTITY, "Tea Local", "EA", false, false, false);
        sourceSku.activateLocal();
        Sku targetSku = createSku(target, FEDERATION_ENTITY, "Tea Shared", "EA", false, false, false);
        // Target is DRAFT, not SHARED
        when(repository.findById(source)).thenReturn(Optional.of(sourceSku));
        when(repository.findById(target)).thenReturn(Optional.of(targetSku));

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(source, target), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.target_not_shared"));
    }

    @Test
    void baseUomMismatchRefuses() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Sku sourceSku = createSku(source, MPCS_ENTITY, "Tea Local", "KG", false, false, false);
        sourceSku.activateLocal();
        Sku targetSku = createSku(target, FEDERATION_ENTITY, "Tea Shared", "EA", false, false, false);
        targetSku.activateShared();
        when(repository.findById(source)).thenReturn(Optional.of(sourceSku));
        when(repository.findById(target)).thenReturn(Optional.of(targetSku));

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(source, target), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.base_uom_mismatch"));
    }

    @Test
    void unitConflictRefuses() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Sku sourceSku = createSku(source, MPCS_ENTITY, "Tea Local", "EA", false, true, false);
        sourceSku.activateLocal();
        Sku targetSku = createSku(target, FEDERATION_ENTITY, "Tea Shared", "EA", false, false, false);
        targetSku.activateShared();
        when(repository.findById(source)).thenReturn(Optional.of(sourceSku));
        when(repository.findById(target)).thenReturn(Optional.of(targetSku));

        assertThatThrownBy(() -> handler.handle(new MergeIntoShared(source, target), scope))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m2.sku.unit_conflict"));
    }

    @Test
    void successfulMergeInsertsAliasMigratesBarcodesRetiresCollisionsAuditsAndPublishes() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Sku sourceSku = createSku(source, MPCS_ENTITY, "Tea Local", "EA", false, false, false);
        sourceSku.activateLocal();
        Sku targetSku = createSku(target, FEDERATION_ENTITY, "Tea Shared", "EA", false, false, false);
        targetSku.activateShared();
        when(repository.findById(source)).thenReturn(Optional.of(sourceSku));
        when(repository.findById(target)).thenReturn(Optional.of(targetSku));

        // Source has 2 barcodes: one collides with target, one does not
        when(jdbc.queryForList(anyString(), eq(source)))
                .thenReturn(List.of(
                        Map.of("barcode", "4791000000001", "symbology", "EAN13", "owner_entity_id", MPCS_ENTITY),
                        Map.of("barcode", "4791000000002", "symbology", "EAN13", "owner_entity_id", MPCS_ENTITY)));

        // 4791000000001 collides
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("4791000000001"), eq("EAN13"), eq(target)))
                .thenReturn(1);
        // 4791000000002 does not collide
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("4791000000002"), eq("EAN13"), eq(target)))
                .thenReturn(0);

        UUID result = handler.handle(new MergeIntoShared(source, target), scope);

        assertThat(result).isEqualTo(target);
        assertThat(sourceSku.status()).isEqualTo(Sku.INACTIVE);
        assertThat(sourceSku.priorStatus()).isEqualTo("LOCAL");

        verify(repository).saveAndFlush(sourceSku);
        // Alias inserted
        verify(jdbc).update(anyString(), eq(source), eq(target), any(), eq(MPCS_ENTITY));
        // Colliding barcode retired
        verify(jdbc).update(anyString(), eq("4791000000001"), eq("EAN13"), eq(MPCS_ENTITY));
        // Non-colliding barcode moved
        verify(jdbc).update(anyString(), eq(target), eq("4791000000002"), eq("EAN13"), eq(MPCS_ENTITY));
        // Open duplicate suspects closed
        verify(jdbc).update(anyString(), eq(USER_ID), any(), eq(source), eq(target), eq(target), eq(source));

        verify(audit).record(eq("SKU_MERGED"), eq(Subject.of("sku", source)), any(), any(), eq(scope));

        ArgumentCaptor<SkuMerged> captor = ArgumentCaptor.forClass(SkuMerged.class);
        verify(events).publish(captor.capture());
        SkuMerged event = captor.getValue();
        assertThat(event.sourceSkuId()).isEqualTo(source);
        assertThat(event.canonicalSkuId()).isEqualTo(target);
        assertThat(event.originEntityId()).isEqualTo(MPCS_ENTITY);
        assertThat(event.status()).isEqualTo("INACTIVE");
    }

    private static Sku createSku(
            UUID skuId, UUID owner, String en, String uom, boolean weight, boolean batch, boolean expiry) {
        SkuDetails details = new SkuDetails(
                en,
                "සිංහල",
                "தமிழ்",
                null,
                null,
                null,
                uom,
                weight,
                batch,
                expiry,
                false,
                null,
                UUID.randomUUID(),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of());
        return Sku.create(skuId, "SKU-" + skuId.toString().substring(0, 8), owner, details);
    }
}
