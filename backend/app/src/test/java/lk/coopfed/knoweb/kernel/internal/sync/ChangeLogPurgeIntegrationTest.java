package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The change-log retention of doc 32 DR-4, applied to the log itself (ChangeLogPurgeJob, kernel
 * V0082): entries older than the retention are removed a whole version at a time, the latest entry
 * of a row still to take effect is kept (the full snapshot reads its apply_from from the log), and
 * a till whose version is below the cut is sent the full snapshot, never a delta with holes.
 */
class ChangeLogPurgeIntegrationTest extends SyncIntegrationTest {

    @Autowired
    ChangeLog changeLog;

    @Autowired
    ChangeLogPurgeJob purge;

    @Autowired
    PlatformTransactionManager transactions;

    private final ScopeContext federation = SystemScope.own(OTHER_ENTITY, null);

    @Test
    void oldVersionsGoWholeAFutureApplyFromStaysAndAnOlderTillTakesTheFullSnapshot() {
        LocalDate later = LocalDate.now().plusDays(60);
        publish(null, Change.upsert("location", SHOP)); // version 1
        publish(later, Change.upsert("till_position", POSITION)); // version 2: a price-list day ahead
        publish(null, Change.upsert("location", SHOP)); // version 3
        recordedAgo(1, Duration.ofDays(40));
        recordedAgo(2, Duration.ofDays(40));
        recordedAgo(3, Duration.ofDays(1));

        int removed = purge.purgeOlderThan(Duration.ofDays(30));

        assertThat(removed).isGreaterThanOrEqualTo(1);
        List<Map<String, Object>> left = superuserJdbc()
                .queryForList(
                        "select version, table_name from kernel.change_log where location_id = ? order by version",
                        SHOP);
        assertThat(left)
                .extracting(r -> r.get("version") + " " + r.get("table_name"))
                .containsExactly("2 till_position", "3 location");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select purged_through_version from kernel.location_snapshot_version where location_id = ?",
                                Long.class,
                                SHOP))
                .isEqualTo(2);

        // A till at version 1 is below the cut: the full snapshot, with the held row's date.
        JsonNode full = snapshot(1);
        assertThat(full.path("full_snapshot_required").asBoolean()).isTrue();
        assertThat(full.path("tables").path("till_position").path("upserts"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("apply_from").asText()).isEqualTo(later.toString()));
        assertThat(changes(1).path("full_snapshot_required").asBoolean()).isTrue();

        // A till at version 2 still gets its delta.
        JsonNode delta = snapshot(2);
        assertThat(delta.path("full_snapshot_required").asBoolean()).isFalse();
        assertThat(delta.path("tables").fieldNames()).toIterable().containsExactly("location");
        assertThat(changes(2).path("full_snapshot_required").asBoolean()).isFalse();
    }

    @Test
    void theLastDayIsNeverPurged() {
        assertThatThrownBy(() -> purge.purgeOlderThan(Duration.ofHours(1)))
                .hasMessageContaining("keeps at least the last 24 hours");
    }

    @Test
    void aShopWithNothingOldIsLeftAlone() {
        publish(null, Change.upsert("location", SHOP));

        purge.purgeOlderThan(Duration.ofDays(30));

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.change_log where location_id = ?", Long.class, SHOP))
                .isEqualTo(1);
        assertThat(snapshot(0).path("full_snapshot_required").asBoolean()).isTrue();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select purged_through_version from kernel.location_snapshot_version where location_id = ?",
                                Long.class,
                                SHOP))
                .isZero();
    }

    private long publish(LocalDate applyFrom, Change... changes) {
        return new TransactionTemplate(transactions)
                .execute(status ->
                        changeLog.append(new Target(ENTITY, SHOP), List.of(changes), applyFrom, false, federation));
    }

    private void recordedAgo(long version, Duration ago) {
        superuserJdbc()
                .update(
                        "update kernel.change_log set recorded_at = ? where location_id = ? and version = ?",
                        Timestamp.from(Instant.now().minus(ago)),
                        SHOP,
                        version);
    }

    private JsonNode snapshot(long since) {
        return get(
                        "/v1/sync/locations/" + SHOP + "/snapshot?since=" + since,
                        TestIdentityProvider.deviceHeaders(DEVICE))
                .getBody();
    }

    private JsonNode changes(long since) {
        return get("/v1/sync/locations/" + SHOP + "/changes?since=" + since, TestIdentityProvider.deviceHeaders(DEVICE))
                .getBody();
    }
}
