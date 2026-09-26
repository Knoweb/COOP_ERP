package lk.coopfed.knoweb.m1party.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ActivateEntity;
import lk.coopfed.knoweb.m1party.api.AppointResponsibleOfficer;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import lk.coopfed.knoweb.m1party.api.ReinstateEntity;
import lk.coopfed.knoweb.m1party.api.SuspendEntity;
import lk.coopfed.knoweb.m1party.query.EntityFilter;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.web.generated.AppointResponsibleOfficerRequest;
import lk.coopfed.knoweb.m1party.web.generated.EntitiesApi;
import lk.coopfed.knoweb.m1party.web.generated.EntityPage;
import lk.coopfed.knoweb.m1party.web.generated.EntityReasonRequest;
import lk.coopfed.knoweb.m1party.web.generated.EntityResponse;
import lk.coopfed.knoweb.m1party.web.generated.RegisterEntityRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EntitiesController implements EntitiesApi {

    private final Handles<RegisterEntity, UUID> registerEntity;
    private final Handles<AppointResponsibleOfficer, UUID> appointResponsibleOfficer;
    private final Handles<ActivateEntity, UUID> activateEntity;
    private final Handles<SuspendEntity, UUID> suspendEntity;
    private final Handles<ReinstateEntity, UUID> reinstateEntity;
    private final PartyQueries queries;
    private final CurrentScope currentScope;

    EntitiesController(
            Handles<RegisterEntity, UUID> registerEntity,
            Handles<AppointResponsibleOfficer, UUID> appointResponsibleOfficer,
            Handles<ActivateEntity, UUID> activateEntity,
            Handles<SuspendEntity, UUID> suspendEntity,
            Handles<ReinstateEntity, UUID> reinstateEntity,
            PartyQueries queries,
            CurrentScope currentScope) {

        this.registerEntity = registerEntity;
        this.appointResponsibleOfficer = appointResponsibleOfficer;
        this.activateEntity = activateEntity;
        this.suspendEntity = suspendEntity;
        this.reinstateEntity = reinstateEntity;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<EntityResponse> registerEntity(String idempotencyKey, RegisterEntityRequest request) {

        ScopeContext scope = currentScope.get();

        UUID entityId = registerEntity.handle(
                new RegisterEntity(
                        request.getEntityCode(),
                        request.getEntityType().getValue(),
                        request.getLegalNameEn(),
                        request.getLegalNameSi(),
                        request.getLegalNameTa(),
                        request.getRegistrationNo(),
                        request.getVatRegistrationNo(),
                        request.getDistrict(),
                        request.getDefaultLanguage().getValue(),
                        request.getFinancialYearStartMonth()),
                scope);

        EntityView created = queries.getEntity(entityId, scope).orElseThrow();

        return ResponseEntity.created(URI.create(EntitiesApi.PATH_LIST_ENTITIES + "/" + entityId))
                .body(toResponse(created));
    }

    @Override
    public ResponseEntity<Void> appointResponsibleOfficer(
            UUID entityId, String idempotencyKey, AppointResponsibleOfficerRequest request) {

        appointResponsibleOfficer.handle(
                new AppointResponsibleOfficer(entityId, request.getUserId(), request.getDataGovernanceSignedOn()),
                currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> activateEntity(UUID entityId, String idempotencyKey) {

        activateEntity.handle(new ActivateEntity(entityId), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> suspendEntity(UUID entityId, String idempotencyKey, EntityReasonRequest request) {

        suspendEntity.handle(
                new SuspendEntity(entityId, request.getReasonCode(), request.getReasonText()), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> reinstateEntity(UUID entityId, String idempotencyKey, EntityReasonRequest request) {

        reinstateEntity.handle(
                new ReinstateEntity(entityId, request.getReasonCode(), request.getReasonText()), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<EntityResponse> getEntity(UUID entityId) {

        return queries.getEntity(entityId, currentScope.get())
                .map(EntitiesController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<EntityPage> listEntities(
            String status, String district, String q, UUID cursor, Integer limit) {

        lk.coopfed.knoweb.m1party.query.EntityPage page =
                queries.listEntities(new EntityFilter(status, district, q, cursor, limit), currentScope.get());

        List<EntityResponse> items =
                page.items().stream().map(EntitiesController::toResponse).toList();

        EntityPage response = new EntityPage(items);

        if (page.nextCursor() != null) {
            response.setNextCursor(UUID.fromString(page.nextCursor()));
        }

        return ResponseEntity.ok(response);
    }

    private static EntityResponse toResponse(EntityView view) {

        EntityResponse response = new EntityResponse(view.entityId(), view.legalNameEn());

        response.setEntityCode(view.entityCode());

        response.setLegalNameSi(view.legalNameSi());

        response.setLegalNameTa(view.legalNameTa());

        response.setRegistrationNo(view.registrationNo());

        response.setVatRegistrationNo(view.vatRegistrationNo());

        response.setDistrict(view.district());

        response.setFinancialYearStartMonth(view.financialYearStartMonth());

        response.setResponsibleOfficerUserId(view.responsibleOfficerUserId());

        response.setDataGovernanceSignedOn(view.dataGovernanceSignedOn());

        if (view.entityType() != null) {
            response.setEntityType(EntityResponse.EntityTypeEnum.fromValue(view.entityType()));
        }

        if (view.defaultLanguage() != null) {
            response.setDefaultLanguage(EntityResponse.DefaultLanguageEnum.fromValue(view.defaultLanguage()));
        }

        if (view.status() != null) {
            response.setStatus(EntityResponse.StatusEnum.fromValue(view.status()));
        }

        return response;
    }
}
