package lk.coopfed.knoweb.m1party.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.m1party.api.BulkRegisterEntities;
import lk.coopfed.knoweb.m1party.api.BulkRegisterNetwork;
import lk.coopfed.knoweb.m1party.api.ValidationReport;
import lk.coopfed.knoweb.m1party.web.generated.BulkApi;
import lk.coopfed.knoweb.m1party.web.generated.BulkDeviceItem;
import lk.coopfed.knoweb.m1party.web.generated.BulkNetworkProblem;
import lk.coopfed.knoweb.m1party.web.generated.BulkNetworkReport;
import lk.coopfed.knoweb.m1party.web.generated.BulkRegisterNetworkRequest;
import lk.coopfed.knoweb.m1party.web.generated.BulkRowProblem;
import lk.coopfed.knoweb.m1party.web.generated.BulkRowResult;
import lk.coopfed.knoweb.m1party.web.generated.BulkShopItem;
import lk.coopfed.knoweb.m1party.web.generated.BulkUserItem;
import lk.coopfed.knoweb.m1party.web.generated.BulkValidationReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /v1/party/bulk-register} (M1-11) and {@code POST /v1/party/locations/bulk} (CAT-08):
 * the bulk network and bulk entity registration controllers.
 */
@RestController
class BulkController implements BulkApi {

    private final Handles<BulkRegisterEntities, ValidationReport> bulkRegister;
    private final Handles<BulkRegisterNetwork, lk.coopfed.knoweb.m1party.api.BulkNetworkReport> bulkRegisterNetwork;
    private final CurrentScope currentScope;

    BulkController(
            Handles<BulkRegisterEntities, ValidationReport> bulkRegister,
            Handles<BulkRegisterNetwork, lk.coopfed.knoweb.m1party.api.BulkNetworkReport> bulkRegisterNetwork,
            CurrentScope currentScope) {
        this.bulkRegister = bulkRegister;
        this.bulkRegisterNetwork = bulkRegisterNetwork;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<BulkValidationReport> bulkRegister(String idempotencyKey, MultipartFile file) {
        String csv;
        try {
            csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ValidationReport report = bulkRegister.handle(new BulkRegisterEntities(csv), currentScope.get());

        return ResponseEntity.ok(toResponse(report));
    }

    @Override
    public ResponseEntity<BulkNetworkReport> bulkRegisterNetwork(
            String idempotencyKey, BulkRegisterNetworkRequest request) {
        BulkRegisterNetwork command = toNetworkCommand(request);
        lk.coopfed.knoweb.m1party.api.BulkNetworkReport report =
                bulkRegisterNetwork.handle(command, currentScope.get());
        return ResponseEntity.ok(toNetworkResponse(report));
    }

    private static BulkRegisterNetwork toNetworkCommand(BulkRegisterNetworkRequest request) {
        List<BulkRegisterNetwork.Shop> shops = request.getShops() == null
                ? List.of()
                : request.getShops().stream().map(BulkController::toShopCommand).toList();
        List<BulkRegisterNetwork.User> users = request.getUsers() == null
                ? List.of()
                : request.getUsers().stream().map(BulkController::toUserCommand).toList();
        List<BulkRegisterNetwork.Device> devices = request.getDevices() == null
                ? List.of()
                : request.getDevices().stream()
                        .map(BulkController::toDeviceCommand)
                        .toList();
        return new BulkRegisterNetwork(shops, users, devices);
    }

    private static BulkRegisterNetwork.Shop toShopCommand(BulkShopItem item) {
        return new BulkRegisterNetwork.Shop(
                item.getLocationCode(),
                item.getNameEn(),
                item.getNameSi(),
                item.getNameTa(),
                item.getAddress(),
                item.getDistrict(),
                item.getGeoLat() != null ? item.getGeoLat().doubleValue() : null,
                item.getGeoLng() != null ? item.getGeoLng().doubleValue() : null,
                item.getLanguage() != null ? item.getLanguage().getValue() : null,
                item.getSizeBand() != null ? item.getSizeBand().getValue() : null,
                item.getTillPositions());
    }

    private static BulkRegisterNetwork.User toUserCommand(BulkUserItem item) {
        return new BulkRegisterNetwork.User(
                item.getUsername(),
                item.getDisplayName(),
                item.getLanguage() != null ? item.getLanguage().getValue() : null,
                item.getUserKind() != null ? item.getUserKind().getValue() : null,
                item.getPin(),
                item.getLocationCode());
    }

    private static BulkRegisterNetwork.Device toDeviceCommand(BulkDeviceItem item) {
        return new BulkRegisterNetwork.Device(
                item.getHardwareSerial(),
                item.getDeviceKind() != null ? item.getDeviceKind().getValue() : null,
                item.getLocationCode(),
                item.getPositionNo(),
                item.getAppVersion());
    }

    private static BulkNetworkReport toNetworkResponse(lk.coopfed.knoweb.m1party.api.BulkNetworkReport report) {
        return new BulkNetworkReport(
                BulkNetworkReport.StatusEnum.fromValue(report.status()),
                report.shopsRegistered(),
                report.positionsRegistered(),
                report.usersRegistered(),
                report.devicesRegistered(),
                report.problems().stream().map(BulkController::toNetworkProblem).toList());
    }

    private static BulkNetworkProblem toNetworkProblem(
            lk.coopfed.knoweb.m1party.api.BulkNetworkReport.Problem problem) {
        BulkNetworkProblem p = new BulkNetworkProblem(
                BulkNetworkProblem.CategoryEnum.fromValue(problem.category()), problem.field(), problem.code());
        p.setIdentifier(problem.identifier());
        return p;
    }

    private static BulkValidationReport toResponse(ValidationReport report) {
        return new BulkValidationReport(
                BulkValidationReport.StatusEnum.fromValue(report.status()),
                report.rows(),
                report.registered(),
                report.rejected(),
                report.results().stream().map(BulkController::toResponse).toList());
    }

    private static BulkRowResult toResponse(ValidationReport.RowResult row) {
        BulkRowResult result = new BulkRowResult(
                row.line(),
                row.entityCode(),
                BulkRowResult.StatusEnum.fromValue(row.status()),
                row.problems().stream()
                        .map(problem -> new BulkRowProblem(problem.field(), problem.code()))
                        .toList());
        result.setEntityId(row.entityId());
        return result;
    }
}
