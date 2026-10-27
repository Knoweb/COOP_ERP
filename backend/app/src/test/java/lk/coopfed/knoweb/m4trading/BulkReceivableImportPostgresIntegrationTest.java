package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.api.BulkImportReceivables;
import lk.coopfed.knoweb.m4trading.api.BulkReceivableReport;
import lk.coopfed.knoweb.m4trading.api.OpeningReceivableLoaded;
import lk.coopfed.knoweb.m4trading.api.ReceivablesBulkImported;
import lk.coopfed.knoweb.m4trading.internal.opening.BulkReceivableImporter;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OpeningReceivableQueries;
import lk.coopfed.knoweb.m4trading.query.OpeningReceivableView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for CAT-12 (Inter-entity receivable opening balances).
 *
 * <p>Proves that a distributor's open balance with an MPCS loads with both signatures,
 * creates the authorized invoice document, participates in exposure tracking,
 * and records proper audit logs and domain events.
 */
class BulkReceivableImportPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BulkReceivableImporter importer;

    @Autowired
    private OpeningReceivableQueries queries;

    @Autowired
    private InvoiceQueries invoices;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());

        // Ensure users exist in security.app_user for signature verification
        superuserJdbc()
                .update(
                        """
                insert into security.app_user (user_id, home_entity_id, username, display_name, status)
                values (?, ?, 'seller_officer', 'Seller Officer', 'ACTIVE')
                on conflict (user_id) do nothing
                """,
                        SELLER_USER,
                        SELLER);

        superuserJdbc()
                .update(
                        """
                insert into security.app_user (user_id, home_entity_id, username, display_name, status)
                values (?, ?, 'buyer_manager', 'Buyer Manager', 'ACTIVE')
                on conflict (user_id) do nothing
                """,
                        BUYER_USER,
                        BUYER);

        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void aDistributorsOpenBalanceWithAnMpcsLoadsWithBothSignatures() {
        String csv =
                """
                "Distributor Code","MPCS Code","Opening Balance","Due","Ref No","Seller Signer","Buyer Signer","Notes"
                "D4S","D4B",150000.00,"2026-11-30","LEGACY-INV-001","seller_officer","buyer_manager","Opening balance for legacy trade"
                """;

        BulkImportReceivables cmd = new BulkImportReceivables(SELLER, csv, null, false);
        BulkReceivableReport report = importer.handle(cmd, seller());

        assertThat(report).isNotNull();
        assertThat(report.status()).isEqualTo(BulkReceivableReport.IMPORTED);
        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.importedCount()).isEqualTo(1);
        assertThat(report.documentIds()).hasSize(1);
        assertThat(report.problems()).isEmpty();

        UUID invoiceId = report.documentIds().get(0);

        // Verify opening receivable query by seller
        OpeningReceivableView opening = queries.getOpeningReceivable(invoiceId, seller())
                .orElseThrow(() -> new AssertionError("Opening receivable not found"));

        assertThat(opening.sellerEntityId()).isEqualTo(SELLER);
        assertThat(opening.buyerEntityId()).isEqualTo(BUYER);
        assertThat(opening.amount()).isEqualByComparingTo("150000.00");
        assertThat(opening.referenceNo()).isEqualTo("LEGACY-INV-001");
        assertThat(opening.notes()).isEqualTo("Opening balance for legacy trade");
        assertThat(opening.sellerSignedBy()).isEqualTo(SELLER_USER);
        assertThat(opening.sellerSignedAt()).isNotNull();
        assertThat(opening.buyerSignedBy()).isEqualTo(BUYER_USER);
        assertThat(opening.buyerSignedAt()).isNotNull();

        // Verify opening receivable query by buyer (counterparty read visibility)
        OpeningReceivableView buyerOpening = queries.getOpeningReceivable(invoiceId, buyer())
                .orElseThrow(() -> new AssertionError("Buyer could not read opening receivable"));
        assertThat(buyerOpening.openingReceivableId()).isEqualTo(opening.openingReceivableId());
        assertThat(buyerOpening.amount()).isEqualByComparingTo("150000.00");

        // Verify trading invoice participation
        InvoiceView invoice = invoices.getInvoice(invoiceId, seller())
                .orElseThrow(() -> new AssertionError("Invoice document not found"));
        assertThat(invoice.sellerEntityId()).isEqualTo(SELLER);
        assertThat(invoice.buyerEntityId()).isEqualTo(BUYER);
        assertThat(invoice.grossAmount()).isEqualByComparingTo("150000.00");
        assertThat(invoice.settledAmount()).isEqualByComparingTo("0.00");
        assertThat(invoice.amountDue()).isEqualByComparingTo("150000.00");

        // Verify audit log
        var audits = kernel.committedAudit();
        assertThat(audits).anySatisfy(a -> {
            assertThat(a.action()).isEqualTo(BulkReceivableImporter.AUDIT_RECEIVABLE_IMPORTED);
            assertThat(a.payload().get("sellerSignedBy")).isEqualTo(SELLER_USER);
            assertThat(a.payload().get("buyerSignedBy")).isEqualTo(BUYER_USER);
            assertThat(a.payload().get("amount")).isEqualTo(new BigDecimal("150000.00"));
        });
        assertThat(audits).anySatisfy(a -> {
            assertThat(a.action()).isEqualTo(BulkReceivableImporter.AUDIT_BULK_IMPORTED);
            assertThat(a.payload().get("totalRows")).isEqualTo(1);
            assertThat(a.payload().get("importedCount")).isEqualTo(1);
        });

        // Verify domain events
        var events = kernel.committedEvents();
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(OpeningReceivableLoaded.class);
            OpeningReceivableLoaded loaded = (OpeningReceivableLoaded) e;
            assertThat(loaded.documentId()).isEqualTo(invoiceId);
            assertThat(loaded.sellerSignedBy()).isEqualTo(SELLER_USER);
            assertThat(loaded.buyerSignedBy()).isEqualTo(BUYER_USER);
            assertThat(loaded.amount()).isEqualByComparingTo("150000.00");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(ReceivablesBulkImported.class);
            ReceivablesBulkImported bulk = (ReceivablesBulkImported) e;
            assertThat(bulk.importedCount()).isEqualTo(1);
            assertThat(bulk.totalAmount()).isEqualByComparingTo("150000.00");
        });
    }

    @Test
    void missingBuyerSignatureFailsAndCommitsNothing() {
        String csv =
                """
                "Distributor Code","MPCS Code","Opening Balance","Due","Ref No","Seller Signer","Buyer Signer"
                "D4S","D4B",50000.00,"2026-11-30","REF-NO-BUYER","seller_officer","non_existent_buyer"
                """;

        BulkImportReceivables cmd = new BulkImportReceivables(SELLER, csv, null, false);
        assertThatThrownBy(() -> importer.handle(cmd, seller()))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m4.receivable.buyer_signature_required"));

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }
}
