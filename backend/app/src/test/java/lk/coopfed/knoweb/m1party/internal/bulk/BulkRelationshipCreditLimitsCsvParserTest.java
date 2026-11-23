package lk.coopfed.knoweb.m1party.internal.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import lk.coopfed.knoweb.m1party.api.BulkSetRelationshipCreditLimits.RelationshipLimitRow;
import org.junit.jupiter.api.Test;

class BulkRelationshipCreditLimitsCsvParserTest {

    @Test
    void parsesCsvWithHeaderAliasing() {
        String csv =
                """
                buyer_code,credit_limit,reason
                B01,50000.00,General increase
                B02,75000.00,Tier 2 limit
                """;

        List<RelationshipLimitRow> rows = BulkRelationshipCreditLimitsCsvParser.parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).buyerCode()).isEqualTo("B01");
        assertThat(rows.get(0).creditLimit()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(rows.get(0).reason()).isEqualTo("General increase");

        assertThat(rows.get(1).buyerCode()).isEqualTo("B02");
        assertThat(rows.get(1).creditLimit()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(rows.get(1).reason()).isEqualTo("Tier 2 limit");
    }

    @Test
    void handlesEmptyAndBlank() {
        assertThat(BulkRelationshipCreditLimitsCsvParser.parse(null)).isEmpty();
        assertThat(BulkRelationshipCreditLimitsCsvParser.parse("")).isEmpty();
        assertThat(BulkRelationshipCreditLimitsCsvParser.parse("   \n  \n")).isEmpty();
    }
}
