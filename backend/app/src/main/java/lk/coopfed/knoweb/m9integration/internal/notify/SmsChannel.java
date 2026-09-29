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

    @Override
    public String send(Outgoing outgoing) {
        return gateway.send(outgoing.notificationId(), outgoing.recipient(), outgoing.body());
    }
}
