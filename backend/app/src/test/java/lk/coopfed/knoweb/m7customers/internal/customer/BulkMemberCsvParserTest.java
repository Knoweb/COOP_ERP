package lk.coopfed.knoweb.m7customers.internal.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import lk.coopfed.knoweb.m7customers.api.BulkImportMembers;
import org.junit.jupiter.api.Test;

class BulkMemberCsvParserTest {

    @Test
    void parsesStandardCsvWithVariousAliases() {
        String csv =
                """
                name,sinhala_name,tamil_name,lang,mobile,nic_number,limit,terms,cap,tags,consents,confirm
                Sita Kumari,සීතා කුමාරි,சீதா குமாரி,si,0771234567,199012345678,5000.00,30,2000.00,"regular,farmer",CREDIT_ACCOUNT,true
                Nimal Perera,,,en,0719876543,901234567V,10000.00,60,5000.00,pensioner,CREDIT_ACCOUNT,false
                """;

        List<BulkImportMembers.MemberRow> rows = BulkMemberCsvParser.parse(csv);

        assertThat(rows).hasSize(2);

        BulkImportMembers.MemberRow row1 = rows.get(0);
        assertThat(row1.displayName()).isEqualTo("Sita Kumari");
        assertThat(row1.displayNameSi()).isEqualTo("සීතා කුමාරි");
        assertThat(row1.displayNameTa()).isEqualTo("சீதா குமாரி");
        assertThat(row1.language()).isEqualTo("si");
        assertThat(row1.phone()).isEqualTo("0771234567");
        assertThat(row1.nic()).isEqualTo("199012345678");
        assertThat(row1.creditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(row1.termsDays()).isEqualTo(30);
        assertThat(row1.offlineCap()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(row1.tags()).containsExactly("regular", "farmer");
        assertThat(row1.consents()).containsExactly("CREDIT_ACCOUNT");
        assertThat(row1.confirmedIdentity()).isTrue();

        BulkImportMembers.MemberRow row2 = rows.get(1);
        assertThat(row2.displayName()).isEqualTo("Nimal Perera");
        assertThat(row2.displayNameSi()).isNull();
        assertThat(row2.language()).isEqualTo("en");
        assertThat(row2.phone()).isEqualTo("0719876543");
        assertThat(row2.nic()).isEqualTo("901234567V");
        assertThat(row2.confirmedIdentity()).isFalse();
    }

    @Test
    void handlesEmptyAndBlank() {
        assertThat(BulkMemberCsvParser.parse(null)).isEmpty();
        assertThat(BulkMemberCsvParser.parse("")).isEmpty();
        assertThat(BulkMemberCsvParser.parse("   \n  \r\n  ")).isEmpty();
    }
}
