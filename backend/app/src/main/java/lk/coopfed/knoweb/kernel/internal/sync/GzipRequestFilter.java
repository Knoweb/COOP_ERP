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
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.stub.ProblemResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Inflates a request body sent with {@code Content-Encoding: gzip} on the sync API (doc 32 S6:
 * "everything is gzip-compressed"; the weakest shop uplink is about 0.5 Mbps) before the
 * controller reads its JSON. The size as sent is kept as the request attribute
 * {@link #WIRE_BYTES}, which the batch limit of doc 32 DR-1 ("2 MB compressed") is measured
 * against.
 *
 * <p>Two bounds, so that a body cannot fill the memory before anything else refuses it: the
 * compressed read stops at the DR-1 limit ({@code sync.batch.max_bytes} of the register) and
 * the inflated body at {@code coop-erp.sync.max-inflated-bytes}; past either the answer is 413
 * {@code sync.batch_too_large}. The filter runs after the security chain and on the sync paths
 * only, so an anonymous client cannot make the server inflate anything (the enrolment call,
 * which carries no token yet, is bounded the same way), and a compressed body on any other path
 * is left as it was sent.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 3)
class GzipRequestFilter extends OncePerRequestFilter {

    static final String WIRE_BYTES = GzipRequestFilter.class.getName() + ".wireBytes";

    private static final String SYNC_PATHS = "/v1/sync/";

    private final ProblemResponses problems;
    private final ObjectMapper mapper;
    private final SyncSettings settings;
    private final CurrentScope currentScope;
    private final long maxInflatedBytes;

    GzipRequestFilter(
            ProblemResponses problems,
            ObjectMapper mapper,
            SyncSettings settings,
            CurrentScope currentScope,
            @Value("${coop-erp.sync.max-inflated-bytes:67108864}") long maxInflatedBytes) {
        this.problems = problems;
        this.mapper = mapper;
        this.settings = settings;
        this.currentScope = currentScope;
        this.maxInflatedBytes = maxInflatedBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String encoding = request.getHeader("Content-Encoding");
        if (encoding == null || !encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(SYNC_PATHS);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long maxCompressedBytes = maxCompressedBytes();
        // One byte more than the limit is read, and no more: enough to know the body is too
        // large, never the whole of what a client chose to send.
        byte[] compressed =
                request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE, maxCompressedBytes + 1));
        if (compressed.length > maxCompressedBytes) {
            refuse(request, response, new ProblemException("sync.batch_too_large"));
            return;
        }
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

    /**
     * The DR-1 limit for the caller: the register's value in the device's scope when the token
     * names one, else (the enrolment call, which has no token yet) the federation-wide value or
     * the register's default. The ingestor measures the batch against the same key afterwards.
     */
    private long maxCompressedBytes() {
        ScopeContext scope;
        try {
            scope = currentScope.get();
        } catch (RuntimeException noToken) {
            scope = null;
        }
        try {
            return Math.max(1, settings.maxBatchBytes(scope));
        } catch (RuntimeException unreadable) {
            return SyncSettings.DR1_MAX_BATCH_BYTES;
        }
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
