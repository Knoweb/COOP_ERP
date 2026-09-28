package lk.coopfed.knoweb.m7customers.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.AmendCustomer;
import lk.coopfed.knoweb.m7customers.api.ChangePhone;
import lk.coopfed.knoweb.m7customers.api.DeactivateCustomer;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.query.CustomerQueries;
import lk.coopfed.knoweb.m7customers.web.generated.AmendCustomerRequest;
import lk.coopfed.knoweb.m7customers.web.generated.ChangePhoneRequest;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerCard;
import lk.coopfed.knoweb.m7customers.web.generated.CustomerSummary;
import lk.coopfed.knoweb.m7customers.web.generated.CustomersApi;
import lk.coopfed.knoweb.m7customers.web.generated.ReasonRequest;
import lk.coopfed.knoweb.m7customers.web.generated.RegisterCustomerRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer register of the society office (27A section 5): translates requests to commands. */
@RestController
class CustomerController implements CustomersApi {

    static final int DEFAULT_LIMIT = 50;

    private final Handles<RegisterCustomer, UUID> register;
    private final Handles<AmendCustomer, UUID> amend;
    private final Handles<ChangePhone, UUID> changePhone;
    private final Handles<DeactivateCustomer, UUID> deactivate;
    private final CustomerQueries queries;
    private final CurrentScope currentScope;

    CustomerController(
            Handles<RegisterCustomer, UUID> register,
            Handles<AmendCustomer, UUID> amend,
            Handles<ChangePhone, UUID> changePhone,
            Handles<DeactivateCustomer, UUID> deactivate,
            CustomerQueries queries,
            CurrentScope currentScope) {
        this.register = register;
        this.amend = amend;
        this.changePhone = changePhone;
        this.deactivate = deactivate;
        this.queries = queries;
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
                        request.getConsents().stream()
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
    public ResponseEntity<CustomerCard> getCustomer(UUID customerId) {
        return queries.card(customerId, currentScope.get())
                .map(CustomerResponses::card)
                .map(ResponseEntity::ok)
                // Not 403: whether the customer exists is itself something another society must not learn.
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

    private CustomerCard card(UUID customerId, ScopeContext scope) {
        return CustomerResponses.card(queries.card(customerId, scope).orElseThrow());
    }
}
