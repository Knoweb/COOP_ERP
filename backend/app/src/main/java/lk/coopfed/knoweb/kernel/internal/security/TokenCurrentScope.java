package lk.coopfed.knoweb.kernel.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
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
 *   X-Scope-Entity     the entity to act in now (one of the caller's scopes)
 *   X-Scope-Location   the location within it
 *   X-Correlation-Id   groups everything one user action causes
 *   Accept-Language    en, si or ta, when the token names no language
 * </pre>
 *
 * <p>A request without a token reaches this only where the security chain lets one through
 * (nothing under {@code /v1}); it gets a context with no user and no scope: nothing to read,
 * nothing to run.
 */
@Component
public class TokenCurrentScope implements CurrentScope {

    public static final String HEADER_ENTITY = "X-Scope-Entity";
    public static final String HEADER_LOCATION = "X-Scope-Location";
    public static final String HEADER_CORRELATION = "X-Correlation-Id";

    static final String REQUEST_ATTRIBUTE = ScopeContext.class.getName();

    private final JwtClaimsMapper mapper;

    public TokenCurrentScope(JwtClaimsMapper mapper) {
        this.mapper = mapper;
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
        String correlation = request.getHeader(HEADER_CORRELATION);

        if (authentication instanceof JwtAuthenticationToken token) {
            return mapper.map(
                    token.getToken(),
                    request.getHeader(HEADER_ENTITY),
                    request.getHeader(HEADER_LOCATION),
                    correlation,
                    requestLocale);
        }

        UUID correlationId;
        try {
            correlationId = correlation == null || correlation.isBlank() ? Ids.next() : UUID.fromString(correlation);
        } catch (IllegalArgumentException notAUuid) {
            correlationId = Ids.next();
        }
        return new ScopeContext(
                null,
                null,
                null,
                List.of(),
                null,
                PolicyClass.NONE,
                Set.of(),
                null,
                requestLocale == null ? Locale.ENGLISH : Locale.forLanguageTag(requestLocale.getLanguage()),
                correlationId);
    }
}
