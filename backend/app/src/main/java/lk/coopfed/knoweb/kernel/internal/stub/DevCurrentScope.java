package lk.coopfed.knoweb.kernel.internal.stub;

import jakarta.servlet.http.HttpServletRequest;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 17A stub of {@link CurrentScope}: the scope comes from request headers, so the stack works
 * before login does. 19A ticket K-02 replaces this class with one that reads the verified
 * token; controllers do not change. The headers, all optional:
 *
 * <pre>
 *   X-Scope-Entity     the entity to act in (kept by 19A as the active-scope choice)
 *   X-Scope-Location   the location within it
 *   X-Dev-User         the acting user id                      (stub only)
 *   X-Dev-Scope-Class  OWN, PARTY, FEDERATION_VIEW, ...        (stub only; default OWN)
 *   X-Correlation-Id   groups everything one user action causes
 *   Accept-Language    en, si or ta
 * </pre>
 *
 * No X-Scope-Entity means no active scope, and row-level security then returns nothing.
 *
 * <p>The context is built once and kept as a request attribute, under the name the 19A scope
 * filter will use, so every call within one request gets the same object and the same
 * correlation id.
 */
@Component
public class DevCurrentScope implements CurrentScope {

    static final String HEADER_ENTITY = "X-Scope-Entity";
    static final String HEADER_LOCATION = "X-Scope-Location";
    static final String HEADER_USER = "X-Dev-User";
    static final String HEADER_CLASS = "X-Dev-Scope-Class";
    static final String HEADER_CORRELATION = "X-Correlation-Id";

    static final String REQUEST_ATTRIBUTE = ScopeContext.class.getName();

    private final DevScopeContextProvider provider;

    public DevCurrentScope(DevScopeContextProvider provider) {
        this.provider = provider;
    }

    @Override
    public ScopeContext get() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException(
                    "CurrentScope.get() was called outside an HTTP request."
                            + " A job or a consumer builds its ScopeContext explicitly and passes it on.");
        }
        HttpServletRequest request = attributes.getRequest();

        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof ScopeContext known) {
            return known;
        }
        ScopeContext scope = fromHeaders(request);
        request.setAttribute(REQUEST_ATTRIBUTE, scope);
        return scope;
    }

    private ScopeContext fromHeaders(HttpServletRequest request) {
        String language = request.getHeader("Accept-Language") == null
                ? null
                : request.getLocale().getLanguage();
        try {
            return provider.fromHeaders(
                    request.getHeader(HEADER_USER),
                    request.getHeader(HEADER_ENTITY),
                    request.getHeader(HEADER_LOCATION),
                    request.getHeader(HEADER_CLASS),
                    request.getHeader(HEADER_CORRELATION),
                    language);
        } catch (IllegalArgumentException e) {
            // A header that is not a UUID, or a policy class that does not exist.
            throw new ProblemException("scope.invalid");
        }
    }
}
