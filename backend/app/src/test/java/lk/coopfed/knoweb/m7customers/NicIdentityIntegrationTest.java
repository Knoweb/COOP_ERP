package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.OTHER;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.officeWithMfa;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.other;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.m7customers.api.AmendAccountLimits;
import lk.coopfed.knoweb.m7customers.api.CustomerNicRecaptured;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.RecaptureNic;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.internal.customer.NicHasher;
import lk.coopfed.knoweb.m7customers.internal.customer.NicNumbers;
import lk.coopfed.knoweb.m7customers.internal.customer.NicRekeyJob;
import lk.coopfed.knoweb.m7customers.query.CustomerQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The NIC after wave 2 (M7CR-01, -02, -03, -15; {@code 2026-10-06-wave2-keyed-hashes.md}): one
 * canonical form, a keyed hash with its key id, a duplicate check that sees every society and
 * names only the caller's own customer, a legacy row re-keyed without its NIC and still found, the
 * NIC asked for when a limit rises above the threshold, and RecaptureNic as the way out. Every
 * NIC and phone number here is plainly made up (19xxxxxxxxxx and 9xxxxxxxxV, 070 000 0xxx).
 */
class NicIdentityIntegrationTest extends PostgresIntegrationTest {

    /** The old form of the card 199000000001: YY 90, DDD 000, SSSC 0001. */
    static final String OLD_FORM = "900000001V";

    static final String NEW_FORM = "199000000001";

    @Autowired
    Handles<RegisterCustomer, UUID> register;

    @Autowired
    Handles<OpenAccount, UUID> open;

    @Autowired
    Handles<AmendAccountLimits, UUID> amend;

    @Autowired
    Handles<RecaptureNic, UUID> recapture;

    @Autowired
    CustomerQueries customers;

    @Autowired
    NicHasher hasher;

    @Autowired
    NicRekeyJob rekeyJob;

    @Autowired
    SystemScope system;

    @Autowired
    JdbcTemplate appJdbc;

    /** A person signed in as a Federation viewer: the job's class, with a user and a real entity. */
    private static ScopeContext federationViewer() {
        UUID federation = UUID.fromString("0190f700-0000-7000-8000-000000000099");
        Scope active = new Scope(federation, null);
        return new ScopeContext(
                CustomersFixture.OFFICE_USER,
                null,
                federation,
                List.of(active),
                active,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                UUID.randomUUID());
    }

