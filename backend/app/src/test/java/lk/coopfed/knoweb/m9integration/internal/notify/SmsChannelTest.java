package lk.coopfed.knoweb.m9integration.internal.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import org.junit.jupiter.api.Test;

/**
 * A gateway's failure leaves the SMS channel without the gateway's text, which can quote the
 * number or the body (wave 2, M9-10): the kernel keeps what the channel throws on its log and in
 * the insert-only audit.
 */
class SmsChannelTest {

    private static final UUID NOTIFICATION = UUID.fromString("0190f000-0000-7000-8000-000000000001");

    /** A gateway that answers the way a careless provider does: with the number and the text in its error. */
    private static SmsGateway failing(RuntimeException failure) {
        return new SmsGateway() {
            @Override
            public String name() {
                return "careless";
            }

            @Override
            public String send(UUID notificationId, String phone, String body) {
                throw failure;
            }
        };
    }

    @Test
    void theGatewaysTextIsNotInWhatTheChannelThrows() {
        SmsChannel channel = new SmsChannel(
                List.of(failing(new IllegalArgumentException("Invalid destination +94771234567: Your cheque bounced"))),
                "careless");

        NotificationChannel.SendFailed failed = assertThrows(
                NotificationChannel.SendFailed.class,
                () -> channel.send(new NotificationChannel.Outgoing(
                        NOTIFICATION, "+94771234567", null, "Your cheque bounced", "en")));

        assertThat(failed.getMessage())
                .contains(NOTIFICATION.toString())
                .contains("IllegalArgumentException")
                .doesNotContain("+94771234567")
                .doesNotContain("771234567")
                .doesNotContain("cheque");
        assertThat(failed.category()).isEqualTo(NotificationChannel.FailureCategory.UNKNOWN);
    }

    @Test
    void aTimeoutIsSaidAsOne() {
        RuntimeException timeout = new IllegalStateException("gateway", new SocketTimeoutException("read timed out"));
        SmsChannel channel = new SmsChannel(List.of(failing(timeout)), "careless");

        NotificationChannel.SendFailed failed = assertThrows(
                NotificationChannel.SendFailed.class,
                () -> channel.send(new NotificationChannel.Outgoing(NOTIFICATION, "0771234567", null, "x", "en")));

        assertThat(failed.category()).isEqualTo(NotificationChannel.FailureCategory.TIMEOUT);
    }
}
