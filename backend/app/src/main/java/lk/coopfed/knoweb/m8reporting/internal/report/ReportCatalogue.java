package lk.coopfed.knoweb.m8reporting.internal.report;

import java.util.List;
import java.util.Optional;
import lk.coopfed.knoweb.m8reporting.query.ReportDefinitionView;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;

/**
 * The three report definitions of the demo (28A section 3.1 has twelve phase-1 placeholders as
 * seed data; the demo's are these, as code: deferred, with the definitions as data, the
 * validator and the audience check). Each names its columns; {@link ReportQueriesImpl} reads its
 * rows from the projections, only ever from {@code reporting.*}.
 */
final class ReportCatalogue {

    static final String STOCK_POSITION = "stock-position";
    static final String TRADE_BY_DISTRIBUTOR = "trade-by-distributor";
    static final String INVOICES_ISSUED = "invoices-issued";

    static final String TEXT = "TEXT";
    static final String DATE = "DATE";
    static final String QTY = "QTY";
    static final String MONEY = "MONEY";

    record Definition(
            String reportId, String titleId, String decisionId, boolean period, boolean location, List<Column> columns) {

        ReportDefinitionView view() {
            return new ReportDefinitionView(reportId, titleId, decisionId, period, location);
        }
    }

    /**
     * The stock position by entity, location and SKU (doc 28 section 3, stock_position). The
     * value is quantity times the entity average at the last movement, a cost: shown to the
     * owner and to the Federation view only (28A section 6, "Cost columns").
     */
    static final Definition STOCK = new Definition(
            STOCK_POSITION,
            "m8.report.stock_position.title",
            "m8.report.stock_position.decision",
            false,
            true,
            List.of(
                    new Column("entity", "m8.col.entity", TEXT),
                    new Column("location", "m8.col.location", TEXT),
                    new Column("skuCode", "m8.col.sku_code", TEXT),
                    new Column("skuName", "m8.col.sku_name", TEXT),
                    new Column("qty", "m8.col.qty", QTY),
                    new Column("value", "m8.col.stock_value", MONEY)));

    /**
     * What each buyer received from each seller, by SKU and day: the RECEIVED lines of the
     * trade projection (the GRN, where ownership passes). A seller sees its buyers', a buyer its
     * sellers', the Federation view everybody's.
     */
    static final Definition TRADE = new Definition(
            TRADE_BY_DISTRIBUTOR,
            "m8.report.trade_by_distributor.title",
            "m8.report.trade_by_distributor.decision",
            true,
            false,
            List.of(
                    new Column("date", "m8.col.date", DATE),
                    new Column("seller", "m8.col.seller", TEXT),
                    new Column("buyer", "m8.col.buyer", TEXT),
                    new Column("skuCode", "m8.col.sku_code", TEXT),
                    new Column("skuName", "m8.col.sku_name", TEXT),
                    new Column("qty", "m8.col.qty", QTY),
                    new Column("value", "m8.col.trade_value", MONEY)));

    /** The invoices issued in the period, by the seller or to the buyer. */
    static final Definition INVOICES = new Definition(
            INVOICES_ISSUED,
            "m8.report.invoices_issued.title",
            "m8.report.invoices_issued.decision",
            true,
            false,
            List.of(
                    new Column("date", "m8.col.tax_point", DATE),
                    new Column("number", "m8.col.invoice_no", TEXT),
                    new Column("seller", "m8.col.seller", TEXT),
                    new Column("buyer", "m8.col.buyer", TEXT),
                    new Column("net", "m8.col.net", MONEY),
                    new Column("tax", "m8.col.tax", MONEY),
                    new Column("gross", "m8.col.gross", MONEY)));

    static final List<Definition> ALL = List.of(STOCK, TRADE, INVOICES);

    private ReportCatalogue() {}

    static Optional<Definition> find(String reportId) {
        return ALL.stream().filter(d -> d.reportId().equals(reportId)).findFirst();
    }
}
