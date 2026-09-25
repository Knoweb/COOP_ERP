package lk.coopfed.knoweb.kernel.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DeviceCredentials;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * {@link DeviceCredentials} as a confidential client of the identity provider per device (doc 19
 * section 2.1: "OAuth2 client-credentials device token (24 h, refreshed on sync)"). The client is
 * {@code device-<device id>}, may use the client-credentials grant and nothing else, and puts two
 * fixed claims into every token it obtains: {@code dev}, the device id, and {@code cls = DEVICE}.
 * So the resource server verifies a device token like any other, and the claims mapper hands it
 * to device authentication. The access token lives 24 hours; the till asks for a new one when
 * it expires, with the same client secret.
 *
 * <p>Plain administration REST API of the provider, through the service account of
 * {@link KeycloakAdminClient}, which holds {@code manage-clients} for this. Another provider is
 * another class of the same size: what it has to do is create a client with two fixed claims,
 * give it a new secret, and disable it.
 */
@Component
public class KeycloakDeviceCredentials implements DeviceCredentials {

    static final String CLIENT_PREFIX = "device-";

    /** doc 19 section 2.1: the device token lives 24 hours and is refreshed on sync. */
    static final String TOKEN_LIFESPAN_SECONDS = "86400";

    private final KeycloakAdminClient admin;
    private final String tokenEndpoint;

    public KeycloakDeviceCredentials(
            KeycloakAdminClient admin, @Value("${coop-erp.security.oidc.token-endpoint}") String tokenEndpoint) {
        this.admin = admin;
        this.tokenEndpoint = tokenEndpoint;
    }

    @Override
    public Credential issue(UUID deviceId, UUID ownerEntityId) {
        String clientId = CLIENT_PREFIX + deviceId;
        String internalId = findClient(clientId);
        if (internalId == null) {
            admin.call(() -> admin.rest()
                    .post()
                    .uri(admin.realmPath() + "/clients")
                    .headers(admin::bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(client(clientId, deviceId))
                    .retrieve()
                    .toBodilessEntity());
            internalId = findClient(clientId);
            if (internalId == null) {
                throw new ProblemException("identity.unavailable");
            }
        } else {
            enable(internalId, true);
        }
        String id = internalId;
        JsonNode secret = admin.call(() -> admin.rest()
                .post()
                .uri(admin.realmPath() + "/clients/{id}/client-secret", id)
                .headers(admin::bearer)
                .retrieve()
                .body(JsonNode.class));
        if (secret == null || !secret.path("value").isTextual()) {
            throw new ProblemException("identity.unavailable");
        }
        return new Credential(clientId, secret.path("value").asText(), tokenEndpoint);
    }

    @Override
    public void revoke(UUID deviceId) {
        String internalId = findClient(CLIENT_PREFIX + deviceId);
        if (internalId != null) {
            enable(internalId, false);
        }
    }

    private void enable(String internalId, boolean enabled) {
        admin.call(() -> admin.rest()
                .put()
                .uri(admin.realmPath() + "/clients/{id}", internalId)
                .headers(admin::bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled))
                .retrieve()
                .toBodilessEntity());
    }

    private String findClient(String clientId) {
        JsonNode found = admin.call(() -> admin.rest()
                .get()
                .uri(admin.realmPath() + "/clients?clientId={clientId}", clientId)
                .headers(admin::bearer)
                .retrieve()
                .body(JsonNode.class));
        if (found == null || !found.isArray() || found.isEmpty()) {
            return null;
        }
        return found.get(0).path("id").asText(null);
    }

    private static Map<String, Object> client(String clientId, UUID deviceId) {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", clientId);
        client.put("name", "Till device " + deviceId);
        client.put("enabled", true);
        client.put("protocol", "openid-connect");
        client.put("publicClient", false);
        client.put("serviceAccountsEnabled", true);
        client.put("standardFlowEnabled", false);
        client.put("implicitFlowEnabled", false);
        client.put("directAccessGrantsEnabled", false);
        client.put("fullScopeAllowed", false);
        client.put("attributes", Map.of("access.token.lifespan", TOKEN_LIFESPAN_SECONDS));
        client.put(
                "protocolMappers",
                List.of(
                        fixedClaim(JwtClaimsMapper.DEVICE, deviceId.toString()),
                        fixedClaim(JwtClaimsMapper.POLICY_CLASS, PolicyClass.DEVICE.name())));
        return client;
    }

    private static Map<String, Object> fixedClaim(String claim, String value) {
        return Map.of(
                "name",
                claim,
                "protocol",
                "openid-connect",
                "protocolMapper",
                "oidc-hardcoded-claim-mapper",
                "config",
                Map.of(
                        "claim.name", claim,
                        "claim.value", value,
                        "jsonType.label", "String",
                        "access.token.claim", "true",
                        "id.token.claim", "false",
                        "userinfo.token.claim", "false",
                        "introspection.token.claim", "true"));
    }
}
