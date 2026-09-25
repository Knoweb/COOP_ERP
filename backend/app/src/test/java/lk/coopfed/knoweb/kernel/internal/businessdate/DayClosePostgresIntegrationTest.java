package lk.coopfed.knoweb.kernel.internal.businessdate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
 * exactly once per calendar day, and the close is recorded and published (19A section 12,
 * "day-close advances exactly once per location per day").
 */
class DayClosePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190e100-0000-7000-8000-000000000001");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e100-0000-7000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("0190e100-0000-7000-8000-000000000101");
    private static final UUID OTHER_SHOP = UUID.fromString("0190e100-0000-7000-8000-000000000102");
    private static final UUID USER = UUID.fromString("0190e100-0000-7000-8000-000000000010");

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

    @BeforeEach
    void cleanState() {
        superuserJdbc().execute("delete from kernel.location_business_date");
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
