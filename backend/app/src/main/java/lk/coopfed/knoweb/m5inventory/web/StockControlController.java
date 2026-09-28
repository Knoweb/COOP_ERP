package lk.coopfed.knoweb.m5inventory.web;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.AcknowledgeNegativeLot;
import lk.coopfed.knoweb.m5inventory.api.AddWriteOffPhoto;
import lk.coopfed.knoweb.m5inventory.api.ApproveAdjustment;
import lk.coopfed.knoweb.m5inventory.api.ApproveWriteOff;
import lk.coopfed.knoweb.m5inventory.api.DefineRecipe;
import lk.coopfed.knoweb.m5inventory.api.ExecuteRepack;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.RejectAdjustment;
import lk.coopfed.knoweb.m5inventory.api.RejectWriteOff;
import lk.coopfed.knoweb.m5inventory.api.RequestWriteOff;
import lk.coopfed.knoweb.m5inventory.api.RetireRecipe;
import lk.coopfed.knoweb.m5inventory.api.ReverseRepack;
import lk.coopfed.knoweb.m5inventory.api.ScheduleCount;
import lk.coopfed.knoweb.m5inventory.api.StartCount;
import lk.coopfed.knoweb.m5inventory.api.SubmitCount;
import lk.coopfed.knoweb.m5inventory.api.SubmitWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WitnessWriteOff;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.NegativeLotView;
import lk.coopfed.knoweb.m5inventory.query.RecipeView;
import lk.coopfed.knoweb.m5inventory.query.RepackView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.m5inventory.query.WriteOffView;
import lk.coopfed.knoweb.m5inventory.web.generated.CountExpectedResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.CountLineResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.CountResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.DefineRecipeRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.ExecuteRepackRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.NegativeLotResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.PhotoUploadRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.PhotoUploadResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.ReasonRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.RecipeResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.RepackResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.RequestWriteOffRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.ScheduleCountRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.StockControlApi;
import lk.coopfed.knoweb.m5inventory.web.generated.SubmitCountRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.WriteOffLineResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.WriteOffPhotoResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.WriteOffResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface of stock control (25A section 5; the screens of section 8): counts with their
 * adjustment, the negative lots to review, write-offs, recipes and repacks. The kernel checks each
 * operation's x-permission (and the fresh second factor of an approval); row-level security decides
 * which rows the caller sees. Every command answers the document as it stands after it.
 */
@RestController
class StockControlController implements StockControlApi {

    private final StockControlQueries queries;
    private final BatchQueries batches;
    private final CurrentScope currentScope;
    private final Handlers handlers;

    /** The command handlers of the slice, one field each, so the constructor stays readable. */
    @org.springframework.stereotype.Component
    record Handlers(
            Handles<ScheduleCount, UUID> scheduleCount,
            Handles<StartCount, UUID> startCount,
            Handles<SubmitCount, UUID> submitCount,
            Handles<ApproveAdjustment, UUID> approveAdjustment,
            Handles<RejectAdjustment, UUID> rejectAdjustment,
            Handles<AcknowledgeNegativeLot, UUID> acknowledgeNegativeLot,
            Handles<RequestWriteOff, UUID> requestWriteOff,
            Handles<AddWriteOffPhoto, Attachments.PresignedUpload> addWriteOffPhoto,
            Handles<SubmitWriteOff, UUID> submitWriteOff,
            Handles<WitnessWriteOff, UUID> witnessWriteOff,
            Handles<ApproveWriteOff, UUID> approveWriteOff,
            Handles<RejectWriteOff, UUID> rejectWriteOff,
            Handles<DefineRecipe, UUID> defineRecipe,
            Handles<RetireRecipe, UUID> retireRecipe,
            Handles<ExecuteRepack, UUID> executeRepack,
            Handles<ReverseRepack, UUID> reverseRepack) {}

