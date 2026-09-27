package lk.coopfed.knoweb.m4trading;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The trading partners of the M4 tests, written as the superuser (no M1 command is under test
 * here): a seller and a buyer, both ACTIVE, with the buyer's warehouse and shop; the ACTIVE
 * relationship of the pair since a month ago; two SHARED items of the Federation (visible to every
 * entity), in EA. Every M4 test uses the same ids and cleans every trading and document row after
 * itself.
 */
public final class TradingFixture {

    public static final UUID SELLER = UUID.fromString("0190f400-0000-7000-8000-000000000001");
    public static final UUID BUYER = UUID.fromString("0190f400-0000-7000-8000-000000000002");
    public static final UUID STRANGER = UUID.fromString("0190f400-0000-7000-8000-000000000003");
    public static final UUID WAREHOUSE = UUID.fromString("0190f400-0000-7000-8000-000000000011");
    public static final UUID SHOP = UUID.fromString("0190f400-0000-7000-8000-000000000012");
    public static final UUID SELLER_USER = UUID.fromString("0190f400-0000-7000-8000-000000000021");
    public static final UUID BUYER_USER = UUID.fromString("0190f400-0000-7000-8000-000000000022");
    public static final UUID RELATIONSHIP = UUID.fromString("0190f400-0000-7000-8000-000000000031");
    public static final UUID TAX_CATEGORY = UUID.fromString("0190f400-0000-7000-8000-000000000041");
    public static final UUID RICE = UUID.fromString("0190f400-0000-7000-8000-000000000051");
    public static final UUID DHAL = UUID.fromString("0190f400-0000-7000-8000-000000000052");
    public static final UUID DRAFT_SKU = UUID.fromString("0190f400-0000-7000-8000-000000000053");
    public static final UUID PRICE_LIST = UUID.fromString("0190f400-0000-7000-8000-000000000061");

    /** The trade prices of the seller's published list for the relationship (M3-04). */
    public static final java.math.BigDecimal RICE_PRICE = new java.math.BigDecimal("120.0000");

    public static final java.math.BigDecimal DHAL_PRICE = new java.math.BigDecimal("80.0000");

    public static final String SELLER_CODE = "D4S";
    public static final String BUYER_CODE = "D4B";

    private TradingFixture() {}

