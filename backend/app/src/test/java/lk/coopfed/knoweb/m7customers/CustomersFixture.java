package lk.coopfed.knoweb.m7customers;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two societies for the M7 tests: SOCIETY, where the office works, and OTHER, which must see
 * nothing of it. Phone numbers and NIC numbers in the tests are plainly made up (070 000 0xxx,
 * 1900000000xx).
 */
public final class CustomersFixture {

    public static final UUID SOCIETY = UUID.fromString("0190f700-0000-7000-8000-000000000001");
    public static final UUID OTHER = UUID.fromString("0190f700-0000-7000-8000-000000000002");
    public static final UUID SHOP = UUID.fromString("0190f700-0000-7000-8000-000000000011");
    public static final UUID OFFICE_USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");
    public static final UUID OTHER_USER = UUID.fromString("0190f700-0000-7000-8000-000000000022");
    public static final String SOCIETY_CODE = "M7S";

    private CustomersFixture() {}

    public static ScopeContext office() {
        return ScopeContext.dev(OFFICE_USER, SOCIETY, null);
    }

    public static ScopeContext other() {
        return ScopeContext.dev(OTHER_USER, OTHER, null);
    }

    /** What a till's upload runs as: the society at its shop, with no user. */
    public static ScopeContext till() {
        return ScopeContext.dev(null, SOCIETY, SHOP);
    }

    public static void arrange(JdbcTemplate admin) {
        clean(admin);
        entity(admin, SOCIETY, SOCIETY_CODE);
        entity(admin, OTHER, "M7O");
        admin.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, status)
                values (?, ?, 'S1', 'SHOP', 'Town shop', 'ACTIVE')
                """,
                SHOP,
                SOCIETY);
    }

    /** Everything the M7 tests and the demo leave behind in the customers schema, and the CPRs. */
    public static void cleanAllCustomers(JdbcTemplate admin) {
        for (String table : new String[] {
            "allocation",
            "account_posting",
            "doc_customer_payment",
            "customer_account",
            "customer_tag",
            "customer_consent",
            "customer_phone",
            "customer"
        }) {
            admin.execute("delete from customers." + table);
        }
        String cpr = "(select document_id from kernel.document where doc_type_code = 'CPR')";
        admin.execute("delete from kernel.document_state_history where document_id in " + cpr);
        admin.execute("delete from kernel.document_line where document_id in " + cpr);
        admin.execute("delete from kernel.document where doc_type_code = 'CPR'");
    }

    public static void clean(JdbcTemplate admin) {
        cleanAllCustomers(admin);
        admin.update("delete from kernel.numbering_series where owner_entity_id in (?, ?)", SOCIETY, OTHER);
        admin.update("delete from party.location where location_id = ?", SHOP);
        admin.update("delete from party.entity_party_directory where entity_id in (?, ?)", SOCIETY, OTHER);
        admin.update("delete from party.entity where entity_id in (?, ?)", SOCIETY, OTHER);
    }

    private static void entity(JdbcTemplate admin, UUID id, String code) {
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, status)
                values (?, ?, 'MPCS', ?, 'ACTIVE')
                """,
                id,
                code,
                code + " Multi-Purpose Cooperative Society");
    }
}
