package lk.coopfed.knoweb.kernel.internal.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ReportRendered;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.attachment.ObjectStore;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.messageresolver.IMessageResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * The kernel's {@link A4Renderer} (19A section 6; K-06b). Thymeleaf fills the template, the
 * kernel puts the fonts and the content security policy in its head, {@link ChromiumPdf} prints
 * it, the store keeps it under {@code reports/{entity}/{reportId}.pdf}, and one transaction of
 * its own records {@code REPORT_RENDERED} and publishes {@code report.rendered.v1}.
 *
 * <p>Rendering is switched on by {@code coop-erp.report.enabled}, true in the worker role's
 * settings only ({@code application-worker.yml}), because only the worker image carries Chromium
 * (decided 27 Sep 2026, PR #145).
 */
@Service
class A4RenderService implements A4Renderer {

    private static final Logger log = LoggerFactory.getLogger(A4RenderService.class);

    static final String AUDIT_RENDERED = "REPORT_RENDERED";
    static final String TIMEOUT = "report.render.timeout_seconds";
    static final String MAX_BYTES = "report.render.max_bytes";
    static final String MAX_HTML_BYTES = "report.render.max_html_bytes";
    static final String MAX_CONCURRENT = "render.max_concurrent";

    private static final Pattern TEMPLATE_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final String TEMPLATES = "reports/templates/";
    private static final String CONTENT_TYPE = "application/pdf";

    /** The web client's three families (fontsource 5.3.0 files), regular and bold. */
    private static final List<String[]> FONTS = List.of(
            new String[] {"Noto Sans", "noto-sans-latin", "400"},
            new String[] {"Noto Sans", "noto-sans-latin", "700"},
            new String[] {"Noto Sans", "noto-sans-latin-ext", "400"},
            new String[] {"Noto Sans", "noto-sans-latin-ext", "700"},
            new String[] {"Noto Sans Sinhala", "noto-sans-sinhala-sinhala", "400"},
            new String[] {"Noto Sans Sinhala", "noto-sans-sinhala-sinhala", "700"},
            new String[] {"Noto Sans Tamil", "noto-sans-tamil-tamil", "400"},
            new String[] {"Noto Sans Tamil", "noto-sans-tamil-tamil", "700"});

    /** Nothing but the page itself: inline styles, the embedded fonts and inline images. */
    private static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; font-src data:; img-src data:";

    private final boolean enabled;
    private final ChromiumPdf chromium;
    private final RenderSlots slots = new RenderSlots();
    private final ObjectStore store;
    private final ConfigRegistry config;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final SystemScope system;
    private final Clock clock;
    private final Duration presignValidity;
    private final TemplateEngine engine;
    private final String head;

    A4RenderService(
            @Value("${coop-erp.report.enabled:false}") boolean enabled,
            @Value("${coop-erp.report.chromium-path:chromium}") String chromiumPath,
            @Value("${coop-erp.object-store.presign-minutes:15}") long presignMinutes,
            ObjectStore store,
            ConfigRegistry config,
            AuditFacade audit,
            EventPublisher events,
            SystemScope system,
            Clock clock,
            Messages messages) {
        this.enabled = enabled;
        this.chromium = new ChromiumPdf(chromiumPath);
        this.store = store;
        this.config = config;
        this.audit = audit;
        this.events = events;
        this.system = system;
        this.clock = clock;
        this.presignValidity = Duration.ofMinutes(presignMinutes);
        this.engine = engine(messages);
        this.head = head();
    }

    @Override
    public Rendered render(String templateId, Map<String, Object> data, Locale language, ScopeContext ctx) {
        if (!enabled) {
            throw new ProblemException("report.renderer_unavailable");
        }
        if (templateId == null || !TEMPLATE_ID.matcher(templateId).matches()) {
            throw new ProblemException("report.template_invalid");
        }
        if (getClass().getClassLoader().getResource(TEMPLATES + templateId + ".html") == null) {
            throw new ProblemException("report.template_unknown", Map.of("templateId", templateId));
        }
        if (ctx == null || ctx.policyClass() != PolicyClass.OWN || ctx.entityId() == null) {
            throw new ProblemException("report.scope_mismatch", Map.of("templateId", templateId));
        }
        Locale lang = language == null ? ctx.locale() : language;

        String html = html(templateId, data == null ? Map.of() : data, lang);
        long maxHtml = config.getInt(MAX_HTML_BYTES, ctx, 5 * 1024 * 1024);
        int htmlBytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (htmlBytes > maxHtml) {
            throw new ProblemException("report.too_large", Map.of("size", htmlBytes, "maxBytes", maxHtml));
        }
        Duration timeout = Duration.ofSeconds(config.getInt(TIMEOUT, ctx, 60));
        long maxBytes = config.getInt(MAX_BYTES, ctx, 10 * 1024 * 1024);

        long started = System.nanoTime();
        // At most render.max_concurrent browsers at once on this instance; a render that cannot
        // start within one render's timeout is answered render.busy (wave 2, TWK-28).
        byte[] pdf = slots.run(
                config.getInt(MAX_CONCURRENT, ctx, 2), timeout, () -> chromium.print(html, timeout, maxBytes));
        log.info(
                "Rendered {} ({}) in {} ms, {} bytes",
                templateId,
                lang.getLanguage(),
                (System.nanoTime() - started) / 1_000_000,
                pdf.length);

        UUID reportId = Ids.next();
        String key = "reports/" + ctx.entityId() + "/" + reportId + ".pdf";
        String hash = sha256(pdf);
        store.put(key, CONTENT_TYPE, pdf);

        system.inOwnTransaction(ctx, () -> {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("templateId", templateId);
            after.put("language", lang.getLanguage());
            after.put("objectKey", key);
            after.put("sizeBytes", pdf.length);
            after.put("contentHash", hash);
            audit.record(AUDIT_RENDERED, Subject.of("report", reportId), null, after, ctx);
            events.publish(new ReportRendered(
                    reportId, templateId, lang.getLanguage(), key, pdf.length, hash, ctx.entityId()));
            return null;
        });
        return new Rendered(
                reportId,
                key,
                store.presignGet(key, CONTENT_TYPE, presignValidity),
                clock.instant().plus(presignValidity),
                pdf.length,
                hash);
    }

    @Override
    public URI presignGet(String objectKey, ScopeContext ctx) {
        if (ctx == null || ctx.entityId() == null || !isReportOf(objectKey, ctx.entityId())) {
            throw new ProblemException("report.scope_mismatch", Map.of("objectKey", String.valueOf(objectKey)));
        }
        return store.presignGet(objectKey, CONTENT_TYPE, presignValidity);
    }

    @Override
    public URI presignGetOfParty(String objectKey, UUID ownerEntityId, ScopeContext ctx) {
        // The module read the document naming this key under its own row-level security; here
        // only an active scope and the key's owner are checked.
        if (ctx == null
                || !ctx.hasActiveScope()
                || ctx.policyClass() == PolicyClass.NONE
                || ownerEntityId == null
                || !isReportOf(objectKey, ownerEntityId)) {
            throw new ProblemException("report.scope_mismatch", Map.of("objectKey", String.valueOf(objectKey)));
        }
        return store.presignGet(objectKey, CONTENT_TYPE, presignValidity);
    }

    private static boolean isReportOf(String objectKey, UUID entityId) {
        return objectKey != null
                && objectKey.startsWith("reports/" + entityId + "/")
                && objectKey.endsWith(".pdf")
                && !objectKey.contains("..");
    }

    /** The template filled, with the kernel's head put first: charset, policy, page size, fonts. */
    String html(String templateId, Map<String, Object> data, Locale lang) {
        Context context = new Context(lang);
        context.setVariable("data", data);
        context.setVariable("lang", lang.getLanguage());
        String body = engine.process(templateId, context);
        int at = body.indexOf("<head>");
        if (at < 0) {
            throw new IllegalStateException("Template " + templateId + " has no <head>");
        }
        at += "<head>".length();
        return body.substring(0, at) + head + body.substring(at);
    }

    private static TemplateEngine engine(Messages messages) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATES);
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageResolver(new CatalogueMessages(messages));
        return engine;
    }

    /** {@code #{id}} in a template reads the kernel's catalogue (en, si, ta) in the page's language. */
    private record CatalogueMessages(Messages messages) implements IMessageResolver {

        @Override
        public String getName() {
            return "kernel-catalogue";
        }

        @Override
        public Integer getOrder() {
            return 0;
        }

        @Override
        public String resolveMessage(
                ITemplateContext context, Class<?> origin, String key, Object[] messageParameters) {
            return messages.t(key, context.getLocale(), messageParameters == null ? new Object[0] : messageParameters);
        }

        @Override
        public String createAbsentMessageRepresentation(
                ITemplateContext context, Class<?> origin, String key, Object[] messageParameters) {
            return key;
        }
    }

    private static String head() {
        StringBuilder css = new StringBuilder();
        for (String[] font : FONTS) {
            css.append("@font-face{font-family:'")
                    .append(font[0])
                    .append("';font-style:normal;font-weight:")
                    .append(font[2])
                    .append(";src:url(data:font/woff2;base64,")
                    .append(Base64.getEncoder().encodeToString(font(font[1] + "-" + font[2] + "-normal.woff2")))
                    .append(") format('woff2');}");
        }
        css.append("@page{size:A4;margin:15mm 15mm 18mm 15mm}")
                .append("html{-webkit-print-color-adjust:exact;print-color-adjust:exact}")
                .append("body{font-family:'Noto Sans','Noto Sans Sinhala','Noto Sans Tamil',sans-serif;")
                .append("font-size:10pt;margin:0}");
        return "<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\"><style>"
                + css + "</style>";
    }

    private static byte[] font(String file) {
        try (InputStream in = A4RenderService.class.getClassLoader().getResourceAsStream("reports/fonts/" + file)) {
            if (in == null) {
                throw new IllegalStateException("The bundled font reports/fonts/" + file + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
