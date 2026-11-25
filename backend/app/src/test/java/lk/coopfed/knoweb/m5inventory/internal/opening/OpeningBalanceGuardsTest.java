package lk.coopfed.knoweb.m5inventory.internal.opening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OpeningBalanceGuardsTest {

    private static final UUID FEDERATION = UUID.fromString("0190e679-0000-7000-8000-000000000001");
    private static final UUID OFFICER = UUID.fromString("0190e679-0000-7000-8000-000000000002");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void federationOfficerInEntityWideOwnScopeIsAccepted() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);

        OpeningBalanceGuards.requireFederation(ScopeContext.dev(OFFICER, FEDERATION, null), jdbc);
    }

    @Test
    void anotherEntityAndALocationScopedFederationAreRefused() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        assertProblem(ScopeContext.dev(OFFICER, UUID.randomUUID(), null), "m5.opening.federation_required");

        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        assertProblem(ScopeContext.dev(OFFICER, FEDERATION, UUID.randomUUID()), "m5.opening.federation_required");
    }

    private void assertProblem(ScopeContext scope, String code) {
        assertThatThrownBy(() -> OpeningBalanceGuards.requireFederation(scope, jdbc))
                .isInstanceOfSatisfying(ProblemException.class, problem -> assertThat(problem.messageId())
                        .isEqualTo(code));
    }
}
