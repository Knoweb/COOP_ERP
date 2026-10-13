package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kernel sync integration test for {@link SnapshotBuilder}:
 * verifies scheduled future tombstones, location scoping, tombstone deduplication/supersession,
 * and that active live rows inherit apply_from strictly from UPSERT operations.
 */
class SnapshotBuilderKernelIntegrationTest extends SyncIntegrationTest {

    @Autowired
    SnapshotBuilder snapshotBuilder;

    @Autowired
    ChangeLog changeLog;

    @Autowired
    SystemScope transactions;

    @Autowired
    ObjectMapper mapper;

    private final ScopeContext central = SystemScope.own(ENTITY, null);
    private final ScopeContext tillShop1 = SystemScope.own(ENTITY, SHOP);
    private final ScopeContext tillShop2 = SystemScope.own(ENTITY, OTHER_SHOP);

    @BeforeEach
    void cleanLogs() {
        JdbcTemplate db = superuserJdbc();
        db.update("delete from kernel.change_log where location_id in (?, ?)", SHOP, OTHER_SHOP);
    }

    @Test
    void futureDeletesIncludedOnlyWhenApplicableAndScopedToLocation() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate yesterday = today.minusDays(1);

        UUID futureDeletedRow = Ids.next();
        UUID pastDeletedRow = Ids.next();
        UUID otherShopRow = Ids.next();

        // 1. Append future delete for SHOP
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.delete("price_list_line", futureDeletedRow)),
                    tomorrow,
                    false,
                    central);
            return null;
        });

        // 2. Append past delete for SHOP
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.delete("price_list_line", pastDeletedRow)),
                    yesterday,
                    false,
                    central);
            return null;
        });

        // 3. Append future delete for OTHER_SHOP (different location)
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, OTHER_SHOP)),
                    List.of(Change.delete("price_list_line", otherShopRow)),
                    tomorrow,
                    false,
                    central);
            return null;
        });

        // Build full snapshot for SHOP
        JsonNode snapshot = mapper.valueToTree(transactions.inOwnTransaction(
                tillShop1, () -> (Object) snapshotBuilder.build(tillShop1, 0, Duration.ofDays(30))));

        JsonNode tombstones = snapshot.path("tables").path("price_list_line").path("tombstones");
        List<String> tombstoneIds = new ArrayList<>();
        tombstones.forEach(t -> {
            tombstoneIds.add(t.path("rowId").asText());
            assertThat(t.path("applyFrom").asText()).isEqualTo(tomorrow.toString());
        });

        // SHOP snapshot must contain futureDeletedRow, and MUST NOT contain pastDeletedRow or otherShopRow
        assertThat(tombstoneIds).contains(futureDeletedRow.toString());
        assertThat(tombstoneIds).doesNotContain(pastDeletedRow.toString());
        assertThat(tombstoneIds).doesNotContain(otherShopRow.toString());

        // Build full snapshot for OTHER_SHOP
        JsonNode snapshot2 = mapper.valueToTree(transactions.inOwnTransaction(
                tillShop2, () -> (Object) snapshotBuilder.build(tillShop2, 0, Duration.ofDays(30))));
        JsonNode tombstones2 = snapshot2.path("tables").path("price_list_line").path("tombstones");
        List<String> tombstoneIds2 = new ArrayList<>();
        tombstones2.forEach(t -> tombstoneIds2.add(t.path("rowId").asText()));

        assertThat(tombstoneIds2).contains(otherShopRow.toString());
        assertThat(tombstoneIds2).doesNotContain(futureDeletedRow.toString());
    }

    @Test
    void rescheduledTombstoneAndUpsertCancellation() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate nextWeek = today.plusDays(7);

        UUID rescheduledRow = Ids.next();
        UUID cancelledRow = Ids.next();

        // 1. Row 1 scheduled for tomorrow, then rescheduled to nextWeek
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.delete("price_list_line", rescheduledRow)),
                    tomorrow,
                    false,
                    central);
            return null;
        });
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.delete("price_list_line", rescheduledRow)),
                    nextWeek,
                    false,
                    central);
            return null;
        });

        // 2. Row 2 scheduled for delete tomorrow, but subsequently re-upserted with UPSERT
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.delete("price_list_line", cancelledRow)),
                    tomorrow,
                    false,
                    central);
            return null;
        });
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.upsert("price_list_line", cancelledRow)),
                    tomorrow,
                    false,
                    central);
            return null;
        });

        JsonNode snapshot = mapper.valueToTree(transactions.inOwnTransaction(
                tillShop1, () -> (Object) snapshotBuilder.build(tillShop1, 0, Duration.ofDays(30))));

        JsonNode tombstones = snapshot.path("tables").path("price_list_line").path("tombstones");
        List<JsonNode> matchingRescheduled = new ArrayList<>();
        List<JsonNode> matchingCancelled = new ArrayList<>();

        tombstones.forEach(t -> {
            if (rescheduledRow.toString().equals(t.path("rowId").asText())) {
                matchingRescheduled.add(t);
            }
            if (cancelledRow.toString().equals(t.path("rowId").asText())) {
                matchingCancelled.add(t);
            }
        });

        // Exactly one tombstone for rescheduledRow with nextWeek date
        assertThat(matchingRescheduled).hasSize(1);
        assertThat(matchingRescheduled.getFirst().path("applyFrom").asText()).isEqualTo(nextWeek.toString());

        // Cancelled row (replaced by later UPSERT) must NOT appear in tombstones
        assertThat(matchingCancelled).isEmpty();
    }

    @Test
    void latestApplyFromOnlyConsidersUpsertOperations() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate tomorrow = today.plusDays(1);

        UUID activeRow = Ids.next();

        // 1. Log an UPSERT with today
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.upsert("price_list_line", activeRow)),
                    today,
                    false,
                    central);
            return null;
        });

        // 2. Log a DELETE with tomorrow
        transactions.inOwnTransaction(central, () -> {
            changeLog.append(
                    List.of(new Target(ENTITY, SHOP)),
                    List.of(Change.delete("price_list_line", activeRow)),
                    tomorrow,
                    false,
                    central);
            return null;
        });

        // Verify latestApplyFrom method in SnapshotBuilder within till scope
        var applyFromMap = transactions.inOwnTransaction(tillShop1, () -> snapshotBuilder.latestApplyFrom(SHOP));
        LocalDate resolvedDate =
                applyFromMap.getOrDefault("price_list_line", java.util.Map.of()).get(activeRow);

        // Must be 'today' from the UPSERT, NOT 'tomorrow' from the DELETE!
        assertThat(resolvedDate).isEqualTo(today);
    }
}
