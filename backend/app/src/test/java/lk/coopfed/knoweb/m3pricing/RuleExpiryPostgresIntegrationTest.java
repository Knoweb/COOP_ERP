package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ActivateRule;
import lk.coopfed.knoweb.m3pricing.api.AuthorRule;
import lk.coopfed.knoweb.m3pricing.api.ExpireRule;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RuleExpired;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;
import lk.coopfed.knoweb.m3pricing.internal.rule.ActivateRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AuthorRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.ExpireRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.RuleExpiryJob;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PRC-07: The rule expiry job and ExpireRuleHandler (M3-05, doc 03 section 12, 23A section 7).
 * Proves that an ACTIVE rule past its valid_to becomes EXPIRED with its audit and domain event,
 * that non-expired, withdrawn and already-expired rules are not transitioned, and that
 * running the job is idempotent.
 */
@Import(PinnedClock.class)
class RuleExpiryPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY_A = UUID.fromString("0190e750-0000-7000-8000-000000000003");
    private static final UUID SOCIETY_B = UUID.fromString("0190e750-0000-7000-8000-000000000004");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e750-0000-7000-8000-000000000100");

    @Autowired
    AuthorRuleHandler author;

    @Autowired
    ActivateRuleHandler activate;

    @Autowired
    ExpireRuleHandler expire;

    @Autowired
    RuleExpiryJob job;

    @Autowired
    PricingQueries queries;

    private final UUID milk = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3EXPIRY', 'M3 expiry tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        insertSku(admin, milk, "M3E-MILK", "SHARED", true);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.discount_rule");
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void anActiveRulePastItsEndBecomesExpiredWithAuditAndDomainEvent() {
        UUID ruleId = author.handle(
                new AuthorRule(
                        "Fresh milk promo",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(milk, null, null, null, null),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("15")),
                        null,
                        today,
                        today),
                own(SOCIETY_A));
        activate.handle(new ActivateRule(ruleId), own(SOCIETY_A));

        assertThat(queries.getRule(ruleId, own(SOCIETY_A))).get().satisfies(v -> {
            assertThat(v.status()).isEqualTo("ACTIVE");
        });

        // Simulate midnight passing to the next business date: valid_to is now strictly before today.
        superuserJdbc()
                .update(
                        "update pricing.discount_rule set valid_from = ?, valid_to = ? where rule_id = ?",
                        Date.valueOf(today.minusDays(7)),
                        Date.valueOf(today.minusDays(1)),
                        ruleId);

        kernel.reset();

        int expiredCount = job.expireRules(execution());
        assertThat(expiredCount).isEqualTo(1);

        // 1. Verify status changed to EXPIRED in queries
        assertThat(queries.getRule(ruleId, own(SOCIETY_A))).get().satisfies(v -> {
            assertThat(v.status()).isEqualTo("EXPIRED");
            assertThat(v.validTo()).isEqualTo(today.minusDays(1));
        });
        assertThat(queries.listRules("EXPIRED", null, own(SOCIETY_A)))
                .extracting(RuleView::ruleId)
                .containsExactly(ruleId);
        assertThat(queries.listRules("ACTIVE", null, own(SOCIETY_A))).isEmpty();

        // 2. Verify audit event RULE_EXPIRED
        assertThat(audit(ExpireRuleHandler.AUDIT_EXPIRED)).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(ruleId);
            assertThat(record.subject().type()).isEqualTo("discount_rule");
            assertThat(((Map<?, ?>) record.before()).get("status")).isEqualTo("ACTIVE");
            Map<?, ?> afterMap = (Map<?, ?>) record.after();
            assertThat(afterMap.get("status")).isEqualTo("EXPIRED");
            assertThat(afterMap.get("validTo")).isEqualTo(today.minusDays(1));
            assertThat(record.scope().entityId()).isEqualTo(SOCIETY_A);
        });

        // 3. Verify domain event RuleExpired (discount_rule.expired.v1)
        assertThat(events(RuleExpired.class))
                .containsExactly(new RuleExpired(ruleId, SOCIETY_A, "TIME_LIMITED_PRICE", today.minusDays(1)));

        // 7. Rerunning the job is idempotent: already EXPIRED rule is not processed again
        kernel.reset();
        int rerunCount = job.expireRules(execution());
        assertThat(rerunCount).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void nonExpiredAndTerminalRulesAreNotChanged() {
        // Rule 1: ACTIVE, ended yesterday -> due for expiry
        UUID expiredDue = insertRule(SOCIETY_A, "Ended yesterday", "ACTIVE", today.minusDays(10), today.minusDays(1));

        // Rule 2: ACTIVE, valid through today -> still active today, must NOT expire
        UUID validToday = insertRule(SOCIETY_A, "Valid today", "ACTIVE", today.minusDays(2), today);

        // Rule 3: ACTIVE, valid through next week -> must NOT expire
        UUID validNextWeek = insertRule(SOCIETY_A, "Future valid", "ACTIVE", today, today.plusDays(7));

        // Rule 4: ACTIVE, open-ended (valid_to is null) -> must NOT expire
        UUID openEnded = insertRule(SOCIETY_A, "Open ended", "ACTIVE", today.minusDays(5), null);

        // Rule 5: WITHDRAWN, ended yesterday -> already terminal, must NOT expire
        UUID withdrawn = insertRule(SOCIETY_A, "Withdrawn rule", "WITHDRAWN", today.minusDays(10), today.minusDays(1));

        // Rule 6: DRAFT, ended yesterday -> draft, must NOT expire
        UUID draft = insertRule(SOCIETY_A, "Draft rule", "DRAFT", today.minusDays(10), today.minusDays(1));

        // Rule 7: ACTIVE on SOCIETY_B, ended yesterday -> due for expiry for SOCIETY_B
        UUID societyBEnded = insertRule(SOCIETY_B, "Society B ended", "ACTIVE", today.minusDays(5), today.minusDays(2));

        kernel.reset();

        int count = job.expireRules(execution());
        assertThat(count).isEqualTo(2);

        // Expired rules
        assertThat(statusOf(expiredDue)).isEqualTo("EXPIRED");
        assertThat(statusOf(societyBEnded)).isEqualTo("EXPIRED");

        // Non-expired active rules remain ACTIVE
        assertThat(statusOf(validToday)).isEqualTo("ACTIVE");
        assertThat(statusOf(validNextWeek)).isEqualTo("ACTIVE");
        assertThat(statusOf(openEnded)).isEqualTo("ACTIVE");

        // Terminal or draft rules are untouched
        assertThat(statusOf(withdrawn)).isEqualTo("WITHDRAWN");
        assertThat(statusOf(draft)).isEqualTo("DRAFT");

        // Audit events recorded for the 2 expired rules in their respective owner scopes
        List<KernelRecorder.AuditRecord> audits = audit(ExpireRuleHandler.AUDIT_EXPIRED);
        assertThat(audits).hasSize(2);
        assertThat(audits).extracting(r -> r.subject().id()).containsExactlyInAnyOrder(expiredDue, societyBEnded);
        assertThat(audits)
                .filteredOn(r -> r.subject().id().equals(expiredDue))
                .singleElement()
                .satisfies(r -> assertThat(r.scope().entityId()).isEqualTo(SOCIETY_A));
        assertThat(audits)
                .filteredOn(r -> r.subject().id().equals(societyBEnded))
                .singleElement()
                .satisfies(r -> assertThat(r.scope().entityId()).isEqualTo(SOCIETY_B));

        // Domain events published for the 2 expired rules
        List<RuleExpired> domainEvents = events(RuleExpired.class);
        assertThat(domainEvents).hasSize(2);
        assertThat(domainEvents).extracting(RuleExpired::ruleId).containsExactlyInAnyOrder(expiredDue, societyBEnded);

        // Second run expires nothing
        kernel.reset();
        assertThat(job.expireRules(execution())).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void expireRuleHandlerGuards() {
        UUID activeDue = insertRule(SOCIETY_A, "Active due", "ACTIVE", today.minusDays(5), today.minusDays(1));
        UUID validFuture = insertRule(SOCIETY_A, "Valid future", "ACTIVE", today, today.plusDays(3));
        UUID withdrawn = insertRule(SOCIETY_A, "Withdrawn", "WITHDRAWN", today.minusDays(5), today.minusDays(1));

        // Invalid scope (federation view, not OWN)
        assertThatThrownBy(() -> expire.handle(new ExpireRule(activeDue), fedView()))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("scope.invalid"));

        // Wrong entity scope -> not found
        assertThatThrownBy(() -> expire.handle(new ExpireRule(activeDue), own(SOCIETY_B)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m3.rule.not_found"));

        // Rule not active (withdrawn)
        assertThatThrownBy(() -> expire.handle(new ExpireRule(withdrawn), own(SOCIETY_A)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m3.rule.not_active"));

        // Rule not due (valid_to is in the future)
        assertThatThrownBy(() -> expire.handle(new ExpireRule(validFuture), own(SOCIETY_A)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e -> assertThat(((ProblemException) e).messageId()).isEqualTo("m3.rule.not_due"));

        // Nothing was committed
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private UUID insertRule(UUID owner, String name, String status, LocalDate from, LocalDate to) {
        UUID ruleId = Ids.next();
        superuserJdbc()
                .update(
                        """
                insert into pricing.discount_rule (rule_id, owner_entity_id, name, kind, predicate, benefit, priority, valid_from, valid_to, status)
                values (?, ?, ?, 'TIME_LIMITED_PRICE', ?::jsonb, ?::jsonb, 100, ?, ?, ?)
                """,
                        ruleId,
                        owner,
                        name,
                        "{\"sku_id\":\"" + milk + "\"}",
                        "{\"kind\":\"PERCENT_OFF\",\"value\":\"10\"}",
                        Date.valueOf(from),
                        to == null ? null : Date.valueOf(to),
                        status);
        return ruleId;
    }

    private String statusOf(UUID ruleId) {
        return superuserJdbc()
                .queryForObject("select status from pricing.discount_rule where rule_id = ?", String.class, ruleId);
    }

    private static ScopeContext own(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                Ids.next(),
                Ids.next(),
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    private static ScopeContext fedView() {
        Scope active = new Scope(FEDERATION, null);
        return new ScopeContext(
                Ids.next(),
                Ids.next(),
                FEDERATION,
                List.of(active),
                active,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    private static JobExecution execution() {
        UUID runId = Ids.next();
        return new JobExecution() {
            @Override
            public UUID runId() {
                return runId;
            }

            @Override
            public void itemsProcessed(int count) {}

            @Override
            public Optional<ScopeContext> systemScope() {
                return Optional.empty();
            }
        };
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
}
