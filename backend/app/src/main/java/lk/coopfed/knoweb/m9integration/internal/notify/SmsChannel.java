package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;

/**
 * The SMS channel (29A section 6.3, HttpSmsAdapter in its phase-1 form): hands each message to
 * the configured {@link SmsGateway}. The gateway is chosen once, at start; an unknown name stops
 * the start rather than losing every SMS quietly.
 */
class SmsChannel implements NotificationChannel {

    private final SmsGateway gateway;

    SmsChannel(List<SmsGateway> gateways, String provider) {
        this.gateway = gateways.stream()
                .filter(g -> g.name().equals(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SMS provider named \"" + provider
                        + "\" (coop-erp.integration.sms.provider); known: "
                        + gateways.stream().map(SmsGateway::name).toList()));
    }

    @Override
    public String channel() {
        return "SMS";
    }

    /**
     * A gateway's failure is rethrown without its text, as the SMTP adapter does: a provider's
     * message can quote the number or the body, and the kernel keeps the failure on its log and,
     * when it gives up, in the insert-only audit (wave 2, M9-10). The original goes along as the
     * cause, which the kernel writes to the application log at DEBUG only.
     */
    @Override
    public String send(Outgoing outgoing) {
        try {
            return gateway.send(outgoing.notificationId(), outgoing.recipient(), outgoing.body());
        } catch (RuntimeException e) {
            SendFailed failed = new SendFailed(
                    categoryOf(e),
                    "SMS gateway did not take notification " + outgoing.notificationId() + " ("
                            + e.getClass().getSimpleName() + ")");
            failed.initCause(e);
            throw failed;
        }
    }

    /** A gateway that times out says so in its exception's type; the rest is not known yet (DR-4). */
    static FailureCategory categoryOf(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return FailureCategory.TIMEOUT;
            }
        }
        return FailureCategory.UNKNOWN;
    }
}
