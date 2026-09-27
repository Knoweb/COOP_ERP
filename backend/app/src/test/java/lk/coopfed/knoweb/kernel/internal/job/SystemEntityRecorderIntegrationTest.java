package lk.coopfed.knoweb.kernel.internal.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database names the Federation (kernel V0061; CR-21A-1 item 2, decided 27 September 2026
 * on the architect's delegation): {@code kernel.system_entity()} answers the entity {@code
 * coop-erp.system.entity-id} names, every class reads it, only the start-up recorder writes it.
 */
class SystemEntityRecorderIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SOMEONE_ELSE = UUID.fromString("0190e000-0000-7000-8000-0000000000e1");

    @Autowired
    SystemEntityRecorder recorder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theApplicationUserReadsTheConfiguredFederation() {
        assertThat(jdbc.queryForObject("select kernel.system_entity()", UUID.class))
                .isEqualTo(TEST_FEDERATION);
    }

    @Test
    void theApplicationUserCannotWriteIt() {
        assertThatThrownBy(() ->
                        jdbc.update("update kernel.system_identity set entity_id = ?::uuid", SOMEONE_ELSE.toString()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from kernel.system_identity"))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("select kernel.system_entity()", UUID.class))
                .isEqualTo(TEST_FEDERATION);
    }

    @Test
    void theRecorderPutsThePropertyBackOnStart() {
        superuserJdbc().update("update kernel.system_identity set entity_id = ?::uuid", SOMEONE_ELSE.toString());

        recorder.record();

        assertThat(superuserJdbc().queryForObject("select entity_id from kernel.system_identity", UUID.class))
                .isEqualTo(TEST_FEDERATION);
    }

    @Test
    void anInstanceWithoutThePropertyLeavesTheRowAlone() {
        new SystemEntityRecorder(POSTGRES.getJdbcUrl(), "coop_migrator", "coop_migrator", "").record();

        assertThat(superuserJdbc().queryForObject("select entity_id from kernel.system_identity", UUID.class))
                .isEqualTo(TEST_FEDERATION);
    }

    @Test
    void withNoRowTheFunctionAnswersNull() {
        superuserJdbc().update("delete from kernel.system_identity");
        try {
            assertThat(jdbc.queryForObject("select kernel.system_entity()", UUID.class))
                    .isNull();
        } finally {
            recorder.record();
        }
    }
}
