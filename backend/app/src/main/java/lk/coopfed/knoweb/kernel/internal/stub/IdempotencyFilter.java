package lk.coopfed.knoweb.kernel.internal.stub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.internal.IdempotencyRequestAttributes;
import lk.coopfed.knoweb.kernel.internal.security.JwtClaimsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every mutating call under {@code /v1} carries an {@code Idempotency-Key} (19A section 3); the
 * key and a hash of the request (method, path and body) are kept on the request for the
 * command interceptor, which stores them in {@code kernel.idempotency_key} and answers a replay
 * from the stored result, or refuses the same key with another request.
 *
 * <p>The hash is an HMAC-SHA256 under a server secret ({@code coop-erp.idempotency.hash-secret}),
 * not a plain digest: a request body can carry a low-entropy secret (a 4-to-6-digit PIN in a
 * PIN reset), and a plain digest of it in the table or a backup would give the PIN back in at
 * most a million tries. With the secret the stored hash tells nothing. The secret has no
 * default outside development (an issuer on localhost or a .test host, as
 * {@link JwtClaimsMapper#isDevelopmentIssuer}); a deployed environment sets its own, and every
 * instance the same one, since the hash of a replay is compared with the stored one.
 *
 * <p>After the security chain, like the gzip filter before it: the body is read into memory
 * for the hash, and a body nobody has authenticated is not read at all.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 5)
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER_KEY = "Idempotency-Key";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final String HMAC = "HmacSHA256";

    /** Development only: what a developer's stack hashes with when nothing is configured. */
    static final String DEVELOPMENT_SECRET = "coop-erp-idempotency-dev";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final ProblemResponses problems;
    private final ObjectMapper mapper;
    private final byte[] secret;

    public IdempotencyFilter(
            ProblemResponses problems,
            ObjectMapper mapper,
            @Value("${coop-erp.idempotency.hash-secret:}") String secret,
            @Value("${coop-erp.security.oidc.issuer:}") String issuer) {
        this.problems = problems;
        this.mapper = mapper;
        this.secret = secretOrDevelopment(secret, issuer).getBytes(StandardCharsets.UTF_8);
    }

    /** The configured secret; on a development stack a fixed one with a warning; else the start fails. */
    static String secretOrDevelopment(String configured, String issuer) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (JwtClaimsMapper.isDevelopmentIssuer(issuer)) {
            log.warn("coop-erp.idempotency.hash-secret is not set; using the development secret"
                    + " (the issuer is a development one). Set COOP_ERP_IDEMPOTENCY_SECRET outside development.");
            return DEVELOPMENT_SECRET;
        }
        throw new IllegalStateException("coop-erp.idempotency.hash-secret must be set outside development:"
                + " the request hashes of the idempotency store are keyed with it");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {

        return !MUTATING.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader(HEADER_KEY);

        if (key == null || key.isBlank()) {
            writeProblem(request, response, new ProblemException("idempotency.key_required"));
            return;
        }

        String prefix = request.getMethod() + " " + request.getRequestURI() + "\n";
        String contentType = request.getContentType();

        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            // A file upload (21A: bulk registration). The container parses the parts once and keeps
            // them; reading the raw stream here instead would leave nothing for that parse, and
            // Spring would then answer "Required part is not present". So the hash is taken from
            // the parts (name, size, bytes, in order) and the request goes on as it is.
            request.setAttribute(IdempotencyRequestAttributes.KEY, key);
            request.setAttribute(IdempotencyRequestAttributes.REQUEST_HASH, hash(prefix, partsOf(request)));
            chain.doFilter(request, response);
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();

        String requestHash = hash(prefix, body);

        request.setAttribute(IdempotencyRequestAttributes.KEY, key);

        request.setAttribute(IdempotencyRequestAttributes.REQUEST_HASH, requestHash);

        chain.doFilter(new ReplayableRequest(request, body), response);
    }

    /** The parts of a multipart request, in order, as one byte sequence: name, size and content of each. */
    private static byte[] partsOf(HttpServletRequest request) throws IOException, ServletException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : request.getParts()) {
            out.write((part.getName() + ":" + part.getSize() + "\n").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = part.getInputStream()) {
                in.transferTo(out);
            }
            out.write('\n');
        }
        return out.toByteArray();
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, ProblemException error)
            throws IOException {

        ProblemDetail problem = problems.toProblem(error, request.getLocale());

        response.setStatus(problem.getStatus());

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        mapper.writeValue(response.getWriter(), problem);
    }

    private String hash(String prefix, byte[] body) {
        return hmac(secret, prefix, body);
    }

    /** HMAC-SHA256 of the prefix and the body under the secret, as 64 hex characters (the column's check). */
    static String hmac(byte[] secret, String prefix, byte[] body) {

        try {
            Mac mac = Mac.getInstance(HMAC);

            mac.init(new SecretKeySpec(secret, HMAC));

            mac.update(prefix.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(mac.doFinal(body));

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
        }
    }

    private static final class ReplayableRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayableRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {

            ByteArrayInputStream input = new ByteArrayInputStream(body);

            return new ServletInputStream() {

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // synchronous replay
                }
            };
        }
    }
}
