package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AppVersionFloor;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.sync.web.generated.HeartbeatResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.Instruction;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncAck;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * The one application floor and its grace (doc 31 section 6; doc 32 section 3.3 step 1, as
 * CR-30-1 point 4 and CR-32-1 item 1 settle it): a till below a floor raised less than
 * {@code sync.app_version_floor.grace} ago keeps syncing and is told FLOOR_NOTICE; once the grace
 * is over its uploads are still taken (facts are always accepted), its heartbeat still answered,
 * and its snapshot and change log withheld (426); the grace runs from when the floor first rose
 * above its version, so a second raise does not restart it. Floor values are
 * written with the time they took effect, as the register keeps them (append-only history).
 */
class AppVersionFloorConformanceIntegrationTest extends SyncIntegrationTest {

    private static final String FLOOR = "sync.app_version_floor";

    @Autowired
    TillSigner signer;

    @Autowired
    JdbcConfigRegistry config;

    @Autowired
    AppVersionFloor floors;

    @BeforeEach
    @AfterEach
    void noFloorLeftBehind() {
        superuserJdbc().update("delete from kernel.config_value where key = ?", FLOOR);
        superuserJdbc()
                .update(
                        "delete from kernel.audit_event where event_type_code = 'SYNC_APP_VERSION_BELOW_FLOOR' and device_id = ?",
                        DEVICE);
        config.invalidate(FLOOR);
    }

    private TillSimulator till(String version) {
        return new TillSimulator(http, json, DEVICE, SHOP, signer.publicKeyBase64()).runningVersion(version);
    }

    @Test
    void atOrAboveTheFloorNothingIsSaid() {
        floorSince("1.2", Duration.ofDays(30));
        TillSimulator till = till("1.2.0");
        till.recordSales(2);

        SyncAck ack = till.uploadOnce(500);

        assertThat(ack.getLastAppliedSeq()).isEqualTo(2);
        assertThat(ack.getInstructions()).isEmpty();
    }

    @Test
    void withinTheGraceTheBatchIsTakenAndTheTillIsToldToUpdate() {
        floorSince("1.3", Duration.ofDays(2));
        TillSimulator till = till("1.2.9");
        till.recordSales(3);

        SyncAck ack = till.uploadOnce(500);

        assertThat(ack.getLastAppliedSeq()).isEqualTo(3);
        assertThat(ack.getInstructions()).singleElement().satisfies(i -> {
            assertThat(i.getType()).isEqualTo(Instruction.TypeEnum.FLOOR_NOTICE);
            assertThat(i.getDetail()).isEqualTo("1.3");
        });
        assertThat(till.heartbeat().getInstructions())
                .extracting(Instruction::getType)
                .contains(Instruction.TypeEnum.FLOOR_NOTICE);
        assertThat(kernel.committedAudit()).noneMatch(a -> "SYNC_APP_VERSION_BELOW_FLOOR".equals(a.eventType()));
    }

    /**
     * CR-30-1 point 4, CR-32-1 item 1 (wave 2, TWK-09): after the grace the facts are still
     * accepted, with a FLOOR_NOTICE; what central withholds is its reference data, the snapshot
     * and the change log, 426. The till sells on its last snapshot.
     */
    @Test
    void afterTheGraceTheBatchIsStillTakenButTheSnapshotAndTheChangeLogAreWithheld426() {
        floorSince("1.3", Duration.ofDays(15));
        TillSimulator till = till("1.2.9");
        till.recordSales(2);

        SyncAck ack = till.uploadOnce(500);

        assertThat(ack.getLastAppliedSeq()).isEqualTo(2);
        assertThat(cursor()).isEqualTo(2);
        assertThat(till.pending()).isZero();
        assertThat(ack.getInstructions()).singleElement().satisfies(i -> {
            assertThat(i.getType()).isEqualTo(Instruction.TypeEnum.FLOOR_NOTICE);
            assertThat(i.getDetail()).isEqualTo("1.3");
        });
        HeartbeatResponse health = till.heartbeat();
        assertThat(health.getInstructions())
                .extracting(Instruction::getType)
                .contains(Instruction.TypeEnum.FLOOR_NOTICE);

        assertThat(kernel.committedAudit())
                .filteredOn(a -> "SYNC_APP_VERSION_BELOW_FLOOR".equals(a.eventType()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.subject().type()).isEqualTo("device");
                    assertThat(record.subject().id()).isEqualTo(DEVICE);
                    assertThat(record.scope().deviceId()).isEqualTo(DEVICE);
                    assertThat(String.valueOf(record.after())).contains("1.2.9").contains("1.3");
                });
        Map<String, Object> audit = superuserJdbc()
                .queryForMap(
                        """
                        select a.*, t.severity
                          from kernel.audit_event a
                          join kernel.audit_event_type t on t.event_type_code = a.event_type_code
                         where a.event_type_code = ? and a.device_id = ?
                        """,
                        "SYNC_APP_VERSION_BELOW_FLOOR",
                        DEVICE);
        assertThat(audit.get("severity")).isEqualTo("ALERT");

