package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.internal.stub.ProblemResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Inflates a request body sent with {@code Content-Encoding: gzip} (doc 32 S6: "everything is
 * gzip-compressed"; the weakest shop uplink is about 0.5 Mbps), before anything reads it: the
 * idempotency filter hashes the body and the controller reads JSON. The size as sent is kept as
 * the request attribute {@link #WIRE_BYTES}, which the batch limit of doc 32 DR-1 ("2 MB
 * compressed") is measured against. A body that inflates beyond
 * {@code coop-erp.sync.max-inflated-bytes} is refused with 413 before it fills the memory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class GzipRequestFilter extends OncePerRequestFilter {

    static final String WIRE_BYTES = GzipRequestFilter.class.getName() + ".wireBytes";

    private final ProblemResponses problems;
    private final ObjectMapper mapper;
    private final long maxInflatedBytes;

    GzipRequestFilter(
            ProblemResponses problems,
            ObjectMapper mapper,
            @Value("${coop-erp.sync.max-inflated-bytes:67108864}") long maxInflatedBytes) {
        this.problems = problems;
        this.mapper = mapper;
        this.maxInflatedBytes = maxInflatedBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String encoding = request.getHeader("Content-Encoding");
        return encoding == null || !encoding.toLowerCase(Locale.ROOT).contains("gzip");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] compressed = request.getInputStream().readAllBytes();
        byte[] body;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxInflatedBytes) {
                    refuse(request, response, new ProblemException("sync.batch_too_large"));
                    return;
                }
                out.write(buffer, 0, read);
            }
            body = out.toByteArray();
        } catch (ZipException | java.io.EOFException notGzip) {
            refuse(request, response, new ProblemException("request.malformed"));
            return;
        }
        request.setAttribute(WIRE_BYTES, (long) compressed.length);
        chain.doFilter(new Inflated(request, body), response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, ProblemException problem)
            throws IOException {
        ProblemDetail detail = problems.toProblem(problem, request.getLocale());
        response.setStatus(detail.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), detail);
    }

    /** The request as if it had been sent uncompressed. */
    private static final class Inflated extends HttpServletRequestWrapper {

        private final byte[] body;

        Inflated(HttpServletRequest request, byte[] body) {
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
                public int read(byte[] b, int off, int len) {
                    return input.read(b, off, len);
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
                    // synchronous
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public String getHeader(String name) {
            if ("Content-Encoding".equalsIgnoreCase(name)) {
                return null;
            }
            if ("Content-Length".equalsIgnoreCase(name)) {
                return Integer.toString(body.length);
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Content-Encoding".equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }
    }
}
