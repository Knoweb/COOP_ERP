package lk.coopfed.knoweb.m7customers.web;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.AmendCustomer;
import lk.coopfed.knoweb.m7customers.api.BulkImportMembers;
import lk.coopfed.knoweb.m7customers.api.BulkMemberReport;
import lk.coopfed.knoweb.m7customers.api.CalculateMonthlyLoyaltyRewards;
import lk.coopfed.knoweb.m7customers.api.ChangePhone;
import lk.coopfed.knoweb.m7customers.api.ConfigureLoyaltyPolicy;
import lk.coopfed.knoweb.m7customers.api.DeactivateCustomer;
import lk.coopfed.knoweb.m7customers.api.RecaptureNic;
import lk.coopfed.knoweb.m7customers.api.RecordLoyaltySpend;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.api.RegisterLoyaltyCustomer;
import lk.coopfed.knoweb.m7customers.api.SubmitInsuranceClaim;
import lk.coopfed.knoweb.m7customers.api.UpdateInsuranceClaimStatus;
import lk.coopfed.knoweb.m7customers.api.UpdateRegionalRegistration;
import lk.coopfed.knoweb.m7customers.query.CustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyEligibility;
import lk.coopfed.knoweb.m7customers.query.LoyaltyInsuranceClaimView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyInsuranceEligibilityView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyRewardPolicyView;
import lk.coopfed.knoweb.m7customers.web.generated.AmendCustomerRequest;
import lk.coopfed.knoweb.m7customers.web.generated.BulkCustomerReport;
import lk.coopfed.knoweb.m7customers.web.generated.BulkImportCustomersRequest;
import lk.coopfed.knoweb.m7customers.web.generated.CalculateMonthlyLoyaltyRewardsRequest;
import lk.coopfed.knoweb.m7customers.web.generated.CalculateMonthlyLoyaltyRewardsResponse;
import lk.coopfed.knoweb.m7customers.web.generated.ChangePhoneRequest;
import lk.coopfed.knoweb.m7customers.web.generated.ConfigureLoyaltyPolicyRequest;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerCard;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerSummary;
import lk.coopfed.knoweb.m7customers.web.generated.CustomersApi;
import lk.coopfed.knoweb.m7customers.web.generated.LoyaltyCustomerResponse;
import lk.coopfed.knoweb.m7customers.web.generated.LoyaltyEligibilityResponse;
import lk.coopfed.knoweb.m7customers.web.generated.LoyaltyInsuranceClaimResponse;
import lk.coopfed.knoweb.m7customers.web.generated.LoyaltyInsuranceEligibilityResponse;
import lk.coopfed.knoweb.m7customers.web.generated.LoyaltyMonthlyRewardResponse;
import lk.coopfed.knoweb.m7customers.web.generated.LoyaltyRewardPolicyResponse;
import lk.coopfed.knoweb.m7customers.web.generated.ReasonRequest;
import lk.coopfed.knoweb.m7customers.web.generated.RecaptureNicRequest;
import lk.coopfed.knoweb.m7customers.web.generated.RecordLoyaltySpendRequest;
import lk.coopfed.knoweb.m7customers.web.generated.RecordLoyaltySpendResponse;
import lk.coopfed.knoweb.m7customers.web.generated.RegisterCustomerRequest;
import lk.coopfed.knoweb.m7customers.web.generated.RegisterLoyaltyCustomerRequest;
import lk.coopfed.knoweb.m7customers.web.generated.SubmitInsuranceClaimRequest;
import lk.coopfed.knoweb.m7customers.web.generated.UpdateInsuranceClaimStatusRequest;
import lk.coopfed.knoweb.m7customers.web.generated.UpdateRegionalRegistrationRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/** The customer register of the society office (27A section 5): translates requests to commands. */
@RestController
class CustomerController implements CustomersApi {

    static final int DEFAULT_LIMIT = 50;

