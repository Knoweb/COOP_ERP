package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The one permission rule (19A section 3) the command interceptor and the kernel's own
 * operations share: the roles, then the second factor from the register's window, refused only
 * when enforcement is on.
 */
class PermissionGateTest {

    private static final String PERMISSION = "sys.device.enrol";

    private final PermissionResolver permissions = mock(PermissionResolver.class);

    @SuppressWarnings("unchecked")
    private final StepUp stepUp = new StepUp(mock(ObjectProvider.class), Clock.systemUTC());

    @Test
    void withEnforcementOffAWouldBeRefusalPasses() {
        when(permissions.allows(any(), eq(PERMISSION))).thenReturn(false);
        when(permissions.requiresMfa(PERMISSION)).thenReturn(true);

        PermissionGate gate = new PermissionGate(permissions, stepUp, false, "http://provider.test/step-up");

        assertThatCode(() -> gate.require(scope(null), PERMISSION)).doesNotThrowAnyException();
    }

    @Test
    void aPermissionNotHeldIsRefused() {
        when(permissions.allows(any(), eq(PERMISSION))).thenReturn(false);

        PermissionGate gate = new PermissionGate(permissions, stepUp, true, "http://provider.test/step-up");

        assertThatThrownBy(() -> gate.require(scope(Instant.now()), PERMISSION))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("permission.denied");
    }

    @Test
    void aStaleSecondFactorIsSentToTheStepUp() {
        when(permissions.allows(any(), eq(PERMISSION))).thenReturn(true);
        when(permissions.requiresMfa(PERMISSION)).thenReturn(true);

        PermissionGate gate = new PermissionGate(permissions, stepUp, true, "http://provider.test/step-up");

        assertThatThrownBy(() -> gate.require(scope(Instant.now().minusSeconds(13 * 3600)), PERMISSION))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("mfa.required");
        assertThatCode(() -> gate.require(scope(Instant.now()), PERMISSION)).doesNotThrowAnyException();
    }

    @Test
    void anOperationWithoutAPermissionIsNotGated() {
        PermissionGate gate = new PermissionGate(permissions, stepUp, true, "");

        assertThatCode(() -> gate.require(scope(null), null)).doesNotThrowAnyException();
        assertThatCode(() -> gate.require(scope(null), " ")).doesNotThrowAnyException();
    }

    @Test
    void aCommandRunsInTheOwnClassOnlyWhateverTheResolverSays() {
        // CR-19A-9: a read code and a command code may be one string (gov.external.grant), and a
        // FEDERATION_VIEW caller resolves every read; the class rule keeps it from the command.
        when(permissions.allows(any(), eq(PERMISSION))).thenReturn(true);

        PermissionGate gate = new PermissionGate(permissions, stepUp, true, "");

        assertThatCode(() -> gate.require(scope(null, PolicyClass.OWN), PERMISSION))
                .doesNotThrowAnyException();
        for (PolicyClass readOnly : List.of(
                PolicyClass.FEDERATION_VIEW, PolicyClass.EXTERNAL_TIMEBOXED, PolicyClass.PARTY, PolicyClass.NONE)) {
            assertThatThrownBy(() -> gate.require(scope(null, readOnly), PERMISSION))
                    .as(readOnly.name())
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("permission.denied");
        }
    }

    @Test
    void aReadPassesWhenTheCallerHoldsItsCodeInAnyClass() {
        when(permissions.allows(any(), eq("cat.sku.view"))).thenReturn(true);
        when(permissions.allows(any(), eq("gov.user.view"))).thenReturn(false);

        PermissionGate gate = new PermissionGate(permissions, stepUp, true, "");

        for (PolicyClass policyClass : List.of(PolicyClass.OWN, PolicyClass.EXTERNAL_TIMEBOXED)) {
            assertThatCode(() -> gate.requireRead(scope(null, policyClass), "cat.sku.view"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> gate.requireRead(scope(null, policyClass), "gov.user.view"))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("permission.denied");
        }
    }

    @Test
    void aReadAsksForNoSecondFactorAndTheSessionReadForNoPermission() {
        when(permissions.allows(any(), eq(PERMISSION))).thenReturn(true);
        when(permissions.requiresMfa(PERMISSION)).thenReturn(true);

        PermissionGate gate = new PermissionGate(permissions, stepUp, true, "");

        assertThatCode(() -> gate.requireRead(scope(null), PERMISSION)).doesNotThrowAnyException();
        assertThatCode(() -> gate.requireRead(scope(null, PolicyClass.NONE), SliceOperations.AUTHENTICATED))
                .doesNotThrowAnyException();
    }

    @Test
    void withEnforcementOffAWouldBeRefusedReadPasses() {
        when(permissions.allows(any(), any())).thenReturn(false);

        PermissionGate gate = new PermissionGate(permissions, stepUp, false, "");

        assertThatCode(() -> gate.requireRead(scope(null), "cat.sku.view")).doesNotThrowAnyException();
    }

    private static ScopeContext scope(Instant mfaAt) {
        return scope(mfaAt, PolicyClass.OWN);
    }

    private static ScopeContext scope(Instant mfaAt, PolicyClass policyClass) {
        Scope active = new Scope(UUID.randomUUID(), null);
        return new ScopeContext(
                UUID.randomUUID(),
                null,
                active.entityId(),
                List.of(active),
                active,
                policyClass,
                policyClass == PolicyClass.EXTERNAL_TIMEBOXED ? Set.of(UUID.randomUUID()) : Set.of(),
                mfaAt,
                Locale.ENGLISH,
                Ids.next());
    }
}
