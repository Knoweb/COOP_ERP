package lk.coopfed.knoweb.m1party.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ActivateLocation;
import lk.coopfed.knoweb.m1party.api.ConfirmLocationConnectivity;
import lk.coopfed.knoweb.m1party.api.MarkLocationDormant;
import lk.coopfed.knoweb.m1party.api.ReactivateLocation;
import lk.coopfed.knoweb.m1party.api.RegisterLocation;
import lk.coopfed.knoweb.m1party.api.RegisterTillPosition;
import lk.coopfed.knoweb.m1party.api.RetireTillPosition;
import lk.coopfed.knoweb.m1party.api.SetPrimaryTill;
import lk.coopfed.knoweb.m1party.api.StartLocationOnboarding;
import lk.coopfed.knoweb.m1party.api.TradingDay;
import lk.coopfed.knoweb.m1party.api.UpdateLocation;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.TillPositionView;
import lk.coopfed.knoweb.m1party.web.generated.LocationResponse;
import lk.coopfed.knoweb.m1party.web.generated.LocationsApi;
import lk.coopfed.knoweb.m1party.web.generated.ReasonRequest;
import lk.coopfed.knoweb.m1party.web.generated.RegisterLocationRequest;
import lk.coopfed.knoweb.m1party.web.generated.RegisterTillPositionRequest;
import lk.coopfed.knoweb.m1party.web.generated.SetPrimaryTillRequest;
import lk.coopfed.knoweb.m1party.web.generated.TillPositionResponse;
import lk.coopfed.knoweb.m1party.web.generated.UpdateLocationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The location and till position operations of openapi/m1party.yaml (tag Locations). */
@RestController
class LocationsController implements LocationsApi {

    private final Handles<RegisterLocation, UUID> registerLocation;
    private final Handles<UpdateLocation, UUID> updateLocation;
    private final Handles<ConfirmLocationConnectivity, UUID> confirmConnectivity;
    private final Handles<StartLocationOnboarding, UUID> startOnboarding;
    private final Handles<ActivateLocation, UUID> activateLocation;
    private final Handles<MarkLocationDormant, UUID> markDormant;
    private final Handles<ReactivateLocation, UUID> reactivateLocation;
    private final Handles<SetPrimaryTill, UUID> setPrimaryTill;
    private final Handles<RegisterTillPosition, UUID> registerTillPosition;
    private final Handles<RetireTillPosition, UUID> retireTillPosition;
    private final PartyQueries queries;
    private final CurrentScope currentScope;

