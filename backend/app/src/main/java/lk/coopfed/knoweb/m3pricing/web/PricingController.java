package lk.coopfed.knoweb.m3pricing.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ActivateRule;
import lk.coopfed.knoweb.m3pricing.api.AuthorRule;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.DraftNewVersion;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.api.WithdrawRule;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.DraftNewVersionHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.ActivateRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AuthorRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.WithdrawRuleHandler;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import lk.coopfed.knoweb.m3pricing.query.TradePrice;
import lk.coopfed.knoweb.m3pricing.web.generated.AuthorRuleRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.CreatePriceListRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.LineOutcome;
import lk.coopfed.knoweb.m3pricing.web.generated.PriceListDetailResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PriceListLineResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PriceListResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PricingApi;
import lk.coopfed.knoweb.m3pricing.web.generated.PublishPriceListRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.web.generated.RulePredicate;
import lk.coopfed.knoweb.m3pricing.web.generated.RuleResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.SetLinesRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.SetLinesResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.TradePriceResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.WithdrawRuleRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The price-list operations of 23A section 5 built so far (PriceListController of 23A section
 * 4) and the trade price lookup. Translates only: request to command, view to response. The
 * permission of each GET is the slice's x-permission, checked by the kernel (PR #144).
 */
@RestController
class PricingController implements PricingApi {

    private final CreatePriceListHandler create;
    private final DraftNewVersionHandler draftNewVersion;
    private final SetLinesHandler setLines;
    private final PublishPriceListHandler publish;
    private final AuthorRuleHandler authorRule;
    private final ActivateRuleHandler activateRule;
    private final WithdrawRuleHandler withdrawRule;
    private final PricingQueries queries;
    private final CurrentScope currentScope;

