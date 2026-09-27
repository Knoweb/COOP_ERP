package lk.coopfed.knoweb.m4trading.internal.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
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

/**
 * The printed tax invoice as a person reads it: the model InvoicePrintConsumer fills, printed by
 * the kernel's real A4 renderer (headless Chromium) through the generic {@code document-a4}
 * template, and the PDF's text extracted. It names the seller and the buyer by their legal names,
 * and each item by its code and name, in the invoice's language, not by internal ids
 * (fix/demo-display-polish, 28 September 2026). The parties and items come from mocked M1 and M2
 * queries; what is tested is the model and the template together.
 *
 * <p>Needs a Chromium, as the kernel's A4 renderer test does ({@code CHROMIUM_PATH} or a usual
 * place): skipped on a laptop without one, failed in the pipeline ({@code CI} set).
 */
@Tag("chromium")
@Import(MemoryObjectStore.class)
class InvoicePrintRenderPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = UUID.fromString("0190a907-0000-7000-8000-000000000001");
    private static final UUID BUYER = UUID.fromString("0190a907-0000-7000-8000-000000000002");
    private static final UUID RICE = UUID.fromString("0190a907-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190a907-0000-7000-8000-000000000010");

    private static final String CHROMIUM = findChromium();

    @DynamicPropertySource
    static void renderer(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.report.enabled", () -> "true");
        registry.add("coop-erp.report.chromium-path", () -> CHROMIUM == null ? "chromium" : CHROMIUM);
    }

    @Autowired
    A4Renderer renderer;

    @Autowired
    Formats formats;

    @Autowired
    Messages messages;

    @Autowired
    MemoryObjectStore store;

    @BeforeEach
    void needsChromium() {
        if (CHROMIUM == null && System.getenv("CI") != null) {
            throw new IllegalStateException("No Chromium on this runner: set CHROMIUM_PATH");
        }
        assumeTrue(CHROMIUM != null, "No Chromium here; set CHROMIUM_PATH to run the invoice print test");
        store.objects.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePrintedInvoiceNamesTheSellerTheBuyerAndEachItemInTheInvoicesLanguage() throws IOException {
        ScopeContext scope = new ScopeContext(
                USER,
                null,
                SELLER,
                List.of(new Scope(SELLER, null)),
                new Scope(SELLER, null),
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.of("si"),
                null);
        PartyQueries parties = mock(PartyQueries.class);
        when(parties.getEntity(SELLER, scope))
                .thenReturn(Optional.of(entity(SELLER, "FED", "Cooperative Federation", "සමුපකාර සම්මේලනය")));
        when(parties.getEntity(BUYER, scope))
                .thenReturn(Optional.of(
                        entity(BUYER, "D101", "Wayamba Cooperative Distributors", "වයඹ සමුපකාර බෙදාහරින්නෝ")));
        CatalogueQueries catalogue = mock(CatalogueQueries.class);
        SkuView rice = mock(SkuView.class);
        when(rice.skuCode()).thenReturn("SKU-DCTEF6PA");
        when(rice.nameEn()).thenReturn("Samba rice 5 kg");
        when(rice.nameSi()).thenReturn("සම්බා සහල් 5 kg");
        when(catalogue.getSku(RICE, scope)).thenReturn(Optional.of(rice));

        InvoicePrintConsumer consumer = new InvoicePrintConsumer(
                mock(InvoiceQueries.class), parties, catalogue, renderer, formats, messages, mock(Handles.class));
        A4Renderer.Rendered rendered =
                renderer.render(A4Renderer.DOCUMENT_A4, consumer.model(invoice(), scope), scope.locale(), scope);

        try (PDDocument document = Loader.loadPDF(store.objects.get(rendered.objectKey()))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("සමුපකාර සම්මේලනය")
                    .contains("වයඹ සමුපකාර බෙදාහරින්නෝ")
                    .contains("FED")
                    .contains("D101")
                    .contains("SKU-DCTEF6PA")
                    .contains("සම්බා සහල් 5 kg")
                    .contains("FED-INV-0000001")
                    .contains("409876543-7000")
                    .doesNotContain(RICE.toString());
        }
    }

    private static InvoiceView invoice() {
        return new InvoiceView(
                UUID.randomUUID(),
                "FED-INV-0000001",
                "ISSUED",
                null,
                SELLER,
                BUYER,
                "409876543-7000",
                "114455667-7000",
                List.of(),
                LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 10, 28),
                null,
                new BigDecimal("134400.00"),
                new BigDecimal("0.00"),
                new BigDecimal("134400.00"),
                List.of(new InvoiceView.InvoiceLineView(
                        UUID.randomUUID(),
                        1,
                        RICE,
                        null,
                        "EA",
                        new BigDecimal("120"),
                        new BigDecimal("1120.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("134400.00"),
                        null)));
    }

    private static EntityView entity(UUID id, String code, String en, String si) {
        return new EntityView(id, code, null, en, si, null, null, null, null, null, null, null, null, null);
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
