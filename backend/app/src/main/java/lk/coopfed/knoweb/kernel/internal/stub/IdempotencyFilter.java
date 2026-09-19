package lk.coopfed.knoweb.kernel.internal.stub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Makes every mutating API call safe to repeat (AGENTS.md: every mutating operation carries
 * an Idempotency-Key). A till on a bad connection, or a user double-clicking, sends the same
 * request twice; the second one must not register a second greeting.
 *
 * <ul>
 *   <li>no Idempotency-Key header: 400 {@code idempotency.key_required}</li>
 *   <li>key seen before with the same body: the stored response is returned, the handler
 *       does not run again</li>
 *   <li>key seen before with a different body: 422 {@code idempotency.request_mismatch}</li>
 *   <li>otherwise the request runs, and a successful (2xx) response is stored under the key</li>
 * </ul>
 *
 * Module code does nothing for this: no handler or controller reads the key.
 *
 * <p>17A stub: it sits in front of the controllers and uses the in-memory
 * {@link IdempotencyStore}, so it holds for one instance only. 19A ticket K-03 moves the
 * check into the command interceptor and the store into the kernel.idempotency_key table,
 * so that every instance answers alike. Two identical requests arriving at the same moment
 * are not yet serialised; the table's unique key does that in 19A.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // after the CORS filter, before everything else
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER_KEY = "Idempotency-Key";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyStore store;
    private final ProblemResponses problems;
    private final ObjectMapper mapper;

    public IdempotencyFilter(
            IdempotencyStore store,
            ProblemResponses problems,
            ObjectMapper mapper) {
        this.store = store;
        this.problems = problems;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String keyValue = request.getHeader(HEADER_KEY);
        if (keyValue == null || keyValue.isBlank()) {
            writeProblem(request, response, new ProblemException("idempotency.key_required"));
            return;
        }

        // The body can be read once only, so it is read here and replayed to the controller.
        byte[] body = request.getInputStream().readAllBytes();
        IdempotencyStore.Key key = new IdempotencyStore.Key(
                keyValue,
                userOf(request),
                sha256(request.getMethod() + " " + request.getRequestURI() + "\n", body));

        Optional<IdempotencyStore.StoredResult> earlier;
        try {
            earlier = store.find(key);
        } catch (ProblemException mismatch) {
            writeProblem(request, response, mismatch);
            return;
        }

        if (earlier.isPresent()) {
            response.setStatus(earlier.get().status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(earlier.get().body());
            return;
        }

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        chain.doFilter(new ReplayableRequest(request, body), captured);

        if (captured.getStatus() >= 200 && captured.getStatus() < 300) {
            store.store(
                    key,
                    new IdempotencyStore.StoredResult(
                            captured.getStatus(),
                            new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8)));
        }
        captured.copyBodyToResponse();
    }

    private void writeProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            ProblemException e) throws IOException {
        ProblemDetail problem = problems.toProblem(e, request.getLocale());
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), problem);
    }

    /** Keys are per user. In the stub the user is a header; a bad value simply means "no user". */
    private static UUID userOf(HttpServletRequest request) {
        String user = request.getHeader(DevCurrentScope.HEADER_USER);
        try {
            return user == null || user.isBlank() ? null : UUID.fromString(user);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sha256(String prefix, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** Gives the controller the body this filter has already read. */
    private static final class ReplayableRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayableRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // Synchronous reads only; nothing to notify.
                }
            };
        }
    }
}
