package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.internal.stub.ProblemResponses;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Wave 2, TWK-24 (decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-kernel-defaults-and-limits.md
 * (3)): every body on the sync API is bounded and counted, gzip or plain, with a Content-Length
 * or chunked. A chunked body (no Content-Length, which is what the old path counted as zero) over
 * the limit is refused 413 after one byte past the limit; one within it reaches the controller
 * with the bytes read as {@link SyncBodyFilter#WIRE_BYTES}.
 */
class SyncBodyFilterTest {

    private static final long PLAIN_LIMIT = 1_000;

    private final ConfigRegistry config = mock(ConfigRegistry.class);
    private final CurrentScope scope = mock(CurrentScope.class);
    private final Messages messages = mock(Messages.class);
    private final SyncBodyFilter filter = new SyncBodyFilter(
            new ProblemResponses(messages), new ObjectMapper(), new SyncSettings(config), scope, 4_000, PLAIN_LIMIT);

    SyncBodyFilterTest() {
        when(messages.text(any(), any(), any())).thenReturn(new Messages.Text("refused", false));
        when(scope.get()).thenThrow(new IllegalStateException("no token in a unit test"));
        when(config.getInt(eq("sync.batch.max_bytes"), any(), anyInt())).thenReturn(500);
    }

    @Test
    void aPlainChunkedBodyWithinTheLimitIsReadOnceAndCounted() throws Exception {
        byte[] body = "{\"events\":[]}".repeat(50).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = chunked(body);
        AtomicReference<HttpServletRequest> passed = new AtomicReference<>();
        FilterChain chain = (req, res) -> passed.set((HttpServletRequest) req);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getAttribute(SyncBodyFilter.WIRE_BYTES)).isEqualTo((long) body.length);
        assertThat(passed.get().getInputStream().readAllBytes()).isEqualTo(body);
    }

    @Test
    void aPlainChunkedBodyOverTheLimitIsRefusedBeforeTheControllerReadsIt() throws Exception {
        MockHttpServletRequest request = chunked(new byte[(int) PLAIN_LIMIT + 500]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("sync.batch_too_large");
        assertThat(reached.get()).isFalse();
    }

    @Test
    void aGzipBodyIsCountedAsSentAndInflated() throws Exception {
        byte[] plain = "{\"events\":[]}".repeat(40).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(plain);
        MockHttpServletRequest request = chunked(compressed);
        request.addHeader("Content-Encoding", "gzip");
        AtomicReference<HttpServletRequest> passed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set((HttpServletRequest) req));

        assertThat(request.getAttribute(SyncBodyFilter.WIRE_BYTES)).isEqualTo((long) compressed.length);
        assertThat(passed.get().getInputStream().readAllBytes()).isEqualTo(plain);
    }

    @Test
    void aGetOrAPathOutsideTheSyncApiIsLeftAlone() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/v1/sync/locations/x/snapshot");
        MockHttpServletRequest elsewhere = new MockHttpServletRequest("POST", "/v1/catalogue/skus");
        elsewhere.setContent(new byte[(int) PLAIN_LIMIT * 2]);
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {get, elsewhere}) {
            AtomicReference<HttpServletRequest> passed = new AtomicReference<>();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set((HttpServletRequest) req));
            assertThat(passed.get()).isSameAs(request);
            assertThat(request.getAttribute(SyncBodyFilter.WIRE_BYTES)).isNull();
        }
    }

    /** A request whose body has no Content-Length: what a chunked transfer looks like to the servlet. */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/v1/sync/devices/0190a800-0000-7000-8000-000000000301/batches");
        request.setContent(body);
        request.removeHeader("Content-Length");
        request.addHeader("Transfer-Encoding", "chunked");
        request.setPreferredLocales(java.util.List.of(Locale.ENGLISH));
        return request;
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(plain);
        }
        return out.toByteArray();
    }
}
