package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ActivateRule;
import lk.coopfed.knoweb.m3pricing.api.AdoptAdvisoryRule;
import lk.coopfed.knoweb.m3pricing.api.AuthorRule;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RuleDrafted;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;
import lk.coopfed.knoweb.m3pricing.internal.rule.ActivateRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AdoptAdvisoryRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AuthorRuleHandler;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(PinnedClock.class)
class AdoptAdvisoryRulePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e751-0000-7000-8000-000000000003");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e751-0000-7000-8000-000000000100");

    private final UUID sku = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;

    @Autowired
    AuthorRuleHandler author;

    @Autowired
    ActivateRuleHandler activate;

    @Autowired
    AdoptAdvisoryRuleHandler adopt;

    @Autowired
    PricingQueries queries;

    @BeforeEach
    void arrange() {
        clean();

        JdbcTemplate admin = superuserJdbc();

        admin.update(
                "insert into party.entity "
                        + "(entity_id, entity_code, entity_type, legal_name_en, status) "
                        + "values (?, 'M3ADOPT', 'MPCS', 'Adoption test society', 'ACTIVE')",
                SOCIETY);

        admin.update("insert into catalogue.uom (uom_code, name_en, is_weight) "
                + "values ('EA', 'Each', false) on conflict do nothing");

        admin.update(
                "insert into catalogue.tax_category "
                        + "(tax_category_id, code, name_en, owner_entity_id) "
                        + "values (?, 'M3ADOPTTAX', 'Adoption tax', ?)",
                TAX_CATEGORY,
                FEDERATION);

        admin.update(
                "insert into catalogue.sku "
                        + "(sku_id, sku_code, owner_entity_id, status, "
                        + "short_name_en, short_name_si, short_name_ta, "
                        + "base_uom_code, sold_by_weight, tax_category_id) "
                        + "values (?, 'M3ADOPTSKU', ?, 'SHARED', "
                        + "'Rice', 'Rice', 'Rice', 'EA', false, ?)",
                sku,
                FEDERATION,
                TAX_CATEGORY);

        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();

        admin.update("delete from pricing.discount_rule " + "where owner_entity_id in (?, ?)", SOCIETY, FEDERATION);

        admin.update("delete from catalogue.sku " + "where sku_code = 'M3ADOPTSKU'");

        admin.update("delete from catalogue.tax_category " + "where tax_category_id = ?", TAX_CATEGORY);

        admin.update("delete from party.entity_party_directory where entity_id = ?", SOCIETY);

        admin.update("delete from party.entity where entity_id = ?", SOCIETY);
    }

    @Test
    void activeFederationAdvisoryIsAdoptedAsSocietyDraft() {
        UUID sourceId = author.handle(
                new AuthorRule(
                        "Federation rice promotion",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(sku, null, null, null, null),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("10")),
                        50,
                        today,
                        today.plusDays(7)),
                ScopeContext.dev(Ids.next(), FEDERATION, null));

        // Test fixture: the public authoring flow does not yet
        // support marking Federation rules as advisory.
        superuserJdbc().update("update pricing.discount_rule set advisory = true " + "where rule_id = ?", sourceId);

        activate.handle(new ActivateRule(sourceId), ScopeContext.dev(Ids.next(), FEDERATION, null));

        kernel.reset();

        ScopeContext societyScope = ScopeContext.dev(Ids.next(), SOCIETY, null);

        UUID adoptedId = adopt.handle(new AdoptAdvisoryRule(sourceId), societyScope);

        assertThat(adoptedId).isNotEqualTo(sourceId);

        var adopted = queries.getRule(adoptedId, societyScope).orElseThrow();

        assertThat(adopted.ownerEntityId()).isEqualTo(SOCIETY);
        assertThat(adopted.status()).isEqualTo("DRAFT");
        assertThat(adopted.advisory()).isFalse();
        assertThat(adopted.adoptedFromRuleId()).isEqualTo(sourceId);
        assertThat(adopted.name()).isEqualTo("Federation rice promotion");
        assertThat(adopted.priority()).isEqualTo(50);
        assertThat(adopted.benefit().value()).isEqualByComparingTo("10");

        var source = queries.getRule(sourceId, societyScope).orElseThrow();

        assertThat(source.ownerEntityId()).isEqualTo(FEDERATION);
        assertThat(source.status()).isEqualTo("ACTIVE");
        assertThat(source.advisory()).isTrue();

        assertThat(kernel.committedAudit().stream()
                        .filter(record -> "RULE_AUTHORED".equals(record.eventType()))
                        .filter(record -> adoptedId.equals(record.subject().id()))
                        .count())
                .isEqualTo(1);

        assertThat(kernel.committedEvents().stream()
                        .filter(RuleDrafted.class::isInstance)
                        .map(RuleDrafted.class::cast))
                .containsExactly(new RuleDrafted(adoptedId, SOCIETY, "TIME_LIMITED_PRICE"));
    }

    @Test
    void adoptionRejectsRulesThatAreNotActiveAdvisories() {
        UUID sourceId = author.handle(
                new AuthorRule(
                        "Ordinary Federation rule",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(sku, null, null, null, null),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("10")),
                        50,
                        today,
                        today.plusDays(7)),
                ScopeContext.dev(Ids.next(), FEDERATION, null));

        ScopeContext societyScope = ScopeContext.dev(Ids.next(), SOCIETY, null);

        kernel.reset();

        // An ordinary Federation rule cannot be adopted.
        assertThatThrownBy(() -> adopt.handle(new AdoptAdvisoryRule(sourceId), societyScope))
                .isInstanceOf(ProblemException.class);

        // A draft advisory rule cannot be adopted.
        superuserJdbc().update("update pricing.discount_rule set advisory = true where rule_id = ?", sourceId);

        assertThatThrownBy(() -> adopt.handle(new AdoptAdvisoryRule(sourceId), societyScope))
                .isInstanceOf(ProblemException.class);

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        Integer adoptedCount = superuserJdbc()
                .queryForObject(
                        "select count(*) from pricing.discount_rule " + "where adopted_from_rule_id = ?",
                        Integer.class,
                        sourceId);

        assertThat(adoptedCount).isZero();
    }
}
