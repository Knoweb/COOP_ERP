package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The change-log fan-out of doc 32 section 5.2: a producer records a publication through
 * {@link ChangeLog} in its own transaction, for every shop it concerns and whatever entity it
 * acts as; each shop's version moves by one per publication; the till pulls the log after its
 * version, a page at a time, never half a publication, and is sent to a full snapshot when it has
 * no version or one older than the retention (DR-4).
 */
class ChangeLogIntegrationTest extends SyncIntegrationTest {

    @Autowired
    ChangeLog changeLog;

    @Autowired
    PlatformTransactionManager transactions;

    /** A central publisher: the Federation's scope, writing into an MPCS's shops. */
    private final ScopeContext federation = SystemScope.own(OTHER_ENTITY, null);

    @Test
    void aPublicationBumpsEachShopsVersionOnceAndTheTillReadsItAfterItsVersion() {
        UUID sku = Ids.next();
        UUID price = Ids.next();
        Map<UUID, Long> first = publish(
                List.of(new Target(ENTITY, SHOP), new Target(ENTITY, OTHER_SHOP)),
                List.of(Change.upsert("sku", sku), Change.upsert("price_list_line", price)),
                null,
                false);
        Map<UUID, Long> second = publish(
                List.of(new Target(ENTITY, SHOP)),
                List.of(Change.delete("price_list_line", price)),
                LocalDate.of(2026, 10, 1),
                true);

        assertThat(first).containsEntry(SHOP, 1L).containsEntry(OTHER_SHOP, 1L);
        assertThat(second).containsEntry(SHOP, 2L);

        JsonNode page = changes(1, 500).getBody();
        assertThat(page.path("current_version").asLong()).isEqualTo(2);
        assertThat(page.path("full_snapshot_required").asBoolean()).isFalse();
        assertThat(page.path("entries")).hasSize(1);
        JsonNode entry = page.path("entries").get(0);
        assertThat(entry.path("version").asLong()).isEqualTo(2);
        assertThat(entry.path("table").asText()).isEqualTo("price_list_line");
        assertThat(entry.path("row_id").asText()).isEqualTo(price.toString());
        assertThat(entry.path("op").asText()).isEqualTo("DELETE");
        assertThat(entry.path("apply_from").asText()).isEqualTo("2026-10-01");
        assertThat(entry.path("urgent").asBoolean()).isTrue();
        assertThat(page.path("next_since").asLong()).isEqualTo(2);
        assertThat(page.path("has_more").asBoolean()).isFalse();
    }

    @Test
    void aTillWithNoVersionIsSentToAFullSnapshotAndGetsTheWholeLog() {
        publish(List.of(new Target(ENTITY, SHOP)), List.of(Change.upsert("sku", Ids.next())), null, false);

        JsonNode page = changes(0, 500).getBody();

        assertThat(page.path("full_snapshot_required").asBoolean()).isTrue();
        assertThat(page.path("entries")).hasSize(1);
    }

    @Test
    void aTillOnAShopNothingWasPublishedToGetsAnEmptyPageNotAFullSnapshot() {
        // Enrolled before any price list or catalogue reached its shop: nothing to download, and
        // the snapshot endpoint has nothing to serve, so "full snapshot required" would strand it.
        JsonNode page = changes(0, 500).getBody();

        assertThat(page.path("current_version").asLong()).isZero();
        assertThat(page.path("full_snapshot_required").asBoolean()).isFalse();
        assertThat(page.path("entries")).isEmpty();
        assertThat(page.path("next_since").asLong()).isZero();
        assertThat(page.path("has_more").asBoolean()).isFalse();
    }

    @Test
    void aTillOlderThanTheRetentionIsSentToAFullSnapshot() {
        publish(List.of(new Target(ENTITY, SHOP)), List.of(Change.upsert("sku", Ids.next())), null, false);
        publish(List.of(new Target(ENTITY, SHOP)), List.of(Change.upsert("sku", Ids.next())), null, false);
        superuserJdbc()
                .update(
                        "update kernel.change_log set recorded_at = now() - interval '40 days' where location_id = ? and version = 2",
                        SHOP);

        assertThat(changes(1, 500).getBody().path("full_snapshot_required").asBoolean())
                .isTrue();
        assertThat(changes(2, 500).getBody().path("full_snapshot_required").asBoolean())
                .isFalse();
    }

    @Test
    void aPageEndsOnAPublicationAndALargePublicationComesWhole() {
        publish(List.of(new Target(ENTITY, SHOP)), rows("sku", 2), null, false); // version 1: two rows
        publish(List.of(new Target(ENTITY, SHOP)), rows("sku", 2), null, false); // version 2: two rows
        publish(List.of(new Target(ENTITY, SHOP)), rows("lot", 5), null, false); // version 3: five rows

        JsonNode first = changes(0, 3).getBody();
        assertThat(first.path("entries").findValues("version"))
                .extracting(JsonNode::asLong)
                .containsOnly(1L);
        assertThat(first.path("next_since").asLong()).isEqualTo(1);
        assertThat(first.path("has_more").asBoolean()).isTrue();

        JsonNode second = changes(1, 3).getBody();
        assertThat(second.path("entries")).hasSize(2);
        assertThat(second.path("next_since").asLong()).isEqualTo(2);

        JsonNode third = changes(2, 3).getBody();
        assertThat(third.path("entries")).hasSize(5);
        assertThat(third.path("next_since").asLong()).isEqualTo(3);
        assertThat(third.path("has_more").asBoolean()).isFalse();
    }

    @Test
    void theLogIsWrittenOnlyInsideThePublishingTransaction() {
        assertThatThrownBy(() -> changeLog.append(
                        new Target(ENTITY, SHOP), List.of(Change.upsert("sku", Ids.next())), null, false, federation))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Change.upsert("", Ids.next())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anotherShopsLogIsNotTheDevicesToRead() {
        ResponseEntity<JsonNode> refused = get(
                "/v1/sync/locations/" + OTHER_SHOP + "/changes?since=0", TestIdentityProvider.deviceHeaders(DEVICE));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("sync.location_mismatch");
    }

    @Test
    void theSnapshotItselfIsTheSecondPartOfTheTicket() {
        ResponseEntity<JsonNode> response =
                get("/v1/sync/locations/" + SHOP + "/snapshot?since=0", TestIdentityProvider.deviceHeaders(DEVICE));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.getBody().path("code").asText()).isEqualTo("sync.snapshot.unavailable");
    }

    private Map<UUID, Long> publish(List<Target> targets, List<Change> changes, LocalDate applyFrom, boolean urgent) {
        return new TransactionTemplate(transactions)
                .execute(status -> changeLog.append(targets, changes, applyFrom, urgent, federation));
    }

    private ResponseEntity<JsonNode> changes(long since, int limit) {
        return get(
                "/v1/sync/locations/" + SHOP + "/changes?since=" + since + "&limit=" + limit,
                TestIdentityProvider.deviceHeaders(DEVICE));
    }

    private static List<Change> rows(String table, int count) {
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            changes.add(Change.upsert(table, Ids.next()));
        }
        return changes;
    }
}
