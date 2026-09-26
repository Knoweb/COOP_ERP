package lk.coopfed.knoweb.m1party.internal.queries;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityFilter;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The register's search box on the server (21A section 8; the review of 26 September found the
 * web client searching only the pages it had loaded). Three societies with names in the three
 * languages, read as the Federation view, which sees every entity.
 */
class EntitySearchPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000009701");

    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID KANDY = UUID.fromString("00000000-0000-0000-0000-000000009711");

    private static final UUID GALLE = UUID.fromString("00000000-0000-0000-0000-000000009712");

    private static final UUID PERCENT = UUID.fromString("00000000-0000-0000-0000-000000009713");

    @Autowired
    private PartyQueries queries;

    @BeforeEach
    void seedSocieties() {
        for (UUID id : List.of(KANDY, GALLE, PERCENT)) {
            // The directory row (a trigger's copy of the names) references the entity.
            superuserJdbc().update("delete from party.entity_party_directory where entity_id = ?", id);
            superuserJdbc().update("delete from party.entity where entity_id = ?", id);
        }
        insert(KANDY, "Q971A", "Kandy Central MPCS", "මහනුවර මධ්‍යම සමිතිය", "கண்டி மத்திய சங்கம்");
        insert(GALLE, "Q971B", "Galle Harbour MPCS", null, null);
        // A name with the characters a LIKE pattern treats as wildcards.
        insert(PERCENT, "Q971C", "100% Co_op MPCS", null, null);
    }

    @Test
    void searchesTheCodeByPrefixAndTheThreeNamesByPart() {
        assertThat(codesFor("q971b")).containsExactly("Q971B");
        assertThat(codesFor("harbour")).containsExactly("Q971B");
        assertThat(codesFor("මධ්‍යම")).containsExactly("Q971A");
        assertThat(codesFor("மத்திய")).containsExactly("Q971A");
        // A part of the code is not a prefix, and nobody's name contains it.
        assertThat(codesFor("71B")).isEmpty();
    }

    @Test
    void treatsPercentUnderscoreAndBackslashAsLetters() {
        assertThat(codesFor("100% co_op")).containsExactly("Q971C");
        assertThat(codesFor("100%")).containsExactly("Q971C");
        assertThat(codesFor("Co_op")).containsExactly("Q971C");
        assertThat(codesFor("C_op")).isEmpty();
        assertThat(codesFor("\\")).isEmpty();
    }

    @Test
    void aBlankSearchNarrowsNothing() {
        assertThat(codesFor("   ")).contains("Q971A", "Q971B", "Q971C");
    }

    private List<String> codesFor(String query) {
        return queries.listEntities(new EntityFilter(null, null, query, null, 100), federationView()).items().stream()
                .map(EntityView::entityCode)
                .filter(code -> code.startsWith("Q971"))
                .toList();
    }

    private static ScopeContext federationView() {
        Scope scope = new Scope(FEDERATION, null);
        return new ScopeContext(
                USER,
                null,
                FEDERATION,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    private static void insert(UUID id, String code, String nameEn, String nameSi, String nameTa) {
        superuserJdbc()
                .update(
                        """
                        insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, legal_name_si, legal_name_ta)
                        values (?, ?, 'MPCS', ?, ?, ?)
                        """,
                        id,
                        code,
                        nameEn,
                        nameSi,
                        nameTa);
    }
}