        assertThatThrownBy(till::refreshSnapshot).isInstanceOfSatisfying(TillSimulator.Refused.class, refused -> {
            assertThat(refused.status).isEqualTo(426);
            assertThat(refused.problem.path("code").asText()).isEqualTo("sync.app_below_floor");
            assertThat(refused.problem.path("params").path("floor").asText()).isEqualTo("1.3");
            assertThat(Instant.parse(refused.problem
                            .path("params")
                            .path("grace_ended_at")
                            .asText()))
                    .isBefore(Instant.now());
        });
        assertThat(till.snapshotVersion())
                .as("the till keeps the snapshot it holds")
                .isZero();
        ResponseEntity<JsonNode> changes =
                get("/v1/sync/locations/" + SHOP + "/changes?since=0", TestIdentityProvider.deviceHeaders(DEVICE));
        assertThat(changes.getStatusCode().value()).isEqualTo(426);
        assertThat(changes.getBody().path("code").asText()).isEqualTo("sync.app_below_floor");
    }

    /**
     * Doc 31 section 6 (FIX-14): a till uploading below the version floor after the grace period
     * produces an ALERT audit event.
     */
    @Test
    void aTillPastTheGracePeriodProducesAnAlertAuditEvent() {
        floorSince("1.4", Duration.ofDays(16));
        TillSimulator till = till("1.3.1");
        till.recordSales(1);

        till.uploadOnce(500);

        assertThat(kernel.committedAudit())
                .filteredOn(a -> "SYNC_APP_VERSION_BELOW_FLOOR".equals(a.eventType()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.subject().type()).isEqualTo("device");
                    assertThat(record.subject().id()).isEqualTo(DEVICE);
                    assertThat(record.scope().deviceId()).isEqualTo(DEVICE);
                    assertThat(record.scope().entityId()).isEqualTo(ENTITY);
                });

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        """
                        select a.event_type_code, a.device_id, t.severity, a.after_state::text as after_state
                          from kernel.audit_event a
                          join kernel.audit_event_type t on t.event_type_code = a.event_type_code
                         where a.event_type_code = 'SYNC_APP_VERSION_BELOW_FLOOR' and a.device_id = ?
                        """,
                        DEVICE);
        assertThat(row.get("severity")).isEqualTo("ALERT");
        assertThat(String.valueOf(row.get("after_state"))).contains("1.3.1").contains("1.4");
    }

    /** An updated till reports its new version and receives the snapshot again at once. */
    @Test
    void aTillUpdatedAboveTheFloorReceivesTheSnapshotAgain() {
        floorSince("1.3", Duration.ofDays(15));
        TillSimulator old = till("1.2.9");
        old.heartbeat();
        assertThatThrownBy(old::refreshSnapshot).isInstanceOf(TillSimulator.Refused.class);

        TillSimulator updated = till("1.3.0");
        updated.heartbeat();

        assertThat(updated.refreshSnapshot().getVersion()).isNotNegative();
    }

    /** Within the grace nothing is withheld: the snapshot is served. */
    @Test
    void withinTheGraceTheSnapshotIsStillServed() {
        floorSince("1.3", Duration.ofDays(2));
        TillSimulator till = till("1.2.9");
        till.heartbeat();

        assertThat(till.refreshSnapshot().getVersion()).isNotNegative();
    }

    @Test
    void aSecondRaiseDoesNotRestartTheGraceOfADeviceAlreadyBelowTheFirst() {
        floorSince("1.3", Duration.ofDays(20));
        floorSince("1.4", Duration.ofDays(1));

        AppVersionFloor.Standing below = standing("1.2.9");
        assertThat(below.below()).isTrue();
        assertThat(below.floor()).isEqualTo("1.4");
        assertThat(below.afterGrace()).isTrue();
        assertThat(below.graceEndsAt())
                .isCloseTo(Instant.now().minus(Duration.ofDays(6)), within(Duration.ofMinutes(5)));

        // A device that was at 1.3 fell below only with the second raise: its grace is fresh.
        AppVersionFloor.Standing fresh = standing("1.3.5");
        assertThat(fresh.below()).isTrue();
        assertThat(fresh.afterGrace()).isFalse();
        assertThat(fresh.graceEndsAt())
                .isCloseTo(Instant.now().plus(Duration.ofDays(13)), within(Duration.ofMinutes(5)));
    }

    @Test
    void aLoweredFloorLiftsTheRefusal() {
        floorSince("1.3", Duration.ofDays(20));
        floorSince("1.2", Duration.ofDays(1));

        assertThat(standing("1.2.0").below()).isFalse();
        TillSimulator till = till("1.2.0");
        till.recordSales(1);
        assertThat(till.uploadOnce(500).getInstructions()).isEmpty();
    }

    // ---- the register ----

    /** A federation-wide floor value that took effect {@code ago}. */
    private void floorSince(String floor, Duration ago) {
        Instant at = Instant.now().minus(ago).truncatedTo(ChronoUnit.MILLIS);
        superuserJdbc()
                .update(
                        """
                        insert into kernel.config_value (key, value, effective_from, changed_at, reason)
                        values (?, to_jsonb(?::text), ?, ?, 'test')
                        """,
                        FLOOR,
                        floor,
                        Timestamp.from(at),
                        Timestamp.from(at));
        config.invalidate(FLOOR);
    }

    private AppVersionFloor.Standing standing(String version) {
        ScopeContext device = SystemScope.own(ENTITY, SHOP);
        return floors.standing(version, device);
    }

    private static org.assertj.core.data.TemporalUnitOffset within(Duration duration) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(duration.toMinutes(), ChronoUnit.MINUTES);
    }
}
