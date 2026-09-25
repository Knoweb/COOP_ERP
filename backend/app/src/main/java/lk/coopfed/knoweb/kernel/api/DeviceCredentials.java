package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * The credential of an enrolled till (19A section 2; doc 19 section 2.1; doc 32 section 8). A
 * device authenticates every sync call with a device token carrying {@code cls = DEVICE} and
 * {@code dev}; this is what it exchanges for one. Decided in K-08 from doc 19 section 2.1 ("OAuth2
 * client-credentials device token, 24 h, refreshed on sync") and 19A section 2 ("OAuth2 client
 * credentials per device; either yields a device token with dev and cls = DEVICE"): the credential
 * is a confidential client of the identity provider, one per device, whose tokens carry the two
 * claims. The kernel signs no token of its own, so the resource server trusts one issuer.
 *
 * <p>The implementation talks to the provider's administration API; no provider type appears
 * here (AGENTS.md). Nothing is audited here: the caller (the enrolment) audits.
 */
public interface DeviceCredentials {

    /**
     * Creates the device's client at the provider, or, for a device that has one already, gives
     * it a new secret (a re-installed application enrols again): the old secret stops working.
     *
     * @throws ProblemException {@code identity.unavailable} when the provider does not answer
     */
    Credential issue(UUID deviceId, UUID ownerEntityId);

    /** Disables the device's client: no new device token can be obtained with it. */
    void revoke(UUID deviceId);

    /**
     * What the device keeps in its keystore. {@link #toString()} hides the secret, so a log line
     * cannot carry it.
     *
     * @param clientId      the client id at the provider, {@code device-<device id>}
     * @param clientSecret  shown to the device once, in the enrolment answer
     * @param tokenEndpoint where the device asks for a token with grant_type=client_credentials
     */
    record Credential(String clientId, String clientSecret, String tokenEndpoint) {
        @Override
        public String toString() {
            return "Credential[clientId=" + clientId + ", secret hidden, tokenEndpoint=" + tokenEndpoint + "]";
        }
    }
}
