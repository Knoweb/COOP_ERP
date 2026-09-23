package lk.coopfed.knoweb.m3pricing.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.PriceListQueries;
import lk.coopfed.knoweb.m3pricing.api.PriceListView;
import lk.coopfed.knoweb.m3pricing.api.RegisterPriceList;
import lk.coopfed.knoweb.m3pricing.web.generated.PriceListResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PricingApi;
import lk.coopfed.knoweb.m3pricing.web.generated.RegisterPriceListRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface of the pricing module. It implements {@link PricingApi}, the interface generated
 * from openapi/m3pricing.yaml at build time (see "OpenAPI first" in app/build.gradle.kts), and so
 * do the request and response classes it uses. Paths, parameters, status codes and JSON shapes
 * are therefore written once, in the slice. Change the slice and this class stops compiling
 * until it follows: that is the point.
 *
 * <p>A controller only translates: request to command, view to response. It holds no rule,
 * opens no transaction and knows nothing about tenants. There are no mapping annotations
 * here; they are on the generated interface.
 *
 * <p>What it does not do, because the kernel does it for every controller: check the
 * Idempotency-Key (the parameter arrives because the slice declares the header, and is not
 * used here), resolve the caller's scope (ask {@link CurrentScope}), turn a ProblemException
 * into an error response, or handle CORS.
 */
@RestController
class PricingController implements PricingApi {

    private final Handles<RegisterPriceList, UUID> registerPriceList;
    private final PriceListQueries queries;
    private final CurrentScope currentScope;

    PricingController(
            Handles<RegisterPriceList, UUID> registerPriceList, PriceListQueries queries, CurrentScope currentScope) {
        this.registerPriceList = registerPriceList;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<PriceListResponse> registerPriceList(
            String idempotencyKey, RegisterPriceListRequest request) {
        ScopeContext scope = currentScope.get();

        UUID id = registerPriceList.handle(
                new RegisterPriceList(request.getTextEn(), request.getTextSi(), request.getTextTa()), scope);

        PriceListView created = queries.find(id, scope).orElseThrow();
        return ResponseEntity.created(URI.create(PricingApi.PATH_LIST_PRICE_LISTS + "/" + id))
                .body(toResponse(created));
    }

    @Override
    public ResponseEntity<List<PriceListResponse>> listPriceLists() {
        List<PriceListResponse> priceLists = queries.list(currentScope.get()).stream()
                .map(PricingController::toResponse)
                .toList();
        return ResponseEntity.ok(priceLists);
    }

    @Override
    public ResponseEntity<PriceListResponse> getPriceList(UUID id) {
        return queries.find(id, currentScope.get())
                .map(PricingController::toResponse)
                .map(ResponseEntity::ok)
                // Not 403: whether the price list exists is itself something another entity must not learn.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The small mapper of 17A section 4.4: from the module's own view to the generated response.
     * The owner entity is left out on purpose; the caller knows its own scope.
     */
    private static PriceListResponse toResponse(PriceListView view) {
        return new PriceListResponse(
                        view.id(),
                        view.textEn(),
                        PriceListResponse.StatusEnum.fromValue(view.status()),
                        view.createdAt())
                .textSi(view.textSi())
                .textTa(view.textTa());
    }
}
