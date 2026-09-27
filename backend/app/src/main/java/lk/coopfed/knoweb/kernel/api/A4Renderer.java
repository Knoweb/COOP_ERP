package lk.coopfed.knoweb.kernel.api;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Printable documents (19A section 6, "A4Renderer"; K-06b, decided 27 Sep 2026 in CR-19A-8): an
 * HTML template filled with a module's data and printed to an A4 PDF by a headless Chromium,
 * with the three Noto families the web client uses (Noto Sans, Noto Sans Sinhala, Noto Sans
 * Tamil) embedded, so Sinhala and Tamil are shaped as a browser shapes them (DR-3).
 *
 * <p><b>Where it runs.</b> Chromium is in the worker image only. {@link #render} works on an
 * instance whose role includes {@code worker} and refuses elsewhere
 * ({@code report.renderer_unavailable}): a module prints from what runs on the worker, a
 * scheduled job (K-12, M3's label job) or an event consumer (an M4 document issued on the web
 * role and printed by a consumer of its event). {@link #presignGet} works on every role, so the
 * web role hands out the link of a PDF the worker stored.
 *
 * <p><b>Templates.</b> A template is a Thymeleaf HTML file on the class path at
 * {@code reports/templates/{templateId}.html}; a module adds its own there (M3's shelf label).
 * The kernel supplies {@code document-a4}, a generic trading document (title, number, date, two
 * parties, lines, totals, notes) that M4's invoice and notes can fill before they have a template
 * of their own; its model is described in {@code reports/templates/README.md}. The data is
 * escaped by the template ({@code th:text}); the page may load nothing from outside itself (a
 * content security policy is put on every page), and the fonts are the kernel's.
 *
 * <p><b>Limits</b> from the register: {@code report.render.timeout_seconds},
 * {@code report.render.max_bytes} (the PDF), {@code report.render.max_html_bytes} (the page).
 */
public interface A4Renderer {

    /** The kernel's generic A4 trading document, for any module to fill. */
    String DOCUMENT_A4 = "document-a4";

    /**
     * A rendered PDF in the object store.
     *
     * @param reportId     the id of this rendering; the object key ends with it
     * @param objectKey    {@code reports/{entity}/{reportId}.pdf}
     * @param url          a pre-signed GET of the PDF, served as a download
     * @param urlExpiresAt when the URL stops working; {@link #presignGet} gives a fresh one
     * @param sizeBytes    the size of the PDF
     * @param sha256Hex    its SHA-256, lower-case hex
     */
    record Rendered(UUID reportId, String objectKey, URI url, Instant urlExpiresAt, long sizeBytes, String sha256Hex) {}

    /**
     * Renders the template with the data in the language, stores the PDF under the entity of the
     * scope, audits {@code REPORT_RENDERED} and publishes {@link ReportRendered}
     * ({@code report.rendered.v1}) in a transaction of its own. Takes seconds: call it outside a
     * transaction, from a job or a consumer on the worker role.
     *
     * @param templateId {@code [a-z0-9-]+}, a file under {@code reports/templates/}
     * @param data       the template's model; the template reads it as {@code ${data.x}}
     * @param language   the language of the printed labels ({@code #{...}} in the template reads
     *                   the kernel's message catalogue in it, and {@code ${lang}} is its code)
     * @param ctx        an OWN scope: the PDF belongs to its entity
     * @throws ProblemException {@code report.renderer_unavailable}, {@code report.template_invalid},
     *                          {@code report.template_unknown}, {@code report.scope_mismatch},
     *                          {@code report.too_large}, {@code report.timeout}, {@code report.failed}
     */
    Rendered render(String templateId, Map<String, Object> data, Locale language, ScopeContext ctx);

    /**
     * A fresh pre-signed GET of a PDF rendered earlier, for a scope of its entity.
     *
     * @throws ProblemException {@code report.scope_mismatch} (another entity's PDF, or a key that
     *                          is not a report's)
     */
    URI presignGet(String objectKey, ScopeContext ctx);
}