    @SuppressWarnings("java:S107") // one handler per operation: the controller is the list of them
    LocationsController(
            Handles<RegisterLocation, UUID> registerLocation,
            Handles<UpdateLocation, UUID> updateLocation,
            Handles<ConfirmLocationConnectivity, UUID> confirmConnectivity,
            Handles<StartLocationOnboarding, UUID> startOnboarding,
            Handles<ActivateLocation, UUID> activateLocation,
            Handles<MarkLocationDormant, UUID> markDormant,
            Handles<ReactivateLocation, UUID> reactivateLocation,
            Handles<SetPrimaryTill, UUID> setPrimaryTill,
            Handles<RegisterTillPosition, UUID> registerTillPosition,
            Handles<RetireTillPosition, UUID> retireTillPosition,
            PartyQueries queries,
            CurrentScope currentScope) {
        this.registerLocation = registerLocation;
        this.updateLocation = updateLocation;
        this.confirmConnectivity = confirmConnectivity;
        this.startOnboarding = startOnboarding;
        this.activateLocation = activateLocation;
        this.markDormant = markDormant;
        this.reactivateLocation = reactivateLocation;
        this.setPrimaryTill = setPrimaryTill;
        this.registerTillPosition = registerTillPosition;
        this.retireTillPosition = retireTillPosition;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<LocationResponse> registerLocation(String idempotencyKey, RegisterLocationRequest request) {
        ScopeContext scope = currentScope.get();
        UUID locationId = registerLocation.handle(
                new RegisterLocation(
                        request.getLocationCode(),
                        request.getLocationType().getValue(),
                        request.getNameEn(),
                        request.getNameSi(),
                        request.getNameTa(),
                        request.getAddress(),
                        request.getDistrict(),
                        request.getGeoLat(),
                        request.getGeoLng(),
                        request.getLanguage() == null
                                ? null
                                : request.getLanguage().getValue(),
                        tradingHours(request.getTradingHours()),
                        request.getSizeBand() == null
                                ? null
                                : request.getSizeBand().getValue()),
                scope);

        LocationView created = queries.getLocation(locationId, scope).orElseThrow();
        return ResponseEntity.created(URI.create(LocationsApi.PATH_LIST_LOCATIONS + "/" + locationId))
                .body(toResponse(created));
    }

    @Override
    public ResponseEntity<Void> updateLocation(UUID locationId, String idempotencyKey, UpdateLocationRequest request) {
        updateLocation.handle(
                new UpdateLocation(
                        locationId,
                        request.getNameEn(),
                        request.getNameSi(),
                        request.getNameTa(),
                        request.getAddress(),
                        request.getDistrict(),
                        request.getGeoLat(),
                        request.getGeoLng(),
                        request.getLanguage() == null
                                ? null
                                : request.getLanguage().getValue(),
                        tradingHours(request.getTradingHours()),
                        request.getSizeBand() == null
                                ? null
                                : request.getSizeBand().getValue()),
                currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> confirmLocationConnectivity(UUID locationId, String idempotencyKey) {
        confirmConnectivity.handle(new ConfirmLocationConnectivity(locationId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> startLocationOnboarding(UUID locationId, String idempotencyKey) {
        startOnboarding.handle(new StartLocationOnboarding(locationId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> activateLocation(UUID locationId, String idempotencyKey) {
        activateLocation.handle(new ActivateLocation(locationId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> markLocationDormant(UUID locationId, String idempotencyKey, ReasonRequest request) {
        markDormant.handle(
                new MarkLocationDormant(locationId, request.getReasonCode(), request.getReasonText()),
                currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> reactivateLocation(UUID locationId, String idempotencyKey) {
        reactivateLocation.handle(new ReactivateLocation(locationId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> setPrimaryTill(UUID locationId, String idempotencyKey, SetPrimaryTillRequest request) {
        setPrimaryTill.handle(
                new SetPrimaryTill(
                        locationId, request.getTillPositionId(), request.getReasonCode(), request.getReasonText()),
                currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<TillPositionResponse> registerTillPosition(
            UUID locationId, String idempotencyKey, RegisterTillPositionRequest request) {
        ScopeContext scope = currentScope.get();
        UUID positionId =
                registerTillPosition.handle(new RegisterTillPosition(locationId, request.getPositionNo()), scope);
        TillPositionView created = queries.listTillPositions(locationId, scope).stream()
                .filter(position -> position.tillPositionId().equals(positionId))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @Override
    public ResponseEntity<Void> retireTillPosition(UUID tillPositionId, String idempotencyKey) {
        retireTillPosition.handle(new RetireTillPosition(tillPositionId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<LocationResponse> getLocation(UUID locationId) {
        return queries.getLocation(locationId, currentScope.get())
                .map(LocationsController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<lk.coopfed.knoweb.m1party.web.generated.LocationPage> listLocations(
            String status, String locationType, UUID cursor, Integer limit) {
        lk.coopfed.knoweb.m1party.query.LocationPage page =
                queries.listLocations(new LocationFilter(status, locationType, cursor, limit), currentScope.get());
        lk.coopfed.knoweb.m1party.web.generated.LocationPage response =
                new lk.coopfed.knoweb.m1party.web.generated.LocationPage(page.items().stream()
                        .map(LocationsController::toResponse)
                        .toList());
        if (page.nextCursor() != null) {
            response.setNextCursor(UUID.fromString(page.nextCursor()));
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<TillPositionResponse>> listTillPositions(UUID locationId) {
        return ResponseEntity.ok(queries.listTillPositions(locationId, currentScope.get()).stream()
                .map(LocationsController::toResponse)
                .toList());
    }

    private static List<TradingDay> tradingHours(List<lk.coopfed.knoweb.m1party.web.generated.TradingDay> days) {
        if (days == null) {
            return null;
        }
        return days.stream()
                .map(day -> new TradingDay(day.getDay().getValue(), day.getOpens(), day.getCloses()))
                .toList();
    }

    private static LocationResponse toResponse(LocationView view) {
        LocationResponse response = new LocationResponse(
                view.locationId(),
                view.ownerEntityId(),
                view.locationCode(),
                LocationResponse.LocationTypeEnum.fromValue(view.locationType()),
                view.nameEn(),
                view.connectivitySpecMet(),
                LocationResponse.StatusEnum.fromValue(view.status()));
        response.setNameSi(view.nameSi());
        response.setNameTa(view.nameTa());
        response.setAddress(view.address());
        response.setDistrict(view.district());
        response.setGeoLat(view.geoLat());
        response.setGeoLng(view.geoLng());
        if (view.language() != null) {
            response.setLanguage(LocationResponse.LanguageEnum.fromValue(view.language()));
        }
        if (view.sizeBand() != null) {
            response.setSizeBand(LocationResponse.SizeBandEnum.fromValue(view.sizeBand()));
        }
        response.setTradingHours(view.tradingHours().stream()
                .map(day -> new lk.coopfed.knoweb.m1party.web.generated.TradingDay(
                        lk.coopfed.knoweb.m1party.web.generated.TradingDay.DayEnum.fromValue(day.day()),
                        day.opens(),
                        day.closes()))
                .toList());
        response.setPrimaryTillPositionId(view.primaryTillPositionId());
        return response;
    }

    private static TillPositionResponse toResponse(TillPositionView view) {
        return new TillPositionResponse(
                view.tillPositionId(),
                view.locationId(),
                view.positionNo(),
                TillPositionResponse.StatusEnum.fromValue(view.status()),
                view.primary());
    }
}
