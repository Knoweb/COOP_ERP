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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.internal.IdempotencyRequestAttributes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER_KEY = "Idempotency-Key";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ProblemResponses problems;
    private final ObjectMapper mapper;

    public IdempotencyFilter(ProblemResponses problems, ObjectMapper mapper) {
        this.problems = problems;
        this.mapper = mapper;
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
            request.setAttribute(IdempotencyRequestAttributes.REQUEST_HASH, sha256(prefix, partsOf(request)));
            chain.doFilter(request, response);
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();

        String requestHash = sha256(prefix, body);

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

    private static String sha256(String prefix, byte[] body) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            digest.update(prefix.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest.digest(body));

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
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
