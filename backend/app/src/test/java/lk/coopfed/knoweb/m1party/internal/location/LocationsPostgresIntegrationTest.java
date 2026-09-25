package lk.coopfed.knoweb.m1party.internal.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesClosed;
import lk.coopfed.knoweb.kernel.api.SeriesHolderChanged;
import lk.coopfed.knoweb.kernel.api.SeriesRegistered;
import lk.coopfed.knoweb.m1party.api.ActivateLocation;
import lk.coopfed.knoweb.m1party.api.ConfirmLocationConnectivity;
import lk.coopfed.knoweb.m1party.api.LocationActivated;
import lk.coopfed.knoweb.m1party.api.LocationDormant;
import lk.coopfed.knoweb.m1party.api.LocationOnboardingStarted;
import lk.coopfed.knoweb.m1party.api.LocationPrimaryChanged;
import lk.coopfed.knoweb.m1party.api.LocationRegistered;
import lk.coopfed.knoweb.m1party.api.LocationUpdated;
import lk.coopfed.knoweb.m1party.api.MarkLocationDormant;
import lk.coopfed.knoweb.m1party.api.ReactivateLocation;
import lk.coopfed.knoweb.m1party.api.RegisterLocation;
import lk.coopfed.knoweb.m1party.api.RegisterTillPosition;
import lk.coopfed.knoweb.m1party.api.RetireTillPosition;
import lk.coopfed.knoweb.m1party.api.SetPrimaryTill;
import lk.coopfed.knoweb.m1party.api.StartLocationOnboarding;
import lk.coopfed.knoweb.m1party.api.TillPositionRegistered;
import lk.coopfed.knoweb.m1party.api.TillPositionRetired;
import lk.coopfed.knoweb.m1party.api.TradingDay;
import lk.coopfed.knoweb.m1party.api.UpdateLocation;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.TillPositionView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M1-05 against PostgreSQL, as the application user: the location and till position commands of
 * 21A section 6 with the audit record and the event each names, every guard as a failing case
 * with its message id, the NumberingService hooks ("series registered on position create"), and
 * what a caller scoped to one shop may see and do.
 */
class LocationsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e500-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0190e500-0000-7000-8000-000000000002");
    private static final UUID SUSPENDED = UUID.fromString("0190e500-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e500-0000-7000-8000-000000000010");
    private static final UUID OPERATOR = UUID.fromString("0190e500-0000-7000-8000-000000000011");
    private static final UUID ROLE = UUID.fromString("0190e500-0000-7000-8000-000000000012");
    private static final UUID DEVICE = UUID.fromString("0190e500-0000-7000-8000-000000000020");

    private static final List<TradingDay> WEEKDAYS =
            List.of(new TradingDay("MON", "08:00", "20:00"), new TradingDay("TUE", "08:00", "20:00"));

    @Autowired
    Handles<RegisterLocation, UUID> registerLocation;

    @Autowired
    Handles<UpdateLocation, UUID> updateLocation;

    @Autowired
    Handles<ConfirmLocationConnectivity, UUID> confirmConnectivity;

    @Autowired
    Handles<StartLocationOnboarding, UUID> startOnboarding;

    @Autowired
    Handles<ActivateLocation, UUID> activateLocation;

    @Autowired
    Handles<MarkLocationDormant, UUID> markDormant;

    @Autowired
    Handles<ReactivateLocation, UUID> reactivateLocation;

    @Autowired
    Handles<SetPrimaryTill, UUID> setPrimaryTill;

    @Autowired
    Handles<RegisterTillPosition, UUID> registerPosition;

    @Autowired
    Handles<RetireTillPosition, UUID> retirePosition;

    @Autowired
    PartyQueries queries;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void threeEntitiesAndNothingElse() {
        JdbcTemplate admin = superuserJdbc();
        admin.update("delete from kernel.numbering_series where owner_entity_id in (?, ?, ?)", MPCS, OTHER, SUSPENDED);
        admin.update("delete from security.user_role where user_id = ?", OPERATOR);
        admin.update("delete from security.app_user where user_id = ?", OPERATOR);
        admin.update("delete from security.role where role_id = ?", ROLE);
        admin.execute("truncate table party.device, party.till_position, party.location, party.entity_relationship,"
                + " party.entity_party_directory, party.federation_identity, party.entity cascade");
        insertEntity(admin, MPCS, "M905", "ACTIVE");
        insertEntity(admin, OTHER, "M906", "ACTIVE");
        insertEntity(admin, SUSPENDED, "M907", "SUSPENDED");
    }

    // ---- RegisterLocation ------------------------------------------------------------------

    @Test
    void aShopIsRegisteredPlannedWithItsLocationSeriesAuditedAndPublished() {
        UUID shop = registerShop("S01");

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select status, language, trading_hours::text as hours from party.location"
                                + " where location_id = ?",
                        shop);
        assertThat(row.get("status")).isEqualTo("PLANNED");
        assertThat(row.get("language")).as("the entity's default language").isEqualTo("si");
        assertThat((String) row.get("hours")).contains("\"MON\"").contains("20:00");

        assertThat(seriesPrefixes(shop, null))
                .containsExactly("M905-S01-CNT", "M905-S01-GRN", "M905-S01-RPK", "M905-S01-WOF", "M905-S01-XFR");

        assertThat(audit("LOCATION_REGISTERED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(shop);
            assertThat(record.before()).isNull();
            assertThat(((Map<?, ?>) record.after()).get("locationCode")).isEqualTo("S01");
        });
        assertThat(audit("SERIES_REGISTERED")).hasSize(5);
        assertThat(events(LocationRegistered.class))
                .containsExactly(new LocationRegistered(shop, MPCS, "S01", "SHOP", "si", "PLANNED"));
        assertThat(events(SeriesRegistered.class)).hasSize(5);
    }

    @Test
    void aWarehouseGetsNoLocationSeries() {
        UUID warehouse = registerLocation.handle(location("W01", "WAREHOUSE"), own(MPCS));

        assertThat(seriesPrefixes(warehouse, null)).isEmpty();
        assertThat(events(SeriesRegistered.class)).isEmpty();
        assertThat(events(LocationRegistered.class)).hasSize(1);
    }

    @Test
    void theGuardsOfRegisterLocation() {
        registerShop("S01");
        kernel.reset();

        refused(() -> registerLocation.handle(location("S01", "SHOP"), own(MPCS)), "m1.location.code_duplicate");
        refused(
                () -> registerLocation.handle(location("S02", "SHOP"), own(SUSPENDED)),
                "m1.location.owner_not_trading");
        refused(
                () -> registerLocation.handle(location("S02", "SHOP"), atShop(MPCS, UUID.randomUUID())),
                "m1.location.entity_scope_required");
        refused(
                () -> registerLocation.handle(location("S02", "SHOP"), view(MPCS, PolicyClass.FEDERATION_VIEW)),
                "m1.location.scope_required");
        refused(
                () -> registerLocation.handle(
                        withHours("S02", List.of(new TradingDay("MON", "20:00", "08:00"))), own(MPCS)),
                "m1.location.trading_hours_invalid");
        refused(
                () -> registerLocation.handle(
                        withHours(
                                "S02",
                                List.of(
                                        new TradingDay("MON", "08:00", "12:00"),
                                        new TradingDay("MON", "13:00", "18:00"))),
                        own(MPCS)),
                "m1.location.trading_hours_invalid");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc().queryForObject("select count(*) from party.location", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anotherEntityMayUseTheSameCode() {
        registerShop("S01");

        UUID theirs = registerLocation.handle(location("S01", "SHOP"), own(OTHER));

        assertThat(seriesPrefixes(theirs, null)).contains("M906-S01-GRN");
    }

    // ---- RegisterTillPosition: the "done when" of M1-05 ---------------------------------------

    @Test
    void aPositionIsRegisteredWithItsTillSeriesInTheSameTransaction() {
        UUID shop = registerShop("S01");
        kernel.reset();

        UUID position = registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS));

        assertThat(seriesPrefixes(shop, position)).containsExactly("M905-S01-T1-CPR", "M905-S01-T1-RCT");
        List<UUID> seriesIds = superuserJdbc()
                .queryForList(
                        "select series_id from kernel.numbering_series where till_position_id = ? order by doc_type_code",
                        UUID.class,
                        position);
        assertThat(superuserJdbc()
                        .queryForList(
                                "select holder_device_id from kernel.numbering_series where till_position_id = ?",
                                UUID.class,
                                position))
                .as("no device holds the counters until one is assigned (M1-06)")
                .containsOnlyNulls();

        assertThat(audit("POSITION_REGISTERED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("till_position");
            assertThat(record.subject().id()).isEqualTo(position);
        });
        assertThat(audit("SERIES_REGISTERED")).hasSize(2);
        assertThat(events(TillPositionRegistered.class))
                .containsExactly(new TillPositionRegistered(position, shop, MPCS, 1, seriesIds));
        assertThat(events(SeriesRegistered.class))
                .extracting(SeriesRegistered::docTypeCode)
                .containsExactlyInAnyOrder("CPR", "RCT");
    }

    @Test
    void theGuardsOfRegisterTillPosition() {
        UUID shop = registerShop("S01");
        registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS));
        UUID warehouse = registerLocation.handle(location("W01", "WAREHOUSE"), own(MPCS));
        UUID theirShop = registerLocation.handle(location("S01", "SHOP"), own(OTHER));
        kernel.reset();

        refused(
                () -> registerPosition.handle(new RegisterTillPosition(warehouse, 1), own(MPCS)),
                "m1.position.location_not_shop");
        refused(
                () -> registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS)),
                "m1.position.number_duplicate");
        refused(
                () -> registerPosition.handle(new RegisterTillPosition(theirShop, 1), own(MPCS)),
                "m1.location.not_found");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- the life cycle of doc 21 section 4.3 -------------------------------------------------

    @Test
    void aShopGoesFromPlannedToActiveOnlyThroughItsGates() {
        UUID shop = registerShop("S01");
        UUID till = registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS));

        refused(() -> activateLocation.handle(new ActivateLocation(shop), own(MPCS)), "m1.location.transition_invalid");

        kernel.reset();
        startOnboarding.handle(new StartLocationOnboarding(shop), own(MPCS));
        assertThat(audit("LOCATION_ONBOARDING")).hasSize(1);
        assertThat(events(LocationOnboardingStarted.class))
                .containsExactly(new LocationOnboardingStarted(shop, MPCS, "SHOP"));

        refused(
                () -> activateLocation.handle(new ActivateLocation(shop), own(MPCS)),
                "m1.location.connectivity_not_met");

        kernel.reset();
        confirmConnectivity.handle(new ConfirmLocationConnectivity(shop), own(MPCS));
        assertThat(audit("LOCATION_CONNECTIVITY_CONFIRMED")).singleElement().satisfies(record -> {
            assertThat(((Map<?, ?>) record.before()).get("connectivitySpecMet")).isEqualTo(false);
            assertThat(((Map<?, ?>) record.after()).get("connectivitySpecMet")).isEqualTo(true);
            assertThat(record.scope().userId()).as("who confirmed it").isEqualTo(USER);
        });
        assertThat(events(LocationUpdated.class))
                .singleElement()
                .satisfies(event -> assertThat(event.connectivitySpecMet()).isTrue());
        refused(
                () -> confirmConnectivity.handle(new ConfirmLocationConnectivity(shop), own(MPCS)),
                "m1.location.connectivity_already_confirmed");

        refused(
                () -> activateLocation.handle(new ActivateLocation(shop), own(MPCS)),
                "m1.location.primary_till_required");
        setPrimaryTill.handle(new SetPrimaryTill(shop, till, "OPENING", null), own(MPCS));
        refused(() -> activateLocation.handle(new ActivateLocation(shop), own(MPCS)), "m1.location.operator_required");

        insertOperator(shop);
        kernel.reset();
        activateLocation.handle(new ActivateLocation(shop), own(MPCS));
        assertThat(status(shop)).isEqualTo("ACTIVE");
        assertThat(audit("LOCATION_ACTIVATED")).hasSize(1);
        assertThat(events(LocationActivated.class))
                .containsExactly(new LocationActivated(shop, MPCS, "SHOP", "ONBOARDING"));

        refused(
                () -> markDormant.handle(new MarkLocationDormant(shop, " ", null), own(MPCS)),
                "m1.location.reason_required");
        kernel.reset();
        markDormant.handle(new MarkLocationDormant(shop, "SEASONAL", "closed for the monsoon"), own(MPCS));
        assertThat(status(shop)).isEqualTo("DORMANT");
        assertThat(audit("LOCATION_DORMANT")).singleElement().satisfies(record -> assertThat(record.reason())
                .isEqualTo("SEASONAL: closed for the monsoon"));
        assertThat(events(LocationDormant.class)).containsExactly(new LocationDormant(shop, MPCS, "SHOP", "SEASONAL"));

        kernel.reset();
        reactivateLocation.handle(new ReactivateLocation(shop), own(MPCS));
        assertThat(status(shop)).isEqualTo("ACTIVE");
        assertThat(audit("LOCATION_REACTIVATED")).hasSize(1);
        assertThat(events(LocationActivated.class))
                .containsExactly(new LocationActivated(shop, MPCS, "SHOP", "DORMANT"));
        refused(
                () -> reactivateLocation.handle(new ReactivateLocation(shop), own(MPCS)),
                "m1.location.transition_invalid");
    }

    @Test
    void aWarehouseActivatesWithoutATillOrAnOperator() {
        UUID warehouse = registerLocation.handle(location("W01", "WAREHOUSE"), own(MPCS));
        startOnboarding.handle(new StartLocationOnboarding(warehouse), own(MPCS));
        confirmConnectivity.handle(new ConfirmLocationConnectivity(warehouse), own(MPCS));

        activateLocation.handle(new ActivateLocation(warehouse), own(MPCS));

        assertThat(status(warehouse)).isEqualTo("ACTIVE");
    }

    @Test
    void anUpdateReplacesTheFactsAndSaysSo() {
        UUID shop = registerShop("S01");
        kernel.reset();

        List<TradingDay> sundays = List.of(new TradingDay("SUN", "09:00", "13:00"));
        updateLocation.handle(
                new UpdateLocation(
                        shop,
                        "Main street shop",
                        null,
                        null,
                        "12 Main Street",
                        "Gampaha",
                        null,
                        null,
                        "ta",
                        sundays,
                        "L"),
                own(MPCS));

        LocationView view = queries.getLocation(shop, own(MPCS)).orElseThrow();
        assertThat(view.nameEn()).isEqualTo("Main street shop");
        assertThat(view.language()).isEqualTo("ta");
        assertThat(view.tradingHours()).containsExactlyElementsOf(sundays);
        assertThat(view.locationCode()).as("the code never changes").isEqualTo("S01");

        assertThat(audit("LOCATION_UPDATED")).singleElement().satisfies(record -> {
            assertThat(((Map<?, ?>) record.before()).get("sizeBand")).isEqualTo("S");
            assertThat(((Map<?, ?>) record.after()).get("sizeBand")).isEqualTo("L");
        });
        assertThat(events(LocationUpdated.class))
                .containsExactly(new LocationUpdated(shop, MPCS, "ta", sundays, "L", false));
    }

    // ---- SetPrimaryTill ----------------------------------------------------------------------

    @Test
    void namingThePrimaryTillMovesTheLocationCountersToItsDevice() {
        UUID shop = registerShop("S01");
        UUID till = registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS));
        insertDevice(till);
        kernel.reset();

        setPrimaryTill.handle(new SetPrimaryTill(shop, till, "OPENING", "first till"), own(MPCS));

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select primary_till_position_id from party.location where location_id = ?",
                                UUID.class,
                                shop))
                .isEqualTo(till);
        assertThat(superuserJdbc()
                        .queryForList(
                                "select holder_device_id from kernel.numbering_series"
                                        + " where location_id = ? and till_position_id is null",
                                UUID.class,
                                shop))
                .hasSize(5)
                .containsOnly(DEVICE);
        assertThat(audit("LOCATION_PRIMARY_CHANGED")).singleElement().satisfies(record -> assertThat(record.reason())
                .isEqualTo("OPENING: first till"));
        assertThat(audit("SERIES_HOLDER_CHANGED")).hasSize(5);
        assertThat(events(SeriesHolderChanged.class)).hasSize(5);
        assertThat(events(LocationPrimaryChanged.class))
                .containsExactly(new LocationPrimaryChanged(shop, MPCS, null, till, DEVICE));
    }

    @Test
    void namingAPrimaryTillWithNoDeviceLeavesTheCountersAlone() {
        UUID shop = registerShop("S01");
        UUID till = registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS));
        kernel.reset();

        setPrimaryTill.handle(new SetPrimaryTill(shop, till, "OPENING", null), own(MPCS));

        assertThat(events(SeriesHolderChanged.class)).isEmpty();
        assertThat(events(LocationPrimaryChanged.class))
                .containsExactly(new LocationPrimaryChanged(shop, MPCS, null, till, null));
        assertThat(queries.listTillPositions(shop, own(MPCS)))
                .containsExactly(new TillPositionView(till, shop, 1, "ACTIVE", true));
    }

    @Test
    void theGuardsOfSetPrimaryTill() {
        UUID shop = registerShop("S01");
        UUID other = registerShop("S02");
        UUID till = registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS));
        UUID retired = registerPosition.handle(new RegisterTillPosition(shop, 2), own(MPCS));
        UUID elsewhere = registerPosition.handle(new RegisterTillPosition(other, 1), own(MPCS));
        retirePosition.handle(new RetireTillPosition(retired), own(MPCS));
        setPrimaryTill.handle(new SetPrimaryTill(shop, till, "OPENING", null), own(MPCS));
        kernel.reset();

        refused(
                () -> setPrimaryTill.handle(new SetPrimaryTill(shop, elsewhere, "X", null), own(MPCS)),
                "m1.position.not_in_location");
        refused(
                () -> setPrimaryTill.handle(new SetPrimaryTill(shop, retired, "X", null), own(MPCS)),
                "m1.position.not_active");
        refused(
                () -> setPrimaryTill.handle(new SetPrimaryTill(shop, till, null, null), own(MPCS)),
                "m1.location.reason_required");
        refused(
                () -> setPrimaryTill.handle(new SetPrimaryTill(shop, till, "X", null), own(MPCS)),
                "m1.location.primary_unchanged");
        refused(
                () -> setPrimaryTill.handle(new SetPrimaryTill(shop, UUID.randomUUID(), "X", null), own(MPCS)),
                "m1.position.not_found");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theDatabaseRefusesAPrimaryTillOfAnotherLocation() {
        UUID shop = registerShop("S01");
        UUID other = registerShop("S02");
        UUID elsewhere = registerPosition.handle(new RegisterTillPosition(other, 1), own(MPCS));

        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update party.location set primary_till_position_id = ? where location_id = ?",
                                elsewhere,
                                shop))
                .hasMessageContaining("location_primary_till_fk");
    }

    // ---- RetireTillPosition ------------------------------------------------------------------

    @Test
    void retiringAPositionClosesItsSeries() {
        UUID shop = registerShop("S01");
        UUID till = registerPosition.handle(new RegisterTillPosition(shop, 2), own(MPCS));
        kernel.reset();

        retirePosition.handle(new RetireTillPosition(till), own(MPCS));

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select status from party.till_position where till_position_id = ?",
                                String.class,
                                till))
                .isEqualTo("RETIRED");
        List<UUID> closed = superuserJdbc()
                .queryForList(
                        "select series_id from kernel.numbering_series where till_position_id = ? and status = 'CLOSED'"
                                + " order by doc_type_code",
                        UUID.class,
                        till);
        assertThat(closed).hasSize(2);
        assertThat(audit("POSITION_RETIRED")).hasSize(1);
        assertThat(audit("SERIES_CLOSED")).hasSize(2);
        assertThat(events(SeriesClosed.class)).hasSize(2);
        assertThat(events(TillPositionRetired.class))
                .containsExactly(new TillPositionRetired(till, shop, MPCS, 2, closed));

        refused(() -> retirePosition.handle(new RetireTillPosition(till), own(MPCS)), "m1.position.not_active");
        refused(
                () -> registerPosition.handle(new RegisterTillPosition(shop, 2), own(MPCS)),
                "m1.position.number_duplicate");
    }

    @Test
    void thePrimaryTillAndAPositionWithADeviceDoNotRetire() {
        UUID shop = registerShop("S01");
        UUID primary = registerPosition.handle(new RegisterTillPosition(shop, 1), own(MPCS));
        UUID withDevice = registerPosition.handle(new RegisterTillPosition(shop, 2), own(MPCS));
        setPrimaryTill.handle(new SetPrimaryTill(shop, primary, "OPENING", null), own(MPCS));
        insertDevice(withDevice);
        kernel.reset();

        refused(() -> retirePosition.handle(new RetireTillPosition(primary), own(MPCS)), "m1.position.is_primary");
        refused(
                () -> retirePosition.handle(new RetireTillPosition(withDevice), own(MPCS)),
                "m1.position.device_assigned");
        refused(
                () -> retirePosition.handle(new RetireTillPosition(UUID.randomUUID()), own(MPCS)),
                "m1.position.not_found");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- row-level security: a caller scoped to one shop --------------------------------------

    @Test
    void aShopScopedCallerSeesAndChangesItsOwnShopOnly() {
        UUID mine = registerShop("S01");
        UUID sibling = registerShop("S02");
        UUID siblingTill = registerPosition.handle(new RegisterTillPosition(sibling, 1), own(MPCS));
        registerLocation.handle(location("S01", "SHOP"), own(OTHER));
        ScopeContext atMine = atShop(MPCS, mine);
        kernel.reset();

        assertThat(queries.listLocations(new LocationFilter(null, null, null, null), atMine)
                        .items())
                .extracting(LocationView::locationId)
                .containsExactly(mine);
        assertThat(queries.getLocation(sibling, atMine)).isEmpty();
        assertThat(queries.listTillPositions(sibling, atMine)).isEmpty();

        refused(() -> startOnboarding.handle(new StartLocationOnboarding(sibling), atMine), "m1.location.not_found");
        refused(() -> registerPosition.handle(new RegisterTillPosition(sibling, 2), atMine), "m1.location.not_found");
        refused(() -> retirePosition.handle(new RetireTillPosition(siblingTill), atMine), "m1.position.not_found");
        assertThat(kernel.committedEvents()).isEmpty();

        UUID myTill = registerPosition.handle(new RegisterTillPosition(mine, 1), atMine);
        assertThat(seriesPrefixes(mine, myTill)).containsExactly("M905-S01-T1-CPR", "M905-S01-T1-RCT");
        startOnboarding.handle(new StartLocationOnboarding(mine), atMine);
        assertThat(status(mine)).isEqualTo("ONBOARDING");
    }

    @Test
    void theFederationViewReadsEveryLocationAndWritesNone() {
        UUID mine = registerShop("S01");
        UUID theirs = registerLocation.handle(location("S01", "SHOP"), own(OTHER));
        ScopeContext federationView = view(MPCS, PolicyClass.FEDERATION_VIEW);

        assertThat(queries.listLocations(new LocationFilter(null, "SHOP", null, null), federationView)
                        .items())
                .extracting(LocationView::locationId)
                .containsExactlyInAnyOrder(mine, theirs);
        refused(
                () -> startOnboarding.handle(new StartLocationOnboarding(theirs), federationView),
                "m1.location.scope_required");
    }

    @Test
    void anExternalGrantReadsTheLocationsOfItsEntitiesOnly() {
        UUID mine = registerShop("S01");
        registerLocation.handle(location("S01", "SHOP"), own(OTHER));
        ScopeContext external = new ScopeContext(
                USER,
                null,
                MPCS,
                List.of(new Scope(MPCS, null)),
                null,
                PolicyClass.EXTERNAL_TIMEBOXED,
                Set.of(MPCS),
                null,
                Locale.ENGLISH,
                null);

        assertThat(queries.listLocations(new LocationFilter(null, null, null, null), external)
                        .items())
                .extracting(LocationView::locationId)
                .containsExactly(mine);
    }

    // ---- over HTTP ---------------------------------------------------------------------------

    @Test
    void theSliceRegistersAShopAndATillAndListsThem() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.token(USER, MPCS));
        headers.set("X-Scope-Entity", MPCS.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> created = http.exchange(
                "/v1/party/locations",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationCode", "S01",
                                "locationType", "SHOP",
                                "nameEn", "Shop one",
                                "tradingHours", List.of(Map.of("day", "MON", "opens", "08:00", "closes", "18:00"))),
                        headers),
                JsonNode.class);
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String locationId = created.getBody().get("locationId").asText();
        assertThat(created.getBody().get("status").asText()).isEqualTo("PLANNED");
        assertThat(created.getBody().get("tradingHours").get(0).get("closes").asText())
                .isEqualTo("18:00");

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> till = http.exchange(
                "/v1/party/locations/" + locationId + "/positions",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("positionNo", 1), headers),
                JsonNode.class);
        assertThat(till.getStatusCode()).as(String.valueOf(till.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(till.getBody().get("primary").asBoolean()).isFalse();

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> badCode = http.exchange(
                "/v1/party/locations",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("locationCode", "S 02", "locationType", "SHOP", "nameEn", "x"), headers),
                JsonNode.class);
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> duplicate = http.exchange(
                "/v1/party/locations/" + locationId + "/positions",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("positionNo", 1), headers),
                JsonNode.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(duplicate.getBody().get("code").asText()).isEqualTo("m1.position.number_duplicate");

        ResponseEntity<JsonNode> positions = http.exchange(
                "/v1/party/locations/" + locationId + "/positions",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);
        assertThat(positions.getBody()).hasSize(1);
        assertThat(positions.getBody().get(0).get("positionNo").asInt()).isEqualTo(1);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private UUID registerShop(String code) {
        return registerLocation.handle(location(code, "SHOP"), own(MPCS));
    }

    private static RegisterLocation location(String code, String type) {
        return new RegisterLocation(
                code, type, "Location " + code, null, null, null, "Gampaha", null, null, null, WEEKDAYS, "S");
    }

    private static RegisterLocation withHours(String code, List<TradingDay> hours) {
        return new RegisterLocation(
                code, "SHOP", "Location " + code, null, null, null, null, null, null, null, hours, null);
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

    private static List<String> seriesPrefixes(UUID location, UUID tillPosition) {
        return superuserJdbc()
                .queryForList(
                        "select prefix from kernel.numbering_series where location_id = ?"
                                + " and till_position_id is not distinct from ? and status = 'ACTIVE' order by prefix",
                        String.class,
                        location,
                        tillPosition);
    }

    private static String status(UUID location) {
        return superuserJdbc()
                .queryForObject("select status from party.location where location_id = ?", String.class, location);
    }

    private static void insertEntity(JdbcTemplate admin, UUID id, String code, String status) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, default_language, status)"
                        + " values (?, ?, 'MPCS', ?, 'si', ?)",
                id,
                code,
                "MPCS " + code,
                status);
    }

    /** A device at the position, as M1-06's AssignDeviceToPosition will leave it. */
    private static void insertDevice(UUID tillPosition) {
        superuserJdbc()
                .update(
                        "insert into party.device (device_id, hardware_serial, device_kind, owner_entity_id,"
                                + " current_till_position_id, status, location_id) values (?, ?, 'POS_TERMINAL', ?, ?, 'ACTIVE',"
                                + " (select location_id from party.till_position where till_position_id = ?))",
                        DEVICE,
                        "SERIAL-" + tillPosition,
                        MPCS,
                        tillPosition,
                        tillPosition);
    }

    /** A till user with a role assignment at the shop: what ActivateLocation counts as an operator. */
    private static void insertOperator(UUID shop) {
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                        + " values (?, ?, 'm1-05-cashier', 'Cashier', 'TILL', 'ACTIVE')",
                OPERATOR,
                MPCS);
        admin.update(
                "insert into security.role (role_id, owner_entity_id, name_en) values (?, ?, 'M1-05 cashier')",
                ROLE,
                MPCS);
        admin.update(
                "insert into security.user_role (user_id, role_id, scope_entity_id, scope_location_id)"
                        + " values (?, ?, ?, ?)",
                OPERATOR,
                ROLE,
                MPCS,
                shop);
    }
}
