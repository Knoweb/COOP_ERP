package lk.coopfed.knoweb.m6pos.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionPayloadCheckTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionPayloadCheck check = new SessionPayloadCheck();

    @Test
    void validClosePayloadWithZeroRemainingPasses() {
        ObjectNode payload = validClosePayload();
        payload.put("open_sessions_remaining", 0);
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload)).isNull();
    }

    @Test
    void validClosePayloadWithPositiveRemainingPasses() {
        ObjectNode payload = validClosePayload();
        payload.put("open_sessions_remaining", 2);
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload)).isNull();
    }

    @Test
    void validClosePayloadWithoutRemainingPasses() {
        ObjectNode payload = validClosePayload();
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload)).isNull();
    }

    @Test
    void invalidClosePayloadWithNegativeRemainingFails() {
        ObjectNode payload = validClosePayload();
        payload.put("open_sessions_remaining", -1);
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload))
                .isEqualTo("open_sessions_remaining is not a non-negative integer");
    }

    @Test
    void invalidClosePayloadWithDecimalRemainingFails() {
        ObjectNode payload = validClosePayload();
        payload.put("open_sessions_remaining", 1.5);
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload))
                .isEqualTo("open_sessions_remaining is not a non-negative integer");
    }

    @Test
    void invalidClosePayloadWithStringRemainingFails() {
        ObjectNode payload = validClosePayload();
        payload.put("open_sessions_remaining", "0");
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload))
                .isEqualTo("open_sessions_remaining is not a non-negative integer");
    }

    @Test
    void missingSessionIdFails() {
        ObjectNode payload = validClosePayload();
        payload.remove("session_id");
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload))
                .isEqualTo("session_id is missing or not a UUID");
    }

    @Test
    void missingClosedAtFails() {
        ObjectNode payload = validClosePayload();
        payload.remove("closed_at");
        assertThat(check.problemWith(PosIngestConsumer.SESSION_CLOSED, payload))
                .isEqualTo("closed_at is missing or not an instant");
    }

    private ObjectNode validClosePayload() {
        ObjectNode node = mapper.createObjectNode();
        node.put("session_id", UUID.randomUUID().toString());
        node.put("till_position_id", UUID.randomUUID().toString());
        node.put("operator_user_id", UUID.randomUUID().toString());
        node.put("business_date", "2026-10-16");
        node.put("closed_at", "2026-10-16T10:00:00Z");
        node.put("counted_cash", "1000.00");
        node.put("expected_cash", "1000.00");
        node.put("variance", "0.00");
        return node;
    }
}
