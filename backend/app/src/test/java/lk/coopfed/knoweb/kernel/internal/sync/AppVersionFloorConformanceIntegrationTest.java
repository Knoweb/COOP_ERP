package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lk.coopfed.knoweb.kernel.api.AppVersionFloor;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.sync.web.generated.HeartbeatResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.Instruction;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncAck;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The one application floor and its grace (doc 31 section 6; doc 32 section 3.3 step 1, "below
 * floor after grace: 426 with reason"): a till below a floor raised less than
 * {@code sync.app_version_floor.grace} ago keeps syncing and is told FLOOR_NOTICE; once the grace
 * is over its uploads are refused, its heartbeat still answered; the grace runs from when the
 * floor first rose above its version, so a second raise does not restart it. Floor values are
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
    }

    @Test
    void afterTheGraceUploadsAreRefused426ButTheHeartbeatIsStillAnswered() {
        floorSince("1.3", Duration.ofDays(15));
        TillSimulator till = till("1.2.9");
        till.recordSales(2);

        assertThatThrownBy(() -> till.uploadOnce(500)).isInstanceOfSatisfying(TillSimulator.Refused.class, refused -> {
            assertThat(refused.status).isEqualTo(426);
            assertThat(refused.problem.path("code").asText()).isEqualTo("sync.app_below_floor");
            assertThat(refused.problem.path("params").path("floor").asText()).isEqualTo("1.3");
            assertThat(Instant.parse(refused.problem
                            .path("params")
                            .path("grace_ended_at")
                            .asText()))
                    .isBefore(Instant.now());
        });
        // Nothing was applied: the till keeps its outbox and sells on (doc 31 section 6).
        assertThat(cursor()).isZero();
        assertThat(till.pending()).isEqualTo(2);
        HeartbeatResponse health = till.heartbeat();
        assertThat(health.getInstructions())
                .extracting(Instruction::getType)
                .contains(Instruction.TypeEnum.FLOOR_NOTICE);
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
