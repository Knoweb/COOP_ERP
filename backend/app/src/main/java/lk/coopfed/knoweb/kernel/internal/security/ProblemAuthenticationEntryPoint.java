package lk.coopfed.knoweb.kernel.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.internal.stub.ProblemResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * A request the resource server turns away is answered as every other refusal of this API: a
 * problem document (17A section 4.4) with the {@code WWW-Authenticate} header RFC 6750 asks
 * for. 401 {@code token.invalid} for a token it refuses (bad signature, wrong issuer, expired,
 * malformed); 401 {@code auth.required} for a request that presents none. Without this,
 * Spring Security answers an empty 401 that the web client cannot read.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponses problems;
    private final ObjectMapper mapper;

    public ProblemAuthenticationEntryPoint(ProblemResponses problems, ObjectMapper mapper) {
        this.problems = problems;
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException cause)
            throws IOException {
        boolean refusedToken = cause instanceof InvalidBearerTokenException
                || (cause.getCause() != null && cause.getCause() instanceof InvalidBearerTokenException);
        String code = refusedToken ? "token.invalid" : "auth.required";
        ProblemDetail problem = problems.toProblem(new ProblemException(code), request.getLocale());
        response.setStatus(problem.getStatus());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, refusedToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), problem);
    }
}
