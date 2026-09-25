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
}
