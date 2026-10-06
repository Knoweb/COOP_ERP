package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The slices read at start: every GET's permission by its path template, and the read set a
 * FEDERATION_VIEW caller holds (CR-19A-9).
 */
class SliceOperationsTest {

    private final SliceOperations real = new SliceOperations();

    @Test
    void theRealSlicesGiveEveryGetItsPermissionByPathTemplate() {
        assertThat(real.permissionOf("GET", "/v1/catalogue/skus/{skuId}")).isEqualTo("cat.sku.view");
        assertThat(real.permissionOf("get", "/v1/party/locations")).isEqualTo("prt.location.view");
        assertThat(real.permissionOf("GET", "/v1/security/external-grants")).isEqualTo("gov.external.grant");
        assertThat(real.permissionOf("GET", "/v1/session")).isEqualTo(SliceOperations.AUTHENTICATED);
        assertThat(real.permissionOf("POST", "/v1/hello/greetings")).isEqualTo("hello.greeting.register");
        assertThat(real.permissionOf("GET", "/v1/nowhere")).isNull();
        assertThat(real.permissionOf("GET", null)).isNull();
    }

    @Test
    void theReadSetIsEveryGetAUserMayCallAndNothingElse() {
        assertThat(real.readPermissions())
                .contains(
                        "cat.sku.view",
                        "prt.location.view",
                        "gov.user.view",
                        "gov.external.grant",
                        "hello.greeting.read")
                // The device's operations name the device principal, not a permission a role holds.
                .doesNotContain("sync.device", "sync.enrolment_code")
                // The session read asks for no permission.
                .doesNotContain(SliceOperations.AUTHENTICATED)
                // A command's code is not a read unless a GET also carries it.
                .doesNotContain("hello.greeting.register", "cat.sku.create", "gov.entity.register");
    }

    /**
     * CR-18-2 (wave 2, TWK-30): the Federation reads its members' trading, stock, finance and
     * reporting data, not their staff administration or their customers' personal data. The
     * operations say so with {@code x-federation-view: false}.
     */
    @Test
    void theFederationViewReadSetLeavesOutTheMarkedOperations() {
        assertThat(real.federationViewReadPermissions())
                .contains("cat.sku.view", "prt.location.view", "gov.external.grant", "hello.greeting.read")
                // M1 user and device administration; M7 customer personal data and privacy requests.
                .doesNotContain("gov.user.view", "sys.device.view", "cus.customer.view", "cus.privacy.record");
        assertThat(real.readPermissions()).containsAll(real.federationViewReadPermissions());
    }

    @Test
    void aMarkedOperationTakesItsCodeOutOfTheFederationViewSetAndNothingElse() {
        String slice =
                """
                paths:
                  /v1/things:
                    get:
                      x-permission: thing.view
                      x-federation-view: false
                  /v1/things/{id}/money:
                    get:
                      x-permission: thing.view
                  /v1/others:
                    get:
                      x-permission: other.view
                      x-federation-view: true
                """;
        SliceOperations operations = new SliceOperations(Map.of("things.yaml", new Yaml().load(slice)));
        assertThat(operations.readPermissions()).containsExactlyInAnyOrder("other.view", "thing.view");
        // One marked GET under a code withholds the code (fail closed), whatever its siblings say.
        assertThat(operations.federationViewReadPermissions()).containsExactly("other.view");
    }

    @Test
    void aSliceOfItsOwnIsReadTheSameWay() {
        String slice =
                """
                paths:
                  /v1/things:
                    parameters: []
                    get:
                      x-permission: thing.view
                    post:
                      x-permission: thing.create
                  /v1/sync/locations/{id}/changes:
                    get:
                      x-permission: sync.device
                """;
        SliceOperations operations = new SliceOperations(Map.of("things.yaml", new Yaml().load(slice)));

        assertThat(operations.permissionOf("GET", "/v1/things")).isEqualTo("thing.view");
        assertThat(operations.permissionOf("POST", "/v1/things")).isEqualTo("thing.create");
        assertThat(operations.readPermissions()).containsExactly("thing.view");
    }
}
