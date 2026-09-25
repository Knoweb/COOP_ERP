package lk.coopfed.knoweb.m1party.web;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.RemoveSodPair;
import lk.coopfed.knoweb.m1party.api.SetSodPair;
import lk.coopfed.knoweb.m1party.query.SecurityQueries;
import lk.coopfed.knoweb.m1party.web.generated.SetSodPairRequest;
import lk.coopfed.knoweb.m1party.web.generated.SodPairList;
import lk.coopfed.knoweb.m1party.web.generated.SodPairResponse;
import lk.coopfed.knoweb.m1party.web.generated.SodPairsApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Separation-of-duties pairs of the caller's entity (21A section 5; M1-08). */
@RestController
class SodPairsController implements SodPairsApi {

    private final Handles<SetSodPair, UUID> setSodPair;
    private final Handles<RemoveSodPair, UUID> removeSodPair;
    private final SecurityQueries queries;
    private final CurrentScope currentScope;

    SodPairsController(
            Handles<SetSodPair, UUID> setSodPair,
            Handles<RemoveSodPair, UUID> removeSodPair,
            SecurityQueries queries,
            CurrentScope currentScope) {
        this.setSodPair = setSodPair;
        this.removeSodPair = removeSodPair;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<SodPairResponse> setSodPair(String idempotencyKey, SetSodPairRequest request) {
        ScopeContext scope = currentScope.get();
        UUID sodPairId = setSodPair.handle(
                new SetSodPair(
                        request.getPermissionA(),
                        request.getPermissionB(),
                        request.getMode().getValue()),
                scope);
        return ResponseEntity.ok(queries.listSodPairs(scope).stream()
                .filter(pair -> pair.sodPairId().equals(sodPairId))
                .map(SodPairsController::toResponse)
                .findFirst()
                .orElseThrow());
    }

    @Override
    public ResponseEntity<Void> removeSodPair(UUID sodPairId, String idempotencyKey) {
        removeSodPair.handle(new RemoveSodPair(sodPairId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SodPairList> listSodPairs() {
        List<SodPairResponse> items = queries.listSodPairs(currentScope.get()).stream()
                .map(SodPairsController::toResponse)
                .toList();
        return ResponseEntity.ok(new SodPairList(items));
    }

    private static SodPairResponse toResponse(SecurityQueries.SodPairView pair) {
        return new SodPairResponse(
                        pair.sodPairId(),
                        pair.permissionA(),
                        pair.permissionB(),
                        SodPairResponse.ModeEnum.fromValue(pair.mode()))
                .ownerEntityId(pair.ownerEntityId());
    }
}
