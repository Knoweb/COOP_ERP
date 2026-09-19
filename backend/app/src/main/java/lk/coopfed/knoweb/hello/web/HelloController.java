package lk.coopfed.knoweb.hello.web;

import lk.coopfed.knoweb.hello.api.GreetingQueries;
import lk.coopfed.knoweb.hello.api.GreetingView;
import lk.coopfed.knoweb.hello.api.RegisterGreeting;
import lk.coopfed.knoweb.hello.web.generated.GreetingResponse;
import lk.coopfed.knoweb.hello.web.generated.HelloApi;
import lk.coopfed.knoweb.hello.web.generated.RegisterGreetingRequest;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * HTTP surface of the hello module. It implements {@link HelloApi}, the interface generated
 * from openapi/hello.yaml at build time (see "OpenAPI first" in app/build.gradle.kts), and so
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
class HelloController implements HelloApi {

    private final Handles<RegisterGreeting, UUID> registerGreeting;
    private final GreetingQueries queries;
    private final CurrentScope currentScope;

    HelloController(
            Handles<RegisterGreeting, UUID> registerGreeting,
            GreetingQueries queries,
            CurrentScope currentScope) {
        this.registerGreeting = registerGreeting;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<GreetingResponse> registerGreeting(
            String idempotencyKey,
            RegisterGreetingRequest request) {
        ScopeContext scope = currentScope.get();

        UUID id = registerGreeting.handle(
                new RegisterGreeting(request.getTextEn(), request.getTextSi(), request.getTextTa()),
                scope);

        GreetingView created = queries.find(id, scope).orElseThrow();
        return ResponseEntity
                .created(URI.create(HelloApi.PATH_LIST_GREETINGS + "/" + id))
                .body(toResponse(created));
    }

    @Override
    public ResponseEntity<List<GreetingResponse>> listGreetings() {
        List<GreetingResponse> greetings = queries.list(currentScope.get()).stream()
                .map(HelloController::toResponse)
                .toList();
        return ResponseEntity.ok(greetings);
    }

    @Override
    public ResponseEntity<GreetingResponse> getGreeting(UUID id) {
        return queries.find(id, currentScope.get())
                .map(HelloController::toResponse)
                .map(ResponseEntity::ok)
                // Not 403: whether the greeting exists is itself something another entity must not learn.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The small mapper of 17A section 4.4: from the module's own view to the generated response.
     * The owner entity is left out on purpose; the caller knows its own scope.
     */
    private static GreetingResponse toResponse(GreetingView view) {
        return new GreetingResponse(
                view.id(),
                view.textEn(),
                GreetingResponse.StatusEnum.fromValue(view.status()),
                view.createdAt())
                .textSi(view.textSi())
                .textTa(view.textTa());
    }
}