    public static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Colombo"));
    }

    public static ScopeContext seller() {
        return ScopeContext.dev(SELLER_USER, SELLER, null);
    }

    public static ScopeContext buyer() {
        return ScopeContext.dev(BUYER_USER, BUYER, null);
    }

    public static ScopeContext buyerAt(UUID location) {
        return ScopeContext.dev(BUYER_USER, BUYER, location);
    }

    public static void arrange(JdbcTemplate admin) {
        clean(admin);
        entity(admin, SELLER, SELLER_CODE, "209876543-7000");
        entity(admin, BUYER, BUYER_CODE, "109876543-7000");
        entity(admin, STRANGER, "D4X", null);
        location(admin, WAREHOUSE, BUYER, "W1", "WAREHOUSE");
        location(admin, SHOP, BUYER, "S1", "SHOP");
        admin.update(
                """
                insert into party.entity_relationship (relationship_id, seller_entity_id, buyer_entity_id, status,
                    effective_from, price_list_id)
                values (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                RELATIONSHIP,
                SELLER,
                BUYER,
                today().minusDays(30),
                PRICE_LIST);
        admin.update("insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false)"
                + " on conflict do nothing");
        admin.update(
                """
                insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)
                values (?, 'M4TRD', 'M4 trading tax', ?) on conflict do nothing
                """,
                TAX_CATEGORY,
                PostgresIntegrationTestFederation.ID);
        sku(admin, RICE, "M4-RICE", "SHARED", PostgresIntegrationTestFederation.ID);
        sku(admin, DHAL, "M4-DHAL", "SHARED", PostgresIntegrationTestFederation.ID);
        sku(admin, DRAFT_SKU, "M4-DRAFT", "DRAFT", BUYER);
        priceList(admin);
    }

    public static void clean(JdbcTemplate admin) {
        for (String table : new String[] {
            "doc_invoice",
            "doc_discrepancy_line",
            "doc_discrepancy",
            "doc_grn_line",
            "doc_grn",
            "doc_delivery_line",
            "doc_delivery_drop",
            "doc_delivery",
            "order_allocation_line",
            "order_allocation",
            "allocation_run",
            "doc_order_line",
            "doc_order"
        }) {
            admin.execute("delete from trading." + table);
        }
        String ours = "(select document_id from kernel.document where owner_entity_id in (?, ?, ?))";
        admin.update(
                "delete from kernel.document_link where from_document_id in " + ours + " or to_document_id in " + ours,
                SELLER,
                BUYER,
                STRANGER,
                SELLER,
                BUYER,
                STRANGER);
        admin.update("delete from kernel.document_state_history where document_id in " + ours, SELLER, BUYER, STRANGER);
        admin.update("delete from kernel.document_line where document_id in " + ours, SELLER, BUYER, STRANGER);
        admin.update("delete from kernel.document where owner_entity_id in (?, ?, ?)", SELLER, BUYER, STRANGER);
        admin.update("delete from kernel.numbering_series where owner_entity_id in (?, ?, ?)", SELLER, BUYER, STRANGER);
        admin.update("delete from catalogue.batch where sku_id in (?, ?, ?)", RICE, DHAL, DRAFT_SKU);
        admin.update("delete from catalogue.sku where sku_id in (?, ?, ?)", RICE, DHAL, DRAFT_SKU);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
        admin.update("update pricing.price_list set status = 'DRAFT' where price_list_id = ?", PRICE_LIST);
        admin.update("delete from pricing.price_list_line where price_list_id = ?", PRICE_LIST);
        admin.update("delete from pricing.price_list where price_list_id = ?", PRICE_LIST);
        admin.update("delete from party.entity_relationship where relationship_id = ?", RELATIONSHIP);
        admin.update("delete from party.location where location_id in (?, ?)", WAREHOUSE, SHOP);
        admin.update("delete from party.entity_party_directory where entity_id in (?, ?, ?)", SELLER, BUYER, STRANGER);
        admin.update("delete from party.entity where entity_id in (?, ?, ?)", SELLER, BUYER, STRANGER);
    }

    /** The seller's TRADE list, bound to the relationship: drafted, lined, then published (lines join drafts only). */
    private static void priceList(JdbcTemplate admin) {
        admin.update(
                """
                insert into pricing.price_list (price_list_id, owner_entity_id, kind, name, version, root_price_list_id,
                    status)
                values (?, ?, 'TRADE', 'D4S trade list', 1, ?, 'DRAFT')
                """,
                PRICE_LIST,
                SELLER,
                PRICE_LIST);
        for (Object[] line : new Object[][] {{RICE, RICE_PRICE}, {DHAL, DHAL_PRICE}}) {
            admin.update(
                    """
                    insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price,
                        effective_from, owner_entity_id)
                    values (?, ?, ?, 'EA', 0, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    PRICE_LIST,
                    line[0],
                    line[1],
                    today().minusDays(30),
                    SELLER);
        }
        admin.update(
                "update pricing.price_list set status = 'PUBLISHED', apply_from = ?, published_at = now() where price_list_id = ?",
                today().minusDays(30),
                PRICE_LIST);
    }

    private static void entity(JdbcTemplate admin, UUID id, String code, String vatNo) {
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, vat_registration_no, status)
                values (?, ?, 'DISTRIBUTOR', ?, ?, 'ACTIVE')
                """,
                id,
                code,
                code + " Distributors",
                vatNo);
    }

    private static void location(JdbcTemplate admin, UUID id, UUID owner, String code, String type) {
        admin.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, status)
                values (?, ?, ?, ?, ?, 'ACTIVE')
                """,
                id,
                owner,
                code,
                type,
                code);
    }

    private static void sku(JdbcTemplate admin, UUID id, String code, String status, UUID owner) {
        admin.update(
                """
                insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,
                    short_name_ta, base_uom_code, sold_by_weight, tax_category_id)
                values (?, ?, ?, ?, ?, ?, ?, 'EA', false, ?)
                """,
                id,
                code,
                owner,
                status,
                code,
                code,
                code,
                TAX_CATEGORY);
    }

    /** The Federation of the test context (PostgresIntegrationTest.TEST_FEDERATION), which owns the SHARED items. */
    static final class PostgresIntegrationTestFederation {
        static final UUID ID = UUID.fromString("0190e000-0000-7000-8000-00000000f0f0");
    }
}
