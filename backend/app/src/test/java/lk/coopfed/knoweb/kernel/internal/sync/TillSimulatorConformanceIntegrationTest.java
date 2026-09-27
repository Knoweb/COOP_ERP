package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotDelta;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cases of doc 32 section 11 that the second part of K-08 makes passable, run by the
 * {@link TillSimulator} against the running application: Snapshot atomicity, apply_from and
 * Burst. Two instances is {@link TwoInstancesConformanceIntegrationTest}. The producers of the
 * change log are M1-10 and M2-09; until they exist the test publishes as they will, a row change
 * and its change-log entry in one transaction.
 */
class TillSimulatorConformanceIntegrationTest extends SyncIntegrationTest {

    @Autowired
    ChangeLog changeLog;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    TillSigner signer;

    @Autowired
    JdbcConfigRegistry config;

    private final ScopeContext federation = SystemScope.own(OTHER_ENTITY, null);

    private TillSimulator till() {
        return new TillSimulator(http, json, DEVICE, SHOP, signer.publicKeyBase64());
    }

    @Test
    void aTillWithNoVersionTakesTheFullSnapshotSignedAndWhole() {
        TillSimulator till = till();

        SnapshotDelta full = till.refreshSnapshot();

        assertThat(full.getFullSnapshotRequired()).isTrue();
        assertThat(full.getVersion()).isZero();
        assertThat(full.getKeyId()).isEqualTo(signer.keyId());
        // The shop and its till position, as M1's contributor gives them.
        assertThat(till.table("location")).containsOnlyKeys(SHOP);
        assertThat(till.table("location").get(SHOP)).containsEntry("name_en", "Sync test shop");
        assertThat(till.table("till_position")).containsOnlyKeys(POSITION);
        assertThat(till.table("till_position").get(POSITION)).containsEntry("primary_till", true);
        // Every contributor's table is in the full snapshot, empty or not, so the till replaces all.
        assertThat(full.getTables()).containsKeys("location", "till_position", "operator", "sku", "tax_category");
    }

    @Test
    void snapshotAtomicity_aPowerCutMidApplyLeavesTheOldVersionAndTheNextApplyCompletes() {
        TillSimulator till = tillAtVersionOne();
        UUID second = addPosition(2);
        UUID third = addPosition(3);
        renameShop("Renamed shop");
        long version = publish(
                null,
                Change.upsert("location", SHOP),
                Change.upsert("till_position", second),
                Change.upsert("till_position", third));

        till.powerCutAfterRows(2);
        assertThatThrownBy(till::refreshSnapshot).isInstanceOf(TillSimulator.PowerCut.class);

        // It trades on the old version: nothing of the new one is live.
        assertThat(till.snapshotVersion()).isEqualTo(1);
        assertThat(till.table("location").get(SHOP)).containsEntry("name_en", "Sync test shop");
        assertThat(till.table("till_position")).containsOnlyKeys(POSITION);

        // Re-apply completes, from the same version, to the whole of the new one.
        SnapshotDelta delta = till.refreshSnapshot();
        assertThat(delta.getFullSnapshotRequired()).isFalse();
        assertThat(delta.getSince()).isEqualTo(1);
        assertThat(till.snapshotVersion()).isEqualTo(version);
        assertThat(till.table("location").get(SHOP)).containsEntry("name_en", "Renamed shop");
        assertThat(till.table("till_position")).containsOnlyKeys(POSITION, second, third);
    }

    @Test
    void aDeltaNamesOnlyWhatChangedAndARowThatLeftTheSnapshotIsATombstone() {
        TillSimulator till = till();
        till.refreshSnapshot();
        UUID second = addPosition(2);
        publish(null, Change.upsert("till_position", second));
        till.refreshSnapshot();

        // The position leaves the shop (M1 moved it; the row is no longer the shop's), and a row
        // that never existed is published as deleted.
        superuserJdbc()
                .update(
                        "update party.till_position set location_id = ? where till_position_id = ?",
                        OTHER_SHOP,
                        second);
        UUID ghost = UUID.randomUUID();
        long version = publish(null, Change.upsert("till_position", second), Change.delete("till_position", ghost));

        SnapshotDelta delta = till.refreshSnapshot();

        assertThat(delta.getTables()).containsOnlyKeys("till_position");
        assertThat(delta.getTables().get("till_position").getUpserts()).isEmpty();
        assertThat(delta.getTables().get("till_position").getTombstones())
                .extracting(t -> t.getRowId())
                .containsExactlyInAnyOrder(second, ghost);
        assertThat(till.snapshotVersion()).isEqualTo(version);
        assertThat(till.table("till_position")).containsOnlyKeys(POSITION);
    }

    @Test
    void aSnapshotWhoseTableDoesNotMatchItsSignedManifestIsNotApplied() {
        TillSimulator till = till();
        UUID second = addPosition(2);
        publish(null, Change.upsert("till_position", second));
        SnapshotDelta genuine = till.refreshSnapshot();

        // A table changed on the way no longer matches the hash in the signed manifest, and a
        // manifest changed on the way no longer matches its signature: either way another till
        // refuses the whole snapshot and keeps what it had.
        TillSimulator other = till();
        genuine.getTables()
                .get("till_position")
                .getUpserts()
                .getFirst()
                .getData()
                .put("position_no", 99);
        assertThatThrownBy(() -> other.apply(genuine)).hasMessageContaining("hash of table till_position");
        genuine.setManifest(genuine.getManifest().replace("\"full\":true", "\"full\":false"));
        assertThatThrownBy(() -> other.apply(genuine)).hasMessageContaining("not signed by central");
        assertThat(other.snapshotVersion()).isZero();
        assertThat(other.table("till_position")).isEmpty();
    }