    private final Handles<RegisterCustomer, UUID> register;
    private final Handles<BulkImportMembers, BulkMemberReport> bulkImporter;
    private final Handles<AmendCustomer, UUID> amend;
    private final Handles<ChangePhone, UUID> changePhone;
    private final Handles<DeactivateCustomer, UUID> deactivate;
    private final Handles<RecaptureNic, UUID> recapture;
    private final Handles<RegisterLoyaltyCustomer, UUID> registerLoyalty;
    private final Handles<UpdateRegionalRegistration, Void> updateRegional;
    private final Handles<ConfigureLoyaltyPolicy, UUID> configurePolicy;
    private final Handles<RecordLoyaltySpend, UUID> recordSpend;
    private final Handles<CalculateMonthlyLoyaltyRewards, List<UUID>> calculateRewards;
    private final Handles<SubmitInsuranceClaim, UUID> submitClaim;
    private final Handles<UpdateInsuranceClaimStatus, Void> updateClaimStatus;
    private final CustomerQueries queries;
    private final LoyaltyCustomerQueries loyaltyQueries;
    private final AuditFacade audit;
    private final CurrentScope currentScope;

    CustomerController(
            Handles<RegisterCustomer, UUID> register,
            Handles<BulkImportMembers, BulkMemberReport> bulkImporter,
            Handles<AmendCustomer, UUID> amend,
            Handles<ChangePhone, UUID> changePhone,
            Handles<DeactivateCustomer, UUID> deactivate,
            Handles<RecaptureNic, UUID> recapture,
            Handles<RegisterLoyaltyCustomer, UUID> registerLoyalty,
            Handles<UpdateRegionalRegistration, Void> updateRegional,
            Handles<ConfigureLoyaltyPolicy, UUID> configurePolicy,
            Handles<RecordLoyaltySpend, UUID> recordSpend,
            Handles<CalculateMonthlyLoyaltyRewards, List<UUID>> calculateRewards,
            Handles<SubmitInsuranceClaim, UUID> submitClaim,
            Handles<UpdateInsuranceClaimStatus, Void> updateClaimStatus,
            CustomerQueries queries,
            LoyaltyCustomerQueries loyaltyQueries,
            AuditFacade audit,
            CurrentScope currentScope) {
        this.register = register;
        this.bulkImporter = bulkImporter;
        this.amend = amend;
        this.changePhone = changePhone;
        this.deactivate = deactivate;
        this.recapture = recapture;
        this.registerLoyalty = registerLoyalty;
        this.updateRegional = updateRegional;
        this.configurePolicy = configurePolicy;
        this.recordSpend = recordSpend;
        this.calculateRewards = calculateRewards;
        this.submitClaim = submitClaim;
        this.updateClaimStatus = updateClaimStatus;
        this.queries = queries;
        this.loyaltyQueries = loyaltyQueries;
        this.audit = audit;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<CustomerSummary>> searchCustomers(String q, String phone, Integer limit) {
        return ResponseEntity.ok(
                queries.search(q, phone, limit == null ? DEFAULT_LIMIT : limit, currentScope.get()).stream()
                        .map(CustomerResponses::summary)
                        .toList());
    }

    @Override
    public ResponseEntity<CustomerCard> registerCustomer(String idempotencyKey, RegisterCustomerRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = register.handle(
                new RegisterCustomer(
                        request.getDisplayName(),
                        request.getDisplayNameSi(),
                        request.getDisplayNameTa(),
                        request.getLanguage() == null
                                ? null
                                : request.getLanguage().getValue(),
                        request.getPhone(),
                        request.getConsents() == null
                                ? List.of()
                                : request.getConsents().stream()
                                        .map(RegisterCustomerRequest.ConsentsEnum::getValue)
                                        .toList(),
                        request.getVia() == null ? null : request.getVia().getValue(),
                        request.getAttributes(),
                        request.getTags(),
                        Boolean.TRUE.equals(request.getConfirmedIdentity())),
                scope);
        return ResponseEntity.created(URI.create(CustomersApi.PATH_REGISTER_CUSTOMER + "/" + id))
                .body(card(id, scope));
    }

    @Override
    public ResponseEntity<BulkCustomerReport> bulkImportCustomers(
            String idempotencyKey, BulkImportCustomersRequest request) {
        ScopeContext scope = currentScope.get();
        List<BulkImportMembers.MemberRow> rows = null;
        if (request.getMembers() != null) {
            rows = request.getMembers().stream()
                    .map(m -> new BulkImportMembers.MemberRow(
                            m.getDisplayName(),
                            m.getDisplayNameSi(),
                            m.getDisplayNameTa(),
                            m.getLanguage() == null ? null : m.getLanguage().getValue(),
                            m.getPhone(),
                            m.getNic(),
                            m.getCreditLimit(),
                            m.getTermsDays(),
                            m.getOfflineCap(),
                            m.getTags(),
                            m.getConsents() == null
                                    ? null
                                    : m.getConsents().stream().map(Enum::name).toList(),
                            Boolean.TRUE.equals(m.getConfirmedIdentity())))
                    .toList();
        }
        BulkMemberReport report = bulkImporter.handle(
                new BulkImportMembers(request.getSheet(), rows, Boolean.TRUE.equals(request.getConfirmedReuse())),
                scope);
        return ResponseEntity.ok(CustomerResponses.bulkReport(report));
    }

    @Override
    @Transactional
    public ResponseEntity<CustomerCard> getCustomer(UUID customerId) {
        ScopeContext scope = currentScope.get();
        return queries.card(customerId, scope)
                .map(CustomerResponses::card)
                .map(card -> {
                    audit.record("CUSTOMER_VIEWED", Subject.of("customer", customerId), null, null, scope);
                    return ResponseEntity.ok(card);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<CustomerCard> amendCustomer(
            String idempotencyKey, UUID customerId, AmendCustomerRequest request) {
        ScopeContext scope = currentScope.get();
        amend.handle(
                new AmendCustomer(
                        customerId,
                        request.getDisplayName(),
                        request.getDisplayNameSi(),
                        request.getDisplayNameTa(),
                        request.getLanguage().getValue(),
                        request.getAttributes(),
                        request.getTags()),
                scope);
        return ResponseEntity.ok(card(customerId, scope));
    }

    @Override
    public ResponseEntity<CustomerCard> changePhone(
            String idempotencyKey, UUID customerId, ChangePhoneRequest request) {
        ScopeContext scope = currentScope.get();
        changePhone.handle(
                new ChangePhone(
                        customerId,
                        request.getNewPhone(),
                        request.getReason(),
                        Boolean.TRUE.equals(request.getConfirmedIdentity())),
                scope);
        return ResponseEntity.ok(card(customerId, scope));
    }

    @Override
    public ResponseEntity<CustomerCard> deactivateCustomer(
            String idempotencyKey, UUID customerId, ReasonRequest request) {
        ScopeContext scope = currentScope.get();
        deactivate.handle(new DeactivateCustomer(customerId, request.getReason()), scope);
        return ResponseEntity.ok(card(customerId, scope));
    }

    @Override
    public ResponseEntity<CustomerCard> recaptureNic(
            String idempotencyKey, UUID customerId, RecaptureNicRequest request) {
        ScopeContext scope = currentScope.get();
        recapture.handle(new RecaptureNic(customerId, request.getNic(), request.getReason()), scope);
        return ResponseEntity.ok(card(customerId, scope));
    }

    private CustomerCard card(UUID customerId, ScopeContext scope) {
        return CustomerResponses.card(queries.card(customerId, scope).orElseThrow());
    }

    @Override
    public ResponseEntity<LoyaltyCustomerResponse> registerLoyaltyCustomer(
            String idempotencyKey, RegisterLoyaltyCustomerRequest request) {
        ScopeContext scope = currentScope.get();
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                request.getCustomerId(),
                request.getDisplayName(),
                request.getDisplayNameSi(),
                request.getDisplayNameTa(),
                request.getLanguage() != null ? request.getLanguage().getValue() : null,
                request.getPhone(),
                request.getNic(),
                request.getRegisteredLocationId(),
                request.getRegionCode(),
                request.getRegionalMemberNo(),
                request.getLoyaltyTier() != null ? request.getLoyaltyTier().getValue() : "STANDARD",
                request.getAllLocationsPermitted() != null ? request.getAllLocationsPermitted() : true,
                request.getPermittedLocationIds() != null ? Set.copyOf(request.getPermittedLocationIds()) : Set.of(),
                request.getRegionalAttributes());

        UUID loyaltyId = registerLoyalty.handle(cmd, scope);
        LoyaltyCustomerView view = loyaltyQueries
                .getLoyaltyCustomer(request.getCustomerId() != null ? request.getCustomerId() : cmd.customerId(), scope)
                .or(() -> loyaltyQueries.lookupByPhone(request.getPhone(), scope))
                .orElseThrow();

        return ResponseEntity.created(URI.create("/v1/customers/loyalty/" + loyaltyId))
                .body(CustomerResponses.toLoyaltyCustomerResponse(view));
    }

    @Override
    public ResponseEntity<LoyaltyCustomerResponse> lookupLoyaltyCustomer(
            String phone, String nic, UUID customerId, String memberNo) {
        ScopeContext scope = currentScope.get();
        Optional<LoyaltyCustomerView> view = Optional.empty();
        if (customerId != null) {
            view = loyaltyQueries.getLoyaltyCustomer(customerId, scope);
        } else if (memberNo != null && !memberNo.isBlank()) {
            view = loyaltyQueries.lookupByMemberNo(memberNo, scope);
        } else if (phone != null && !phone.isBlank()) {
            view = loyaltyQueries.lookupByPhone(phone, scope);
        } else if (nic != null && !nic.isBlank()) {
            view = loyaltyQueries.lookupByNic(nic, scope);
        }
        return view.map(v -> ResponseEntity.ok(CustomerResponses.toLoyaltyCustomerResponse(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<LoyaltyEligibilityResponse> checkLoyaltyEligibility(String identifier, UUID locationId) {
        ScopeContext scope = currentScope.get();
        UUID effectiveLocation = locationId != null ? locationId : scope.locationId();
        LoyaltyEligibility eligibility = loyaltyQueries.checkEligibility(identifier, effectiveLocation, scope);
        return ResponseEntity.ok(CustomerResponses.toLoyaltyEligibilityResponse(eligibility));
    }

    @Override
    public ResponseEntity<LoyaltyCustomerResponse> updateRegionalRegistration(
            String idempotencyKey, UUID customerId, UpdateRegionalRegistrationRequest request) {
        ScopeContext scope = currentScope.get();
        UpdateRegionalRegistration cmd = new UpdateRegionalRegistration(
                customerId,
                request.getRegisteredLocationId(),
                request.getRegionCode(),
                request.getRegionalMemberNo(),
                request.getLoyaltyTier() != null ? request.getLoyaltyTier().getValue() : "STANDARD",
                request.getStatus() != null ? request.getStatus().getValue() : "ACTIVE",
                request.getAllLocationsPermitted() != null ? request.getAllLocationsPermitted() : true,
                request.getPermittedLocationIds() != null ? Set.copyOf(request.getPermittedLocationIds()) : Set.of(),
                request.getRegionalAttributes());

        updateRegional.handle(cmd, scope);
        LoyaltyCustomerView view =
                loyaltyQueries.getLoyaltyCustomer(customerId, scope).orElseThrow();
        return ResponseEntity.ok(CustomerResponses.toLoyaltyCustomerResponse(view));
    }

    // --- PGL-M3-03: Loyalty Rewards & Insurance Endpoints ---

    @Override
    public ResponseEntity<LoyaltyRewardPolicyResponse> configureLoyaltyPolicy(
            String idempotencyKey, ConfigureLoyaltyPolicyRequest request) {
        ScopeContext scope = currentScope.get();
        ConfigureLoyaltyPolicy cmd = new ConfigureLoyaltyPolicy(
                request.getPolicyName(),
                request.getMonthlySpendThreshold(),
                request.getMinMembershipMonths(),
                request.getMonthlyRewardPoints(),
                request.getInsuranceBenefitAmount(),
                request.getActive());

        configurePolicy.handle(cmd, scope);
        LoyaltyRewardPolicyView view =
                loyaltyQueries.getActiveLoyaltyPolicy(scope).orElseThrow();
        return ResponseEntity.ok(CustomerResponses.toLoyaltyRewardPolicyResponse(view));
    }

    @Override
    public ResponseEntity<LoyaltyRewardPolicyResponse> getActiveLoyaltyPolicy() {
        ScopeContext scope = currentScope.get();
        return loyaltyQueries
                .getActiveLoyaltyPolicy(scope)
                .map(v -> ResponseEntity.ok(CustomerResponses.toLoyaltyRewardPolicyResponse(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<RecordLoyaltySpendResponse> recordLoyaltySpend(
            String idempotencyKey, RecordLoyaltySpendRequest request) {
        ScopeContext scope = currentScope.get();
        RecordLoyaltySpend cmd = new RecordLoyaltySpend(
                request.getLoyaltyId(),
                request.getLocationId(),
                request.getSpendDate(),
                request.getAmount(),
                request.getReceiptNumber());

        UUID spendId = recordSpend.handle(cmd, scope);
        RecordLoyaltySpendResponse res = new RecordLoyaltySpendResponse();
        res.setSpendId(spendId);
        res.setLoyaltyId(request.getLoyaltyId());
        res.setAmount(request.getAmount());
        return ResponseEntity.status(201).body(res);
    }

    @Override
    public ResponseEntity<CalculateMonthlyLoyaltyRewardsResponse> calculateMonthlyLoyaltyRewards(
            String idempotencyKey, CalculateMonthlyLoyaltyRewardsRequest request) {
        ScopeContext scope = currentScope.get();
        CalculateMonthlyLoyaltyRewards cmd =
                new CalculateMonthlyLoyaltyRewards(request.getPeriodMonth(), request.getCustomerId());

        List<UUID> rewardIds = calculateRewards.handle(cmd, scope);
        CalculateMonthlyLoyaltyRewardsResponse res = new CalculateMonthlyLoyaltyRewardsResponse();
        res.setPeriodMonth(request.getPeriodMonth());
        res.setEvaluatedCount(rewardIds.size());
        res.setRewardIds(rewardIds);
        return ResponseEntity.ok(res);
    }

    @Override
    public ResponseEntity<List<LoyaltyMonthlyRewardResponse>> getLoyaltyRewards(UUID loyaltyId, String periodMonth) {
        ScopeContext scope = currentScope.get();
        if (periodMonth != null && !periodMonth.isBlank()) {
            return loyaltyQueries
                    .getMonthlyReward(loyaltyId, periodMonth, scope)
                    .map(CustomerResponses::toLoyaltyMonthlyRewardResponse)
                    .map(List::of)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.ok(List.of()));
        }
        List<LoyaltyMonthlyRewardResponse> list = loyaltyQueries.listMonthlyRewards(loyaltyId, scope).stream()
                .map(CustomerResponses::toLoyaltyMonthlyRewardResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    @Override
    public ResponseEntity<LoyaltyInsuranceEligibilityResponse> checkInsuranceEligibility(
            UUID loyaltyId, LocalDate asOfDate) {
        ScopeContext scope = currentScope.get();
        LoyaltyInsuranceEligibilityView view = loyaltyQueries.evaluateInsuranceEligibility(loyaltyId, asOfDate, scope);
        return ResponseEntity.ok(CustomerResponses.toLoyaltyInsuranceEligibilityResponse(view));
    }

    @Override
    public ResponseEntity<LoyaltyInsuranceClaimResponse> submitInsuranceClaim(
            String idempotencyKey, SubmitInsuranceClaimRequest request) {
        ScopeContext scope = currentScope.get();
        SubmitInsuranceClaim cmd = new SubmitInsuranceClaim(
                request.getLoyaltyId(),
                request.getClaimType() != null ? request.getClaimType().getValue() : null,
                request.getIncidentDate(),
                request.getClaimantRelationship(),
                request.getSupportingDocumentRef());

        UUID claimId = submitClaim.handle(cmd, scope);
        LoyaltyInsuranceClaimView view =
                loyaltyQueries.getInsuranceClaim(claimId, scope).orElseThrow();
        return ResponseEntity.status(201).body(CustomerResponses.toLoyaltyInsuranceClaimResponse(view));
    }

    @Override
    public ResponseEntity<LoyaltyInsuranceClaimResponse> getInsuranceClaim(UUID claimId) {
        ScopeContext scope = currentScope.get();
        return loyaltyQueries
                .getInsuranceClaim(claimId, scope)
                .map(v -> ResponseEntity.ok(CustomerResponses.toLoyaltyInsuranceClaimResponse(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<LoyaltyInsuranceClaimResponse> updateInsuranceClaimStatus(
            String idempotencyKey, UUID claimId, UpdateInsuranceClaimStatusRequest request) {
        ScopeContext scope = currentScope.get();
        UpdateInsuranceClaimStatus cmd = new UpdateInsuranceClaimStatus(
                claimId,
                request.getStatus() != null ? request.getStatus().getValue() : null,
                request.getRejectionReason());

        updateClaimStatus.handle(cmd, scope);
        LoyaltyInsuranceClaimView view =
                loyaltyQueries.getInsuranceClaim(claimId, scope).orElseThrow();
        return ResponseEntity.ok(CustomerResponses.toLoyaltyInsuranceClaimResponse(view));
    }
}
