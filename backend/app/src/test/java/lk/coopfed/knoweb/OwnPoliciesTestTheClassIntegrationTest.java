package lk.coopfed.knoweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * CR-17A-3, kept for good: every policy that admits the caller's own entity, in every schema,
 * tests the scope class as well as the entity. A FEDERATION_VIEW or EXTERNAL_TIMEBOXED
 * caller has a scope entity too, and doc 18 section 3.7 makes those classes read-only; a
 * policy that trusts the entity alone lets them read and write through it. The template is
 * db/migration/RLS_POLICY_TEMPLATE.md; RlsMatrixIntegrationTest proves the template, this
 * test proves every table follows it, partitions included.
 *
 * <p>Each clause is checked on its own (wave 2, RLS-17 b and c): a USING and a WITH CHECK are
 * separate gates, and a class test in one says nothing about the other. The class test must be
 * a top-level conjunct of the clause, so {@code (class = 'OWN' AND x) OR true} does not pass
 * for having the words in it.
 */
class OwnPoliciesTestTheClassIntegrationTest extends PostgresIntegrationTest {

    static final String CLASS_TEST = "kernel.scope_class() = 'OWN'::text";
    static final String DOCUMENT_OWNED = "kernel.document_owned(";

    /**
     * own_* clauses with a branch that admits rows without a class test, by design, and why
     * (schema.table.policy clause, as a pattern: partitions carry their parent's policies).
     */
    static final Map<String, String> OWN_READS_OF_NOBODYS_ROWS = Map.of(
            "kernel\\.config_value\\.own_read USING",
            "the federation-wide values (scope_entity_id NULL) are nobody's: every caller resolves its"
                    + " configuration from them (kernel V0052)",
            "security\\.role_permission\\.own_read USING",
            "follows the parent role inside EXISTS, whose two branches each test the class (own role, or a"
                    + " Federation template for every class but NONE, m1security V0001); a text check does not"
                    + " see into the subquery");

    @Test
    void everyOwnPolicyTestsTheScopeClass() {
        List<Map<String, Object>> policies = superuserJdbc()
                .queryForList(
                        """
                        select schemaname, tablename, policyname, cmd, qual, with_check
                          from pg_policies
                         where policyname like 'own\\_%'
                            or policyname = 'event_outbox_app_insert'
                         order by schemaname, tablename, policyname
                        """);

        assertThat(policies).isNotEmpty();

        List<String> ungated = clausesWithoutTheClass(policies);
        assertThat(OWN_READS_OF_NOBODYS_ROWS.keySet())
                .as("every own_* departure still names a clause without the class test")
                .allSatisfy(pattern -> assertThat(ungated).anyMatch(clause -> clause.matches(pattern)));
        assertThat(ungated.stream()
                        .filter(clause ->
                                OWN_READS_OF_NOBODYS_ROWS.keySet().stream().noneMatch(clause::matches))
                        .toList())
                .as("policies that admit the caller's entity without testing the scope class in each clause"
                        + " (CR-17A-3)")
                .isEmpty();
    }

    /**
     * A shop writes only at its own location (PLAN_TO_M2 6.12, decided 27 September 2026): on a
     * table with a {@code location_id} column, every policy that lets the application user
     * insert or update carries the location line in its WITH CHECK, and every policy that lets it
     * update or delete carries it in its USING too (wave 2, RLS-17 c: an UPDATE whose WITH CHECK
     * keeps the row at the shop but whose USING reaches a sibling shop's rows still edits them),
     * partitions included. The RLS matrix proves the behaviour on each parent table; this proves
     * the text on every one.
     */
    @Test
    void everyWritePolicyOnATableWithALocationKeepsAShopAtItsLocation() {
        List<String> withoutTheLine = superuserJdbc()
                .queryForList(
                        """
                        select p.schemaname || '.' || p.tablename || '.' || p.policyname || ' ' || clause.name
                          from pg_policies p
                          join pg_namespace n on n.nspname = p.schemaname
                          join pg_class c on c.relnamespace = n.oid and c.relname = p.tablename
                          cross join lateral (values
                                ('WITH CHECK', p.cmd in ('INSERT', 'UPDATE', 'ALL'), p.with_check),
                                ('USING', p.cmd in ('UPDATE', 'DELETE', 'ALL'), p.qual)) clause (name, applies, text)
                         where ('app_rw' = any (p.roles) or 'public' = any (p.roles))
                           and clause.applies
                           and exists (select 1 from pg_attribute a
                                        where a.attrelid = c.oid and a.attname = 'location_id'
                                          and not a.attisdropped)
                           and position('kernel.scope_location()' in coalesce(clause.text, '')) = 0
                         order by 1
                        """,
                        String.class);

        assertThat(withoutTheLine)
                .as("write policies on a table with a location whose USING or WITH CHECK lets a shop write elsewhere")
                .isEmpty();
    }