    @Test
    void applyFrom_pricesDatedTomorrowDoNotSellTodayAndActivateAtDayOpen() {
        TillSimulator till = till();
        till.refreshSnapshot();
        LocalDate tomorrow = till.businessDate().plusDays(1);
        UUID second = addPosition(2);
        long version = publish(tomorrow, Change.upsert("till_position", second));

        till.refreshSnapshot();

        // Downloaded and held: the till is at the new version, the row is not live today.
        assertThat(till.snapshotVersion()).isEqualTo(version);
        assertThat(till.table("till_position")).doesNotContainKey(second);
        assertThat(till.held()).extracting(TillSimulator.HeldRow::rowId).containsExactly(second);

        till.dayOpen(tomorrow);

        assertThat(till.table("till_position")).containsKey(second);
        assertThat(till.held()).isEmpty();
    }

    @Test
    void anUrgentChangeIsSaidInTheDeltaAndAtTheHeartbeat() {
        TillSimulator till = tillAtVersionOne();
        publish(null, true, Change.upsert("location", SHOP));

        assertThat(till.heartbeat().getUrgentChange()).isTrue();
        assertThat(till.refreshSnapshot().getUrgent()).isTrue();
    }

    @Test
    void burst_aFleetBacklogIsAbsorbedAndEveryTillIsServed() throws Exception {
        int tills = 12;
        int eventsPerTill = 60;
        List<TillSimulator> fleet = new ArrayList<>();
        for (int i = 0; i < tills; i++) {
            UUID device = addTill(10 + i);
            TillSimulator till = new TillSimulator(http, json, device, SHOP, signer.publicKeyBase64());
            till.recordSales(eventsPerTill);
            fleet.add(till);
        }
        // Two batches at a time on this instance: most tills are told to wait, then served.
        setLimit("sync.ingest.max_concurrent", "2");
        try {
            Instant deadline = Instant.now().plusSeconds(120);
            ExecutorService pool = Executors.newFixedThreadPool(tills);
            List<Future<Integer>> waits = new ArrayList<>();
            for (TillSimulator till : fleet) {
                waits.add(pool.submit(() -> till.drain(20, deadline)));
            }
            int totalWaits = 0;
            for (Future<Integer> wait : waits) {
                totalWaits += wait.get();
            }
            pool.shutdown();

            // Absorbed: every till's backlog is at central, applied once, in order; fairness:
            // every till was served, none left behind by the others.
            for (TillSimulator till : fleet) {
                assertThat(till.pending()).isZero();
                assertThat(till.lastAcknowledged()).isEqualTo(eventsPerTill);
                assertThat(cursorOf(till.deviceId())).isEqualTo(eventsPerTill);
                assertThat(outboxSequencesOf(till.deviceId()))
                        .hasSize(eventsPerTill)
                        .doesNotHaveDuplicates();
            }
            assertThat(rateLimiter.inFlight()).isZero();
            System.out.println("Burst: " + tills + " tills told to wait " + totalWaits + " times in all");
        } finally {
            setLimit("sync.ingest.max_concurrent", "32");
        }
    }

    // ---- arranging ----

    /**
     * A till holding version 1 of its shop: something was published, and the till took the full
     * snapshot. (A till at version 0 holds nothing, and is always sent the full snapshot.)
     */
    private TillSimulator tillAtVersionOne() {
        publish(null, Change.upsert("location", SHOP));
        TillSimulator till = till();
        assertThat(till.refreshSnapshot().getFullSnapshotRequired()).isTrue();
        assertThat(till.snapshotVersion()).isEqualTo(1);
        return till;
    }

    private long publish(LocalDate applyFrom, Change... changes) {
        return publish(applyFrom, false, changes);
    }

    private long publish(LocalDate applyFrom, boolean urgent, Change... changes) {
        return new TransactionTemplate(transactions)
                .execute(status ->
                        changeLog.append(new Target(ENTITY, SHOP), List.of(changes), applyFrom, urgent, federation));
    }

    private UUID addPosition(int number) {
        UUID position = UUID.randomUUID();
        superuserJdbc()
                .update(
                        "insert into party.till_position (till_position_id, location_id, position_no, owner_entity_id) values (?, ?, ?, ?)",
                        position,
                        SHOP,
                        number,
                        ENTITY);
        return position;
    }

    /** Another till of the shop: a position, an active device on it, its cursor. */
    private UUID addTill(int number) {
        UUID position = addPosition(number);
        UUID device = UUID.randomUUID();
        JdbcTemplate db = superuserJdbc();
        db.update(
                """
                insert into party.device (device_id, hardware_serial, device_kind, owner_entity_id,
                                          current_till_position_id, status, enrolled_at, location_id)
                values (?, ?, 'POS_TERMINAL', ?, ?, 'ACTIVE', now(), ?)
                """,
                device,
                "SN-BURST-" + number,
                ENTITY,
                position,
                SHOP);
        db.update("insert into kernel.device_sync_cursor (device_id, owner_entity_id) values (?, ?)", device, ENTITY);
        return device;
    }

    private void renameShop(String name) {
        superuserJdbc().update("update party.location set name_en = ? where location_id = ?", name, SHOP);
    }

    private void setLimit(String key, String value) {
        superuserJdbc()
                .update("update kernel.config_item set default_value = cast(? as jsonb) where key = ?", value, key);
        config.invalidate(key);
    }

    private long cursorOf(UUID device) {
        return superuserJdbc()
                .queryForObject(
                        "select last_applied_seq from kernel.device_sync_cursor where device_id = ?",
                        Long.class,
                        device);
    }

    private List<Long> outboxSequencesOf(UUID device) {
        return superuserJdbc()
                .queryForList(
                        "select source_seq from kernel.event_outbox where source = ? order by source_seq",
                        Long.class,
                        device.toString());
    }
}
