package lk.coopfed.knoweb.kernel.internal.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A token is accepted only when it was issued to a client of this platform: the web client
 * (and any other client named in {@code coop-erp.security.oidc.accepted-clients}) by its
 * {@code azp} or an audience, or a till's client, whose id starts with the device prefix
 * (K-08, one client per device). Without this a token minted for any client of the realm, the
 * provider's own admin-cli with a password grant among them, opened the API and bypassed
 * whatever second factor the browser flow binds.
 */
final class AcceptedClientsValidator implements OAuth2TokenValidator<Jwt> {

    static final String AUTHORIZED_PARTY = "azp";

    private final Set<String> acceptedClients;
    private final String devicePrefix;

    AcceptedClientsValidator(List<String> acceptedClients, String devicePrefix) {
        this.acceptedClients = Set.copyOf(acceptedClients);
        this.devicePrefix = devicePrefix;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String party = token.getClaimAsString(AUTHORIZED_PARTY);
        if (party != null) {
            // The client the token was issued to decides alone: an audience naming the web
            // client does not open the API to a token another client obtained.
            return acceptedClients.contains(party) || party.startsWith(devicePrefix)
                    ? OAuth2TokenValidatorResult.success()
                    : refused();
        }
        List<String> audience = token.getAudience();
        if (audience != null && audience.stream().anyMatch(acceptedClients::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        return refused();
    }

    private static OAuth2TokenValidatorResult refused() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The token was not issued to a client of this platform", null));
    }
}
