package lk.coopfed.knoweb.m5inventory.internal.opening;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

class OpeningBalanceActivationPrerequisiteTest {

    private static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Mock
    private JdbcTemplate jdbc;

    private OpeningBalanceActivationPrerequisite prerequisite;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        prerequisite = new OpeningBalanceActivationPrerequisite(jdbc);
    }

    @Test
    void aPostedOpeningBalanceSatisfiesAStockLocationsGate() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(ENTITY), eq(LOCATION)))
                .thenReturn(true);

        prerequisite.requireSatisfied(ENTITY, LOCATION, "WAREHOUSE");

        verify(jdbc).queryForObject(anyString(), eq(Boolean.class), eq(ENTITY), eq(LOCATION));
    }

    @Test
    void aMissingPostedBalanceRefusesActivation() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(ENTITY), eq(LOCATION)))
                .thenReturn(false);

        assertThatThrownBy(() -> prerequisite.requireSatisfied(ENTITY, LOCATION, "SHOP"))
                .isInstanceOfSatisfying(ProblemException.class, problem -> org.assertj.core.api.Assertions.assertThat(
                                problem.messageId())
                        .isEqualTo("m5.opening.required_before_activation"));
    }

    @Test
    void anOfficeNeedsNoInventoryOpeningBalance() {
        prerequisite.requireSatisfied(ENTITY, LOCATION, "OFFICE");

        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), eq(ENTITY), eq(LOCATION));
    }
}
