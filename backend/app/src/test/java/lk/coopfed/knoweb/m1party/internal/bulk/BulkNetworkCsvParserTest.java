package lk.coopfed.knoweb.m1party.internal.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import lk.coopfed.knoweb.m1party.api.BulkRegisterNetwork;
import org.junit.jupiter.api.Test;

class BulkNetworkCsvParserTest {

    @Test
    void parsesTabularSpreadsheetIntoStructuredNetworkCommand() {
        String csv =
                """
                shop_code,name_en,district,language,size_band,position_no,username,display_name,user_kind,pin,hardware_serial,device_kind,app_version
                SH001,Colombo Main,Colombo,en,L,1,colombo.cashier1,Cashier 1,TILL,1234,POS-001,POS_TERMINAL,1.2.0
                SH001,Colombo Main,Colombo,en,L,2,colombo.cashier2,Cashier 2,TILL,5678,POS-002,POS_TERMINAL,1.2.0
                SH002,Kandy Branch,Kandy,si,M,1,kandy.cashier1,Kandy Cashier,TILL,4321,POS-003,POS_TERMINAL,1.2.0
                """;

        BulkRegisterNetwork cmd = BulkNetworkCsvParser.parse(csv);

        assertThat(cmd.shops()).hasSize(2);
        BulkRegisterNetwork.Shop sh1 = cmd.shops().stream()
                .filter(s -> s.locationCode().equals("SH001"))
                .findFirst()
                .orElseThrow();
        assertThat(sh1.nameEn()).isEqualTo("Colombo Main");
        assertThat(sh1.district()).isEqualTo("Colombo");
        assertThat(sh1.language()).isEqualTo("en");
        assertThat(sh1.sizeBand()).isEqualTo("L");
        assertThat(sh1.tillPositions()).containsExactly(1, 2);

        BulkRegisterNetwork.Shop sh2 = cmd.shops().stream()
                .filter(s -> s.locationCode().equals("SH002"))
                .findFirst()
                .orElseThrow();
        assertThat(sh2.nameEn()).isEqualTo("Kandy Branch");
        assertThat(sh2.tillPositions()).containsExactly(1);

        assertThat(cmd.users()).hasSize(3);
        BulkRegisterNetwork.User u1 = cmd.users().stream()
                .filter(u -> u.username().equals("colombo.cashier1"))
                .findFirst()
                .orElseThrow();
        assertThat(u1.displayName()).isEqualTo("Cashier 1");
        assertThat(u1.pin()).isEqualTo("1234");
        assertThat(u1.userKind()).isEqualTo("TILL");
        assertThat(u1.locationCode()).isEqualTo("SH001");

        assertThat(cmd.devices()).hasSize(3);
        BulkRegisterNetwork.Device d1 = cmd.devices().stream()
                .filter(d -> d.hardwareSerial().equals("POS-001"))
                .findFirst()
                .orElseThrow();
        assertThat(d1.locationCode()).isEqualTo("SH001");
        assertThat(d1.positionNo()).isEqualTo(1);
        assertThat(d1.appVersion()).isEqualTo("1.2.0");
    }

    @Test
    void emptyOrBlankInputReturnsEmptyCommand() {
        BulkRegisterNetwork cmd1 = BulkNetworkCsvParser.parse("");
        assertThat(cmd1.shops()).isEmpty();
        assertThat(cmd1.users()).isEmpty();
        assertThat(cmd1.devices()).isEmpty();

        BulkRegisterNetwork cmd2 = BulkNetworkCsvParser.parse("   \n\n  ");
        assertThat(cmd2.shops()).isEmpty();
    }
}
