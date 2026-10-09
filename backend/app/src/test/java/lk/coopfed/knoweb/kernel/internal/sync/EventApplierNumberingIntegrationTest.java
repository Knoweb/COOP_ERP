package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EventApplierNumberingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private EventApplier eventApplier;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OuterCommand outer;

    private ScopeContext deviceAt(UUID entityId, UUID locationId, UUID deviceId) {
        Scope scope = new Scope(entityId, locationId);
        return new ScopeContext(
                null,
                deviceId,
                entityId,
                List.of(scope),
                scope,
                PolicyClass.OWN,
                null,
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    @Test
    void realIngestion_validNumberingMetadataIsStored() throws Exception {
        UUID deviceId = Ids.next();
        UUID entityId = TEST_FEDERATION;
        UUID seriesId = Ids.next();
        long docNumber = 42L;

        DeviceDirectory.DeviceRecord record =
                new DeviceDirectory.DeviceRecord(deviceId, entityId, "ACTIVE", "SN", null, null, null, false);

        ObjectNode event = mapper.createObjectNode();
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode document = mapper.createObjectNode();
        document.put("series_id", seriesId.toString());
        document.put("doc_number", docNumber);
        payload.set("document", document);
        event.set("payload", payload);

        ScopeContext ctx = deviceAt(entityId, null, deviceId);
        outer.run(ctx, () -> {
            eventApplier.quarantine(
                    ctx, record, Ids.next(), 1L, Ids.next(), "receipt.issued.v1", "SCHEMA", "Bad format", "{}", event);
            return null;
        });

        // Verify trusted numbering metadata was extracted and stored
        superuserJdbc()
                .query(
                        "select series_id, doc_number from kernel.sync_quarantine where device_id = ? and device_seq = 1",
                        rs -> {
                            assertThat(rs.getObject("series_id", UUID.class)).isEqualTo(seriesId);
                            assertThat(rs.getLong("doc_number")).isEqualTo(docNumber);
                        },
                        deviceId);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.sync_quarantine where device_id = ?",
                                Long.class,
                                deviceId))
                .isEqualTo(1L);
    }

    @Test
    void realIngestion_invalidNumbersAreRejectedWithoutBreakingStorage() throws Exception {
        UUID deviceId = Ids.next();
        UUID entityId = TEST_FEDERATION;
        UUID seriesId = Ids.next();

        DeviceDirectory.DeviceRecord record =
                new DeviceDirectory.DeviceRecord(deviceId, entityId, "ACTIVE", "SN", null, null, null, false);
        ScopeContext ctx = deviceAt(entityId, null, deviceId);

        // Scenario A: Negative doc number
        ObjectNode eventA = mapper.createObjectNode();
        ObjectNode docA = mapper.createObjectNode();
        docA.put("series_id", seriesId.toString());
        docA.put("doc_number", -5L);
        eventA.set("payload", mapper.createObjectNode().set("document", docA));

        outer.run(ctx, () -> {
            eventApplier.quarantine(
                    ctx, record, Ids.next(), 1L, Ids.next(), "receipt.issued.v1", "SCHEMA", "Bad format", "{}", eventA);
            return null;
        });

        // Scenario B: Fractional number
        ObjectNode eventB = mapper.createObjectNode();
        ObjectNode docB = mapper.createObjectNode();
        docB.put("series_id", seriesId.toString());
        docB.put("doc_number", 42.5);
        eventB.set("payload", mapper.createObjectNode().set("document", docB));

        outer.run(ctx, () -> {
            eventApplier.quarantine(
                    ctx, record, Ids.next(), 2L, Ids.next(), "receipt.issued.v1", "SCHEMA", "Bad format", "{}", eventB);
            return null;
        });

        // Scenario C: Overflowing string number
        ObjectNode eventC = mapper.createObjectNode();
        ObjectNode docC = mapper.createObjectNode();
        docC.put("series_id", seriesId.toString());
        docC.put("doc_number", "9999999999999999999999999");
        eventC.set("payload", mapper.createObjectNode().set("document", docC));

        outer.run(ctx, () -> {
            eventApplier.quarantine(
                    ctx, record, Ids.next(), 3L, Ids.next(), "receipt.issued.v1", "SCHEMA", "Bad format", "{}", eventC);
            return null;
        });

        // Scenario D: Zero number
        ObjectNode eventD = mapper.createObjectNode();
        ObjectNode docD = mapper.createObjectNode();
        docD.put("series_id", seriesId.toString());
        docD.put("doc_number", 0);
        eventD.set("payload", mapper.createObjectNode().set("document", docD));

        outer.run(ctx, () -> {
            eventApplier.quarantine(
                    ctx, record, Ids.next(), 4L, Ids.next(), "receipt.issued.v1", "SCHEMA", "Bad format", "{}", eventD);
            return null;
        });

        // Verify none of these populated the doc_number, but quarantine was saved
        superuserJdbc()
                .query(
                        "select device_seq, doc_number from kernel.sync_quarantine where device_id = ? order by device_seq",
                        rs -> {
                            assertThat(rs.getObject("doc_number")).isNull();
                        },
                        deviceId);

        Long count = superuserJdbc()
                .queryForObject(
                        "select count(*) from kernel.sync_quarantine where device_id = ?", Long.class, deviceId);
        assertThat(count).isEqualTo(4L);
    }
}
