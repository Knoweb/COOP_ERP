package lk.coopfed.knoweb.m4trading.internal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M4 seed loader: the posting map of 24A section 3.1 is in trading.posting_map after the
 * start (the context of this test ran the loader once already), and running it again changes
 * nothing. The count is the rows of seed/m4trading/posting-map.yaml; change them together.
 */
class M4SeedLoaderTest extends PostgresIntegrationTest {

    /** 24A section 3.1's eleven rows, plus the two CN GOODS BUYER rows of wave 2 (CR-24A-3 item 5). */
    private static final int POSTING_MAP_ROWS = 13;

    @Autowired
    private M4SeedLoader loader;

    @Test
    void theStartUpLoadPutThePostingMapInPlaceAndASecondRunIsIdempotent() {
        JdbcTemplate db = superuserJdbc();

        assertThat(loader.readPostingMap()).hasSize(POSTING_MAP_ROWS);
        assertThat(db.queryForObject("select count(*) from trading.posting_map", Integer.class))
                .isEqualTo(POSTING_MAP_ROWS);

        // Upserted by key: a second run rewrites the same rows and adds none.
        assertThat(loader.loadSeeds()).isEqualTo(POSTING_MAP_ROWS);
        assertThat(db.queryForObject("select count(*) from trading.posting_map", Integer.class))
                .isEqualTo(POSTING_MAP_ROWS);

        // The seller's invoice (doc 24 section 3.9): receivable against revenue and VAT output.
        List<Map<String, Object>> invoice = db.queryForList(
                """
                select debit_role, credit_role, amount_source from trading.posting_map
                 where doc_type_code = 'INV' and line_kind = 'GOODS' and side = 'SELLER'
                 order by credit_role
                """);
        assertThat(invoice)
                .extracting(
                        row -> row.get("debit_role") + "/" + row.get("credit_role") + "/" + row.get("amount_source"))
                .containsExactly("RECEIVABLE/REVENUE/net", "RECEIVABLE/VAT_OUTPUT/tax");

        // The buyer's credit note (wave 2, CR-24A-3 item 5): payable reduced against inventory and
        // VAT input, two rows with one key each beside the seller's two.
        List<Map<String, Object>> creditNote = db.queryForList(
                """
                select side, debit_role, credit_role, amount_source from trading.posting_map
                 where doc_type_code = 'CN' and line_kind = 'GOODS'
                 order by side, credit_role
                """);
        assertThat(creditNote)
                .extracting(row -> row.get("side") + " " + row.get("debit_role") + "/" + row.get("credit_role") + "/"
                        + row.get("amount_source"))
                .containsExactly(
                        "BUYER PAYABLE/INVENTORY/net",
                        "BUYER PAYABLE/VAT_INPUT/tax",
                        "SELLER REVENUE/RECEIVABLE/net",
                        "SELLER VAT_OUTPUT/RECEIVABLE/tax");
    }
}
