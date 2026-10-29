package lk.coopfed.knoweb.m4trading.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.coopfed.knoweb.m4trading.web.generated.TransferRequestResponse;
import org.junit.jupiter.api.Test;

class TransferRequestResponseContractTest {

    @Test
    void transferStatusAcceptsAndSerializesEveryM5LifecycleState() throws Exception {
        assertThat(TransferRequestResponse.TransferStatusEnum.fromValue("ISSUED"))
                .isEqualTo(TransferRequestResponse.TransferStatusEnum.ISSUED);
        assertThat(TransferRequestResponse.TransferStatusEnum.fromValue("IN_TRANSIT"))
                .isEqualTo(TransferRequestResponse.TransferStatusEnum.IN_TRANSIT);
        assertThat(TransferRequestResponse.TransferStatusEnum.fromValue("RECEIVED"))
                .isEqualTo(TransferRequestResponse.TransferStatusEnum.RECEIVED);
        assertThat(TransferRequestResponse.TransferStatusEnum.fromValue("CANCELLED"))
                .isEqualTo(TransferRequestResponse.TransferStatusEnum.CANCELLED);
        assertThat(new ObjectMapper().writeValueAsString(TransferRequestResponse.TransferStatusEnum.CANCELLED))
                .isEqualTo("\"CANCELLED\"");
    }
}
