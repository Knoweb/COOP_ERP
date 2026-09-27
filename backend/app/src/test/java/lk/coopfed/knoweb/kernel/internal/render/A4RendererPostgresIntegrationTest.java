package lk.coopfed.knoweb.kernel.internal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ReportRendered;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * K-06b, the A4 renderer (19A section 6; its Tests row: "golden PDF rendering of a trilingual
 * invoice compared by text extraction"). The kernel's {@code document-a4} template filled with
 * Sinhala, Tamil and English is printed by a real headless Chromium; the PDF is A4, its text
 * extracts to the strings that went in, and the three Noto families are embedded in it. The
 * rendering is stored, audited and published once; the guards refuse what they must.
 *
 * <p>Needs a Chromium: {@code CHROMIUM_PATH}, or one of the usual places (the pipeline points it
 * at the worker image's Chromium, {@code tools/chromium-in-docker.sh}). Without one the test is skipped on a laptop and fails in the pipeline
 * ({@code CI} set), so it cannot pass there by being skipped. Tagged {@code chromium} as well,
 * so it can be selected or left out on its own.
 */
@Tag("chromium")
@Import(MemoryObjectStore.class)
class A4RendererPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190a906-0000-7000-8000-000000000001");
    private static final UUID STRANGER = UUID.fromString("0190a906-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190a906-0000-7000-8000-000000000010");

    // A shop name in Sinhala and Tamil, words with vowel signs and conjuncts, which a renderer
    // without real shaping gets wrong, and the Latin of the same invoice.
    private static final String SINHALA = "සමුපකාර සමිතිය";
    private static final String SINHALA_ITEM = "සහල් කිලෝ";
    private static final String TAMIL = "கூட்டுறவுச் சங்கம்";
    private static final String TAMIL_ITEM = "அரிசி கிலோ";
    private static final String LATIN = "Federation of Cooperatives";

    private static final String CHROMIUM = findChromium();

    @DynamicPropertySource
    static void renderer(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.report.enabled", () -> "true");
        registry.add("coop-erp.report.chromium-path", () -> CHROMIUM == null ? "chromium" : CHROMIUM);
    }

    @Autowired
    A4Renderer renderer;

    @Autowired
    MemoryObjectStore store;

    @BeforeEach
    void needsChromium() {
        if (CHROMIUM == null && System.getenv("CI") != null) {
            throw new IllegalStateException("No Chromium on this runner: set CHROMIUM_PATH");
        }
        assumeTrue(CHROMIUM != null, "No Chromium here; set CHROMIUM_PATH to run the A4 renderer test");
        store.objects.clear();
        kernel.reset();
    }

    @Test
    void aTrilingualDocumentPrintsToAnA4PdfWithItsTextAndTheThreeFontsEmbedded() throws IOException {
        A4Renderer.Rendered rendered = renderer.render(A4Renderer.DOCUMENT_A4, invoice(), Locale.of("si"), own(ENTITY));

        assertThat(rendered.objectKey()).isEqualTo("reports/" + ENTITY + "/" + rendered.reportId() + ".pdf");
        byte[] pdf = store.objects.get(rendered.objectKey());
        assertThat(pdf).isNotNull().hasSize((int) rendered.sizeBytes());
        assertThat(MemoryObjectStore.sha256(pdf)).isEqualTo(rendered.sha256Hex());
        assertThat(rendered.url().toString()).contains(rendered.objectKey());

        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDPage page = document.getPage(0);
            assertThat(page.getMediaBox().getWidth()).isCloseTo(PDRectangle.A4.getWidth(), within());
            assertThat(page.getMediaBox().getHeight()).isCloseTo(PDRectangle.A4.getHeight(), within());

            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains(LATIN)
                    .contains(SINHALA)
                    .contains(SINHALA_ITEM)
                    .contains(TAMIL)
                    .contains(TAMIL_ITEM)
                    .contains("INV-0001")
                    .contains("1,250.00")
                    // A label of the template, from the catalogue in the requested language.
                    .contains("ඒකක මිල");

            List<String> fonts = new ArrayList<>();
            for (PDPage each : document.getPages()) {
                for (var name : each.getResources().getFontNames()) {
                    PDFont font = each.getResources().getFont(name);
                    assertThat(font.isEmbedded())
                            .as("font %s embedded", font.getName())
                            .isTrue();
                    fonts.add(font.getName());
                }
            }
            assertThat(fonts).anyMatch(f -> f.contains("NotoSansSinhala"));
            assertThat(fonts).anyMatch(f -> f.contains("NotoSansTamil"));
            assertThat(fonts).anyMatch(f -> f.contains("NotoSans") && !f.contains("Sinhala") && !f.contains("Tamil"));
        }

        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo(A4RenderService.AUDIT_RENDERED);
            assertThat(record.subject().id()).isEqualTo(rendered.reportId());
        });
        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(ReportRendered.class, event -> {
            assertThat(event.reportId()).isEqualTo(rendered.reportId());
            assertThat(event.templateId()).isEqualTo(A4Renderer.DOCUMENT_A4);
            assertThat(event.language()).isEqualTo("si");
            assertThat(event.objectKey()).isEqualTo(rendered.objectKey());
            assertThat(event.ownerEntityId()).isEqualTo(ENTITY);
            assertThat(event.contentHash()).isEqualTo(rendered.sha256Hex());
        });

        // A fresh link for the entity's own scope; never for another entity.
        assertThat(renderer.presignGet(rendered.objectKey(), own(ENTITY))).isNotNull();
        refused(() -> renderer.presignGet(rendered.objectKey(), own(STRANGER)), "report.scope_mismatch");
        refused(
                () -> renderer.presignGet("objects/m2catalogue/" + ENTITY + "/x.pdf", own(ENTITY)),
                "report.scope_mismatch");
    }

    @Test
    void theGuardsRefuseBeforeAnythingIsPrintedOrStored() {
        refused(() -> renderer.render("../secret", Map.of(), Locale.ENGLISH, own(ENTITY)), "report.template_invalid");
        refused(() -> renderer.render(null, Map.of(), Locale.ENGLISH, own(ENTITY)), "report.template_invalid");
        refused(
                () -> renderer.render("no-such-template", Map.of(), Locale.ENGLISH, own(ENTITY)),
                "report.template_unknown");
        ScopeContext view = new ScopeContext(
                USER,
                null,
                ENTITY,
                List.of(new Scope(ENTITY, null)),
                new Scope(ENTITY, null),
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
        refused(
                () -> renderer.render(A4Renderer.DOCUMENT_A4, invoice(), Locale.ENGLISH, view),
                "report.scope_mismatch");
        refused(
                () -> renderer.render(A4Renderer.DOCUMENT_A4, invoice(), Locale.ENGLISH, null),
                "report.scope_mismatch");

        assertThat(store.objects).isEmpty();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theDataIsEscapedAndThePageLoadsNothingFromOutside() {
        A4RenderService service = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(renderer);
        String html =
                service.html(A4Renderer.DOCUMENT_A4, Map.of("title", "<script>alert(1)</script>"), Locale.ENGLISH);
        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
        assertThat(html).contains("Content-Security-Policy").contains("default-src 'none'");
    }

    @Test
    void aPdfOverTheLimitOrAStuckBrowserIsRefused() {
        ChromiumPdf chromium = new ChromiumPdf(CHROMIUM);
        String page = "<!DOCTYPE html><html><head></head><body><p>" + LATIN + "</p></body></html>";
        refused(() -> chromium.print(page, Duration.ofSeconds(60), 10), "report.too_large");
        refused(() -> chromium.print(page, Duration.ofMillis(1), 10_000_000), "report.timeout");
        refused(
                () -> new ChromiumPdf("no-such-browser-binary").print(page, Duration.ofSeconds(5), 1000),
                "report.failed");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Map<String, Object> invoice() {
        return Map.of(
                "title", "ඉන්වොයිසිය / விலைப்பட்டியல் / Invoice",
                "number", "INV-0001",
                "date", "27/09/2026",
                "issuer", Map.of("title", LATIN, "lines", "Colombo 07"),
                "from", Map.of("title", LATIN, "lines", "Colombo 07"),
                "to", Map.of("title", SINHALA, "lines", TAMIL),
                "lines",
                        List.of(
                                Map.of(
                                        "description",
                                        SINHALA_ITEM,
                                        "quantity",
                                        "10.000",
                                        "unitPrice",
                                        "125.00",
                                        "amount",
                                        "1,250.00"),
                                Map.of(
                                        "description",
                                        TAMIL_ITEM,
                                        "quantity",
                                        "5.000",
                                        "unitPrice",
                                        "130.00",
                                        "amount",
                                        "650.00")),
                "totals", List.of(Map.of("label", "Total", "value", "Rs 1,900.00")));
    }

    private static org.assertj.core.data.Offset<Float> within() {
        return org.assertj.core.data.Offset.offset(2f);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(messageId));
    }

    private static ScopeContext own(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER, null, entity, List.of(active), active, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static String findChromium() {
        String configured = System.getenv("CHROMIUM_PATH");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Stream.of(
                        "/usr/bin/chromium",
                        "/usr/bin/chromium-browser",
                        "/usr/bin/google-chrome",
                        "/usr/bin/google-chrome-stable",
                        "C:/Program Files/Google/Chrome/Application/chrome.exe",
                        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
                .filter(p -> Files.isExecutable(Path.of(p)))
                .findFirst()
                .orElse(null);
    }
}
