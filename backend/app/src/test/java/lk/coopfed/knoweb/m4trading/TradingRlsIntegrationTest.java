package lk.coopfed.knoweb.m4trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rows of the RLS matrix for the trading tables (24A section 9, "RLS matrix rows"; doc 24
 * section 9.3): an extension row follows its document's header, so the owner reads and writes
 * it, the counterparty reads it, a stranger sees nothing, a shop-scoped session of the owner
 * reads the documents at its shop only; and the seller's allocation is read by the buyer as its
 * counterparty and written by nobody else. The fixtures are written by the superuser; every
 * read and write under test goes through the application's connection (coop_app) with the scope
 * set on the transaction, the way CatalogueRlsIntegrationTest does it. The template tables
 * (allocation_run, order_allocation, order_allocation_line) are also run cell by cell by
 * {@code RlsMatrixIntegrationTest}.
 */
class TradingRlsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-0000000004b1");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-0000000004a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final UUID SHOP_1 = UUID.fromString("00000000-0000-0000-0000-0000000004d1");
    private static final UUID SHOP_2 = UUID.fromString("00000000-0000-0000-0000-0000000004d2");

    private final UUID order = Ids.next();
    private final UUID grnAtShop1 = Ids.next();
    private final UUID grnAtShop2 = Ids.next();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        // The buyer's order, entity-wide, with the seller as counterparty.
        insertDocument(admin, order, "ORD", BUYER, SELLER, null);
        admin.update(
                "insert into trading.doc_order (document_id, relationship_id, buyer_entity_id, seller_entity_id)"
                        + " values (?, ?, ?, ?)",
                order,
                Ids.next(),
                BUYER,
                SELLER);
        // The seller's acceptance of it: the seller's own row, the buyer its counterparty.
        admin.update(
                "insert into trading.order_allocation (order_id, run_id, owner_entity_id, counterparty_entity_id,"
                        + " relationship_id, status, committed_eta, lock_at)"
                        + " values (?, ?, ?, ?, ?, 'ACCEPTED', current_date, now())",
                order,
                Ids.next(),
                SELLER,
                BUYER,
                Ids.next());
        // Two GRNs of the buyer, one at each of its shops.
        insertDocument(admin, grnAtShop1, "GRN", BUYER, SELLER, SHOP_1);
        insertGrn(admin, grnAtShop1, SHOP_1);
        insertDocument(admin, grnAtShop2, "GRN", BUYER, SELLER, SHOP_2);
        insertGrn(admin, grnAtShop2, SHOP_2);
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.update("delete from trading.doc_grn where document_id in (?, ?)", grnAtShop1, grnAtShop2);
        admin.update("delete from trading.doc_order where document_id = ?", order);
        admin.update("delete from trading.order_allocation where order_id = ?", order);
        admin.update("delete from kernel.document where document_id in (?, ?, ?)", order, grnAtShop1, grnAtShop2);
    }

    @Test
    void anExtensionRowIsReadByTheOwnerAndTheCounterpartyAndByNobodyElse() {
        assertThat(inScope(BUYER, "OWN", () -> orderRows())).isEqualTo(1);
        assertThat(inScope(SELLER, "OWN", () -> orderRows())).isEqualTo(1);
        assertThat(inScope(SELLER, "PARTY", () -> orderRows())).isEqualTo(1);
        assertThat(inScope(STRANGER, "OWN", () -> orderRows())).isZero();
        assertThat(inScope(STRANGER, "FEDERATION_VIEW", () -> orderRows())).isEqualTo(1);
        assertThat(inScope(STRANGER, "NONE", () -> orderRows())).isZero();
    }

    @Test
    void anExtensionRowIsWrittenByTheOwnerOfTheDocumentOnly() {
        UUID buyersLine = Ids.next();
        assertThat(inScope(BUYER, "OWN", () -> insertOrderLine(buyersLine))).isEqualTo(1);

        // The seller (the counterparty), a stranger, and a read-only class: refused by
        // document_write, which asks kernel.document_owned.
        assertThat(inScope(SELLER, "OWN", () -> tryInsertOrderLine(Ids.next()))).isFalse();
        assertThat(inScope(STRANGER, "OWN", () -> tryInsertOrderLine(Ids.next())))
                .isFalse();
        assertThat(inScope(BUYER, "FEDERATION_VIEW", () -> tryInsertOrderLine(Ids.next())))
                .isFalse();
    }

    @Test
    void aShopScopedSessionOfTheOwnerReadsTheDocumentsAtItsShopOnly() {
        // Entity-wide: both GRNs; at shop 1: its own; the seller as counterparty: both, since the
        // location is the owner's (party_read on kernel.document, CR-17A-3).
        assertThat(inScopeAt(BUYER, null, "OWN", () -> grnRows())).isEqualTo(2);
        assertThat(inScopeAt(BUYER, SHOP_1, "OWN", () -> grnRows())).isEqualTo(1);
        assertThat(inScopeAt(BUYER, SHOP_1, "OWN", () -> grnLocations())).containsExactly(SHOP_1);
        assertThat(inScopeAt(SELLER, null, "PARTY", () -> grnRows())).isEqualTo(2);
    }

    @Test
    void theSellersAllocationIsReadByTheBuyerAsCounterpartyAndWrittenBySellerOnly() {
        assertThat(inScope(SELLER, "OWN", () -> allocationRows())).isEqualTo(1);
        assertThat(inScope(BUYER, "OWN", () -> allocationRows())).isEqualTo(1);
        assertThat(inScope(BUYER, "PARTY", () -> allocationRows())).isEqualTo(1);
        assertThat(inScope(STRANGER, "OWN", () -> allocationRows())).isZero();

        // The buyer cannot write a line of the seller's allocation (own_write: the owner is the
        // scope entity). That the buyer never writes an allocation row of its own is the
        // handler's rule, not the policy's: only AcceptOrder, the seller's command, inserts here.
        assertThat(inScope(BUYER, "OWN", () -> tryInsertAllocationLine(SELLER))).isFalse();
        assertThat(inScope(STRANGER, "OWN", () -> tryInsertAllocationLine(SELLER)))
                .isFalse();
        UUID sellersLine = Ids.next();
        assertThat(inScope(SELLER, "OWN", () -> insertAllocationLine(sellersLine, SELLER)))
                .isEqualTo(1);
    }

    // ---- reads and writes under test -------------------------------------------------------

    private int orderRows() {
        return jdbc.queryForObject(
                "select count(*) from trading.doc_order where document_id = ?", Integer.class, order);
    }

    private int grnRows() {
        return jdbc.queryForObject(
                "select count(*) from trading.doc_grn where document_id in (?, ?)",
                Integer.class,
                grnAtShop1,
                grnAtShop2);
    }

    private List<UUID> grnLocations() {
        return jdbc.queryForList(
                "select receiver_location_id from trading.doc_grn where document_id in (?, ?)",
                UUID.class,
                grnAtShop1,
                grnAtShop2);
    }

    private int allocationRows() {
        return jdbc.queryForObject(
                "select count(*) from trading.order_allocation where order_id = ?", Integer.class, order);
    }

    private int insertOrderLine(UUID lineId) {
        return jdbc.update(
                "insert into trading.doc_order_line (line_id, document_id, requested_qty) values (?, ?, 1)",
                lineId,
                order);
    }

    private boolean tryInsertOrderLine(UUID lineId) {
        try {
            return insertOrderLine(lineId) == 1;
        } catch (org.springframework.dao.DataAccessException refused) {
            return false;
        }
    }

    private int insertAllocationLine(UUID lineId, UUID owner) {
        return jdbc.update(
                "insert into trading.order_allocation_line"
                        + " (order_line_id, order_id, owner_entity_id, counterparty_entity_id, allocated_qty)"
                        + " values (?, ?, ?, ?, 1)",
                lineId,
                order,
                owner,
                owner.equals(SELLER) ? BUYER : SELLER);
    }

    private boolean tryInsertAllocationLine(UUID owner) {
        try {
            return insertAllocationLine(Ids.next(), owner) == 1;
        } catch (org.springframework.dao.DataAccessException refused) {
            return false;
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static void insertDocument(
            JdbcTemplate admin, UUID id, String type, UUID owner, UUID counterparty, UUID location) {
        admin.update(
                "insert into kernel.document (document_id, doc_type_code, owner_entity_id, counterparty_entity_id,"
                        + " location_id, status) values (?, ?, ?, ?, ?, 'DRAFT')",
                id,
                type,
                owner,
                counterparty,
                location);
    }

    private static void insertGrn(JdbcTemplate admin, UUID id, UUID location) {
        admin.update(
                "insert into trading.doc_grn (document_id, receiver_entity_id, receiver_location_id, seller_entity_id,"
                        + " drop_id, received_on) values (?, ?, ?, ?, ?, current_date)",
                id,
                BUYER,
                location,
                SELLER,
                Ids.next());
    }

    /** One transaction as the application user with the scope set, the way the kernel sets it for a request; always rolled back. */
    private <T> T inScope(UUID entityId, String policyClass, Supplier<T> work) {
        return inScopeAt(entityId, null, policyClass, work);
    }

    private <T> T inScopeAt(UUID entityId, UUID locationId, String policyClass, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', ?, true),"
                            + " set_config('app.scope_class', ?, true), set_config('app.granted_entities', '{}', true)",
                    Ids.next().toString(),
                    Ids.next().toString(),
                    entityId.toString(),
                    locationId == null ? "" : locationId.toString(),
                    policyClass);
            try {
                return work.get();
            } finally {
                // Always rolled back: the fixtures are the superuser's, and a refused insert has
                // already aborted the transaction.
                status.setRollbackOnly();
            }
        });
    }
}
