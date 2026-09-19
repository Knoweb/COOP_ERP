package lk.coopfed.knoweb.kernel.api;

/**
 * Gives a controller the {@link ScopeContext} of the request it is serving: who is calling,
 * for which entity and location, in which language.
 *
 * <p>Why a controller asks for it instead of receiving it as a parameter: a controller
 * implements an interface generated from its OpenAPI slice, so its method signatures are
 * fixed by the slice and have no room for an extra parameter. The kernel resolves the scope
 * once per request (doc 19 section 1; 19A section 1: "token, ScopeContext, request
 * attribute") and hands out the same object every time it is asked within that request.
 *
 * <pre>
 *   public ResponseEntity&lt;...&gt; registerGreeting(String idempotencyKey, RegisterGreetingRequest body) {
 *       ScopeContext scope = currentScope.get();
 *       UUID id = registerGreeting.handle(new RegisterGreeting(...), scope);
 * </pre>
 *
 * Only controllers use this. A handler or a query receives the scope as a parameter, which
 * keeps them free of any web dependency and lets a job or a test call them with a scope it
 * built itself.
 */
public interface CurrentScope {

    /**
     * The scope of the current HTTP request. Never null: a caller without an active scope
     * gets a context whose {@link ScopeContext#hasActiveScope()} is false, and row-level
     * security then shows that caller nothing.
     *
     * @throws ProblemException      {@code scope.invalid} when the request names a scope that
     *                               cannot be understood
     * @throws IllegalStateException when there is no HTTP request, for example in a scheduled
     *                               job; such code builds its ScopeContext explicitly
     */
    ScopeContext get();
}
