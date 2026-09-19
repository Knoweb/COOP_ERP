package lk.coopfed.knoweb.kernel.internal.stub;

import jakarta.servlet.http.HttpServletRequest;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller method simply declare a {@link ScopeContext} parameter.
 *
 * <p>17A stub: the scope comes from request headers, so the stack works before login does.
 * 19A ticket K-02 replaces this class with one that reads the verified token; controllers
 * do not change. The headers, all optional:
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
 */
@Component
public class DevScopeArgumentResolver implements HandlerMethodArgumentResolver {

    static final String HEADER_ENTITY = "X-Scope-Entity";
    static final String HEADER_LOCATION = "X-Scope-Location";
    static final String HEADER_USER = "X-Dev-User";
    static final String HEADER_CLASS = "X-Dev-Scope-Class";
    static final String HEADER_CORRELATION = "X-Correlation-Id";

    private final DevScopeContextProvider provider;

    public DevScopeArgumentResolver(DevScopeContextProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ScopeContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
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
