package lk.coopfed.knoweb.hello.web;

import lk.coopfed.knoweb.hello.api.GreetingQueries;
import lk.coopfed.knoweb.hello.api.GreetingView;
import lk.coopfed.knoweb.hello.api.RegisterGreeting;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP surface of the hello module: one method per operation of openapi/hello.yaml, with
 * the operationId as the method name. A controller only translates: request to command,
 * view to response. It holds no rule, opens no transaction and knows nothing about tenants.
 *
 * <p>What it does not do, because the kernel does it for every controller: read the
 * Idempotency-Key, build the {@link ScopeContext} (declare the parameter and it arrives),
 * turn a ProblemException into an error response, or handle CORS.
 *
 * <p>Deviation from 17A section 12: the guide has the controller implement a HelloApi
 * interface generated from the slice. Server-side generation is not in the build yet
 * (S0-06), so this class is written by hand against the slice; when the generator lands it
 * gains {@code implements HelloApi} and the request and response records below go away.
 */
@RestController
@RequestMapping("/v1/hello/greetings")
class HelloController {

    /** Request body of registerGreeting; mirrors RegisterGreetingRequest in the slice. */
    record RegisterGreetingRequest(String textEn, String textSi, String textTa) {
    }

    /** Mirrors GreetingResponse in the slice. The owner entity is not exposed: the caller knows its own scope. */
    record GreetingResponse(UUID id, String textEn, String textSi, String textTa, String status, Instant createdAt) {

        static GreetingResponse of(GreetingView view) {
            return new GreetingResponse(view.id(), view.textEn(), view.textSi(), view.textTa(), view.status(),
                    view.createdAt());
        }
    }

    private final Handles<RegisterGreeting, UUID> registerGreeting;
    private final GreetingQueries queries;

    HelloController(Handles<RegisterGreeting, UUID> registerGreeting, GreetingQueries queries) {
        this.registerGreeting = registerGreeting;
        this.queries = queries;
    }

    @PostMapping
    ResponseEntity<GreetingResponse> registerGreeting(
            @RequestBody RegisterGreetingRequest request,
            ScopeContext scope) {
        UUID id = registerGreeting.handle(
                new RegisterGreeting(request.textEn(), request.textSi(), request.textTa()),
                scope);

        GreetingView created = queries.find(id, scope).orElseThrow();
        return ResponseEntity
                .created(URI.create("/v1/hello/greetings/" + id))
                .body(GreetingResponse.of(created));
    }

    @GetMapping
    List<GreetingResponse> listGreetings(ScopeContext scope) {
        return queries.list(scope).stream()
                .map(GreetingResponse::of)
                .toList();
    }

    @GetMapping("/{id}")
    ResponseEntity<GreetingResponse> getGreeting(@PathVariable UUID id, ScopeContext scope) {
        return queries.find(id, scope)
                .map(GreetingResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
