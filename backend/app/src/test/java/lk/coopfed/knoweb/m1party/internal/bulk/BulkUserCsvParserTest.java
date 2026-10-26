package lk.coopfed.knoweb.m1party.internal.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lk.coopfed.knoweb.m1party.api.BulkImportUsers;
import org.junit.jupiter.api.Test;

class BulkUserCsvParserTest {

    @Test
    void parsesCsvUserSheetSuccessfully() {
        String csv =
                """
                username,display_name,language,user_kind,pin,password,location_code
                colombo.officer1,Colombo Officer,en,BACK_OFFICE,,Secret123!,
                colombo.cashier1,Colombo Cashier,si,BOTH,1234,CashSecret!,SH001
                colombo.tillonly,Till Only,ta,TILL,5678,,SH002
                """;

        List<BulkImportUsers.UserRow> rows = BulkUserCsvParser.parse(csv);

        assertThat(rows).hasSize(3);

        BulkImportUsers.UserRow r1 = rows.get(0);
        assertThat(r1.username()).isEqualTo("colombo.officer1");
        assertThat(r1.displayName()).isEqualTo("Colombo Officer");
        assertThat(r1.language()).isEqualTo("en");
        assertThat(r1.userKind()).isEqualTo("BACK_OFFICE");
        assertThat(r1.pin()).isNull();
        assertThat(r1.password()).isEqualTo("Secret123!");
        assertThat(r1.locationCode()).isNull();

        BulkImportUsers.UserRow r2 = rows.get(1);
        assertThat(r2.username()).isEqualTo("colombo.cashier1");
        assertThat(r2.displayName()).isEqualTo("Colombo Cashier");
        assertThat(r2.language()).isEqualTo("si");
        assertThat(r2.userKind()).isEqualTo("BOTH");
        assertThat(r2.pin()).isEqualTo("1234");
        assertThat(r2.password()).isEqualTo("CashSecret!");
        assertThat(r2.locationCode()).isEqualTo("SH001");

        BulkImportUsers.UserRow r3 = rows.get(2);
        assertThat(r3.username()).isEqualTo("colombo.tillonly");
        assertThat(r3.displayName()).isEqualTo("Till Only");
        assertThat(r3.language()).isEqualTo("ta");
        assertThat(r3.userKind()).isEqualTo("TILL");
        assertThat(r3.pin()).isEqualTo("5678");
        assertThat(r3.password()).isNull();
        assertThat(r3.locationCode()).isEqualTo("SH002");
    }

    @Test
    void handlesEmptyAndBlankCsvGracefully() {
        assertThat(BulkUserCsvParser.parse("")).isEmpty();
        assertThat(BulkUserCsvParser.parse("   \n\n  ")).isEmpty();
        assertThat(BulkUserCsvParser.parse(null)).isEmpty();
    }
}
