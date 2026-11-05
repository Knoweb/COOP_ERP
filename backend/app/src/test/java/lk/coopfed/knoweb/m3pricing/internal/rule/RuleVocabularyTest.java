package lk.coopfed.knoweb.m3pricing.internal.rule;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleVocabularyTest {

    private CatalogueQueries catalogue;
    private PartyQueries party;
    private RuleVocabulary vocabulary;
    private ScopeContext scope;
    private final UUID entityId = UUID.randomUUID();
    private final UUID skuId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        catalogue = mock(CatalogueQueries.class);
        party = mock(PartyQueries.class);
        vocabulary = new RuleVocabulary(catalogue, party);
        scope = ScopeContext.dev(UUID.randomUUID(), entityId, null);

        SkuView sku = new SkuView(
                skuId, "SKU-001", null, "SHARED", "Tea", null, null, null, null, null, "EA", false, false, false, false,
                null, null, null, null, Map.of(), null);
        when(catalogue.getSku(any(), any())).thenReturn(Optional.of(sku));

        LocationView loc = new LocationView(
                shopId, entityId, "S01", "SHOP", "Main Shop", null, null, null, null, null, null, "en", List.of(), null, true, null, "ACTIVE");
        when(party.getLocation(eq(shopId), any())).thenReturn(Optional.of(loc));
    }

    @Test
    void allowsGeneralRuleWithNullOrBlankCustomerClass() {
        RulePredicate nullClass = new RulePredicate(skuId, null, null, null, null, null);
        RulePredicate blankClass = new RulePredicate(skuId, null, null, null, null, "   ");
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatCode(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, nullClass, benefit, scope))
                .doesNotThrowAnyException();
        assertThatCode(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, blankClass, benefit, scope))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsMemberCustomerClass() {
        RulePredicate memberClass = new RulePredicate(skuId, null, null, null, null, "MEMBER");
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatCode(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, memberClass, benefit, scope))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsEmployeeCustomerClass() {
        RulePredicate employeeClass = new RulePredicate(skuId, null, null, null, null, "EMPLOYEE");
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatCode(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, employeeClass, benefit, scope))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesInvalidCustomerClass() {
        RulePredicate invalidClass = new RulePredicate(skuId, null, null, null, null, "STUDENT");
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatThrownBy(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, invalidClass, benefit, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m3.rule.customer_class_invalid");
    }

    @Test
    void allowsEntityWideRuleWithNullLocationId() {
        RulePredicate entityWide = new RulePredicate(skuId, null, null, null, null, null, null);
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatCode(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, entityWide, benefit, scope))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsBranchRuleWhenLocationBelongsToEntity() {
        RulePredicate branchRule = new RulePredicate(skuId, null, null, null, null, null, shopId);
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatCode(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, branchRule, benefit, scope))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesBranchRuleWhenLocationBelongsToAnotherEntity() {
        UUID otherEntity = UUID.randomUUID();
        UUID otherShop = UUID.randomUUID();
        LocationView otherLoc = new LocationView(
                otherShop, otherEntity, "S02", "SHOP", "Other Shop", null, null, null, null, null, null, "en", List.of(), null, true, null, "ACTIVE");
        when(party.getLocation(eq(otherShop), any())).thenReturn(Optional.of(otherLoc));

        RulePredicate foreignShopRule = new RulePredicate(skuId, null, null, null, null, null, otherShop);
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatThrownBy(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, foreignShopRule, benefit, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m3.rule.location_invalid");
    }

    @Test
    void refusesBranchRuleWhenLocationNotFound() {
        UUID missingShop = UUID.randomUUID();
        when(party.getLocation(eq(missingShop), any())).thenReturn(Optional.empty());

        RulePredicate missingShopRule = new RulePredicate(skuId, null, null, null, null, null, missingShop);
        RuleBenefit benefit = new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00"));

        assertThatThrownBy(() -> vocabulary.check(RuleVocabulary.TIME_LIMITED_PRICE, missingShopRule, benefit, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m3.rule.location_invalid");
    }
}
