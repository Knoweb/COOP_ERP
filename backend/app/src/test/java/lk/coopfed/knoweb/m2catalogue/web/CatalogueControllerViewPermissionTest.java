package lk.coopfed.knoweb.m2catalogue.web;

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
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuCommandRouter;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import org.junit.jupiter.api.Test;

/** The reads check cat.sku.view, as their slice declares, when permissions are enforced. */
class CatalogueControllerViewPermissionTest {

    private static final UUID USER = UUID.fromString("0190e630-0000-7000-8000-000000000010");
    private static final UUID MPCS = UUID.fromString("0190e630-0000-7000-8000-000000000002");

    private final CatalogueQueries queries = mock(CatalogueQueries.class);
    private final PermissionResolver permissions = mock(PermissionResolver.class);

    @Test
    void anOwnCallerWithoutTheViewPermissionIsRefused() {
        ScopeContext own = ScopeContext.dev(USER, MPCS, null);
        when(permissions.allows(own, "cat.sku.view")).thenReturn(false);
        CatalogueController controller = controller(own, true);

        assertThatThrownBy(() -> controller.getSku(UUID.randomUUID()))
                .isInstanceOf(ProblemException.class)
                .extracting(error -> ((ProblemException) error).messageId())
                .isEqualTo("permission.denied");
        assertThatThrownBy(() -> controller.listSkus(null, "en", null, 0, 10))
                .isInstanceOf(ProblemException.class)
                .extracting(error -> ((ProblemException) error).messageId())
                .isEqualTo("permission.denied");
        verify(queries, never()).getSku(any(), any());
    }

    @Test
    void anOwnCallerWithTheViewPermissionReads() {
        ScopeContext own = ScopeContext.dev(USER, MPCS, null);
        when(permissions.allows(own, "cat.sku.view")).thenReturn(true);
        when(queries.listSkus(any(), any())).thenReturn(new SkuPage(List.of(), null));

        assertThat(controller(own, true)
                        .listSkus(null, "en", null, 0, 10)
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
        when(queries.getSku(any(), any())).thenReturn(Optional.empty());

        // Row-level security limits the read-only classes; the answer is the missing SKU.
        assertThatThrownBy(() -> controller(view, true).getSku(UUID.randomUUID()))
                .extracting(error -> ((ProblemException) error).messageId())
                .isEqualTo("m2.sku.not_found");
        assertThatThrownBy(() ->
                        controller(ScopeContext.dev(USER, MPCS, null), false).getSku(UUID.randomUUID()))
                .extracting(error -> ((ProblemException) error).messageId())
                .isEqualTo("m2.sku.not_found");
    }

    private CatalogueController controller(ScopeContext scope, boolean enforce) {
        CurrentScope current = () -> scope;
        return new CatalogueController(mock(SkuCommandRouter.class), queries, current, permissions, enforce);
    }
}
