package lk.coopfed.knoweb.m1party.internal.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lk.coopfed.knoweb.m1party.api.UserUpdated;
import org.junit.jupiter.api.Test;

/** Which user updates the till must pick up at the next heartbeat (wave 4 TD-05). */
class UserUpdatedUrgencyTest {

    private static final UUID USER = UUID.fromString("0190a810-0000-7000-8900-000000000901");
    private static final UUID ENTITY = UUID.fromString("0190a810-0000-7000-8900-000000000902");

    private final ObjectMapper mapper = new ObjectMapper();

    private boolean urgent(String kind, String status) {
        return M1ChangeLogFanOut.takesTheOperatorOffTheTill(
                mapper.valueToTree(new UserUpdated(USER, USER, ENTITY, kind, status)));
    }

    @Test
    void aKindThatDoesNotSignInAtATillIsUrgent() {
        assertThat(urgent("BACK_OFFICE", "ACTIVE")).isTrue();
    }

    @Test
    void aStatusOtherThanActiveIsUrgent() {
        assertThat(urgent("TILL", "LOCKED")).isTrue();
        assertThat(urgent("BOTH", "PENDING")).isTrue();
    }

    @Test
    void anActiveTillOperatorIsNotUrgent() {
        assertThat(urgent("TILL", "ACTIVE")).isFalse();
        assertThat(urgent("BOTH", "ACTIVE")).isFalse();
    }
}