    /**
     * The same rule for every policy that lets the application user write, whatever its name
     * (CR-17A-3, accepted 27 September 2026): FEDERATION_VIEW and EXTERNAL_TIMEBOXED write
     * nothing, so each clause of a write policy tests for OWN, directly or through {@code
     * kernel.document_owned()} (which does), or the policy is listed below with the reason it
     * belongs to no tenant. A policy with no {@code TO} clause applies to PUBLIC, the
     * application user included, so it is checked too.
     */
    @Test
    void everyWritePolicyOfTheApplicationUserTestsTheScopeClass() {
        // schema.table.policy, as a pattern (partitions carry their parent's policies), and why.
        Map<String, String> tenantless = Map.of(
                "kernel\\.(scheduled_job|job_run|shedlock)\\.platform",
                "the platform's job register, run log and lock (kernel V0051): no tenant",
                "kernel\\.idempotency_key\\w*\\.idempotency_key_(insert|update)",
                "a caller's own keys, by app.user_id whatever its scope (kernel V0010)");

        List<Map<String, Object>> policies = superuserJdbc()
                .queryForList(
                        """
                        select schemaname, tablename, policyname, qual, with_check
                          from pg_policies
                         where ('app_rw' = any (roles) or 'public' = any (roles))
                           and permissive = 'PERMISSIVE'
                           and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
                         order by schemaname, tablename, policyname
                        """);

        assertThat(policies).isNotEmpty();

        List<String> withoutTheClass = clausesWithoutTheClass(policies);

        assertThat(tenantless.keySet())
                .as("every tenant-less exception still names a policy")
                .allSatisfy(pattern -> assertThat(withoutTheClass)
                        .anyMatch(name -> policyOf(name).matches(pattern)));

        List<String> ungated = withoutTheClass.stream()
                .filter(name -> tenantless.keySet().stream().noneMatch(policyOf(name)::matches))
                .toList();

        assertThat(ungated)
                .as("write policies of app_rw with a clause that does not test the scope class (CR-17A-3)")
                .isEmpty();
    }

    /** Proof that the clause check bites where the old whole-text check did not. */
    @Test
    void theClassTestCountsOnlyAsATopLevelConjunctOfEachClause() {
        assertThat(testsTheClass(
                        "((kernel.scope_class() = 'OWN'::text) AND (owner_entity_id = kernel.scope_entity()))"))
                .isTrue();
        assertThat(testsTheClass(
                        "(kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id))"))
                .isTrue();
        assertThat(testsTheClass("kernel.document_owned(receipt_document_id)")).isTrue();
        // The words are there, but OR true admits everyone.
        assertThat(
                        testsTheClass(
                                "(((kernel.scope_class() = 'OWN'::text) AND (owner_entity_id = kernel.scope_entity())) OR true)"))
                .isFalse();
        assertThat(testsTheClass("((owner_entity_id = kernel.scope_entity()) OR (kernel.scope_class() = 'OWN'::text))"))
                .isFalse();
        assertThat(testsTheClass("(owner_entity_id = kernel.scope_entity())")).isFalse();
        // A read with a branch per class passes when every branch tests one, and not otherwise.
        assertThat(readTestsTheClass(
                        "(((kernel.scope_class() = 'OWN'::text) AND (owner_entity_id = kernel.scope_entity()))"
                                + " OR ((owner_entity_id IS NULL) AND (kernel.scope_class() <> 'NONE'::text)))"))
                .isTrue();
        assertThat(readTestsTheClass("((scope_entity_id IS NULL) OR ((kernel.scope_class() = 'OWN'::text)"
                        + " AND (scope_entity_id = kernel.scope_entity())))"))
                .isFalse();
        // A class test in USING does not cover a WITH CHECK that has none.
        assertThat(clausesWithoutTheClass(List.of(Map.of(
                        "schemaname", "zz",
                        "tablename", "t",
                        "policyname", "own_update",
                        "qual", "((kernel.scope_class() = 'OWN'::text) AND (owner_entity_id = kernel.scope_entity()))",
                        "with_check", "(owner_entity_id = kernel.scope_entity())"))))
                .containsExactly("zz.t.own_update WITH CHECK");
    }

