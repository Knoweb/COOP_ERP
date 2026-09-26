package lk.coopfed.knoweb.m1party.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.DeviceQueries;
import lk.coopfed.knoweb.m1party.query.ExternalGrantQueries;
import lk.coopfed.knoweb.m1party.query.UserPage;
import lk.coopfed.knoweb.m1party.query.UserQueries;
import org.junit.jupiter.api.Test;

/**
 * The user, device and grant reads check the x-permission their slice declares (gov.user.view,
 * sys.device.view, gov.external.grant) when permissions are enforced, as M2's
 * CatalogueController does: no command interceptor runs for a GET, so the controller is where
 * the check lives until the kernel enforces it on the generated interfaces.
 */
class ReadPermissionsInTheControllersTest {

    private static final UUID USER = UUID.fromString("0190e640-0000-7000-8000-000000000010");
    private static final UUID MPCS = UUID.fromString("0190e640-0000-7000-8000-000000000002");

    private final UserQueries users = mock(UserQueries.class);
    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final ExternalGrantQueries grants = mock(ExternalGrantQueries.class);
    private final PermissionResolver permissions = mock(PermissionResolver.class);

    @Test
    void anOwnCallerWithoutTheViewPermissionIsRefused() {
        ScopeContext own = ScopeContext.dev(USER, MPCS, null);
        when(permissions.allows(any(), any())).thenReturn(false);

        assertRefused(() -> usersController(own, true).listUsers(null, null, null, 20), "gov.user.view");
        assertRefused(() -> usersController(own, true).getUser(UUID.randomUUID()), "gov.user.view");
        assertRefused(() -> devicesController(own, true).listDevices(null, null), "sys.device.view");
        assertRefused(() -> devicesController(own, true).getDevice(UUID.randomUUID()), "sys.device.view");
        assertRefused(() -> grantsController(own, true).listExternalGrants(), "gov.external.grant");

        verify(users, never()).listUsers(any(), any());
        verify(users, never()).getUser(any(), any());
        verify(devices, never()).listDevices(any(), any());
        verify(devices, never()).getDevice(any(), any());
        verify(grants, never()).listExternalGrants(any());
    }

    @Test
    void anOwnCallerWithTheViewPermissionReads() {
        ScopeContext own = ScopeContext.dev(USER, MPCS, null);
        when(permissions.allows(any(), any())).thenReturn(true);
        when(users.listUsers(any(), any())).thenReturn(new UserPage(List.of(), null));
        when(devices.listDevices(any(), any())).thenReturn(List.of());
        when(grants.listExternalGrants(any())).thenReturn(List.of());

        assertThat(usersController(own, true)
                        .listUsers(null, null, null, 20)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(devicesController(own, true)
                        .listDevices(null, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(grantsController(own, true)
                        .listExternalGrants()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void aReadOnlyClassAndAnUnenforcedStackAreNotCheckedHere() {
        Scope scope = new Scope(MPCS, null);
        ScopeContext view = new ScopeContext(
                USER,
                null,
                MPCS,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
        when(users.getUser(any(), any())).thenReturn(Optional.empty());

        // Row-level security limits the read-only classes; the answer is the missing user.
        assertThat(usersController(view, true)
                        .getUser(UUID.randomUUID())
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(usersController(ScopeContext.dev(USER, MPCS, null), false)
                        .getUser(UUID.randomUUID())
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        verify(permissions, never()).allows(any(), any());
    }

    private static void assertRefused(Runnable read, String permission) {
        assertThatThrownBy(read::run).isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.messageId()).isEqualTo("permission.denied");
            assertThat(e.parameters()).containsEntry("permission", permission);
        });
    }

    @SuppressWarnings("unchecked")
    private UsersController usersController(ScopeContext scope, boolean enforce) {
        CurrentScope current = () -> scope;
        return new UsersController(
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                users,
                current,
                permissions,
                enforce);
    }

    @SuppressWarnings("unchecked")
    private DevicesController devicesController(ScopeContext scope, boolean enforce) {
        CurrentScope current = () -> scope;
        return new DevicesController(
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                devices,
                current,
                permissions,
                enforce);
    }

    @SuppressWarnings("unchecked")
    private GrantsController grantsController(ScopeContext scope, boolean enforce) {
        CurrentScope current = () -> scope;
        return new GrantsController(
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                mock(lk.coopfed.knoweb.kernel.api.Handles.class),
                grants,
                current,
                permissions,
                enforce);
    }
}
