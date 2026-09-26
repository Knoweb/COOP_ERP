package lk.coopfed.knoweb.m1party.internal.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesHolderChanged;
import lk.coopfed.knoweb.kernel.api.SyncStatus;
import lk.coopfed.knoweb.m1party.api.AssignDeviceToPosition;
import lk.coopfed.knoweb.m1party.api.DeviceAssigned;
import lk.coopfed.knoweb.m1party.api.DeviceEnrolled;
import lk.coopfed.knoweb.m1party.api.DevicePositionChanged;
import lk.coopfed.knoweb.m1party.api.DeviceReinstated;
import lk.coopfed.knoweb.m1party.api.DeviceRetired;
import lk.coopfed.knoweb.m1party.api.DeviceRevoked;
import lk.coopfed.knoweb.m1party.api.DeviceSuspended;
import lk.coopfed.knoweb.m1party.api.EnrolDevice;
import lk.coopfed.knoweb.m1party.api.ReinstateDevice;
import lk.coopfed.knoweb.m1party.api.RetireDevice;
import lk.coopfed.knoweb.m1party.api.SuspendDevice;
import lk.coopfed.knoweb.m1party.query.DeviceFilter;
import lk.coopfed.knoweb.m1party.query.DeviceQueries;
import lk.coopfed.knoweb.m1party.query.DeviceView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M1-06 against PostgreSQL, as the application user: the device commands of 21A section 6 with
 * the audit records and events each names, doc 21 flow 6.6 (replacing a faulty till, with the
 * counter transfer through the kernel and its series.holder_changed.v1), every guard as a
 * failing case with its message id and nothing committed, the revoke the sync gateway relays,
 * and what a caller scoped to one shop may see and do.
 *
 * <p>The positions and series are arranged by the superuser, as M1-05's handlers would leave
 * them (a position's RCT and CPR series, the shop's GRN series held by its primary till), so
 * this test does not depend on M1-05. The sync gateway's answer to "is this device drained" is
 * {@link TestBeans.SwitchableSyncStatus}: no device is drained unless a test says so, which is
 * what the kernel answers for a device that never reported to the sync gateway.
 */