    @BeforeEach
    void arrange() {
        CustomersFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void theOldAndTheNewFormOfOneCardAreOnePersonAndTheHashIsKeyed() {
        UUID a = member("Old Card", "0700000901");
        open.handle(new OpenAccount(a, new BigDecimal("1000"), null, null, OLD_FORM), office());
        Map<String, Object> row = nicRow(a);
        assertThat(row.get("nic_hash"))
                .isEqualTo(hasher.keyed(NEW_FORM))
                .isNotEqualTo(NicNumbers.legacySha256(OLD_FORM))
                .isNotEqualTo(NicNumbers.legacySha256(NEW_FORM));
        assertThat(row.get("nic_key_id")).isEqualTo(hasher.keyId());
        assertThat(row.get("nic_last4")).isEqualTo("0001");
        assertThat(customers.card(a, office()).orElseThrow().nicLast4()).isEqualTo("0001");

        // The same card in its new form, for another customer: the same person (M7CR-02).
        UUID b = member("New Card", "0700000902");
        assertThatThrownBy(
                        () -> open.handle(new OpenAccount(b, new BigDecimal("1000"), null, null, NEW_FORM), office()))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.messageId()).isEqualTo("m7.account.nic_held");
                    assertThat(e.parameters()).containsEntry("customerId", a.toString());
                });
        // The card re-typed in the other form for its own holder is the same card: no mismatch.
        amend.handle(
                new AmendAccountLimits(accountOf(a), new BigDecimal("2000"), null, null, "Pays on time", NEW_FORM),
                officeWithMfa());
        assertThat(nicRow(a).get("nic_hash")).isEqualTo(hasher.keyed(NEW_FORM));
    }

    @Test
    void aLegacyRowIsReKeyedWithoutItsNicAndIsStillFoundThenRecaptureCleansIt() {
        UUID c = member("Legacy Row", "0700000903");
        // What a row looked like before wave 2: the plain SHA-256 of the form as typed (the old
        // form here), the last four as typed, no key id.
        String legacy = NicNumbers.legacySha256(OLD_FORM);
        superuserJdbc()
                .update(
                        "update customers.customer set nic_hash = ?, nic_last4 = ?, nic_key_id = null where customer_id = ?",
                        legacy,
                        "001V",
                        c);
        UUID d = member("Same Person Again", "0700000904");

        // Before the job: the legacy candidate set finds the row.
        assertThatThrownBy(
                        () -> open.handle(new OpenAccount(d, new BigDecimal("1000"), null, null, NEW_FORM), office()))
                .hasMessageContaining("m7.account.nic_held");

        // A person's Federation viewer session is the same class as the job's, but it is not the
        // job: it gets no legacy hash and re-keys nothing (M7M8M9-04, V0005), here through the
        // job's own page and directly through rekey_nic.
        ScopeContext viewer = federationViewer();
        assertThat(rekeyJob.rekeyPage(viewer)).isZero();
        assertThat(system.inScope(
                        viewer,
                        () -> appJdbc.queryForObject(
                                "select customers.rekey_nic(?, ?, ?, ?)",
                                Boolean.class,
                                c,
                                legacy,
                                hasher.rekey(legacy),
                                hasher.keyId())))
                .isFalse();
        assertThat(nicRow(c).get("nic_hash")).isEqualTo(legacy);
        assertThat(nicRow(c).get("nic_key_id")).isNull();

        // The job: HMAC(pepper, stored_sha256), the pepper never in SQL, the NIC never known.
        assertThat(rekeyJob.rekeyPage(SystemScope.federationView())).isEqualTo(1);
        Map<String, Object> row = nicRow(c);
        assertThat(row.get("nic_hash")).isEqualTo(hasher.rekey(legacy));
        assertThat(row.get("nic_key_id")).isEqualTo(hasher.keyId());
        assertThat(rekeyJob.rekeyPage(SystemScope.federationView()))
                .as("nothing left")
                .isZero();

        // After the job: the keyed candidate set finds the row, whichever form is typed.
        assertThatThrownBy(
                        () -> open.handle(new OpenAccount(d, new BigDecimal("1000"), null, null, NEW_FORM), office()))
                .hasMessageContaining("m7.account.nic_held");
        assertThatThrownBy(() ->
                        open.handle(new OpenAccount(d, new BigDecimal("1000"), null, null, "900000001X"), office()))
                .hasMessageContaining("m7.account.nic_held");
        // And the holder's own account opens: the re-keyed old form is the same card.
        open.handle(new OpenAccount(c, new BigDecimal("1000"), null, null, NEW_FORM), office());
        // The capture cleaned the row to the keyed canonical form.
        assertThat(nicRow(c).get("nic_hash")).isEqualTo(hasher.keyed(NEW_FORM));
        assertThat(nicRow(c).get("nic_last4")).isEqualTo("0001");

        // A row under a key since retired: the officer's way out is RecaptureNic.
        superuserJdbc()
                .update("update customers.customer set nic_key_id = 'retired0retired0' where customer_id = ?", c);
        assertThatThrownBy(() -> amend.handle(
                        new AmendAccountLimits(accountOf(c), new BigDecimal("2000"), null, null, "Pays", NEW_FORM),
                        officeWithMfa()))
                .hasMessageContaining("m7.account.nic_mismatch");
        kernel.reset();
        assertThatThrownBy(() -> recapture.handle(new RecaptureNic(c, NEW_FORM, "Card presented"), office()))
                .hasMessageContaining("mfa.required");
        assertThatThrownBy(() ->
                        recapture.handle(new RecaptureNic(c, NEW_FORM, "card seen, call 0771234567"), officeWithMfa()))
                .hasMessageContaining("m7.field.personal_data");
        assertThat(kernel.committedAudit()).isEmpty();
        recapture.handle(new RecaptureNic(c, NEW_FORM, "Card presented at the counter"), officeWithMfa());
        assertThat(nicRow(c).get("nic_key_id")).isEqualTo(hasher.keyId());
        assertThat(nicRow(c).get("nic_hash")).isEqualTo(hasher.keyed(NEW_FORM));
        AuditRecord audit = single("NIC_RECAPTURED");
        assertThat(audit.after()).isEqualTo(Map.of("nicCaptured", true));
        assertThat(audit.reason()).isEqualTo("Card presented at the counter");
        assertThat(String.valueOf(audit.after()) + audit.reason()).doesNotContain("0001", "199000000001", "900000001");
        assertThat(kernel.committedEvents()).containsExactly(new CustomerNicRecaptured(c, SOCIETY));
    }

    @Test
    void aNicHeldAtAnotherSocietyIsRefusedNamingNobody() {
        UUID elsewhere = register.handle(command("Member Elsewhere", "0700000905"), other());
        open.handle(new OpenAccount(elsewhere, new BigDecimal("1000"), null, null, NEW_FORM), other());
        UUID here = member("Member Here", "0700000906");
        kernel.reset();

        assertThatThrownBy(() ->
                        open.handle(new OpenAccount(here, new BigDecimal("1000"), null, null, OLD_FORM), office()))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.messageId()).isEqualTo("m7.account.nic_held_elsewhere");
                    assertThat(e.parameters()).isEmpty();
                    assertThat(e.getMessage()).doesNotContain(elsewhere.toString(), OTHER.toString());
                });
        assertThat(kernel.committedAudit()).isEmpty();
        // The function itself answers nothing about the holder to the asking society.
        List<Map<String, Object>> answer = superuserJdbc()
                .queryForList("select * from customers.nic_holders(cast(? as char(64)[]))", (Object)
                        new String[] {hasher.keyed(NEW_FORM)});
        // As the superuser (no scope class): nothing at all.
        assertThat(answer).isEmpty();
    }

    @Test
    void raisingTheLimitAboveTheThresholdAsksForTheNicOnce() {
        UUID c = member("No Nic Yet", "0700000907");
        UUID accountId = open.handle(new OpenAccount(c, BigDecimal.ZERO, null, null, null), office());
        assertThat(nicRow(c).get("nic_hash")).isNull();
        kernel.reset();

        assertThatThrownBy(() -> amend.handle(
                        new AmendAccountLimits(accountId, new BigDecimal("50000.00"), null, null, "Raise"),
                        officeWithMfa()))
                .hasMessageContaining("m7.account.nic_required");
        // The cap alone never asks.
        amend.handle(new AmendAccountLimits(accountId, null, null, new BigDecimal("2000"), "Cap"), office());
        assertThat(nicRow(c).get("nic_hash")).isNull();
        kernel.reset();

        amend.handle(
                new AmendAccountLimits(accountId, new BigDecimal("50000.00"), null, null, "Raise", NEW_FORM),
                officeWithMfa());
        assertThat(nicRow(c).get("nic_hash")).isEqualTo(hasher.keyed(NEW_FORM));
        AuditRecord audit = single("ACCOUNT_LIMIT_AMENDED");
        assertThat(String.valueOf(audit.after())).contains("nicCaptured=true").doesNotContain("199000000001");
        // Recorded now: a later rise asks nothing; a different card is a mismatch.
        amend.handle(
                new AmendAccountLimits(accountId, new BigDecimal("60000.00"), null, null, "Again"), officeWithMfa());
        assertThatThrownBy(() -> amend.handle(
                        new AmendAccountLimits(
                                accountId, new BigDecimal("70000.00"), null, null, "Other", "199000000002"),
                        officeWithMfa()))
                .hasMessageContaining("m7.account.nic_mismatch");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private UUID member(String name, String phone) {
        return register.handle(command(name, phone), office());
    }

    private static RegisterCustomer command(String name, String phone) {
        return new RegisterCustomer(
                name, null, null, "si", phone, List.of("CREDIT_ACCOUNT"), "PAPER", Map.of(), List.of(), false);
    }

    private Map<String, Object> nicRow(UUID customerId) {
        return superuserJdbc()
                .queryForMap(
                        "select nic_hash, nic_last4, nic_key_id from customers.customer where customer_id = ?",
                        customerId);
    }

    private UUID accountOf(UUID customerId) {
        return superuserJdbc()
                .queryForObject(
                        "select account_id from customers.customer_account where customer_id = ?",
                        UUID.class,
                        customerId);
    }

    private AuditRecord single(String type) {
        List<AuditRecord> found = kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(type))
                .toList();
        assertThat(found).as(type).hasSize(1);
        return found.get(0);
    }
}
