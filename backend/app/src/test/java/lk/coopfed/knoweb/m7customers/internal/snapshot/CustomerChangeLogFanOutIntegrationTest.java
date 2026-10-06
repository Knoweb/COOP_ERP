package lk.coopfed.knoweb.m7customers.internal.snapshot;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.OTHER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP_2;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.m7customers.CustomersFixture;
import lk.coopfed.knoweb.m7customers.api.AccountCharged;
import lk.coopfed.knoweb.m7customers.api.AccountSuspended;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The change-log fan-out (wave 2, M7CR-14): an M7 event, delivered as the dispatcher delivers it
 * (the society's OWN scope, at the shop that sold when the event was published inside a till's
 * consumer), writes one UPSERT of the customer's row for every shop of the society, urgent for a
 * state change and not for a balance; nothing for another society's shop.
 */
class CustomerChangeLogFanOutIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    CustomerChangeLogFanOut fanOut;

    @Autowired
    SystemScope transactions;

    @Autowired
    ObjectMapper json;

    private final UUID otherShop = UUID.fromString("0190f700-0000-7000-8000-000000000013");

    @BeforeEach
    void arrange() {
        CustomersFixture.arrange(superuserJdbc());
        superuserJdbc()
                .update(
                        """
                        insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, status)
                        values (?, ?, 'O1', 'SHOP', 'Other shop', 'ACTIVE')
                        """,
                        otherShop,
                        OTHER);
    }

    @AfterEach
    void clean() {
        superuserJdbc().update("delete from party.location where location_id = ?", otherShop);
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void aStateChangeReachesEveryShopOfTheSocietyUrgentlyAndABalanceChangeQuietly() {
        UUID customerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        // A suspension, published by the office (entity-wide) and delivered entity-wide.
        ScopeContext office = SystemScope.own(SOCIETY, null);
        transactions.inScope(office, () -> {
            fanOut.onStateChanged(json.valueToTree(new AccountSuspended(accountId, customerId, SOCIETY)), office);
            return null;
        });
        List<Map<String, Object>> suspended = changes(customerId);
        assertThat(suspended).hasSize(2);
        assertThat(suspended).extracting(row -> row.get("location_id")).containsExactlyInAnyOrder(SHOP, SHOP_2);
        assertThat(suspended).allSatisfy(row -> {
            assertThat(row.get("owner_entity_id")).isEqualTo(SOCIETY);
            assertThat(row.get("table_name")).isEqualTo("customer");
            assertThat(row.get("op")).isEqualTo("UPSERT");
            assertThat(row.get("urgent")).isEqualTo(true);
        });

        // A charge, published inside the till's consumer at one shop and delivered at that shop:
        // the fan-out still reaches both shops, and is not urgent.
        ScopeContext atTheShop = SystemScope.own(SOCIETY, SHOP);
        transactions.inScope(atTheShop, () -> {
            fanOut.onRowChanged(
                    json.valueToTree(new AccountCharged(
                            accountId,
                            customerId,
                            SOCIETY,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            new BigDecimal("100.00"),
                            new BigDecimal("100.00"),
                            false,
                            false)),
                    atTheShop);
            return null;
        });
        List<Map<String, Object>> charged = changes(customerId);
        assertThat(charged).hasSize(4);
        assertThat(charged.stream().filter(row -> Boolean.FALSE.equals(row.get("urgent"))))
                .extracting(row -> row.get("location_id"))
                .containsExactlyInAnyOrder(SHOP, SHOP_2);
        // Each shop's snapshot version moved by one per publication; the other society's shop by none.
        assertThat(version(SHOP)).isEqualTo(2L);
        assertThat(version(SHOP_2)).isEqualTo(2L);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.change_log where location_id = ?",
                                Integer.class,
                                otherShop))
                .isZero();
    }

    @Test
    void anEventWithoutItsCustomerOrSocietyIsPoison() {
        ScopeContext office = SystemScope.own(SOCIETY, null);
        assertThatThrownBy(() -> transactions.inScope(office, () -> {
                    fanOut.onStateChanged(json.valueToTree(Map.of("accountId", UUID.randomUUID())), office);
                    return null;
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<Map<String, Object>> changes(UUID rowId) {
        return superuserJdbc()
                .queryForList(
                        """
                        select location_id, owner_entity_id, table_name, row_id, op, urgent
                          from kernel.change_log where row_id = ? order by location_id, version
                        """,
                        rowId);
    }

    private Long version(UUID location) {
        return superuserJdbc()
                .queryForObject(
                        "select current_version from kernel.location_snapshot_version where location_id = ?",
                        Long.class,
                        location);
    }
}
