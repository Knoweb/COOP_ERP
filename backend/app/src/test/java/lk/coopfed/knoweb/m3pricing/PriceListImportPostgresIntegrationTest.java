package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.ImportPriceListLines;
import lk.coopfed.knoweb.m3pricing.api.PriceListImportReport;
import lk.coopfed.knoweb.m3pricing.api.PriceListLinesImported;
import lk.coopfed.knoweb.m3pricing.api.PriceListLinesSet;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.ImportPriceListLinesHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for task CAT-13 (Import price list lines from a file, with a screen).
 * Explicitly verifies: "A price list sheet fills a draft list".
 */
@Import(PinnedClock.class)
class PriceListImportPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID DISTRIBUTOR = UUID.fromString("0190e750-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e750-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e750-0000-7000-8000-000000000100");

    @Autowired
    CreatePriceListHandler create;

    @Autowired
    SetLinesHandler setLines;

    @Autowired
    ImportPriceListLinesHandler importHandler;

    @Autowired
    PublishPriceListHandler publish;

    @Autowired
    PricingQueries queries;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID rice = Ids.next();
    private final UUID sugar = Ids.next();
    private final UUID flour = Ids.next();

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3IMPORT', 'M3 import tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        insertSku(admin, rice, "M3-IMP-RICE", "Rice 1kg", "SHARED");
        insertSku(admin, sugar, "M3-IMP-SUGAR", "Sugar 1kg", "SHARED");
        insertSku(admin, flour, "M3-IMP-FLOUR", "Flour 1kg", "SHARED");

        admin.update(
                "insert into catalogue.sku_barcode (barcode_id, sku_id, barcode, owner_entity_id, status)"
                        + " values (?, ?, '4790001112223', ?, 'ACTIVE')",
                Ids.next(),
                flour,
                FEDERATION);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void aPriceListSheetFillsADraftList() {
        UUID listId = create.handle(new CreatePriceList("TRADE", "Wholesale Draft List"), own(FEDERATION));
        kernel.reset();

        String csv =
                """
                sku,uom,tier,price
                M3-IMP-RICE,EA,0,135.0000
                M3-IMP-SUGAR,EA,0,240.5000
                """;

        PriceListImportReport report =
                importHandler.handle(new ImportPriceListLines(listId, csv, null, true), own(FEDERATION));

        assertThat(report.saved()).isTrue();
        assertThat(report.totalRows()).isEqualTo(2);
        assertThat(report.importedLines()).isEqualTo(2);
        assertThat(report.errorRows()).isZero();
        assertThat(report.problems()).isEmpty();

        // Verification: Populates draft list, but does not activate/publish the prices
        assertThat(queries.getPriceList(listId, own(FEDERATION))).get().satisfies(view -> {
            assertThat(view.status()).isEqualTo("DRAFT");
            assertThat(view.version()).isEqualTo(1);
        });

        List<PriceListLineView> lines = queries.lines(listId, own(FEDERATION));
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(PriceListLineView::skuId).containsExactlyInAnyOrder(rice, sugar);

        PriceListLineView riceLine =
                lines.stream().filter(l -> l.skuId().equals(rice)).findFirst().orElseThrow();
        assertThat(riceLine.price()).isEqualByComparingTo("135.0000");
        assertThat(riceLine.tierFromQty()).isEqualByComparingTo("0");
        assertThat(riceLine.uomCode()).isEqualTo("EA");

        // Auditing and events verification
        assertThat(audit("PRICELIST_LINES_IMPORTED")).singleElement().satisfies(r -> {
            assertThat(r.subject().id()).isEqualTo(listId);
            Map<?, ?> details = (Map<?, ?>) r.after();
            assertThat(details.get("lines")).isEqualTo(2);
            assertThat(details.get("replaced")).isEqualTo(true);
        });

        assertThat(events(PriceListLinesImported.class))
                .containsExactly(new PriceListLinesImported(listId, FEDERATION, 2, true));
        assertThat(events(PriceListLinesSet.class)).containsExactly(new PriceListLinesSet(listId, FEDERATION, 2));
    }

    @Test
    void importResolvesBarcodesAndDefaultBaseUom() {
        UUID listId = create.handle(new CreatePriceList("TRADE", "Barcode List"), own(FEDERATION));
        kernel.reset();

        String csv = """
                code,price
                4790001112223,195.00
                """;

        PriceListImportReport report =
                importHandler.handle(new ImportPriceListLines(listId, csv, null, true), own(FEDERATION));

        assertThat(report.saved()).isTrue();
        assertThat(report.importedLines()).isEqualTo(1);

        List<PriceListLineView> lines = queries.lines(listId, own(FEDERATION));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).skuId()).isEqualTo(flour);
        assertThat(lines.get(0).uomCode()).isEqualTo("EA");
        assertThat(lines.get(0).price()).isEqualByComparingTo("195.0000");
    }

    @Test
    void attemptsToImportIntoPublishedListRefused() {
        UUID listId = create.handle(new CreatePriceList("TRADE", "To Publish"), own(FEDERATION));
        setLines.handle(
                new SetLines(listId, List.of(new SetLines.Line(rice, "EA", BigDecimal.ZERO, new BigDecimal("100")))),
                own(FEDERATION));
        publish.handle(new PublishPriceList(listId, PinnedClock.TODAY.plusDays(1)), own(FEDERATION));
        kernel.reset();

        String csv = "sku,price\nM3-IMP-RICE,150.00\n";
        assertThatThrownBy(
                        () -> importHandler.handle(new ImportPriceListLines(listId, csv, null, true), own(FEDERATION)))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m3.price_list.not_draft");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void unauthorizedBranchOrEntityRefusedByRlsAndScope() {
        UUID listId = create.handle(new CreatePriceList("TRADE", "Federation Own"), own(FEDERATION));
        kernel.reset();

        String csv = "sku,price\nM3-IMP-RICE,150.00\n";
        assertThatThrownBy(
                        () -> importHandler.handle(new ImportPriceListLines(listId, csv, null, true), own(DISTRIBUTOR)))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m3.price_list.not_found");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void invalidRowsAreReportedAndLeaveDraftUntouched() {
        UUID listId = create.handle(new CreatePriceList("TRADE", "Initial Draft"), own(FEDERATION));
        setLines.handle(
                new SetLines(listId, List.of(new SetLines.Line(rice, "EA", BigDecimal.ZERO, new BigDecimal("100")))),
                own(FEDERATION));
        kernel.reset();

        String csv =
                """
                sku,price
                UNKNOWN-SKU-999,150.00
                M3-IMP-SUGAR,-10.00
                """;

        PriceListImportReport report =
                importHandler.handle(new ImportPriceListLines(listId, csv, null, true), own(FEDERATION));

        assertThat(report.saved()).isFalse();
        assertThat(report.errorRows()).isGreaterThan(0);
        assertThat(report.problems()).isNotEmpty();

        // Previous lines remain untouched
        List<PriceListLineView> lines = queries.lines(listId, own(FEDERATION));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).skuId()).isEqualTo(rice);
        assertThat(lines.get(0).price()).isEqualByComparingTo("100");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private static void insertSku(JdbcTemplate admin, UUID skuId, String code, String name, String status) {
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
                        + " values (?, ?, ?, ?, ?, ?, ?, 'EA', false, ?)",
                skuId,
                code,
                FEDERATION,
                status,
                name,
                name,
                name,
                TAX_CATEGORY);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private List<KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
