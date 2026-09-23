package lk.coopfed.knoweb.kernel.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditFacadeImplTest {

    private final AuditFacadeImpl audit = new AuditFacadeImpl(null, new ObjectMapper());

    @Test
    void updateStoresOnlyChangedTopLevelColumns() {
        AuditFacadeImpl.AuditDiff diff = audit.minimalDiff(
                Map.of(
                        "status", "PENDING",
                        "unchanged", "same"),
                Map.of(
                        "status", "ACTIVE",
                        "unchanged", "same"));

        assertThat(diff.beforeState().size()).isEqualTo(1);
        assertThat(diff.afterState().size()).isEqualTo(1);

        assertThat(diff.beforeState().get("status").asText()).isEqualTo("PENDING");

        assertThat(diff.afterState().get("status").asText()).isEqualTo("ACTIVE");

        assertThat(diff.beforeState().has("unchanged")).isFalse();

        assertThat(diff.afterState().has("unchanged")).isFalse();
    }

    @Test
    void createStoresOnlyAfterState() {
        AuditFacadeImpl.AuditDiff diff = audit.minimalDiff(
                null,
                Map.of(
                        "status", "ACTIVE",
                        "code", "E001"));

        assertThat(diff.beforeState()).isNull();
        assertThat(diff.afterState()).isNotNull();
        assertThat(diff.afterState().size()).isEqualTo(2);
    }

    @Test
    void identicalStatesProduceNoDiff() {
        Map<String, String> state = Map.of("status", "ACTIVE");

        AuditFacadeImpl.AuditDiff diff = audit.minimalDiff(state, state);

        assertThat(diff.beforeState()).isNull();
        assertThat(diff.afterState()).isNull();
    }
}
