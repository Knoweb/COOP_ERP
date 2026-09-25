package lk.coopfed.knoweb.kernel.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;

/** K-08: which principal may call which path (doc 32 section 9; 19A section 2). */
class ScopeFilterPrincipalTest {

    private static final UUID ENTITY = UUID.fromString("0190a900-0000-7000-8000-000000000001");
    private static final UUID SHOP = UUID.fromString("0190a900-0000-7000-8000-000000000002");
    private static final UUID DEVICE = UUID.fromString("0190a900-0000-7000-8000-000000000003");

    private final ScopeContext device = new ScopeContext(
            null,
            DEVICE,
            ENTITY,
            List.of(new Scope(ENTITY, SHOP)),
            null,
            PolicyClass.DEVICE,
            Set.of(),
            null,
            null,
            null);
    private final ScopeContext user = ScopeContext.dev(UUID.randomUUID(), ENTITY, null);
    private final ScopeContext nobody =
            new ScopeContext(null, null, null, List.of(), null, PolicyClass.NONE, Set.of(), null, null, null);

    @Test
    void theTillsOperationsTakeADeviceAndNothingElse() {
        for (String path : List.of(
                "/v1/sync/devices/" + DEVICE + "/batches",
                "/v1/sync/devices/" + DEVICE + "/heartbeat",
                "/v1/sync/locations/" + SHOP + "/changes",
                "/v1/sync/locations/" + SHOP + "/snapshot",
                "/v1/sync/attachments/presign")) {
            assertThatCode(() -> ScopeFilter.validatePrincipal(device, path)).doesNotThrowAnyException();
            assertThatThrownBy(() -> ScopeFilter.validatePrincipal(user, path))
                    .isInstanceOf(ProblemException.class)
                    .hasMessage("sync.device_token_required");
            assertThatThrownBy(() -> ScopeFilter.validatePrincipal(nobody, path))
                    .isInstanceOf(ProblemException.class)
                    .hasMessage("sync.device_token_required");
        }
    }

    @Test
    void aDeviceOpensNothingElseAndTheEnrolmentIsOpenToAll() {
        assertThatThrownBy(() -> ScopeFilter.validatePrincipal(device, "/v1/party/entities"))
                .isInstanceOf(ProblemException.class)
                .hasMessage("sync.device_token_not_allowed");
        assertThatThrownBy(
                        () -> ScopeFilter.validatePrincipal(device, "/v1/sync/devices/" + DEVICE + "/enrolment-codes"))
                .isInstanceOf(ProblemException.class)
                .hasMessage("sync.device_token_not_allowed");
        assertThatCode(() -> ScopeFilter.validatePrincipal(user, "/v1/sync/devices/" + DEVICE + "/enrolment-codes"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScopeFilter.validatePrincipal(nobody, "/v1/sync/devices/" + DEVICE + "/enrol"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScopeFilter.validatePrincipal(device, "/v1/sync/devices/" + DEVICE + "/enrol"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScopeFilter.validatePrincipal(user, "/v1/party/entities"))
                .doesNotThrowAnyException();
    }
}