    StockControlController(
            StockControlQueries queries, BatchQueries batches, CurrentScope currentScope, Handlers handlers) {
        this.queries = queries;
        this.batches = batches;
        this.currentScope = currentScope;
        this.handlers = handlers;
    }

    // ---- counts -------------------------------------------------------------------------------

    @Override
    public ResponseEntity<List<CountResponse>> listCounts(UUID locationId) {
        ScopeContext scope = currentScope.get();
        Map<UUID, String> known = new HashMap<>();
        return ResponseEntity.ok(queries.counts(locationId, scope).stream()
                .map(c -> toResponse(c, known, scope))
                .toList());
    }

    @Override
    public ResponseEntity<CountResponse> getCount(UUID taskId) {
        ScopeContext scope = currentScope.get();
        return queries.count(taskId, scope)
                .map(c -> toResponse(c, new HashMap<>(), scope))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<CountResponse> scheduleCount(String idempotencyKey, ScheduleCountRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = handlers.scheduleCount()
                .handle(
                        new ScheduleCount(
                                request.getLocationId(),
                                request.getScopeKind().getValue(),
                                request.getSkuIds(),
                                request.getScheduledFor()),
                        scope);
        return ResponseEntity.created(URI.create("/v1/inventory/counts/" + id)).body(count(id, scope));
    }

    @Override
    public ResponseEntity<CountResponse> startCount(String idempotencyKey, UUID taskId) {
        ScopeContext scope = currentScope.get();
        handlers.startCount().handle(new StartCount(taskId), scope);
        return ResponseEntity.ok(count(taskId, scope));
    }

    @Override
    public ResponseEntity<CountResponse> submitCount(String idempotencyKey, UUID taskId, SubmitCountRequest request) {
        ScopeContext scope = currentScope.get();
        List<SubmitCount.Line> lines = request.getLines().stream()
                .map(l -> new SubmitCount.Line(
                        l.getBatchId(),
                        l.getCondition() == null
                                ? LotCondition.GOOD
                                : LotCondition.valueOf(l.getCondition().getValue()),
                        l.getCountedQty(),
                        l.getSkipReason()))
                .toList();
        handlers.submitCount().handle(new SubmitCount(taskId, lines), scope);
        return ResponseEntity.ok(count(taskId, scope));
    }

    @Override
    public ResponseEntity<CountResponse> approveAdjustment(String idempotencyKey, UUID taskId) {
        ScopeContext scope = currentScope.get();
        handlers.approveAdjustment().handle(new ApproveAdjustment(taskId), scope);
        return ResponseEntity.ok(count(taskId, scope));
    }

    @Override
    public ResponseEntity<CountResponse> rejectAdjustment(String idempotencyKey, UUID taskId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        handlers.rejectAdjustment().handle(new RejectAdjustment(taskId, request.getReason()), scope);
        return ResponseEntity.ok(count(taskId, scope));
    }

    private CountResponse count(UUID taskId, ScopeContext scope) {
        return toResponse(queries.count(taskId, scope).orElseThrow(), new HashMap<>(), scope);
    }

    private CountResponse toResponse(CountView c, Map<UUID, String> known, ScopeContext scope) {
        return new CountResponse(
                        c.taskId(),
                        c.locationId(),
                        CountResponse.ScopeKindEnum.fromValue(c.scopeKind()),
                        c.skuIds(),
                        c.scheduledFor(),
                        CountResponse.StatusEnum.fromValue(c.status()),
                        c.expectation().stream()
                                .map(e -> new CountExpectedResponse(
                                                e.batchId(),
                                                e.skuId(),
                                                CountExpectedResponse.ConditionEnum.fromValue(e.condition()),
                                                e.expectedQty())
                                        .batchNo(batchNo(known, e.batchId(), scope)))
                                .toList(),
                        c.lines().stream()
                                .map(l -> new CountLineResponse(
                                                l.lineNo(),
                                                l.batchId(),
                                                l.skuId(),
                                                CountLineResponse.ConditionEnum.fromValue(l.condition()),
                                                l.expectedQty(),
                                                l.varianceQty(),
                                                l.varianceValue(),
                                                l.withinTolerance())
                                        .batchNo(batchNo(known, l.batchId(), scope))
                                        .countedQty(l.countedQty())
                                        .skipReason(l.skipReason()))
                                .toList())
                .outcome(c.outcome() == null ? null : CountResponse.OutcomeEnum.fromValue(c.outcome()))
                .scheduledBy(c.scheduledBy())
                .startedBy(c.startedBy())
                .startedAt(c.startedAt())
                .submittedBy(c.submittedBy())
                .submittedAt(c.submittedAt())
                .reviewValue(c.reviewValue())
                .reviewBand(c.reviewBand())
                .reviewedBy(c.reviewedBy())
                .reviewedAt(c.reviewedAt())
                .reviewReason(c.reviewReason());
    }

    // ---- negative lots ------------------------------------------------------------------------

    @Override
    public ResponseEntity<List<NegativeLotResponse>> listNegativeLots(UUID locationId) {
        ScopeContext scope = currentScope.get();
        Map<UUID, String> known = new HashMap<>();
        return ResponseEntity.ok(queries.negativeLots(locationId, scope).stream()
                .map(l -> toResponse(l, known, scope))
                .toList());
    }

    @Override
    public ResponseEntity<NegativeLotResponse> acknowledgeNegativeLot(
            String idempotencyKey, UUID stockLotId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        handlers.acknowledgeNegativeLot().handle(new AcknowledgeNegativeLot(stockLotId, request.getReason()), scope);
        // Acknowledging changes no stock: the lot is still below zero until a count corrects it.
        return ResponseEntity.ok(
                toResponse(queries.negativeLot(stockLotId, scope).orElseThrow(), new HashMap<>(), scope));
    }

    private NegativeLotResponse toResponse(NegativeLotView l, Map<UUID, String> known, ScopeContext scope) {
        return new NegativeLotResponse(
                        l.stockLotId(),
                        l.locationId(),
                        l.skuId(),
                        l.batchId(),
                        NegativeLotResponse.ConditionEnum.fromValue(l.condition()),
                        l.qtyOnHand())
                .batchNo(batchNo(known, l.batchId(), scope))
                .negativeSince(l.negativeSince())
                .acknowledgedAt(l.acknowledgedAt());
    }

    // ---- write-offs ---------------------------------------------------------------------------

    @Override
    public ResponseEntity<List<WriteOffResponse>> listWriteOffs(UUID locationId) {
        ScopeContext scope = currentScope.get();
        Map<UUID, String> known = new HashMap<>();
        return ResponseEntity.ok(queries.writeOffs(locationId, scope).stream()
                .map(w -> toResponse(w, known, scope))
                .toList());
    }

    @Override
    public ResponseEntity<WriteOffResponse> getWriteOff(UUID writeOffId) {
        ScopeContext scope = currentScope.get();
        return queries.writeOff(writeOffId, scope)
                .map(w -> toResponse(w, new HashMap<>(), scope))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<WriteOffResponse> requestWriteOff(String idempotencyKey, RequestWriteOffRequest request) {
        ScopeContext scope = currentScope.get();
        List<RequestWriteOff.Line> lines = request.getLines().stream()
                .map(l -> new RequestWriteOff.Line(
                        l.getBatchId(),
                        l.getCondition() == null
                                ? LotCondition.GOOD
                                : LotCondition.valueOf(l.getCondition().getValue()),
                        l.getQty()))
                .toList();
        UUID id = handlers.requestWriteOff()
                .handle(
                        new RequestWriteOff(
                                request.getLocationId(),
                                LossCategory.valueOf(request.getCategory().getValue()),
                                request.getNote(),
                                lines),
                        scope);
        return ResponseEntity.created(URI.create("/v1/inventory/write-offs/" + id))
                .body(writeOff(id, scope));
    }

    @Override
    public ResponseEntity<PhotoUploadResponse> addWriteOffPhoto(
            String idempotencyKey, UUID writeOffId, PhotoUploadRequest request) {
        Attachments.PresignedUpload upload = handlers.addWriteOffPhoto()
                .handle(
                        new AddWriteOffPhoto(writeOffId, request.getContentType(), request.getContentLength()),
                        currentScope.get());
        return ResponseEntity.ok(
                new PhotoUploadResponse(upload.attachmentId(), upload.url().toString(), upload.expiresAt()));
    }

    @Override
    public ResponseEntity<WriteOffResponse> submitWriteOff(String idempotencyKey, UUID writeOffId) {
        ScopeContext scope = currentScope.get();
        handlers.submitWriteOff().handle(new SubmitWriteOff(writeOffId), scope);
        return ResponseEntity.ok(writeOff(writeOffId, scope));
    }

    @Override
    public ResponseEntity<WriteOffResponse> witnessWriteOff(String idempotencyKey, UUID writeOffId) {
        ScopeContext scope = currentScope.get();
        handlers.witnessWriteOff().handle(new WitnessWriteOff(writeOffId), scope);
        return ResponseEntity.ok(writeOff(writeOffId, scope));
    }

    @Override
    public ResponseEntity<WriteOffResponse> approveWriteOff(String idempotencyKey, UUID writeOffId) {
        ScopeContext scope = currentScope.get();
        handlers.approveWriteOff().handle(new ApproveWriteOff(writeOffId), scope);
        return ResponseEntity.ok(writeOff(writeOffId, scope));
    }

    @Override
    public ResponseEntity<WriteOffResponse> rejectWriteOff(
            String idempotencyKey, UUID writeOffId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        handlers.rejectWriteOff().handle(new RejectWriteOff(writeOffId, request.getReason()), scope);
        return ResponseEntity.ok(writeOff(writeOffId, scope));
    }

    private WriteOffResponse writeOff(UUID writeOffId, ScopeContext scope) {
        return toResponse(queries.writeOff(writeOffId, scope).orElseThrow(), new HashMap<>(), scope);
    }

    private WriteOffResponse toResponse(WriteOffView w, Map<UUID, String> known, ScopeContext scope) {
        return new WriteOffResponse(
                        w.writeOffId(),
                        w.locationId(),
                        w.category(),
                        WriteOffResponse.StatusEnum.fromValue(w.status()),
                        w.remoteWitness(),
                        w.photosRequired(),
                        w.lines().stream()
                                .map(l -> new WriteOffLineResponse(
                                                l.lineNo(),
                                                l.batchId(),
                                                l.skuId(),
                                                WriteOffLineResponse.ConditionEnum.fromValue(l.condition()),
                                                l.qty())
                                        .batchNo(batchNo(known, l.batchId(), scope)))
                                .toList(),
                        w.photos().stream()
                                .map(p -> new WriteOffPhotoResponse(
                                        p.attachmentId(), WriteOffPhotoResponse.StatusEnum.fromValue(p.status())))
                                .toList())
                .note(w.note())
                .requestedBy(w.requestedBy())
                .requestedAt(w.requestedAt())
                .submittedAt(w.submittedAt())
                .documentNo(w.documentNo())
                .value(w.value())
                .band(w.band())
                .witnessUserId(w.witnessUserId())
                .witnessedAt(w.witnessedAt())
                .approverUserId(w.approverUserId())
                .decidedAt(w.decidedAt())
                .rejectReason(w.rejectReason());
    }

    // ---- recipes and repacks ------------------------------------------------------------------

    @Override
    public ResponseEntity<List<RecipeResponse>> listRecipes() {
        return ResponseEntity.ok(queries.recipes(currentScope.get()).stream()
                .map(StockControlController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<RecipeResponse> defineRecipe(String idempotencyKey, DefineRecipeRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = handlers.defineRecipe()
                .handle(
                        new DefineRecipe(
                                request.getName(),
                                request.getInputSkuId(),
                                request.getInputQty(),
                                request.getOutputSkuId(),
                                request.getOutputQty(),
                                request.getExpectedLossPct()),
                        scope);
        return ResponseEntity.created(URI.create("/v1/inventory/recipes/" + id)).body(recipe(id, scope));
    }

    @Override
    public ResponseEntity<RecipeResponse> retireRecipe(String idempotencyKey, UUID recipeId) {
        ScopeContext scope = currentScope.get();
        handlers.retireRecipe().handle(new RetireRecipe(recipeId), scope);
        return ResponseEntity.ok(recipe(recipeId, scope));
    }

    private RecipeResponse recipe(UUID recipeId, ScopeContext scope) {
        return queries.recipes(scope).stream()
                .filter(r -> r.recipeId().equals(recipeId))
                .findFirst()
                .map(StockControlController::toResponse)
                .orElseThrow();
    }

    private static RecipeResponse toResponse(RecipeView r) {
        return new RecipeResponse(
                r.recipeId(),
                r.name(),
                r.inputSkuId(),
                r.inputQty(),
                r.outputSkuId(),
                r.outputQty(),
                r.expectedLossPct(),
                RecipeResponse.StatusEnum.fromValue(r.status()));
    }

    @Override
    public ResponseEntity<List<RepackResponse>> listRepacks(UUID locationId) {
        ScopeContext scope = currentScope.get();
        Map<UUID, String> known = new HashMap<>();
        return ResponseEntity.ok(queries.repacks(locationId, scope).stream()
                .map(r -> toResponse(r, known, scope))
                .toList());
    }

    @Override
    public ResponseEntity<RepackResponse> getRepack(UUID repackId) {
        ScopeContext scope = currentScope.get();
        return queries.repack(repackId, scope)
                .map(r -> toResponse(r, new HashMap<>(), scope))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<RepackResponse> executeRepack(String idempotencyKey, ExecuteRepackRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = handlers.executeRepack()
                .handle(
                        new ExecuteRepack(
                                request.getRecipeId(),
                                request.getLocationId(),
                                request.getInputBatchId(),
                                request.getInputQty(),
                                request.getActualOutputQty(),
                                request.getPrintedMrp()),
                        scope);
        return ResponseEntity.created(URI.create("/v1/inventory/repacks/" + id))
                .body(toResponse(queries.repack(id, scope).orElseThrow(), new HashMap<>(), scope));
    }

    @Override
    public ResponseEntity<RepackResponse> reverseRepack(String idempotencyKey, UUID repackId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        handlers.reverseRepack().handle(new ReverseRepack(repackId, request.getReason()), scope);
        return ResponseEntity.ok(toResponse(queries.repack(repackId, scope).orElseThrow(), new HashMap<>(), scope));
    }

    private RepackResponse toResponse(RepackView r, Map<UUID, String> known, ScopeContext scope) {
        return new RepackResponse(
                        r.repackId(),
                        r.locationId(),
                        r.recipeId(),
                        r.inputBatchId(),
                        r.inputSkuId(),
                        r.inputQty(),
                        r.outputSkuId(),
                        r.outputBatchId(),
                        r.expectedOutputQty(),
                        r.actualOutputQty(),
                        r.varianceQty(),
                        RepackResponse.StatusEnum.fromValue(r.status()))
                .inputUnitCost(r.inputUnitCost())
                .outputUnitCost(r.outputUnitCost())
                .outputBatchNo(batchNo(known, r.outputBatchId(), scope))
                .executedBy(r.executedBy())
                .executedAt(r.executedAt())
                .reversalReason(r.reversalReason())
                .reversedAt(r.reversedAt());
    }

    /** The printed batch number, from M2, read once per batch of the answer. */
    private String batchNo(Map<UUID, String> known, UUID batchId, ScopeContext scope) {
        return known.computeIfAbsent(
                batchId,
                id -> batches.getBatch(id, scope).map(BatchView::batchNo).orElse(null));
    }
}
