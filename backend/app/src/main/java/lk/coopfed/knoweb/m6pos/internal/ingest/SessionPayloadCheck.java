package lk.coopfed.knoweb.m6pos.internal.ingest;

import static lk.coopfed.knoweb.kernel.api.DevicePayloadCheck.firstNot;
import static lk.coopfed.knoweb.kernel.api.DevicePayloadCheck.present;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.DevicePayloadCheck;
import org.springframework.stereotype.Component;

/**
 * The shape of a till's session events as {@link PosIngestConsumer} reads them, for the sync
 * gateway to check before the event reaches the outbox (wave 2, M6-01, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (1)). A session event
 * that fails it is quarantined as SCHEMA with its ALERT, instead of failing in this module's
 * consumer three times and going to the dead letters, applied nowhere and flagged nowhere.
 *
 * <pre>
 *   session_id                                          a UUID
 *   opened_at (opened.v1), closed_at (closed.v1)        an instant
 *   till_position_id, operator_user_id                  when present, a UUID
 *   business_date                                       when present, a date
 *   float_amount, counted_cash, expected_cash, variance when present, a decimal written as text
 * </pre>
 *
 * Exactly what the till writes (till/core Facts.sessionOpened, sessionClosed) and what the
 * consumer parses; no business rule (a close without its open, a variance) is checked here.
 */
@Component
class SessionPayloadCheck implements DevicePayloadCheck {

    private static final List<String> UUIDS = List.of("till_position_id", "operator_user_id");
    private static final List<String> DECIMALS = List.of("float_amount", "counted_cash", "expected_cash", "variance");

    @Override
    public Set<String> eventTypes() {
        return Set.of(PosIngestConsumer.SESSION_OPENED, PosIngestConsumer.SESSION_CLOSED);
    }

    @Override
    public String problemWith(String eventType, JsonNode payload) {
        if (!DevicePayloadCheck.isUuid(present(payload, "session_id"))) {
            return "session_id is missing or not a UUID";
        }
        String at = PosIngestConsumer.SESSION_OPENED.equals(eventType) ? "opened_at" : "closed_at";
        if (!DevicePayloadCheck.isInstant(present(payload, at))) {
            return at + " is missing or not an instant";
        }
        String wrong = firstNot(payload, UUIDS, DevicePayloadCheck::isUuid, "a UUID");
        if (wrong == null) {
            wrong = firstNot(payload, List.of("business_date"), DevicePayloadCheck::isDate, "a date");
        }
        if (wrong == null) {
            wrong = firstNot(payload, DECIMALS, DevicePayloadCheck::isDecimalText, "a decimal written as text");
        }
        return wrong;
    }
}
