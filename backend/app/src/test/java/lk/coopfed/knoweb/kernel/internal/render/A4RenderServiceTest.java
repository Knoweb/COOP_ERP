package lk.coopfed.knoweb.kernel.internal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;

/**
 * K-06b: an instance without the worker role (no Chromium in its image) refuses to render,
 * whatever it is asked, and says why (19A section 6; decided 27 Sep 2026, PR #145).
 */
class A4RenderServiceTest {

    @Test
    void anInstanceWithoutTheWorkerRoleDoesNotRender() {
        A4RenderService web = new A4RenderService(false, "chromium", 15, null, null, null, null, null, null, null);
        ScopeContext ctx = ScopeContext.dev(UUID.randomUUID(), UUID.randomUUID(), null);

        assertThatThrownBy(() -> web.render(A4Renderer.DOCUMENT_A4, Map.of("title", "x"), Locale.ENGLISH, ctx))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("report.renderer_unavailable"));
    }
}
