package lk.coopfed.knoweb.kernel.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.stub.DevScopeContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link CurrentScope} from the verified token (19A section 2): the resource server has
 * checked the signature, the issuer and the times before this runs, and {@link JwtClaimsMapper}
 * turns the claims into the context. The context is built once per request and kept as a
 * request attribute, so every call within one request sees the same object and the same
 * correlation id.
 *
 * <pre>
 *   Authorization      Bearer token of the provider
 *   X-Scope-Entity     the entity to act in now (one of the token's scopes)
 *   X-Scope-Location   the location within it
 *   X-Correlation-Id   groups everything one user action causes
 *   Accept-Language    en, si or ta, when the token names no language
 * </pre>
 *
 * <p><b>Until the second K-02 pull request:</b> a request that presents no token may still
 * name its user and scope in the 17A development headers (X-Dev-User, X-Dev-Scope-Class,
 * X-Scope-Entity, X-Scope-Location), while {@code coop-erp.security.dev-headers} is true. The
 * web client sends both, the token and the headers, and the token wins when it is there. The
 * module tests send the headers alone. The second pull request deletes the headers on both
 * sides, {@link DevScopeContextProvider} with them, and a request without a token then gets
 * no user and no scope: nothing to read and nothing to run.
 */
@Component
public class TokenCurrentScope implements CurrentScope {

    private static final Logger log = LoggerFactory.getLogger(TokenCurrentScope.class);

    public static final String HEADER_ENTITY = "X-Scope-Entity";
    public static final String HEADER_LOCATION = "X-Scope-Location";
    public static final String HEADER_CORRELATION = "X-Correlation-Id";

    static final String DEV_HEADER_USER = "X-Dev-User";
    static final String DEV_HEADER_CLASS = "X-Dev-Scope-Class";

    static final String REQUEST_ATTRIBUTE = ScopeContext.class.getName();

    private final JwtClaimsMapper mapper;
    private final DevScopeContextProvider devHeaders;
    private final boolean devHeadersAccepted;

    public TokenCurrentScope(
            JwtClaimsMapper mapper,
            DevScopeContextProvider devHeaders,
            @Value("${coop-erp.security.dev-headers:false}") boolean devHeadersAccepted) {
        this.mapper = mapper;
        this.devHeaders = devHeaders;
        this.devHeadersAccepted = devHeadersAccepted;
        if (devHeadersAccepted) {
            log.warn("A request without a bearer token may name its user in the X-Dev-User header"
                    + " (coop-erp.security.dev-headers=true): development and tests only, never outside");
        }
    }

    @Override
    public ScopeContext get() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("CurrentScope.get() was called outside an HTTP request."
                    + " A job or a consumer builds its ScopeContext explicitly and passes it on.");
        }
        HttpServletRequest request = attributes.getRequest();

        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof ScopeContext known) {
            return known;
        }
        ScopeContext scope = resolve(request);
        request.setAttribute(REQUEST_ATTRIBUTE, scope);
        return scope;
    }

    private ScopeContext resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Locale requestLocale = request.getHeader("Accept-Language") == null ? null : request.getLocale();

        if (authentication instanceof JwtAuthenticationToken token) {
            return mapper.map(
                    token.getToken(),
                    request.getHeader(HEADER_ENTITY),
                    request.getHeader(HEADER_LOCATION),
                    request.getHeader(HEADER_CORRELATION),
                    requestLocale);
        }

        try {
            return devHeaders.fromHeaders(
                    devHeadersAccepted ? request.getHeader(DEV_HEADER_USER) : null,
                    request.getHeader(HEADER_ENTITY),
                    request.getHeader(HEADER_LOCATION),
                    devHeadersAccepted ? request.getHeader(DEV_HEADER_CLASS) : null,
                    request.getHeader(HEADER_CORRELATION),
                    requestLocale == null ? null : requestLocale.getLanguage());
        } catch (IllegalArgumentException e) {
            // A header that is not a UUID, or a policy class that does not exist.
            throw new ProblemException("scope.invalid");
        }
    }
}
