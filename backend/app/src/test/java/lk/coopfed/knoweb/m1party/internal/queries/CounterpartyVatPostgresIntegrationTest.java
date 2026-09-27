package lk.coopfed.knoweb.m1party.internal.queries;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CR-21A-6: the seller reads the buyer's VAT number through M1's counterparty view. A trading
 * relationship must be ACTIVE (the directory's RLS narrowing, V0004/V0013); an entity that is
 * neither the caller's own nor an active counterparty stays invisible, VAT number included.
 */
class CounterpartyVatPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-000000009721");

    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-000000009722");

    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000009723");

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000009724");

    @Autowired
    private PartyQueries queries;

    @BeforeEach
    void seedPartiesAndRelationship() {
        for (UUID id : List.of(SELLER, BUYER, STRANGER)) {
            superuserJdbc()
                    .update(
                            "delete from party.entity_relationship where seller_entity_id = ? or buyer_entity_id = ?",
                            id,
                            id);
            superuserJdbc().update("delete from party.entity_party_directory where entity_id = ?", id);
            superuserJdbc().update("delete from party.entity where entity_id = ?", id);
        }
        insertEntity(SELLER, "Q972A", "DISTRIBUTOR", "Colombo Distributor", "VAT-SELLER-9721");
        insertEntity(BUYER, "Q972B", "MPCS", "Kurunegala MPCS", "VAT-BUYER-9722");
        insertEntity(STRANGER, "Q972C", "MPCS", "Unrelated MPCS", "VAT-STRANGER-9723");
        superuserJdbc()
                .update(
                        """
                        insert into party.entity_relationship
                            (relationship_id, seller_entity_id, buyer_entity_id, status, effective_from)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                        UUID.randomUUID(),
                        SELLER,
                        BUYER,
                        LocalDate.now().minusDays(30));
    }

    @Test
    void theSellerReadsItsActiveCounterpartysVatNumber() {
        EntityView view = queries.getEntity(BUYER, ownScope(SELLER)).orElseThrow();

        assertThat(view.legalNameEn()).isEqualTo("Kurunegala MPCS");
        assertThat(view.vatRegistrationNo()).isEqualTo("VAT-BUYER-9722");
        // The counterparty projection, not the full row: fields PARTY may not see stay null.
        assertThat(view.entityCode()).isNull();
        assertThat(view.registrationNo()).isNull();
    }

    @Test
    void theSellerCannotReadAnUnrelatedEntitysVatNumber() {
        assertThat(queries.getEntity(STRANGER, ownScope(SELLER))).isEmpty();
    }

    @Test
    void theSellerStillReadsItsOwnFullRow() {
        EntityView view = queries.getEntity(SELLER, ownScope(SELLER)).orElseThrow();

        assertThat(view.entityCode()).isEqualTo("Q972A");
        assertThat(view.vatRegistrationNo()).isEqualTo("VAT-SELLER-9721");
    }

    private static void insertEntity(UUID id, String code, String type, String nameEn, String vatNo) {
        superuserJdbc()
                .update(
                        """
                        insert into party.entity
                            (entity_id, entity_code, entity_type, legal_name_en, vat_registration_no)
                        values (?, ?, ?, ?, ?)
                        """,
                        id,
                        code,
                        type,
                        nameEn,
                        vatNo);
    }

    private static ScopeContext ownScope(UUID entityId) {
        Scope scope = new Scope(entityId, null);
        return new ScopeContext(
                USER, null, entityId, List.of(scope), scope, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }
}
