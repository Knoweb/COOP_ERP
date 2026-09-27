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

    private static final int POSTING_MAP_ROWS = 11;

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
    }
}
