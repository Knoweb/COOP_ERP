package lk.coopfed.knoweb.m7customers.internal.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import lk.coopfed.knoweb.m7customers.api.BulkSetCustomerCreditLimits.CustomerLimitRow;
import org.junit.jupiter.api.Test;

class BulkCustomerCreditLimitCsvParserTest {

    @Test
    void parsesCsvWithHeaderAliasing() {
        String csv =
                """
                account_no,phone,nic,credit_limit,reason
                ACC-101,,198012345678,15000.00,Approved increase
                ,0771234567,,20000.00,Farmer quota
                """;

        List<CustomerLimitRow> rows = BulkCustomerCreditLimitCsvParser.parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).accountNo()).isEqualTo("ACC-101");
        assertThat(rows.get(0).nic()).isEqualTo("198012345678");
        assertThat(rows.get(0).creditLimit()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(rows.get(0).reason()).isEqualTo("Approved increase");

        assertThat(rows.get(1).phone()).isEqualTo("0771234567");
        assertThat(rows.get(1).creditLimit()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(rows.get(1).reason()).isEqualTo("Farmer quota");
    }

    @Test
    void handlesEmptyAndBlank() {
        assertThat(BulkCustomerCreditLimitCsvParser.parse(null)).isEmpty();
        assertThat(BulkCustomerCreditLimitCsvParser.parse("")).isEmpty();
        assertThat(BulkCustomerCreditLimitCsvParser.parse("   \n\t\n")).isEmpty();
    }
}
