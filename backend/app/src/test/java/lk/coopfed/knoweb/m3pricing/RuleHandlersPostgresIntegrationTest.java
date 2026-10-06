package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ActivateRule;
import lk.coopfed.knoweb.m3pricing.api.AuthorRule;
import lk.coopfed.knoweb.m3pricing.api.RuleActivated;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RuleDrafted;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;
import lk.coopfed.knoweb.m3pricing.api.RuleWithdrawn;
import lk.coopfed.knoweb.m3pricing.api.WithdrawRule;
import lk.coopfed.knoweb.m3pricing.internal.rule.ActivateRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AuthorRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.WithdrawRuleHandler;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M3-05 (23A section 7; doc 23 section 4.2, flow 6.3): AuthorRule, ActivateRule and WithdrawRule,
 * each guard with its failing case and nothing committed, the vocabulary of every kind, what each
 * handler audits and publishes, and who sees a rule.
 */
@Import(PinnedClock.class)
class RuleHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e750-0000-7000-8000-000000000003");
    private static final UUID STRANGER = UUID.fromString("0190e750-0000-7000-8000-000000000004");
    private static final UUID USER = UUID.fromString("0190e750-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e750-0000-7000-8000-000000000100");

    @Autowired
    AuthorRuleHandler author;

    @Autowired
    ActivateRuleHandler activate;

    @Autowired
    WithdrawRuleHandler withdraw;

    @Autowired
    PricingQueries queries;

    private final UUID milk = Ids.next();
    private final UUID biscuits = Ids.next();
    private final UUID draftSku = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3RULE', 'M3 rule tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        insertSku(admin, milk, "M3R-MILK", "SHARED", true);
        insertSku(admin, biscuits, "M3R-BISCUIT", "SHARED", false);
        insertSku(admin, draftSku, "M3R-DRAFT", "DRAFT", false);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.discount_rule");
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    // ---- flow 6.3: author, activate, withdraw ------------------------------------------------------

    @Test
    void aRuleIsAuthoredActivatedAndWithdrawn() {
        UUID rule = author.handle(
                new AuthorRule(
                        " Milk week ",
                        "TIME_LIMITED_PRICE",
                        item(milk),
                        benefit("PERCENT_OFF", "10"),
                        null,
                        today,
                        today.plusDays(6)),
                own(SOCIETY));

        assertThat(queries.getRule(rule, own(SOCIETY))).get().satisfies(view -> {
            assertThat(view.status()).isEqualTo("DRAFT");
            assertThat(view.name()).isEqualTo("Milk week");
            assertThat(view.priority()).isEqualTo(100);
            assertThat(view.predicate().skuId()).isEqualTo(milk);
            assertThat(view.benefit().kind()).isEqualTo("PERCENT_OFF");
            assertThat(view.benefit().value()).isEqualByComparingTo("10");
            assertThat(view.validTo()).isEqualTo(today.plusDays(6));
        });
        assertThat(audit("RULE_AUTHORED"))
                .singleElement()
                .satisfies(r -> assertThat(r.subject().id()).isEqualTo(rule));
        assertThat(events(RuleDrafted.class)).containsExactly(new RuleDrafted(rule, SOCIETY, "TIME_LIMITED_PRICE"));
        kernel.reset();

        activate.handle(new ActivateRule(rule), own(SOCIETY));
        assertThat(queries.getRule(rule, own(SOCIETY))).get().satisfies(v -> assertThat(v.status())
                .isEqualTo("ACTIVE"));
        assertThat(audit("RULE_ACTIVATED")).singleElement().satisfies(r -> assertThat(
                        ((Map<?, ?>) r.after()).get("status"))
                .isEqualTo("ACTIVE"));
        assertThat(events(RuleActivated.class))
                .containsExactly(new RuleActivated(rule, SOCIETY, "TIME_LIMITED_PRICE", today, today.plusDays(6)));
        kernel.reset();

        withdraw.handle(new WithdrawRule(rule, "Supplier stopped the promotion"), own(SOCIETY));
        assertThat(queries.getRule(rule, own(SOCIETY))).get().satisfies(v -> assertThat(v.status())
                .isEqualTo("WITHDRAWN"));
        assertThat(audit("RULE_WITHDRAWN")).singleElement().satisfies(r -> assertThat(
                        ((Map<?, ?>) r.after()).get("reason"))
                .isEqualTo("Supplier stopped the promotion"));
        assertThat(events(RuleWithdrawn.class)).containsExactly(new RuleWithdrawn(rule, SOCIETY, "TIME_LIMITED_PRICE"));
    }

    @Test
    void everyKindOfTheDemoIsAccepted() {
        author.handle(
                rule(
                        "QUANTITY_BREAK",
                        new RulePredicate(biscuits, null, new BigDecimal("3"), null, null),
                        benefit("FIXED_PRICE", "90")),
                own(SOCIETY));
        author.handle(
                rule(
                        "BILL_THRESHOLD",
                        new RulePredicate(null, null, null, null, new BigDecimal("5000")),
                        benefit("AMOUNT_OFF", "100")),
                own(SOCIETY));
        author.handle(
                rule("EXPIRY_MARKDOWN", new RulePredicate(milk, null, null, 3, null), benefit("PERCENT_OFF", "25")),
                own(SOCIETY));
        author.handle(
                rule(
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(milk, "EA", null, null, null),
                        benefit("AMOUNT_OFF", "5.50")),
                own(SOCIETY));

        assertThat(queries.listRules(null, null, own(SOCIETY))).hasSize(4);
        assertThat(queries.listRules("DRAFT", "BILL_THRESHOLD", own(SOCIETY)))
                .singleElement()
                .satisfies(r -> assertThat(r.predicate().billTotalFrom()).isEqualByComparingTo("5000"));
        assertThat(queries.listRules(null, null, own(STRANGER)))
                .as("another entity's rules")
                .isEmpty();
        assertThat(queries.listRules(null, null, viewer(FEDERATION)))
                .as("the Federation view")
                .hasSize(4);
    }

    // ---- guards, each with nothing committed --------------------------------------------------------

    @Test
    void theVocabularyRefusesWhatAKindDoesNotTake() {
        refused(rule("FREE_ITEM", item(milk), benefit("FREE_QTY", "1")), "m3.rule.kind_not_available");
        refused(rule("SOMETHING", item(milk), benefit("PERCENT_OFF", "1")), "m3.rule.kind_invalid");
        refused(rule("TIME_LIMITED_PRICE", item(null), benefit("PERCENT_OFF", "1")), "m3.rule.sku_required");
        refused(rule("TIME_LIMITED_PRICE", item(draftSku), benefit("PERCENT_OFF", "1")), "m3.rule.sku_not_active");
        refused(rule("TIME_LIMITED_PRICE", item(Ids.next()), benefit("PERCENT_OFF", "1")), "m3.rule.sku_not_active");
        refused(
                rule(
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(milk, "KG", null, null, null),
                        benefit("PERCENT_OFF", "1")),
                "m3.rule.uom_invalid");
        refused(
                rule(
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(milk, null, BigDecimal.ONE, null, null),
                        benefit("PERCENT_OFF", "1")),
                "m3.rule.field_not_allowed");
        refused(rule("QUANTITY_BREAK", item(milk), benefit("PERCENT_OFF", "1")), "m3.rule.min_qty_required");
        refused(
                rule(
                        "QUANTITY_BREAK",
                        new RulePredicate(milk, null, new BigDecimal("0.0001"), null, null),
                        benefit("PERCENT_OFF", "1")),
                "m3.rule.min_qty_required");
        refused(rule("EXPIRY_MARKDOWN", item(milk), benefit("PERCENT_OFF", "1")), "m3.rule.days_to_expiry_required");
        refused(
                rule("EXPIRY_MARKDOWN", new RulePredicate(biscuits, null, null, 3, null), benefit("PERCENT_OFF", "1")),
                "m3.rule.not_expiry_tracked");
        refused(
                rule("BILL_THRESHOLD", new RulePredicate(null, null, null, null, null), benefit("PERCENT_OFF", "1")),
                "m3.rule.bill_total_required");
        refused(
                rule(
                        "BILL_THRESHOLD",
                        new RulePredicate(milk, null, null, null, BigDecimal.TEN),
                        benefit("PERCENT_OFF", "1")),
                "m3.rule.field_not_allowed");
        refused(
                rule(
                        "BILL_THRESHOLD",
                        new RulePredicate(null, null, null, null, BigDecimal.TEN),
                        benefit("FIXED_PRICE", "1")),
                "m3.rule.benefit_not_allowed");
        refused(
                rule("TIME_LIMITED_PRICE", item(milk), benefit("PERCENT_OFF", "100.01")),
                "m3.rule.benefit_value_invalid");
        refused(rule("TIME_LIMITED_PRICE", item(milk), benefit("PERCENT_OFF", "0")), "m3.rule.benefit_value_invalid");
        refused(rule("TIME_LIMITED_PRICE", item(milk), benefit("AMOUNT_OFF", "0")), "m3.rule.benefit_value_invalid");
        refused(
                rule("TIME_LIMITED_PRICE", item(milk), benefit("FIXED_PRICE", "1.005")),
                "m3.rule.benefit_value_invalid");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    /**
     * CR-23A-1 (D11, M3-07): no free goods through a price. A fixed price of 0 or 100 % off is refused
     * by every kind that takes it; giving goods away is a DONATION or SAMPLES write-off.
     */
    @Test
    void aRuleThatMakesTheItemFreeIsRefused() {
        RulePredicate quantity = new RulePredicate(milk, null, BigDecimal.TEN, null, null);
        RulePredicate expiry = new RulePredicate(milk, null, null, 3, null);
        for (AuthorRule free : List.of(
                rule("TIME_LIMITED_PRICE", item(milk), benefit("FIXED_PRICE", "0")),
                rule("TIME_LIMITED_PRICE", item(milk), benefit("FIXED_PRICE", "0.00")),
                rule("TIME_LIMITED_PRICE", item(milk), benefit("PERCENT_OFF", "100")),
                rule("QUANTITY_BREAK", quantity, benefit("FIXED_PRICE", "0")),
                rule("QUANTITY_BREAK", quantity, benefit("PERCENT_OFF", "100.00")),
                rule("EXPIRY_MARKDOWN", expiry, benefit("FIXED_PRICE", "0")),
                rule("EXPIRY_MARKDOWN", expiry, benefit("PERCENT_OFF", "100")),
                rule(
                        "BILL_THRESHOLD",
                        new RulePredicate(null, null, null, null, BigDecimal.TEN),
                        benefit("PERCENT_OFF", "100")))) {
            refused(free, "m3.rule.benefit_value_invalid");
        }
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // Just short of free is a discount, not a gift.
        author.handle(rule("TIME_LIMITED_PRICE", item(milk), benefit("PERCENT_OFF", "99.99")), own(SOCIETY));
        author.handle(rule("EXPIRY_MARKDOWN", expiry, benefit("FIXED_PRICE", "0.01")), own(SOCIETY));
        assertThat(kernel.committedAudit()).hasSize(2);
    }

    @Test
    void authorRefusesAWrongScopeAndBadDatesAndPriorities() {
        AuthorRule good = rule("TIME_LIMITED_PRICE", item(milk), benefit("PERCENT_OFF", "5"));
        refused(() -> author.handle(good, atShop(SOCIETY)), "scope.invalid");
        refused(() -> author.handle(good, viewer(FEDERATION)), "scope.invalid");
        refused(
                () -> author.handle(
                        new AuthorRule("  ", good.kind(), good.predicate(), good.benefit(), null, today, null),
                        own(SOCIETY)),
                "request.field.required");
        refused(
                () -> author.handle(
                        new AuthorRule("x", good.kind(), good.predicate(), good.benefit(), null, null, null),
                        own(SOCIETY)),
                "request.field.required");
        refused(
                () -> author.handle(
                        new AuthorRule(
                                "x", good.kind(), good.predicate(), good.benefit(), null, today, today.minusDays(1)),
                        own(SOCIETY)),
                "m3.rule.validity_invalid");
        refused(
                () -> author.handle(
                        new AuthorRule("x", good.kind(), good.predicate(), good.benefit(), 40000, today, null),
                        own(SOCIETY)),
                "m3.rule.priority_invalid");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void activateAndWithdrawRefuseWhatTheyMayNotChange() {
        UUID rule = author.handle(rule("TIME_LIMITED_PRICE", item(milk), benefit("PERCENT_OFF", "5")), own(SOCIETY));
        UUID old = author.handle(
                new AuthorRule("Old", "TIME_LIMITED_PRICE", item(milk), benefit("PERCENT_OFF", "5"), null, today, null),
                own(SOCIETY));
        superuserJdbc()
                .update("update pricing.discount_rule set valid_from = ? where rule_id = ?", today.minusDays(1), old);
        kernel.reset();

        refused(() -> activate.handle(new ActivateRule(rule), own(STRANGER)), "m3.rule.not_found");
        refused(() -> activate.handle(new ActivateRule(Ids.next()), own(SOCIETY)), "m3.rule.not_found");
        refused(() -> activate.handle(new ActivateRule(rule), atShop(SOCIETY)), "scope.invalid");
        refused(() -> activate.handle(new ActivateRule(old), own(SOCIETY)), "m3.rule.valid_from_past");
        refused(() -> withdraw.handle(new WithdrawRule(rule, "why"), own(SOCIETY)), "m3.rule.not_active");

        // The item was deactivated after the rule was authored: the vocabulary runs again.
        superuserJdbc().update("update catalogue.sku set status = 'INACTIVE' where sku_id = ?", milk);
        refused(() -> activate.handle(new ActivateRule(rule), own(SOCIETY)), "m3.rule.sku_not_active");
        superuserJdbc().update("update catalogue.sku set status = 'SHARED' where sku_id = ?", milk);
        assertThat(kernel.committedAudit()).isEmpty();

        activate.handle(new ActivateRule(rule), own(SOCIETY));
        kernel.reset();
        refused(() -> activate.handle(new ActivateRule(rule), own(SOCIETY)), "m3.rule.not_draft");
        refused(() -> withdraw.handle(new WithdrawRule(rule, " "), own(SOCIETY)), "request.field.required");
        refused(() -> withdraw.handle(new WithdrawRule(rule, "why"), own(STRANGER)), "m3.rule.not_found");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private AuthorRule rule(String kind, RulePredicate predicate, RuleBenefit benefit) {
        return new AuthorRule(kind + " rule", kind, predicate, benefit, 50, today, null);
    }

    private static RulePredicate item(UUID sku) {
        return new RulePredicate(sku, null, null, null, null);
    }

    private static RuleBenefit benefit(String kind, String value) {
        return new RuleBenefit(kind, new BigDecimal(value));
    }

    private void refused(AuthorRule command, String messageId) {
        refused(() -> author.handle(command, own(SOCIETY)), messageId);
    }

    private static void insertSku(JdbcTemplate admin, UUID skuId, String code, String status, boolean expiryTracked) {
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id, batch_tracked,"
                        + " expiry_tracked) values (?, ?, ?, ?, ?, ?, ?, 'EA', false, ?, ?, ?)",
                skuId,
                code,
                FEDERATION,
                status,
                code,
                code,
                code,
                TAX_CATEGORY,
                expiryTracked,
                expiryTracked);
    }

    private static ScopeContext own(UUID entity) {
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(new Scope(entity, null)),
                new Scope(entity, null),
                PolicyClass.OWN,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
    }

    private static ScopeContext atShop(UUID entity) {
        return ScopeContext.dev(USER, entity, Ids.next());
    }

    private static ScopeContext viewer(UUID entity) {
        Scope scope = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

    private List<KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
