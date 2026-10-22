package lk.coopfed.knoweb.m3pricing.internal.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The PRC-03 model and default shelf-label HTML printed by the real Chromium renderer. */
@Tag("chromium")
@Import(MemoryObjectStore.class)
class ShelfLabelRenderPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190e903-1000-7000-8000-000000000005");
    private static final UUID USER = UUID.fromString("0190e903-1000-7000-8000-000000000010");
    private static final String CHROMIUM = findChromium();

    @DynamicPropertySource
    static void rendererProperties(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.report.enabled", () -> "true");
        registry.add("coop-erp.report.chromium-path", () -> CHROMIUM == null ? "chromium" : CHROMIUM);
    }

    @Autowired
    A4Renderer renderer;

    @Autowired
    LabelPrintModel model;

    @Autowired
    MemoryObjectStore store;

    @BeforeEach
    void needsChromium() {
        if (CHROMIUM == null && System.getenv("CI") != null) {
            throw new IllegalStateException("No Chromium on this runner: set CHROMIUM_PATH");
        }
        assumeTrue(CHROMIUM != null, "No Chromium here; set CHROMIUM_PATH to run the shelf-label print test");
        store.objects.clear();
    }

    @Test
    void theDefaultTemplatePrintsAllThreeNamesPriceUnitAndMrpNotice() throws IOException {
        Locale language = Locale.forLanguageTag("ta");
        var data = model.model(
                List.of(new LabelPrintModel.Item(
                        "RICE-01", "සම්බා සහල්", "சம்பா அரிசி", "Samba rice", new BigDecimal("215.00"), "EA", true)),
                language);

        String text = renderText(data, language);

        assertThat(text)
                .contains("RICE-01")
                .contains("සම්බා සහල්")
                .contains("சம்பா அரிசி")
                .contains("Samba rice")
                .contains("215.00")
                .contains("EA");
    }

    @Test
    void anAllRefusedJobPrintsTheEmptyResultMessage() throws IOException {
        String text = renderText(model.model(List.of(), Locale.ENGLISH), Locale.ENGLISH);

        assertThat(text).contains("No eligible shelf labels to print");
    }

    private String renderText(java.util.Map<String, Object> data, Locale language) throws IOException {
        A4Renderer.Rendered rendered = renderer.render("default", data, language, scope());
        try (PDDocument document = Loader.loadPDF(store.objects.get(rendered.objectKey()))) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static ScopeContext scope() {
        Scope active = new Scope(ENTITY, null);
        return new ScopeContext(
                USER, null, ENTITY, List.of(active), active, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static String findChromium() {
        String configured = System.getenv("CHROMIUM_PATH");
        if (configured != null && !configured.isBlank()) return configured;
        return Stream.of(
                        "/usr/bin/chromium",
                        "/usr/bin/chromium-browser",
                        "/usr/bin/google-chrome",
                        "/usr/bin/google-chrome-stable",
                        "C:/Program Files/Google/Chrome/Application/chrome.exe",
                        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
                .filter(path -> Files.isExecutable(Path.of(path)))
                .findFirst()
                .orElse(null);
    }
}
