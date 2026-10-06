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

    /** A second shop of SOCIETY: what a snapshot change must reach beside the shop that sold. */
    public static final UUID SHOP_2 = UUID.fromString("0190f700-0000-7000-8000-000000000012");

    public static final UUID OFFICE_USER = UUID.fromString("0190f700-0000-7000-8000-000000000021");
    public static final UUID OTHER_USER = UUID.fromString("0190f700-0000-7000-8000-000000000022");
    public static final UUID OFFICER_USER = UUID.fromString("0190f700-0000-7000-8000-000000000023");
    public static final UUID SECOND_CLERK = UUID.fromString("0190f700-0000-7000-8000-000000000024");
    public static final String SOCIETY_CODE = "M7S";

    private CustomersFixture() {}

    public static ScopeContext office() {
        return ScopeContext.dev(OFFICE_USER, SOCIETY, null);
    }

    public static ScopeContext other() {
        return ScopeContext.dev(OTHER_USER, OTHER, null);
    }

    /** The society's responsible officer (appointed by {@link #appointOfficer}), second factor fresh. */
    public static ScopeContext officer() {
        return withMfa(ScopeContext.dev(OFFICER_USER, SOCIETY, null), java.time.Instant.now());
    }

    /** The office clerk, having presented the second factor at {@code mfaAt}. */
    public static ScopeContext officeWithMfa() {
        return withMfa(office(), java.time.Instant.now());
    }

    public static ScopeContext withMfa(ScopeContext scope, java.time.Instant mfaAt) {
        return new ScopeContext(
                scope.userId(),
                scope.deviceId(),
                scope.homeEntityId(),
                scope.scopes(),
                scope.activeScope(),
                scope.policyClass(),
                scope.grantedEntities(),
                mfaAt,
                scope.locale(),
                scope.correlationId());
    }

    /** M1 appoints the society's responsible officer (doc 21 CR-21-1); here straight in the table. */
    public static void appointOfficer(JdbcTemplate admin) {
        admin.update(
                "update party.entity set responsible_officer_user_id = ? where entity_id = ?", OFFICER_USER, SOCIETY);
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
        admin.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, status)
                values (?, ?, 'S2', 'SHOP', 'Junction shop', 'ACTIVE')
                """,
                SHOP_2,
                SOCIETY);
    }

    /** Everything the M7 tests and the demo leave behind in the customers schema, and the CPRs. */
    public static void cleanAllCustomers(JdbcTemplate admin) {
        for (String table : new String[] {
            "allocation_reversal",
            "account_history",
            "account_adjustment",
            "data_subject_request",
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
        admin.execute(
                "delete from kernel.document_link where from_document_id in " + cpr + " or to_document_id in " + cpr);
        admin.execute("delete from kernel.document_state_history where document_id in " + cpr);
        admin.execute("delete from kernel.document_line where document_id in " + cpr);
        admin.execute("delete from kernel.document where doc_type_code = 'CPR'");
    }

    public static void clean(JdbcTemplate admin) {
        cleanAllCustomers(admin);
        admin.update("delete from kernel.numbering_series where owner_entity_id in (?, ?)", SOCIETY, OTHER);
        admin.update("delete from kernel.change_log where owner_entity_id in (?, ?)", SOCIETY, OTHER);
        admin.update("delete from kernel.location_snapshot_version where owner_entity_id in (?, ?)", SOCIETY, OTHER);
        admin.update("delete from party.location where location_id in (?, ?)", SHOP, SHOP_2);
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
