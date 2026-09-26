package lk.coopfed.knoweb.kernel.internal.businessdate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.BusinessDate;
import lk.coopfed.knoweb.kernel.api.DayClose;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LocationDayClosed;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * K-13 against PostgreSQL: the business date of a location moves only through a day close,
 * exactly once per business day whoever closes it and however often, and the close is
 * recorded and published (19A section 12, "day-close advances exactly once per location per
 * day"); a location has a row from its registration; issuance holds the date it read.
 */
class DayClosePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190e100-0000-7000-8000-000000000001");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e100-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190e100-0000-7000-8000-000000000101");
    private static final UUID OTHER_SHOP = UUID.fromString("0190e100-0000-7000-8000-000000000102");
    private static final UUID USER = UUID.fromString("0190e100-0000-7000-8000-000000000010");
    private static final UUID UNREGISTERED_SHOP = UUID.fromString("0190e100-0000-7000-8000-000000000103");

    @Autowired
    BusinessDate businessDate;

    @Autowired
    DayClose dayClose;

    @Autowired
    LocationBusinessDates dates;

    @Autowired
    DayCloseCutoffJob cutoff;

    @Autowired
    DayCloseTrigger trigger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Value("${coop-erp.business-timezone}")
    String zone;

    @Autowired
    LocationRegisteredListener registered;

    @BeforeEach
    void cleanState() {
        superuserJdbc().execute("delete from kernel.location_business_date");
        superuserJdbc().update("delete from party.location where location_id = ?", UNREGISTERED_SHOP);
    }

    @Test
    void aLocationGetsItsRowWhenItIsRegistered() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        ObjectMapper mapper = new ObjectMapper();

        inScope(ENTITY, () -> {
            registered.onLocationRegistered(
                    mapper.createObjectNode()
                            .put("registeredLocationId", SHOP.toString())
                            .put("ownerEntityId", ENTITY.toString()),
                    scope(ENTITY));
            return null;
        });

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select business_date from kernel.location_business_date where location_id = ?",
                                LocalDate.class,
                                SHOP))
                .isEqualTo(today);

        // Registered today, so not behind: the cut-off leaves it, and a second delivery changes nothing.
        assertThat(cutoff.closeOverdueDays()).isZero();
        inScope(ENTITY, () -> {
            registered.onLocationRegistered(
                    mapper.createObjectNode()
                            .put("registeredLocationId", SHOP.toString())
                            .put("ownerEntityId", ENTITY.toString()),
                    scope(ENTITY));
            return null;
        });
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today);
    }

    @Test
    void theCutOffGivesARowToALocationRegisteredBeforeTheRowsExisted() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        superuserJdbc()
                .update(
                        "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)"
                                + " values (?, ?, 'BD-TEST', 'SHOP', 'Business date test shop')",
                        UNREGISTERED_SHOP,
                        ENTITY);

        assertThat(cutoff.closeOverdueDays()).isZero();

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select business_date from kernel.location_business_date where location_id = ?",
                                LocalDate.class,
                                UNREGISTERED_SHOP))
                .isEqualTo(today);

        // From the next night it is cut off like any other location.
        superuserJdbc()
                .update(
                        "update kernel.location_business_date set business_date = ? where location_id = ?",
                        today.minusDays(1),
                        UNREGISTERED_SHOP);
        assertThat(cutoff.closeOverdueDays()).isEqualTo(1);
        assertThat(inScope(ENTITY, () -> businessDate.current(UNREGISTERED_SHOP)))
                .isEqualTo(today);
    }

    @Test
    void twoClosesOfTheSameDayMakeOneChange() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<LocalDate> close = () -> {
                start.await();
                return inScope(ENTITY, () -> dayClose.close(SHOP, scope(ENTITY)));
            };
            Future<LocalDate> first = pool.submit(close);
            Future<LocalDate> second = pool.submit(close);
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(today.plusDays(1));
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(today.plusDays(1));
        } finally {
            pool.shutdownNow();
        }

        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("DAY_CLOSED");
        assertThat(kernel.committedEvents()).hasSize(1);
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today.plusDays(1));
    }

    @Test
    void aCutOffAfterMidnightDoesNotSwallowThatEveningsClose() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        // Yesterday's day never closed; the cut-off closes it in the small hours of today.
        superuserJdbc()
                .update(
                        "insert into kernel.location_business_date (location_id, owner_entity_id, business_date, closed_at)"
                                + " values (?, ?, ?, now() - interval '1 day')",
                        SHOP,
                        ENTITY,
                        today.minusDays(1));
        assertThat(cutoff.closeOverdueDays()).isEqualTo(1);
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today);

        // That evening the last session closes: today closes too, on the same calendar date.
        assertThat(inScope(ENTITY, () -> dayClose.close(SHOP, scope(ENTITY)))).isEqualTo(today.plusDays(1));

        // The event delivered again: nothing moves.
        assertThat(inScope(ENTITY, () -> dayClose.close(SHOP, scope(ENTITY)))).isEqualTo(today.plusDays(1));

        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("DAY_CLOSED", "DAY_CLOSED");
        assertThat(kernel.committedEvents()).hasSize(2);
    }

    @Test
    void aDocumentIssuedWhileTheDayClosesCarriesTheDayItWasIssuedOn() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        superuserJdbc()
                .update(
                        "insert into kernel.location_business_date (location_id, owner_entity_id, business_date)"
                                + " values (?, ?, ?)",
                        SHOP,
                        ENTITY,
                        today);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            // Issuance: reads the date held, then works on until it commits.
            Future<LocalDate> issuing = pool.submit(() -> inScope(ENTITY, () -> {
                LocalDate date = businessDate.currentHeld(SHOP);
                held.countDown();
                await(commit);
                return date;
            }));
            held.await(20, TimeUnit.SECONDS);

            // The close waits for the issuing transaction.
            Future<LocalDate> closing = pool.submit(() -> inScope(ENTITY, () -> dayClose.close(SHOP, scope(ENTITY))));
            Thread.sleep(500);
            assertThat(closing.isDone()).isFalse();

            commit.countDown();
            assertThat(issuing.get(20, TimeUnit.SECONDS)).isEqualTo(today);
            assertThat(closing.get(20, TimeUnit.SECONDS)).isEqualTo(today.plusDays(1));
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aLocationTradesOnTheCalendarDateUntilItsFirstClose() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today);
    }

    @Test
    void closingTheDayAdvancesTheDateOnceAndTellsEverybody() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));

        LocalDate next = inScope(ENTITY, () -> dayClose.close(SHOP, scope(ENTITY)));
        assertThat(next).isEqualTo(today.plusDays(1));
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today.plusDays(1));

        // The same day again: nothing moves, nothing is recorded twice.
        assertThat(inScope(ENTITY, () -> dayClose.close(SHOP, scope(ENTITY)))).isEqualTo(today.plusDays(1));

        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("DAY_CLOSED");
        assertThat(kernel.committedEvents())
                .hasSize(1)
                .first()
                .isInstanceOfSatisfying(LocationDayClosed.class, event -> {
                    assertThat(event.closedLocationId()).isEqualTo(SHOP);
                    assertThat(event.closedDate()).isEqualTo(today);
                    assertThat(event.businessDate()).isEqualTo(today.plusDays(1));
                });

        // Another location of another entity is its own business.
        assertThat(inScope(OTHER_ENTITY, () -> businessDate.current(OTHER_SHOP)))
                .isEqualTo(today);
    }

    @Test
    void aLocationShutForAWeekReopensOnTodayNotAWeekBehind() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        superuserJdbc()
                .update(
                        "insert into kernel.location_business_date (location_id, owner_entity_id, business_date, closed_at)"
                                + " values (?, ?, ?, now() - interval '8 days')",
                        SHOP,
                        ENTITY,
                        today.minusDays(7));

        assertThat(inScope(ENTITY, () -> dayClose.close(SHOP, scope(ENTITY)))).isEqualTo(today);
    }

    @Test
    void theCutOffClosesEveryLocationThatIsBehind() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into kernel.location_business_date (location_id, owner_entity_id, business_date, closed_at)"
                        + " values (?, ?, ?, now() - interval '1 day')",
                SHOP,
                ENTITY,
                today.minusDays(1));
        admin.update(
                "insert into kernel.location_business_date (location_id, owner_entity_id, business_date, closed_at)"
                        + " values (?, ?, ?, now() - interval '1 day')",
                OTHER_SHOP,
                OTHER_ENTITY,
                today.minusDays(1));

        assertThat(cutoff.closeOverdueDays()).isEqualTo(2);

        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today);
        assertThat(inScope(OTHER_ENTITY, () -> businessDate.current(OTHER_SHOP)))
                .isEqualTo(today);
        assertThat(cutoff.closeOverdueDays()).isZero();
    }

    @Test
    void theTriggerClosesTheDayWhenTheLastSessionOfALocationCloses() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        ObjectMapper mapper = new ObjectMapper();

        // One session still open: nothing moves.
        inScope(ENTITY, () -> {
            trigger.onSessionClosed(
                    mapper.createObjectNode().put("locationId", SHOP.toString()).put("openSessionsRemaining", 1),
                    scope(ENTITY));
            return null;
        });
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today);

        // A payload without the field: the cut-off's business, not the trigger's.
        inScope(ENTITY, () -> {
            trigger.onSessionClosed(mapper.createObjectNode().put("locationId", SHOP.toString()), scope(ENTITY));
            return null;
        });
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today);

        // The last one: the day closes.
        inScope(ENTITY, () -> {
            trigger.onSessionClosed(
                    mapper.createObjectNode().put("locationId", SHOP.toString()).put("openSessionsRemaining", 0),
                    scope(ENTITY));
            return null;
        });
        assertThat(inScope(ENTITY, () -> businessDate.current(SHOP))).isEqualTo(today.plusDays(1));
    }

    private static ScopeContext scope(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    private <T> T inScope(UUID entity, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString());
            return work.get();
        });
    }
}
