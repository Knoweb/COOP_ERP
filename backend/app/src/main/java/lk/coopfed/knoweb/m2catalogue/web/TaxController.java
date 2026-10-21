package lk.coopfed.knoweb.m2catalogue.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.DefineTaxCategory;
import lk.coopfed.knoweb.m2catalogue.api.PublishTaxRate;
import lk.coopfed.knoweb.m2catalogue.internal.tax.DefineTaxCategoryHandler;
import lk.coopfed.knoweb.m2catalogue.internal.tax.PublishTaxRateHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.TaxCategoryView;
import lk.coopfed.knoweb.m2catalogue.query.TaxRateHistoryView;
import lk.coopfed.knoweb.m2catalogue.web.generated.DefineTaxCategoryRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.PublishTaxRateRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.TaxApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.TaxCategoryResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.TaxRateResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * TaxController implements generated TaxApi (22A section 5; doc 22 section 3.6).
 * Manages tax categories and publication of effective-dated tax rates.
 */
@RestController
class TaxController implements TaxApi {

    private final DefineTaxCategoryHandler defineCategory;
    private final PublishTaxRateHandler publishRate;
    private final CatalogueQueries queries;
    private final CurrentScope currentScope;

    TaxController(
            DefineTaxCategoryHandler defineCategory,
            PublishTaxRateHandler publishRate,
            CatalogueQueries queries,
            CurrentScope currentScope) {
        this.defineCategory = defineCategory;
        this.publishRate = publishRate;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<TaxCategoryResponse>> listTaxCategories() {
        ScopeContext scope = currentScope.get();
        List<TaxCategoryResponse> categories = queries.taxCategories(scope).stream()
                .map(TaxController::toCategoryResponse)
                .toList();
        return ResponseEntity.ok(categories);
    }

    @Override
    public ResponseEntity<TaxCategoryResponse> defineTaxCategory(
            String idempotencyKey, DefineTaxCategoryRequest request) {
        ScopeContext scope = currentScope.get();
        UUID categoryId = defineCategory.handle(
                new DefineTaxCategory(request.getCode(), request.getNameEn(), request.getNameSi(), request.getNameTa()),
                scope);

        TaxCategoryResponse response = queries.taxCategories(scope).stream()
                .filter(cat -> cat.taxCategoryId().equals(categoryId))
                .findFirst()
                .map(TaxController::toCategoryResponse)
                .orElseGet(() -> new TaxCategoryResponse(categoryId, request.getCode(), request.getNameEn())
                        .nameSi(request.getNameSi())
                        .nameTa(request.getNameTa()));

        return ResponseEntity.created(URI.create("/v1/catalogue/tax/categories/" + categoryId))
                .body(response);
    }

    @Override
    public ResponseEntity<List<TaxRateResponse>> listTaxRates(UUID categoryId, LocalDate date) {
        ScopeContext scope = currentScope.get();
        List<TaxRateResponse> rates = queries.taxRates(categoryId, date, scope).stream()
                .map(TaxController::toRateResponse)
                .toList();
        return ResponseEntity.ok(rates);
    }

    @Override
    public ResponseEntity<TaxRateResponse> publishTaxRate(String idempotencyKey, PublishTaxRateRequest request) {
        ScopeContext scope = currentScope.get();
        BigDecimal ratePercent = BigDecimal.valueOf(request.getRatePercent().doubleValue());
        publishRate.handle(new PublishTaxRate(request.getTaxCategoryId(), ratePercent, request.getApplyFrom()), scope);

        TaxRateResponse response = queries.taxRates(request.getTaxCategoryId(), request.getApplyFrom(), scope).stream()
                .map(TaxController::toRateResponse)
                .findFirst()
                .orElseGet(() -> new TaxRateResponse(request.getTaxCategoryId(), ratePercent, request.getApplyFrom()));

        return ResponseEntity.created(URI.create("/v1/catalogue/tax/rates")).body(response);
    }

    private static TaxCategoryResponse toCategoryResponse(TaxCategoryView view) {
        TaxCategoryResponse response = new TaxCategoryResponse(view.taxCategoryId(), view.code(), view.nameEn());
        response.setNameSi(view.nameSi());
        response.setNameTa(view.nameTa());
        return response;
    }

    private static TaxRateResponse toRateResponse(TaxRateHistoryView view) {
        TaxRateResponse response = new TaxRateResponse(view.taxCategoryId(), view.ratePercent(), view.effectiveFrom());
        response.setTaxCategoryCode(view.taxCategoryCode());
        response.setEffectiveTo(view.effectiveTo());
        return response;
    }
}