    PricingController(
            CreatePriceListHandler create,
            DraftNewVersionHandler draftNewVersion,
            SetLinesHandler setLines,
            PublishPriceListHandler publish,
            AuthorRuleHandler authorRule,
            ActivateRuleHandler activateRule,
            WithdrawRuleHandler withdrawRule,
            PricingQueries queries,
            CurrentScope currentScope) {
        this.create = create;
        this.draftNewVersion = draftNewVersion;
        this.setLines = setLines;
        this.publish = publish;
        this.authorRule = authorRule;
        this.activateRule = activateRule;
        this.withdrawRule = withdrawRule;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    // ---- discount rules (M3-05) ----------------------------------------------------------------

    @Override
    public ResponseEntity<List<RuleResponse>> listRules(String status, String kind) {
        return ResponseEntity.ok(queries.listRules(status, kind, currentScope.get()).stream()
                .map(PricingController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<RuleResponse> getRule(UUID ruleId) {
        return queries.getRule(ruleId, currentScope.get())
                .map(PricingController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<RuleResponse> authorRule(String idempotencyKey, AuthorRuleRequest request) {
        ScopeContext scope = currentScope.get();
        RulePredicate p = request.getPredicate();
        UUID id = authorRule.handle(
                new AuthorRule(
                        request.getName(),
                        request.getKind().getValue(),
                        p == null
                                ? null
                                : new lk.coopfed.knoweb.m3pricing.api.RulePredicate(
                                        p.getSkuId(),
                                        p.getUomCode(),
                                        p.getMinQty(),
                                        p.getDaysToExpiry(),
                                        p.getBillTotalFrom()),
                        new lk.coopfed.knoweb.m3pricing.api.RuleBenefit(
                                request.getBenefit().getKind().getValue(),
                                request.getBenefit().getValue()),
                        request.getPriority(),
                        request.getValidFrom(),
                        request.getValidTo()),
                scope);
        return ResponseEntity.created(URI.create("/v1/pricing/rules/" + id))
                .body(toResponse(queries.getRule(id, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<RuleResponse> activateRule(String idempotencyKey, UUID ruleId) {
        ScopeContext scope = currentScope.get();
        UUID id = activateRule.handle(new ActivateRule(ruleId), scope);
        return ResponseEntity.ok(toResponse(queries.getRule(id, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<RuleResponse> withdrawRule(String idempotencyKey, UUID ruleId, WithdrawRuleRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = withdrawRule.handle(new WithdrawRule(ruleId, request.getReason()), scope);
        return ResponseEntity.ok(toResponse(queries.getRule(id, scope).orElseThrow()));
    }

    private static RuleResponse toResponse(RuleView rule) {
        lk.coopfed.knoweb.m3pricing.api.RulePredicate p = rule.predicate();
        return new RuleResponse(
                        rule.ruleId(),
                        rule.ownerEntityId(),
                        rule.name(),
                        RuleResponse.KindEnum.fromValue(rule.kind()),
                        new RulePredicate()
                                .skuId(p.skuId())
                                .uomCode(p.uomCode())
                                .minQty(p.minQty())
                                .daysToExpiry(p.daysToExpiry())
                                .billTotalFrom(p.billTotalFrom()),
                        new RuleBenefit(
                                RuleBenefit.KindEnum.fromValue(rule.benefit().kind()),
                                rule.benefit().value()),
                        rule.priority(),
                        rule.validFrom(),
                        RuleResponse.StatusEnum.fromValue(rule.status()),
                        rule.createdAt())
                .validTo(rule.validTo());
    }

    // ---- price lists (M3-04) -------------------------------------------------------------------

    @Override
    public ResponseEntity<List<PriceListResponse>> listPriceLists(String kind, String status) {
        return ResponseEntity.ok(queries.listPriceLists(kind, status, currentScope.get()).stream()
                .map(PricingController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<PriceListResponse> createPriceList(String idempotencyKey, CreatePriceListRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = create.handle(new CreatePriceList(request.getKind().getValue(), request.getName()), scope);
        return created(id, scope);
    }

    @Override
    public ResponseEntity<PriceListDetailResponse> getPriceList(UUID listId) {
        ScopeContext scope = currentScope.get();
        return queries.getPriceList(listId, scope)
                .map(list -> new PriceListDetailResponse(
                        toResponse(list),
                        queries.lines(listId, scope).stream()
                                .map(PricingController::toResponse)
                                .toList()))
                .map(ResponseEntity::ok)
                // Not 403: whether the list exists is itself something another entity must not learn.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<PriceListResponse> draftNewVersion(String idempotencyKey, UUID listId) {
        ScopeContext scope = currentScope.get();
        UUID id = draftNewVersion.handle(new DraftNewVersion(listId), scope);
        return created(id, scope);
    }

    @Override
    public ResponseEntity<SetLinesResponse> setLines(String idempotencyKey, UUID listId, SetLinesRequest request) {
        List<SetLines.Line> lines = request.getLines().stream()
                .map(line ->
                        new SetLines.Line(line.getSkuId(), line.getUomCode(), line.getTierFromQty(), line.getPrice()))
                .toList();
        SetLinesResult result = setLines.handle(new SetLines(listId, lines), currentScope.get());
        return ResponseEntity.ok(new SetLinesResponse(
                result.saved(),
                result.outcomes().stream()
                        .map(outcome -> new LineOutcome(
                                        outcome.skuId(), outcome.uomCode(), outcome.tierFromQty(), outcome.ok())
                                .reason(outcome.reason())
                                .review(outcome.review()))
                        .toList()));
    }

    @Override
    public ResponseEntity<PriceListResponse> publishPriceList(
            String idempotencyKey, UUID listId, PublishPriceListRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = publish.handle(new PublishPriceList(listId, request.getApplyFrom()), scope);
        return ResponseEntity.ok(toResponse(queries.getPriceList(id, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<TradePriceResponse> resolveTradePrice(
            UUID relationshipId, UUID skuId, String uom, BigDecimal qty, LocalDate date) {
        return queries.resolveTradePrice(relationshipId, skuId, uom, qty, date, currentScope.get())
                .map(PricingController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<PriceListResponse> created(UUID id, ScopeContext scope) {
        PriceListView view = queries.getPriceList(id, scope).orElseThrow();
        return ResponseEntity.created(URI.create("/v1/pricing/lists/" + id)).body(toResponse(view));
    }

    private static PriceListResponse toResponse(PriceListView view) {
        return new PriceListResponse(
                        view.priceListId(),
                        view.rootPriceListId(),
                        PriceListResponse.KindEnum.fromValue(view.kind()),
                        view.name(),
                        view.version(),
                        PriceListResponse.StatusEnum.fromValue(view.status()),
                        view.createdAt())
                .ownerEntityId(view.ownerEntityId())
                .sourceVersionId(view.sourceVersionId())
                .applyFrom(view.applyFrom())
                .publishedAt(view.publishedAt());
    }

    private static PriceListLineResponse toResponse(PriceListLineView line) {
        return new PriceListLineResponse(
                        line.lineId(),
                        line.skuId(),
                        line.uomCode(),
                        line.tierFromQty(),
                        line.price(),
                        line.effectiveFrom())
                .effectiveTo(line.effectiveTo());
    }

    private static TradePriceResponse toResponse(TradePrice price) {
        return new TradePriceResponse(
                price.relationshipId(),
                price.priceListId(),
                price.lineId(),
                price.skuId(),
                price.uomCode(),
                price.tierFromQty(),
                price.unitPrice(),
                price.engineVersion());
    }
}
