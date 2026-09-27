package lk.coopfed.knoweb;

import static org.assertj.core.api.Assertions.assertThat;

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
 */
class OwnPoliciesTestTheClassIntegrationTest extends PostgresIntegrationTest {

    @Test
    void everyOwnPolicyTestsTheScopeClass() {
        List<Map<String, Object>> policies = superuserJdbc()
                .queryForList(
                        """
                        select schemaname, tablename, policyname, coalesce(qual, '') as qual,
                               coalesce(with_check, '') as with_check
                          from pg_policies
                         where policyname like 'own\\_%'
                            or policyname = 'event_outbox_app_insert'
                         order by schemaname, tablename, policyname
                        """);

        assertThat(policies).isNotEmpty();

        List<String> ungated = policies.stream()
                .filter(policy -> {
                    String text = policy.get("qual") + " " + policy.get("with_check");
                    return !text.contains("kernel.scope_class() = 'OWN'::text");
                })
                .map(policy ->
                        policy.get("schemaname") + "." + policy.get("tablename") + "." + policy.get("policyname"))
                .toList();

        assertThat(ungated)
                .as("policies that admit the caller's entity without testing the scope class (CR-17A-3)")
                .isEmpty();
    }

    /**
     * The same rule for every policy that lets the application user write, whatever its name
     * (CR-17A-3, accepted 27 September 2026): FEDERATION_VIEW and EXTERNAL_TIMEBOXED write
     * nothing, so a write policy tests for OWN, directly or through {@code
     * kernel.document_owned()} (which does), or is listed below with the reason it belongs to
     * no tenant. A policy with no {@code TO} clause applies to PUBLIC, the application user
     * included, so it is checked too.
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
                        select schemaname, tablename, policyname, cmd, coalesce(qual, '') as qual,
                               coalesce(with_check, '') as with_check
                          from pg_policies
                         where ('app_rw' = any (roles) or 'public' = any (roles))
                           and permissive = 'PERMISSIVE'
                           and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
                         order by schemaname, tablename, policyname
                        """);

        assertThat(policies).isNotEmpty();

        List<String> withoutTheClass = policies.stream()
                .filter(policy -> {
                    String text = policy.get("qual") + " " + policy.get("with_check");
                    return !text.contains("kernel.scope_class() = 'OWN'::text")
                            && !text.contains("kernel.document_owned(");
                })
                .map(policy ->
                        policy.get("schemaname") + "." + policy.get("tablename") + "." + policy.get("policyname"))
                .toList();

        assertThat(tenantless.keySet())
                .as("every tenant-less exception still names a policy")
                .allSatisfy(pattern -> assertThat(withoutTheClass).anyMatch(name -> name.matches(pattern)));

        List<String> ungated = withoutTheClass.stream()
                .filter(name -> tenantless.keySet().stream().noneMatch(name::matches))
                .toList();

        assertThat(ungated)
                .as("write policies of app_rw that do not test the scope class (CR-17A-3)")
                .isEmpty();
    }
}
