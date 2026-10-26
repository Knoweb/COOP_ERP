package lk.coopfed.knoweb.m3pricing.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.engine.Basket;
import lk.coopfed.knoweb.engine.TenderKind;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ActivateRule;
import lk.coopfed.knoweb.m3pricing.api.AdoptAdvisoryRule;
import lk.coopfed.knoweb.m3pricing.api.AmendDraftRule;
import lk.coopfed.knoweb.m3pricing.api.AuthorRule;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.DraftNewVersion;
import lk.coopfed.knoweb.m3pricing.api.EnterControlPrice;
import lk.coopfed.knoweb.m3pricing.api.PrintShelfLabels;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.RescindControlPrice;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult;
import lk.coopfed.knoweb.m3pricing.api.SetMrpPolicy;
import lk.coopfed.knoweb.m3pricing.api.WithdrawRule;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.EnterControlPriceHandler;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.RescindControlPriceHandler;
import lk.coopfed.knoweb.m3pricing.internal.label.PrintShelfLabelsHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.DraftNewVersionHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.internal.policy.SetMrpPolicyHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.ActivateRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AdoptAdvisoryRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AmendDraftRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AuthorRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.WithdrawRuleHandler;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import lk.coopfed.knoweb.m3pricing.query.LabelJobView;
import lk.coopfed.knoweb.m3pricing.query.MrpPolicyView;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import lk.coopfed.knoweb.m3pricing.query.TradePrice;
import lk.coopfed.knoweb.m3pricing.web.generated.AmendDraftRuleRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.AuthorRuleRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.BasketResult;
import lk.coopfed.knoweb.m3pricing.web.generated.BatchCandidate;
import lk.coopfed.knoweb.m3pricing.web.generated.ControlPriceResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.CreatePriceListRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.EnterControlPriceRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.LabelJobResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.LineOutcome;
import lk.coopfed.knoweb.m3pricing.web.generated.LineResult;
import lk.coopfed.knoweb.m3pricing.web.generated.MrpPolicyResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PriceListDetailResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PriceListLineResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PriceListResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.PricingApi;
import lk.coopfed.knoweb.m3pricing.web.generated.PrintShelfLabelsRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.PublishPriceListRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.RescindControlPriceRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.ResolveBasketLineRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.ResolveBasketRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.RetailPriceResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.ReviewItem;
import lk.coopfed.knoweb.m3pricing.web.generated.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.web.generated.RulePredicate;
import lk.coopfed.knoweb.m3pricing.web.generated.RuleResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.SetLinesRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.SetLinesResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.SetMrpPolicyRequest;
import lk.coopfed.knoweb.m3pricing.web.generated.TradePriceResponse;
import lk.coopfed.knoweb.m3pricing.web.generated.WithdrawRuleRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
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
    private final AmendDraftRuleHandler amendDraftRule;
    private final ActivateRuleHandler activateRule;
    private final WithdrawRuleHandler withdrawRule;
    private final EnterControlPriceHandler enterControlPrice;
    private final RescindControlPriceHandler rescindControlPrice;
    private final SetMrpPolicyHandler setMrpPolicy;
    private final PrintShelfLabelsHandler printShelfLabels;
    private final PricingQueries queries;
    private final CurrentScope currentScope;
    private final ObjectProvider<A4Renderer> renderer;
    private final AdoptAdvisoryRuleHandler adoptAdvisoryRule;

    @SuppressWarnings("java:S107") // one handler per operation of the slice; a controller only translates
    PricingController(
            CreatePriceListHandler create,
            DraftNewVersionHandler draftNewVersion,
            SetLinesHandler setLines,
            PublishPriceListHandler publish,
            AuthorRuleHandler authorRule,
            AmendDraftRuleHandler amendDraftRule,
            AdoptAdvisoryRuleHandler adoptAdvisoryRule,
            ActivateRuleHandler activateRule,
            WithdrawRuleHandler withdrawRule,
            EnterControlPriceHandler enterControlPrice,
            RescindControlPriceHandler rescindControlPrice,
            SetMrpPolicyHandler setMrpPolicy,
            PrintShelfLabelsHandler printShelfLabels,
            PricingQueries queries,
            CurrentScope currentScope,
            ObjectProvider<A4Renderer> renderer) {
        this.enterControlPrice = enterControlPrice;
        this.rescindControlPrice = rescindControlPrice;
        this.setMrpPolicy = setMrpPolicy;
        this.printShelfLabels = printShelfLabels;
        this.create = create;
        this.draftNewVersion = draftNewVersion;
        this.setLines = setLines;
        this.publish = publish;
        this.authorRule = authorRule;
        this.amendDraftRule = amendDraftRule;
        this.activateRule = activateRule;
        this.withdrawRule = withdrawRule;
        this.queries = queries;
        this.currentScope = currentScope;
        this.renderer = renderer;
        this.adoptAdvisoryRule = adoptAdvisoryRule;
    }

    // ---- shelf-label printing (M3-09) ----------------------------------------------------------

    @Override
    public ResponseEntity<LabelJobResponse> printShelfLabels(String idempotencyKey, PrintShelfLabelsRequest request) {
        ScopeContext scope = currentScope.get();
        UUID jobId = printShelfLabels.handle(
                new PrintShelfLabels(request.getLocationId(), List.copyOf(request.getSkuIds())), scope);
        LabelJobView job = queries.labelJob(jobId, scope).orElseThrow();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(job, scope));
    }

    @Override
    public ResponseEntity<LabelJobResponse> getLabelJob(UUID jobId) {
        ScopeContext scope = currentScope.get();
        return queries.labelJob(jobId, scope)
                .map(job -> ResponseEntity.ok(toResponse(job, scope)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<RuleResponse> adoptAdvisoryRule(String idempotencyKey, UUID ruleId) {
        ScopeContext scope = currentScope.get();

        UUID newId = adoptAdvisoryRule.handle(new AdoptAdvisoryRule(ruleId), scope);

        return ResponseEntity.created(URI.create("/v1/pricing/rules/" + newId))
                .body(toResponse(queries.getRule(newId, scope).orElseThrow()));
    }

    private LabelJobResponse toResponse(LabelJobView job, ScopeContext scope) {
        LabelJobResponse response = new LabelJobResponse(
                job.jobId(),
                job.locationId(),
                LabelJobResponse.StatusEnum.fromValue(job.status()),
                job.requestedCount(),
                job.renderedCount(),
                job.refused().stream()
                        .map(refusal -> new lk.coopfed.knoweb.m3pricing.web.generated.LabelRefusal(
                                        refusal.skuId(),
                                        refusal.skuCode(),
                                        lk.coopfed.knoweb.m3pricing.web.generated.LabelRefusal.ReasonEnum.fromValue(
                                                refusal.reason()),
                                        refusal.listPrice(),
                                        refusal.ceilingPrice())
                                .ceilingReference(refusal.ceilingReference()))
                        .toList(),
                job.createdAt());
        A4Renderer pdf = renderer.getIfAvailable();
        if (job.objectKey() != null && pdf != null) {
            response.setDownloadUrl(pdf.presignGet(job.objectKey(), scope));
        }
        return response;
    }

    // ---- control prices (M3-06) ----------------------------------------------------------------

    @Override
    public ResponseEntity<List<ControlPriceResponse>> listControlPrices(UUID skuId, LocalDate inForceOn) {
        ScopeContext scope = currentScope.get();
        List<ControlPriceView> rows = inForceOn == null
                ? queries.controlPrices(skuId, scope)
                : queries.controlPricesInForce(inForceOn, scope).stream()
                        .filter(row -> skuId == null || skuId.equals(row.skuId()))
                        .toList();
        return ResponseEntity.ok(
                rows.stream().map(PricingController::toResponse).toList());
    }

    @Override
    public ResponseEntity<ControlPriceResponse> enterControlPrice(
            String idempotencyKey, EnterControlPriceRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = enterControlPrice.handle(
                new EnterControlPrice(
                        request.getSkuId(),
                        request.getCeilingPrice(),
                        request.getCeilingUomCode(),
                        request.getEffectiveFrom(),
                        request.getEffectiveTo(),
                        request.getGazetteReference()),
                scope);
        return ResponseEntity.created(URI.create("/v1/pricing/control-prices/" + id))
                .body(toResponse(controlPrice(id, request.getSkuId(), scope)));
    }

    @Override
    public ResponseEntity<ControlPriceResponse> rescindControlPrice(
            String idempotencyKey, UUID controlPriceId, RescindControlPriceRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = rescindControlPrice.handle(
                new RescindControlPrice(
                        controlPriceId, request.getLastDay(), request.getReason(), request.getGazetteReference()),
                scope);
        return ResponseEntity.ok(toResponse(controlPrice(id, null, scope)));
    }

    private ControlPriceView controlPrice(UUID id, UUID skuId, ScopeContext scope) {
        return queries.controlPrices(skuId, scope).stream()
                .filter(row -> row.controlPriceId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static ControlPriceResponse toResponse(ControlPriceView row) {
        return new ControlPriceResponse(
                        row.controlPriceId(),
                        row.skuId(),
                        row.ceilingPrice(),
                        row.ceilingUomCode(),
                        row.effectiveFrom(),
                        row.gazetteReference(),
                        row.enteredAt())
                .effectiveTo(row.effectiveTo())
                .enteredBy(row.enteredBy());
    }

    // ---- MRP policy (M3-07) --------------------------------------------------------------------

    @Override
    public ResponseEntity<List<MrpPolicyResponse>> listMrpPolicies() {
        return ResponseEntity.ok(queries.listMrpPolicies(currentScope.get()).stream()
                .map(PricingController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<MrpPolicyResponse> getEffectiveMrpPolicy(UUID skuId) {
        return ResponseEntity.ok(toResponse(queries.effectiveMrpPolicy(skuId, currentScope.get())));
    }

    @Override
    public ResponseEntity<MrpPolicyResponse> setMrpPolicy(String idempotencyKey, SetMrpPolicyRequest request) {
        ScopeContext scope = currentScope.get();
        setMrpPolicy.handle(
                new SetMrpPolicy(
                        request.getSkuId(),
                        request.getPolicy().getValue(),
                        request.getGapAmount(),
                        request.getGapPercent()),
                scope);
        return ResponseEntity.ok(toResponse(queries.effectiveMrpPolicy(request.getSkuId(), scope)));
    }

    private static MrpPolicyResponse toResponse(MrpPolicyView view) {
        return new MrpPolicyResponse(
                        view.skuId(),
                        MrpPolicyResponse.PolicyEnum.fromValue(view.policy()),
                        MrpPolicyResponse.SourceEnum.fromValue(view.source()))
                .policyId(view.policyId())
                .gapAmount(view.gapAmount())
                .gapPercent(view.gapPercent())
                .ownerEntityId(view.ownerEntityId())
                .setAt(view.setAt());
    }

    // ---- retail prices (M3-06) -----------------------------------------------------------------

    @Override
    public ResponseEntity<RetailPriceResponse> resolveRetailPrice(
            UUID locationId, UUID skuId, String uom, BigDecimal qty, LocalDate date) {
        return queries.resolveRetailPrice(locationId, skuId, uom, qty, date, currentScope.get())
                .map(price -> new RetailPriceResponse(
                                price.locationId(),
                                price.skuId(),
                                price.uomCode(),
                                price.sellable(),
                                RetailPriceResponse.CapReasonEnum.fromValue(price.capReason()),
                                RetailPriceResponse.PolicyEnum.fromValue(price.policy()),
                                price.engineVersion())
                        .reason(price.reason())
                        .priceListId(price.priceListId())
                        .listPrice(price.listPrice())
                        .unitPrice(price.unitPrice())
                        .mrpApplied(price.mrpApplied())
                        .controlPrice(price.controlPrice())
                        .batchId(price.batchId()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<RetailPriceResponse> resolveCommercialPrice(
            UUID locationId, UUID skuId, String uom, BigDecimal qty, LocalDate date) {
        return queries.resolveCommercialPrice(locationId, skuId, uom, qty, date, currentScope.get())
                .map(price -> new RetailPriceResponse(
                                price.locationId(),
                                price.skuId(),
                                price.uomCode(),
                                price.sellable(),
                                RetailPriceResponse.CapReasonEnum.fromValue(price.capReason()),
                                RetailPriceResponse.PolicyEnum.fromValue(price.policy()),
                                price.engineVersion())
                        .reason(price.reason())
                        .priceListId(price.priceListId())
                        .listPrice(price.listPrice())
                        .unitPrice(price.unitPrice())
                        .mrpApplied(price.mrpApplied())
                        .controlPrice(price.controlPrice())
                        .batchId(price.batchId()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<List<PriceListLineResponse>> listAdvisoryLines(LocalDate date) {
        return ResponseEntity.ok(queries.advisoryLines(date, currentScope.get()).stream()
                .map(PricingController::toResponse)
                .toList());
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
                        request.getValidTo(),
                        Boolean.TRUE.equals(request.getAdvisory())),
                scope);
        return ResponseEntity.created(URI.create("/v1/pricing/rules/" + id))
                .body(toResponse(queries.getRule(id, scope).orElseThrow()));
    }

    @Override
    public ResponseEntity<RuleResponse> amendDraftRule(
            String idempotencyKey, UUID ruleId, AmendDraftRuleRequest request) {
        ScopeContext scope = currentScope.get();
        RulePredicate p = request.getPredicate();

        UUID id = amendDraftRule.handle(
                new AmendDraftRule(
                        ruleId,
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

        return ResponseEntity.ok(toResponse(queries.getRule(id, scope).orElseThrow()));
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
                .validTo(rule.validTo())
                .advisory(rule.advisory())
                .adoptedFromRuleId(rule.adoptedFromRuleId());
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
                                .review(outcome.review())
                                .ceilingKind(outcome.ceilingKind())
                                .ceilingValue(outcome.ceilingValue())
                                .ceilingRef(outcome.ceilingRef()))
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

    // ---- basket resolution and reviews (M3-08) ---------------------------------------------------

    /**
     * The tenders of the slice the engine prices (M3R-02). Doc 23 section 3.5 (FR-PRC-100, G-10):
     * cash rounds the receipt to the rupee, card and account are exact, and "other tenders exact".
     * KHATA is the member's credit account, the engine's ACCOUNT; a CHEQUE is exact, priced as a
     * card. A VOUCHER is out of scope for M3 (doc 23 section 1: coupons and vouchers are later
     * phases), so it is refused with a 400, not priced as something it is not.
     */
    static TenderKind engineTender(ResolveBasketRequest.TenderKindEnum tender) {
        return switch (tender) {
            case CASH -> TenderKind.CASH;
            case CARD, CHEQUE -> TenderKind.CARD;
            case KHATA -> TenderKind.ACCOUNT;
            default ->
                throw new ProblemException("m3.basket.tender_not_priced", Map.of("tenderKind", tender.getValue()));
        };
    }

    @Override
    public ResponseEntity<BasketResult> resolveBasket(String idempotencyKey, ResolveBasketRequest request) {
        ScopeContext scope = currentScope.get();
        TenderKind tender = engineTender(request.getTenderKind());

        List<lk.coopfed.knoweb.engine.LineInput> engineLines = new ArrayList<>();
        for (ResolveBasketLineRequest l : request.getLines()) {
            lk.coopfed.knoweb.engine.Quantity qty;
            try {
                qty = lk.coopfed.knoweb.engine.Quantity.of(l.getQty());
            } catch (ArithmeticException moreThanThreeDecimals) {
                // doc 18: a quantity is numeric(14,3); the engine refuses a fourth decimal (M3R-02).
                throw new ProblemException("m3.basket.qty_precision", Map.of("skuId", l.getSkuId()));
            }
            engineLines.add(new lk.coopfed.knoweb.engine.LineInput(
                    l.getSkuId(), l.getUom(), qty, l.getBatchId(), l.getPickedBatchId()));
        }

        return queries.resolveBasket(
                        request.getLocationId(), request.getBusinessDate(), tender, new Basket(engineLines), scope)
                .map(result -> ResponseEntity.ok(toResponse(result)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static BasketResult toResponse(lk.coopfed.knoweb.engine.BasketResult result) {
        BasketResult br = new BasketResult();
        br.setTotal(result.getTotal().getAmount());
        br.setSubtotal(result.getSubtotal().getAmount());
        br.setRounding(result.getRounding().getAmount());
        br.setBillDiscount(result.getBillDiscount().getAmount());
        br.setBillRuleId(result.getBillRuleId());
        br.setEngineVersion(result.getEngineVersion());
        // Central reads the tables, not a snapshot: there is no snapshot version to report (M3R-03).
        br.setSnapshotVersion(null);
        List<LineResult> lines = new ArrayList<>();
        for (lk.coopfed.knoweb.engine.LineResult l : result.getLines()) {
            lines.add(toResponse(l));
        }
        br.setLines(lines);
        return br;
    }

    private static LineResult toResponse(lk.coopfed.knoweb.engine.LineResult l) {
        LineResult lr = new LineResult();
        lr.setSkuId(l.getSkuId());
        lr.setUom(l.getInput().getUom());
        lr.setQty(l.getInput().getQty().getValue());
        lr.setUnitPrice(l.getUnitPrice().getAmount());
        lr.setLineTotal(l.getLineTotal().getAmount());
        lr.setDiscountAmount(l.getDiscountAmount().getAmount());
        lr.setBatchId(l.getBatch() != null ? l.getBatch().getBatchId() : null);
        lr.setRuleId(l.getRuleId());
        lr.setTaxRatePercent(l.getTaxRatePercent());
        lr.setTaxAmount(l.getTaxAmount().getAmount());
        lr.setMrpApplied(l.getMrpApplied() != null ? l.getMrpApplied().getAmount() : null);
        lr.setControlPriceApplied(
                l.getControlPriceApplied() != null ? l.getControlPriceApplied().getAmount() : null);
        // The engine's own names, as RetailPriceResponse reports them (M3R-03).
        lr.setCapReason(LineResult.CapReasonEnum.fromValue(l.getCapReason().name()));
        // 23A section 5: "candidates only for PICKER"; the engine fills them when the till must pick.
        List<BatchCandidate> candidates = new ArrayList<>();
        for (lk.coopfed.knoweb.engine.BatchCandidate c : l.getCandidates()) {
            BatchCandidate candidate = new BatchCandidate();
            candidate.setBatchId(c.getBatchId());
            candidate.setBatchNo(c.getBatchNo());
            candidate.setPrintedMrp(
                    c.getPrintedMrp() != null ? c.getPrintedMrp().getAmount() : null);
            candidate.setExpiry(c.getExpiry());
            candidates.add(candidate);
        }
        lr.setCandidates(candidates);
        return lr;
    }

    @Override
    public ResponseEntity<List<ReviewItem>> listReviews(String kind, String status) {
        ScopeContext scope = currentScope.get();

        List<ReviewItem> result = new ArrayList<>();
        for (lk.coopfed.knoweb.m3pricing.query.ReviewItem r : queries.listReviews(kind, status, scope)) {
            ReviewItem item = new ReviewItem();
            item.setReviewId(r.reviewId());
            item.setKind(r.kind());
            item.setSeverity(r.severity());
            item.setStatus(ReviewItem.StatusEnum.fromValue(r.status()));
            item.setOccurredAt(r.occurredAt().toInstant());
            item.setOwnerEntityId(r.ownerEntityId());
            item.setSkuId(r.skuId());
            item.setSkuName(r.skuName());
            item.setSkuCode(r.skuCode());
            item.setDetails(r.details());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }
}