    /** schema.table.policy and the clause ("USING" or "WITH CHECK") of every clause without the test. */
    static List<String> clausesWithoutTheClass(List<Map<String, Object>> policies) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> policy : policies) {
            String name = policy.get("schemaname") + "." + policy.get("tablename") + "." + policy.get("policyname");
            Object qual = policy.get("qual");
            Object withCheck = policy.get("with_check");
            boolean read = "SELECT".equals(policy.get("cmd"));
            if (qual != null && !(read ? readTestsTheClass(qual.toString()) : testsTheClass(qual.toString()))) {
                found.add(name + " USING");
            }
            if (withCheck != null && !testsTheClass(withCheck.toString())) {
                found.add(name + " WITH CHECK");
            }
        }
        return found;
    }

    private static String policyOf(String clause) {
        return clause.substring(0, clause.lastIndexOf(clause.endsWith(" WITH CHECK") ? " WITH CHECK" : " USING"));
    }

    /**
     * Whether the expression, as pg_get_expr prints it, gates on the OWN class: a conjunction one
     * of whose terms is the OWN class test or {@code kernel.document_owned(...)}, or a disjunction
     * every branch of which does (a write admitted by any branch must be OWN's).
     */
    static boolean testsTheClass(String expression) {
        return gates(expression, term -> term.equals(CLASS_TEST) || term.startsWith(DOCUMENT_OWNED));
    }

    /**
     * The same for a read of the caller's own rows: every branch tests the class, whichever
     * comparison (an own_read may admit shared rows to every class but NONE in a branch of its
     * own, as security.role does the Federation's role templates).
     */
    static boolean readTestsTheClass(String expression) {
        return gates(expression, term -> term.startsWith("kernel.scope_class() ") || term.startsWith(DOCUMENT_OWNED));
    }

    private static boolean gates(String expression, java.util.function.Predicate<String> classTest) {
        List<String> branches = split(expression, " OR ");
        if (branches.size() > 1) {
            return branches.stream().allMatch(branch -> gates(branch, classTest));
        }
        return split(expression, " AND ").stream()
                .anyMatch(term -> classTest.test(term) || (split(term, " OR ").size() > 1 && gates(term, classTest)));
    }

    /** The top-level terms of an expression joined by the operator, each without its enclosing parentheses. */
    static List<String> split(String expression, String operator) {
        String text = unwrap(expression.trim());
        List<String> terms = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
            } else if (!quoted && ch == '(') {
                depth++;
            } else if (!quoted && ch == ')') {
                depth--;
            } else if (!quoted && depth == 0 && text.startsWith(operator, i)) {
                terms.add(unwrap(text.substring(start, i).trim()));
                start = i + operator.length();
            }
        }
        terms.add(unwrap(text.substring(start).trim()));
        return terms;
    }

    /** Removes parentheses that enclose the whole expression, as many pairs as there are. */
    private static String unwrap(String text) {
        String current = text;
        while (current.startsWith("(") && closes(current) == current.length() - 1) {
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    /** The index of the parenthesis that closes the one at index 0. */
    private static int closes(String text) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
            } else if (!quoted && ch == '(') {
                depth++;
            } else if (!quoted && ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