@Import(DevicesPostgresIntegrationTest.TestBeans.class)
class DevicesPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e600-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0190e600-0000-7000-8000-000000000002");
    private static final UUID SHOP1 = UUID.fromString("0190e600-0000-7000-8000-000000000101");
    private static final UUID SHOP2 = UUID.fromString("0190e600-0000-7000-8000-000000000102");
    private static final UUID OTHER_SHOP = UUID.fromString("0190e600-0000-7000-8000-000000000201");
    private static final UUID P1 = UUID.fromString("0190e600-0000-7000-8000-000000000111");
    private static final UUID P2 = UUID.fromString("0190e600-0000-7000-8000-000000000112");
    private static final UUID RETIRED_POSITION = UUID.fromString("0190e600-0000-7000-8000-000000000119");
    private static final UUID P3 = UUID.fromString("0190e600-0000-7000-8000-000000000121");
    private static final UUID RCT_P1 = UUID.fromString("0190e600-0000-7000-8000-000000000301");
    private static final UUID CPR_P1 = UUID.fromString("0190e600-0000-7000-8000-000000000302");
    private static final UUID GRN_SHOP1 = UUID.fromString("0190e600-0000-7000-8000-000000000303");
    private static final UUID RCT_P2 = UUID.fromString("0190e600-0000-7000-8000-000000000304");
    private static final UUID USER = UUID.fromString("0190e600-0000-7000-8000-000000000010");

    private static final String FLOOR_KEY = AssignDeviceToPositionHandler.VERSION_FLOOR;

    @Autowired
    Handles<EnrolDevice, UUID> enrolDevice;

    @Autowired
    Handles<AssignDeviceToPosition, UUID> assignDevice;

    @Autowired
    Handles<SuspendDevice, UUID> suspendDevice;

    @Autowired
    Handles<ReinstateDevice, UUID> reinstateDevice;

    @Autowired
    Handles<RetireDevice, UUID> retireDevice;

    @Autowired
    DeviceQueries queries;

    @Autowired
    TestBeans.SwitchableSyncStatus sync;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void twoSocietiesThreeShopsFourPositions() {
        JdbcTemplate admin = superuserJdbc();
        admin.update("delete from kernel.numbering_series where owner_entity_id in (?, ?)", MPCS, OTHER);
        admin.update("delete from kernel.config_value where key = ?", FLOOR_KEY);
        admin.execute("truncate table party.device, party.till_position, party.location, party.entity_relationship,"
                + " party.entity_party_directory, party.federation_identity, party.entity cascade");
        sync.drained.clear();

        insertEntity(admin, MPCS, "M960");
        insertEntity(admin, OTHER, "M961");
        insertShop(admin, SHOP1, MPCS, "S01");
        insertShop(admin, SHOP2, MPCS, "S02");
        insertShop(admin, OTHER_SHOP, OTHER, "S01");
        insertPosition(admin, P1, SHOP1, MPCS, 1, "ACTIVE");
        insertPosition(admin, P2, SHOP1, MPCS, 2, "ACTIVE");
        insertPosition(admin, RETIRED_POSITION, SHOP1, MPCS, 9, "RETIRED");
        insertPosition(admin, P3, SHOP2, MPCS, 1, "ACTIVE");
        admin.update("update party.location set primary_till_position_id = ? where location_id = ?", P1, SHOP1);

        // What RegisterTillPosition and SetPrimaryTill (M1-05) register: the till series of each
        // position and the shop's location series. The counters stand where the sales left them.
        insertSeries(admin, RCT_P1, "RCT", "TILL_POSITION", SHOP1, P1, "M960-S01-T1-RCT", 42);
        insertSeries(admin, CPR_P1, "CPR", "TILL_POSITION", SHOP1, P1, "M960-S01-T1-CPR", 7);
        insertSeries(admin, GRN_SHOP1, "GRN", "LOCATION", SHOP1, null, "M960-S01-GRN", 15);
        insertSeries(admin, RCT_P2, "RCT", "TILL_POSITION", SHOP1, P2, "M960-S01-T2-RCT", 1);
    }

    @AfterEach
    void noFloorLeftBehind() {
        superuserJdbc().update("delete from kernel.config_value where key = ?", FLOOR_KEY);
    }

    // ---- EnrolDevice -------------------------------------------------------------------------

    @Test
    void aDeviceIsEnrolledAtAShopAuditedAndPublished() {
        UUID device = enrol("SN-0001", SHOP1);

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select status, owner_entity_id, location_id, current_till_position_id, app_version,"
                                + " enrolled_at from party.device where device_id = ?",
                        device);
        assertThat(row.get("status")).isEqualTo("ENROLLED");
        assertThat(row.get("owner_entity_id")).isEqualTo(MPCS);
        assertThat(row.get("location_id")).isEqualTo(SHOP1);
        assertThat(row.get("current_till_position_id")).isNull();
        assertThat(row.get("app_version")).isEqualTo("1.4.0");
        assertThat(row.get("enrolled_at")).isNotNull();

        assertThat(audit("DEVICE_ENROLLED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("device");
            assertThat(record.subject().id()).isEqualTo(device);
            assertThat(record.before()).isNull();
            assertThat(((Map<?, ?>) record.after()).get("stagingReference")).isEqualTo("STG-SN-0001");
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("ENROLLED");
        });
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new DeviceEnrolled(device, MPCS, SHOP1, "SN-0001", "POS_TERMINAL", "1.4.0", "ENROLLED"));
    }

    @Test
    void theGuardsOfEnrolDevice() {
        enrol("SN-0001", SHOP1);
        kernel.reset();

        refused(
                () -> enrolDevice.handle(enrolment("SN-0002", SHOP1), view(MPCS, PolicyClass.FEDERATION_VIEW)),
                "m1.device.scope_required");
        refused(() -> enrolDevice.handle(enrolment("SN-0002", OTHER_SHOP), own(MPCS)), "m1.device.location_not_found");
        refused(
                () -> enrolDevice.handle(enrolment("SN-0002", SHOP2), atShop(MPCS, SHOP1)),
                "m1.device.location_not_found");
        refused(
                () -> enrolDevice.handle(new EnrolDevice("SN-0002", "TOASTER", SHOP1, "1.4.0", "STG"), own(MPCS)),
                "m1.device.kind_invalid");
        refused(
                () -> enrolDevice.handle(new EnrolDevice("SN-0002", "POS_TERMINAL", SHOP1, "1.4.0", " "), own(MPCS)),
                "m1.device.not_attested");
        refused(
                () -> enrolDevice.handle(new EnrolDevice("SN-0002", "POS_TERMINAL", SHOP1, "latest", "STG"), own(MPCS)),
                "m1.device.app_version_invalid");
        refused(
                () -> enrolDevice.handle(new EnrolDevice(" ", "POS_TERMINAL", SHOP1, "1.4.0", "STG"), own(MPCS)),
                "m1.device.serial_required");
        refused(() -> enrolDevice.handle(enrolment("SN-0001", SHOP2), own(MPCS)), "m1.device.serial_duplicate");
        // Another society cannot see the serial, and the unique key still refuses it in words.
        refused(() -> enrolDevice.handle(enrolment("SN-0001", OTHER_SHOP), own(OTHER)), "m1.device.serial_duplicate");

        assertNothingCommitted();
        assertThat(superuserJdbc().queryForObject("select count(*) from party.device", Integer.class))
                .isEqualTo(1);
    }

    // ---- AssignDeviceToPosition ----------------------------------------------------------------

    @Test
    void aFirstAssignmentActivatesTheDeviceAndMovesTheCountersOfThePositionAndOfThePrimaryTill() {
        UUID device = enrol("SN-0001", SHOP1);
        kernel.reset();

        assignDevice.handle(assign(device, P1), own(MPCS));

        assertThat(deviceRow(device)).containsEntry("status", "ACTIVE").containsEntry("current_till_position_id", P1);
        assertThat(holders(RCT_P1, CPR_P1, GRN_SHOP1)).containsOnly(device);
        assertThat(nextNumber(RCT_P1)).as("the counter is moved, never touched").isEqualTo(42L);

        assertThat(audit("DEVICE_ASSIGNED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(device);
            assertThat(record.reason()).isEqualTo("NEW_TILL: first till of the shop");
            assertThat(nested(record.before(), "device").get("status")).isEqualTo("ENROLLED");
            assertThat(nested(record.after(), "device").get("status")).isEqualTo("ACTIVE");
            assertThat(nested(record.after(), "device").get("tillPositionId")).isEqualTo(P1);
        });
        assertThat(audit("SERIES_HOLDER_CHANGED")).hasSize(3);
        assertThat(events(DeviceAssigned.class)).singleElement().satisfies(event -> {
            assertThat(event.assignedDeviceId()).isEqualTo(device);
            assertThat(event.tillPositionId()).isEqualTo(P1);
            assertThat(event.seriesMoved()).containsExactlyInAnyOrder(RCT_P1, CPR_P1, GRN_SHOP1);
        });
        assertThat(events(SeriesHolderChanged.class))
                .extracting(SeriesHolderChanged::seriesId)
                .containsExactlyInAnyOrder(RCT_P1, CPR_P1, GRN_SHOP1);
        assertThat(events(DevicePositionChanged.class)).isEmpty();
    }

    @Test
    void aPositionThatIsNotThePrimaryTillMovesOnlyItsOwnCounters() {
        UUID device = enrol("SN-0002", SHOP1);
        kernel.reset();

        assignDevice.handle(assign(device, P2), own(MPCS));

        assertThat(holders(RCT_P2)).containsOnly(device);
        assertThat(holders(RCT_P1, CPR_P1, GRN_SHOP1)).containsOnlyNulls();
        assertThat(events(SeriesHolderChanged.class)).singleElement().satisfies(event -> {
            assertThat(event.seriesId()).isEqualTo(RCT_P2);
            assertThat(event.previousDeviceId()).isNull();
            assertThat(event.deviceId()).isEqualTo(device);
        });
    }

    /**
     * Doc 21 flow 6.6, "Replacing a faulty till": the administrator suspends the old device, a
     * drained-check failure blocks the assignment until the loss is recorded, the new device
     * enrolled at staging is assigned and takes the counters over from where central has them,
     * and the old device is retired. Every step is audited and published; the kernel publishes
     * series.holder_changed.v1 for each series that moved (21A section 9, "asserts the kernel
     * series.holder_changed event").
     */
    @Test
    void scenario66ReplacingAFaultyTill() {
        UUID old = enrol("SN-OLD", SHOP1);
        assignDevice.handle(assign(old, P1), own(MPCS));
        kernel.reset();

        // 1. The administrator suspends the old device; the gateway is told to revoke it.
        suspendDevice.handle(new SuspendDevice(old, "STOLEN", "taken from the counter"), own(MPCS));

        assertThat(deviceRow(old)).containsEntry("status", "SUSPENDED").containsEntry("current_till_position_id", P1);
        assertThat(audit("DEVICE_SUSPENDED")).singleElement().satisfies(record -> {
            assertThat(record.reason()).isEqualTo("STOLEN: taken from the counter");
            assertThat(((Map<?, ?>) record.before()).get("status")).isEqualTo("ACTIVE");
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("SUSPENDED");
        });
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new DeviceSuspended(old, MPCS, SHOP1, P1, "STOLEN"),
                        new DeviceRevoked(old, MPCS, SHOP1, "SN-OLD", "SUSPENDED", "STOLEN"));
        assertThat(holders(RCT_P1, CPR_P1, GRN_SHOP1))
                .as("the counters stay with the suspended device until a replacement takes them")
                .containsOnly(old);

        // 2. The replacement is enrolled at staging and shipped.
        UUID replacement = enrol("SN-NEW", SHOP1);
        kernel.reset();

        // 3. The old device's outbox was never drained: the assignment is refused, nothing moves.
        refused(() -> assignDevice.handle(replacement(replacement, false), own(MPCS)), "m1.device.outbox_not_drained");
        assertNothingCommitted();
        assertThat(holders(RCT_P1, CPR_P1, GRN_SHOP1)).containsOnly(old);
        assertThat(deviceRow(replacement)).containsEntry("status", "ENROLLED");

        // 4. The administrator records the loss; the counters transfer from central's highest number.
        assignDevice.handle(replacement(replacement, true), own(MPCS));

        assertThat(deviceRow(replacement))
                .containsEntry("status", "ACTIVE")
                .containsEntry("current_till_position_id", P1);
        assertThat(deviceRow(old)).containsEntry("status", "SUSPENDED").containsEntry("current_till_position_id", null);
        assertThat(holders(RCT_P1, CPR_P1, GRN_SHOP1)).containsOnly(replacement);
        assertThat(nextNumber(RCT_P1)).isEqualTo(42L);
        assertThat(nextNumber(GRN_SHOP1)).isEqualTo(15L);

        assertThat(audit("DEVICE_POSITION_CHANGED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(replacement);
            assertThat(record.reason()).isEqualTo("REPLACEMENT: stolen till replaced");
            assertThat(nested(record.before(), "previousDevice").get("tillPositionId"))
                    .isEqualTo(P1);
            assertThat(nested(record.after(), "previousDevice").get("tillPositionId"))
                    .isNull();
            assertThat(nested(record.after(), "device").get("tillPositionId")).isEqualTo(P1);
        });
        assertThat(audit("DEVICE_OUTBOX_LOSS_RECORDED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("till_position");
            assertThat(record.subject().id()).isEqualTo(P1);
            assertThat(((Map<?, ?>) record.after()).get("deviceIds")).isEqualTo(List.of(old));
        });
        assertThat(audit("SERIES_HOLDER_CHANGED")).hasSize(3);
        assertThat(events(DevicePositionChanged.class)).singleElement().satisfies(event -> {
            assertThat(event.assignedDeviceId()).isEqualTo(replacement);
            assertThat(event.tillPositionId()).isEqualTo(P1);
            assertThat(event.previousDeviceId()).isEqualTo(old);
            assertThat(event.previousTillPositionId()).isNull();
            assertThat(event.outboxLossRecorded()).isTrue();
            assertThat(event.seriesMoved()).containsExactlyInAnyOrder(RCT_P1, CPR_P1, GRN_SHOP1);
        });
        assertThat(events(SeriesHolderChanged.class)).hasSize(3).allSatisfy(event -> {
            assertThat(event.previousDeviceId()).isEqualTo(old);
            assertThat(event.deviceId()).isEqualTo(replacement);
            assertThat(event.ownerEntityId()).isEqualTo(MPCS);
        });
        kernel.reset();

        // 5. The old device is retired, wiped; the gateway refuses it for good.
        retireDevice.handle(new RetireDevice(old, "WIPED", null), own(MPCS));

        assertThat(deviceRow(old)).containsEntry("status", "RETIRED");
        assertThat(audit("DEVICE_RETIRED")).singleElement().satisfies(record -> assertThat(record.reason())
                .isEqualTo("WIPED"));
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new DeviceRetired(old, MPCS, SHOP1, "WIPED"),
                        new DeviceRevoked(old, MPCS, SHOP1, "SN-OLD", "RETIRED", "WIPED"));
    }

    @Test
    void aDrainedOldDeviceNeedsNoLossRecorded() {
        UUID old = enrol("SN-OLD", SHOP1);
        assignDevice.handle(assign(old, P1), own(MPCS));
        suspendDevice.handle(new SuspendDevice(old, "FAULTY", null), own(MPCS));
        UUID replacement = enrol("SN-NEW", SHOP1);
        sync.drained.add(old);
        kernel.reset();

        assignDevice.handle(replacement(replacement, false), own(MPCS));

        assertThat(holders(RCT_P1, CPR_P1, GRN_SHOP1)).containsOnly(replacement);
        assertThat(audit("DEVICE_OUTBOX_LOSS_RECORDED")).isEmpty();
        assertThat(events(DevicePositionChanged.class))
                .singleElement()
                .satisfies(event -> assertThat(event.outboxLossRecorded()).isFalse());
    }

    @Test
    void anActiveDeviceMovesToAnotherPositionOfItsShopOnceItIsDrained() {
        UUID device = enrol("SN-0001", SHOP1);
        assignDevice.handle(assign(device, P2), own(MPCS));
        kernel.reset();

        refused(() -> assignDevice.handle(assign(device, P1), own(MPCS)), "m1.device.outbox_not_drained");
        assertNothingCommitted();

        sync.drained.add(device);
        assignDevice.handle(assign(device, P1), own(MPCS));

        assertThat(deviceRow(device)).containsEntry("current_till_position_id", P1);
        assertThat(events(DevicePositionChanged.class)).singleElement().satisfies(event -> {
            assertThat(event.previousDeviceId()).isNull();
            assertThat(event.previousTillPositionId()).isEqualTo(P2);
            assertThat(event.outboxLossRecorded()).isFalse();
        });
        assertThat(audit("DEVICE_POSITION_CHANGED")).hasSize(1);
    }

    @Test
    void theGuardsOfAssignDeviceToPosition() {
        UUID active = enrol("SN-ACTIVE", SHOP1);
        assignDevice.handle(assign(active, P1), own(MPCS));
        UUID enrolled = enrol("SN-ENROLLED", SHOP1);
        UUID suspended = enrol("SN-SUSPENDED", SHOP1);
        assignDevice.handle(assign(suspended, P2), own(MPCS));
        suspendDevice.handle(new SuspendDevice(suspended, "LOST", null), own(MPCS));
        UUID workstation =
                enrolDevice.handle(new EnrolDevice("SN-WS", "WORKSTATION", SHOP1, "1.4.0", "STG-WS"), own(MPCS));
        UUID otherSocietys = enrolDevice.handle(enrolment("SN-OTHER", OTHER_SHOP), own(OTHER));
        // The floor is read through the register's cache, keyed by scope: a shop made for this
        // test is a scope nothing has read the floor for, so no cached answer stands in the way.
        UUID floorShop = UUID.randomUUID();
        UUID floorPosition = UUID.randomUUID();
        insertShop(superuserJdbc(), floorShop, MPCS, "F" + floorShop.toString().substring(0, 6));
        insertPosition(superuserJdbc(), floorPosition, floorShop, MPCS, 1, "ACTIVE");
        UUID old = enrolDevice.handle(
                new EnrolDevice("SN-OLDAPP", "POS_TERMINAL", floorShop, "1.2.9", "STG-OLDAPP"), own(MPCS));
        kernel.reset();

        refused(
                () -> assignDevice.handle(assign(enrolled, P2), view(MPCS, PolicyClass.FEDERATION_VIEW)),
                "m1.device.scope_required");
        refused(() -> assignDevice.handle(assign(otherSocietys, P2), own(MPCS)), "m1.device.not_found");
        refused(() -> assignDevice.handle(assign(suspended, P2), own(MPCS)), "m1.device.not_assignable");
        refused(() -> assignDevice.handle(assign(workstation, P2), own(MPCS)), "m1.device.not_a_till");
        refused(
                () -> assignDevice.handle(assign(enrolled, UUID.randomUUID()), own(MPCS)),
                "m1.device.position_not_found");
        refused(
                () -> assignDevice.handle(assign(enrolled, RETIRED_POSITION), own(MPCS)),
                "m1.device.position_not_active");
        refused(() -> assignDevice.handle(assign(enrolled, P3), own(MPCS)), "m1.device.position_other_location");
        refused(() -> assignDevice.handle(assign(active, P1), own(MPCS)), "m1.device.already_assigned");
        refused(() -> assignDevice.handle(assign(enrolled, P1), own(MPCS)), "m1.device.position_occupied");
        refused(
                () -> assignDevice.handle(new AssignDeviceToPosition(enrolled, P2, " ", null, true), own(MPCS)),
                "m1.device.reason_required");
        refused(() -> assignDevice.handle(assign(enrolled, P2), own(MPCS)), "m1.device.outbox_not_drained");

        // The version floor comes from the configuration register (doc 31 section 6).
        superuserJdbc()
                .update(
                        "insert into kernel.config_value (key, value, reason) values (?, '\"1.3\"'::jsonb, 'test')",
                        FLOOR_KEY);
        refused(
                () -> assignDevice.handle(assign(old, floorPosition), atShop(MPCS, floorShop)),
                "m1.device.below_floor");

        assertNothingCommitted();
        assertThat(deviceRow(enrolled)).containsEntry("status", "ENROLLED");
        assertThat(holders(RCT_P2)).containsOnly(suspended);
    }

    // ---- SuspendDevice, ReinstateDevice, RetireDevice -------------------------------------------

    @Test
    void aRecoveredDeviceIsReinstatedIntoItsOwnPosition() {
        UUID device = enrol("SN-0001", SHOP1);
        assignDevice.handle(assign(device, P1), own(MPCS));
        suspendDevice.handle(new SuspendDevice(device, "LOST", null), own(MPCS));
        kernel.reset();

        reinstateDevice.handle(new ReinstateDevice(device, "RECOVERED", "found in the store room"), own(MPCS));

        assertThat(deviceRow(device)).containsEntry("status", "ACTIVE").containsEntry("current_till_position_id", P1);
        assertThat(holders(RCT_P1)).containsOnly(device);
        assertThat(audit("DEVICE_REINSTATED")).singleElement().satisfies(record -> {
            assertThat(record.reason()).isEqualTo("RECOVERED: found in the store room");
            assertThat(((Map<?, ?>) record.before()).get("status")).isEqualTo("SUSPENDED");
        });
        assertThat(kernel.committedEvents())
                .containsExactly(new DeviceReinstated(device, MPCS, SHOP1, P1, "RECOVERED"));
    }

    @Test
    void aDeviceReplacedWhileSuspendedComesBackWithoutAPosition() {
        UUID old = enrol("SN-OLD", SHOP1);
        assignDevice.handle(assign(old, P1), own(MPCS));
        suspendDevice.handle(new SuspendDevice(old, "FAULTY", null), own(MPCS));
        UUID replacement = enrol("SN-NEW", SHOP1);
        assignDevice.handle(replacement(replacement, true), own(MPCS));
        kernel.reset();

        reinstateDevice.handle(new ReinstateDevice(old, "REPAIRED", null), own(MPCS));

        assertThat(deviceRow(old)).containsEntry("status", "ACTIVE").containsEntry("current_till_position_id", null);
        assertThat(kernel.committedEvents()).containsExactly(new DeviceReinstated(old, MPCS, SHOP1, null, "REPAIRED"));
    }

    @Test
    void theGuardsOfSuspendReinstateAndRetire() {
        UUID enrolled = enrol("SN-ENROLLED", SHOP1);
        UUID active = enrol("SN-ACTIVE", SHOP1);
        assignDevice.handle(assign(active, P1), own(MPCS));
        UUID retired = enrol("SN-RETIRED", SHOP1);
        retireDevice.handle(new RetireDevice(retired, "WIPED", null), own(MPCS));
        kernel.reset();

        refused(
                () -> suspendDevice.handle(
                        new SuspendDevice(active, "LOST", null), view(MPCS, PolicyClass.FEDERATION_VIEW)),
                "m1.device.scope_required");
        refused(
                () -> suspendDevice.handle(new SuspendDevice(UUID.randomUUID(), "LOST", null), own(MPCS)),
                "m1.device.not_found");
        refused(
                () -> suspendDevice.handle(new SuspendDevice(enrolled, "LOST", null), own(MPCS)),
                "m1.device.not_active");
        refused(
                () -> suspendDevice.handle(new SuspendDevice(active, null, null), own(MPCS)),
                "m1.device.reason_required");

        refused(
                () -> reinstateDevice.handle(new ReinstateDevice(active, "RECOVERED", null), own(MPCS)),
                "m1.device.not_suspended");
        suspendDevice.handle(new SuspendDevice(active, "LOST", null), own(MPCS));
        kernel.reset();
        refused(
                () -> reinstateDevice.handle(new ReinstateDevice(active, "", null), own(MPCS)),
                "m1.device.reason_required");

        refused(
                () -> retireDevice.handle(new RetireDevice(active, "WIPED", null), own(MPCS)),
                "m1.device.still_assigned");
        refused(
                () -> retireDevice.handle(new RetireDevice(retired, "WIPED", null), own(MPCS)),
                "m1.device.already_retired");
        refused(
                () -> retireDevice.handle(new RetireDevice(enrolled, null, null), own(MPCS)),
                "m1.device.reason_required");

        assertNothingCommitted();
        assertThat(deviceRow(enrolled)).containsEntry("status", "ENROLLED");
        assertThat(deviceRow(active)).containsEntry("status", "SUSPENDED");
    }

    @Test
    void anEnrolledDeviceThatNeverWorkedIsRetiredAndRevoked() {
        UUID device = enrol("SN-DOA", SHOP1);
        kernel.reset();

        retireDevice.handle(new RetireDevice(device, "DEAD_ON_ARRIVAL", "wiped at staging"), own(MPCS));

        assertThat(deviceRow(device)).containsEntry("status", "RETIRED");
        assertThat(events(DeviceRevoked.class))
                .containsExactly(new DeviceRevoked(device, MPCS, SHOP1, "SN-DOA", "RETIRED", "DEAD_ON_ARRIVAL"));
    }

    // ---- Row-level security: a caller scoped to one shop ---------------------------------------

    @Test
    void aShopScopedCallerSeesAndManagesOnlyTheDevicesOfItsShop() {
        UUID atShop1 = enrol("SN-S1", SHOP1);
        UUID atShop2 = enrol("SN-S2", SHOP2);
        UUID elsewhere = enrolDevice.handle(enrolment("SN-X", OTHER_SHOP), own(OTHER));
        kernel.reset();

        ScopeContext shop1 = atShop(MPCS, SHOP1);
        assertThat(queries.listDevices(new DeviceFilter(null, null), shop1))
                .extracting(DeviceView::deviceId)
                .containsExactly(atShop1);
        assertThat(queries.getDevice(atShop2, shop1)).isEmpty();
        assertThat(queries.listDevices(new DeviceFilter(null, null), own(MPCS)))
                .extracting(DeviceView::deviceId)
                .containsExactlyInAnyOrder(atShop1, atShop2);
        assertThat(queries.listDevices(new DeviceFilter(null, null), view(MPCS, PolicyClass.FEDERATION_VIEW)))
                .extracting(DeviceView::deviceId)
                .containsExactlyInAnyOrder(atShop1, atShop2, elsewhere);
        assertThat(queries.listDevices(new DeviceFilter(SHOP2, null), own(MPCS)))
                .extracting(DeviceView::deviceId)
                .containsExactly(atShop2);

        // The handlers see what the policies let them see: a sibling shop's device is not found.
        refused(() -> suspendDevice.handle(new SuspendDevice(atShop2, "LOST", null), shop1), "m1.device.not_found");
        refused(() -> assignDevice.handle(assign(atShop2, P3), shop1), "m1.device.not_found");
        assertNothingCommitted();

        // Its own shop's device it may assign, and the list then shows the primary till.
        assignDevice.handle(assign(atShop1, P1), shop1);
        assertThat(queries.getDevice(atShop1, shop1)).hasValueSatisfying(view -> {
            assertThat(view.tillPositionId()).isEqualTo(P1);
            assertThat(view.positionNo()).isEqualTo(1);
            assertThat(view.primaryTill()).isTrue();
            assertThat(view.status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void thePoliciesThemselvesKeepAShopScopedWriterToItsShop() {
        UUID atShop2 = enrol("SN-S2", SHOP2);

        // Insert at the sibling shop: refused by own_write.
        assertThatThrownBy(() -> asApplicationUser(
                        MPCS,
                        SHOP1,
                        "OWN",
                        () -> jdbc.update(
                                "insert into party.device (device_id, hardware_serial, device_kind, owner_entity_id,"
                                        + " location_id) values (?, 'SN-SQL', 'POS_TERMINAL', ?, ?)",
                                UUID.randomUUID(),
                                MPCS,
                                SHOP2)))
                .isInstanceOf(DataAccessException.class);
        // Update of the sibling shop's device: the row is invisible, nothing changes.
        assertThat(asApplicationUser(
                        MPCS,
                        SHOP1,
                        "OWN",
                        () -> jdbc.update("update party.device set status = 'RETIRED' where device_id = ?", atShop2)))
                .isZero();
        // The Federation view reads it and writes nothing.
        assertThat(asApplicationUser(
                        MPCS,
                        null,
                        "FEDERATION_VIEW",
                        () -> jdbc.update("update party.device set status = 'RETIRED' where device_id = ?", atShop2)))
                .isZero();
        // A position is held only by an ACTIVE or SUSPENDED device (V0009).
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update party.device set current_till_position_id = ? where device_id = ?",
                                P3,
                                atShop2))
                .isInstanceOf(DataAccessException.class);
        assertThat(deviceRow(atShop2)).containsEntry("status", "ENROLLED");
    }

    // ---- over HTTP ---------------------------------------------------------------------------

    @Test
    void theSliceEnrolsAssignsListsAndSuspendsADevice() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(USER, MPCS));
        headers.set("X-Scope-Entity", MPCS.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> enrolled = http.exchange(
                "/v1/party/devices",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "hardwareSerial", "SN-HTTP-1",
                                "deviceKind", "POS_TERMINAL",
                                "locationId", SHOP1.toString(),
                                "appVersion", "1.4.0",
                                "stagingReference", "STG-HTTP-1"),
                        headers),
                JsonNode.class);
        assertThat(enrolled.getStatusCode())
                .as(String.valueOf(enrolled.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String deviceId = enrolled.getBody().get("deviceId").asText();
        assertThat(enrolled.getBody().get("status").asText()).isEqualTo("ENROLLED");

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> badVersion = http.exchange(
                "/v1/party/devices",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "hardwareSerial", "SN-HTTP-2",
                                "deviceKind", "POS_TERMINAL",
                                "locationId", SHOP1.toString(),
                                "appVersion", "one",
                                "stagingReference", "STG-HTTP-2"),
                        headers),
                JsonNode.class);
        assertThat(badVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> assigned = http.exchange(
                "/v1/party/devices/" + deviceId + "/assign",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("tillPositionId", P1.toString(), "reasonCode", "NEW_TILL"), headers),
                JsonNode.class);
        assertThat(assigned.getStatusCode())
                .as(String.valueOf(assigned.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody().get("tillPositionId").asText()).isEqualTo(P1.toString());
        assertThat(assigned.getBody().get("primaryTill").asBoolean()).isTrue();

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> occupied = http.exchange(
                "/v1/party/devices/" + deviceId + "/assign",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("tillPositionId", P1.toString(), "reasonCode", "NEW_TILL"), headers),
                JsonNode.class);
        assertThat(occupied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(occupied.getBody().get("code").asText()).isEqualTo("m1.device.already_assigned");

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> badReason = http.exchange(
                "/v1/party/devices/" + deviceId + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reasonCode", "BORED"), headers),
                JsonNode.class);
        assertThat(badReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Void> suspended = http.exchange(
                "/v1/party/devices/" + deviceId + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reasonCode", "FAULTY"), headers),
                Void.class);
        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> list = http.exchange(
                "/v1/party/devices?locationId=" + SHOP1, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody().get(0).get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(list.getBody().get(0).get("positionNo").asInt()).isEqualTo(1);
        assertThat(events(DeviceRevoked.class)).hasSize(1);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private UUID enrol(String serial, UUID location) {
        return enrolDevice.handle(enrolment(serial, location), own(MPCS));
    }

    private static EnrolDevice enrolment(String serial, UUID location) {
        return new EnrolDevice(serial, "POS_TERMINAL", location, "1.4.0", "STG-" + serial);
    }

    private static AssignDeviceToPosition assign(UUID device, UUID position) {
        return new AssignDeviceToPosition(device, position, "NEW_TILL", "first till of the shop", false);
    }

    private static AssignDeviceToPosition replacement(UUID device, boolean lossRecorded) {
        return new AssignDeviceToPosition(device, P1, "REPLACEMENT", "stolen till replaced", lossRecorded);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private static ScopeContext atShop(UUID entity, UUID location) {
        return ScopeContext.dev(USER, entity, location);
    }

    private static ScopeContext view(UUID entity, PolicyClass policyClass) {
        Scope scope = new Scope(entity, null);
        return new ScopeContext(
                USER, null, entity, List.of(scope), scope, policyClass, Set.of(), null, Locale.ENGLISH, null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call)
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo(messageId));
    }

    private void assertNothingCommitted() {
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private List<KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static Map<?, ?> nested(Object state, String key) {
        return (Map<?, ?>) ((Map<?, ?>) state).get(key);
    }

    private static Map<String, Object> deviceRow(UUID device) {
        return superuserJdbc()
                .queryForMap("select status, current_till_position_id from party.device where device_id = ?", device);
    }

    private static List<UUID> holders(UUID... series) {
        return java.util.Arrays.stream(series)
                .map(id -> superuserJdbc()
                        .queryForObject(
                                "select holder_device_id from kernel.numbering_series where series_id = ?",
                                UUID.class,
                                id))
                .toList();
    }

    private static long nextNumber(UUID series) {
        return superuserJdbc()
                .queryForObject(
                        "select next_number from kernel.numbering_series where series_id = ?", Long.class, series);
    }

    /** One transaction as the application user in the given scope, rolled back, the way M1RlsIntegrationTest does. */
    private <T> T asApplicationUser(
            UUID entity, UUID location, String policyClass, java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', ?, true), set_config('app.granted_entities', '{}', true)",
                    entity.toString(),
                    location == null ? "" : location.toString(),
                    policyClass);
            try {
                return work.get();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private static void insertEntity(JdbcTemplate admin, UUID id, String code) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, status)"
                        + " values (?, ?, 'MPCS', ?, 'ACTIVE')",
                id,
                code,
                "MPCS " + code);
    }

    private static void insertShop(JdbcTemplate admin, UUID id, UUID owner, String code) {
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en,"
                        + " status) values (?, ?, ?, 'SHOP', ?, 'ONBOARDING')",
                id,
                owner,
                code,
                "Shop " + code);
    }

    private static void insertPosition(
            JdbcTemplate admin, UUID id, UUID location, UUID owner, int positionNo, String status) {
        admin.update(
                "insert into party.till_position (till_position_id, location_id, position_no, status, owner_entity_id)"
                        + " values (?, ?, ?, ?, ?)",
                id,
                location,
                positionNo,
                status,
                owner);
    }

    private static void insertSeries(
            JdbcTemplate admin,
            UUID id,
            String type,
            String scope,
            UUID location,
            UUID position,
            String prefix,
            long nextNumber) {
        admin.update(
                "insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id,"
                        + " location_id, till_position_id, prefix, next_number) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                type,
                scope,
                MPCS,
                location,
                position,
                prefix,
                nextNumber);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** The sync gateway's answer, set by the test: no device is drained unless listed. */
        static class SwitchableSyncStatus implements SyncStatus {

            final Set<UUID> drained = ConcurrentHashMap.newKeySet();

            @Override
            public boolean drained(UUID deviceId) {
                return drained.contains(deviceId);
            }

            @Override
            public java.util.Optional<DeviceSyncState> state(
                    UUID deviceId, lk.coopfed.knoweb.kernel.api.ScopeContext ctx) {
                return java.util.Optional.empty();
            }
        }

        @Bean
        @Primary
        SwitchableSyncStatus switchableSyncStatus() {
            return new SwitchableSyncStatus();
        }
    }
}
