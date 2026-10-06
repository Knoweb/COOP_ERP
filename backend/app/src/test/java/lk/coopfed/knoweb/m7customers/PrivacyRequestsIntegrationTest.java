package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.officeWithMfa;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.officer;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.other;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m7customers.api.AccessExportDownloaded;
import lk.coopfed.knoweb.m7customers.api.ChangeAccountStatus;
import lk.coopfed.knoweb.m7customers.api.CustomerAnonymised;
import lk.coopfed.knoweb.m7customers.api.DataSubjectRequestFulfilled;
import lk.coopfed.knoweb.m7customers.api.DataSubjectRequestReceived;
import lk.coopfed.knoweb.m7customers.api.DataSubjectRequestRefused;
import lk.coopfed.knoweb.m7customers.api.DownloadAccessExport;
import lk.coopfed.knoweb.m7customers.api.FulfilDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.api.PostAccountTender;
import lk.coopfed.knoweb.m7customers.api.RecordCustomerPayment;
import lk.coopfed.knoweb.m7customers.api.RecordDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.RefuseDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.RegisterCustomer;
import lk.coopfed.knoweb.m7customers.internal.privacy.PrivacyExporter;
import lk.coopfed.knoweb.m7customers.query.AccountQueries;
import lk.coopfed.knoweb.m7customers.query.CustomerCard;
import lk.coopfed.knoweb.m7customers.query.CustomerQueries;
import lk.coopfed.knoweb.m7customers.query.PrivacyQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder.AuditRecord;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The data-subject requests (27A section 6; doc 27 flow 6.7): received by the society that
 * registered the customer, answered only by its responsible officer; the access export holds every
 * row about the customer (not the NIC's hash) and is handed over by an audited command; an erasure
 * waits while an account is open, has a balance or was closed within the offline window, then the
 * Anonymiser leaves no identity and no free text about the person, and the ledger exactly as it was
 * (the balance still the sum of the postings); a refusal records its legal ground. Nothing about
 * the person reaches an event or an audit record. Every number here is plainly made up.
 */
class PrivacyRequestsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    Handles<RegisterCustomer, UUID> register;

    @Autowired
    Handles<OpenAccount, UUID> open;

    @Autowired
    Handles<PostAccountTender, UUID> tender;

    @Autowired
    Handles<RecordCustomerPayment, UUID> pay;

    @Autowired
    Handles<RecordDataSubjectRequest, UUID> record;

    @Autowired
    Handles<FulfilDataSubjectRequest, UUID> fulfil;

    @Autowired
    Handles<RefuseDataSubjectRequest, UUID> refuse;

    @Autowired
    Handles<DownloadAccessExport, Map<String, Object>> download;

    @Autowired
    Handles<ChangeAccountStatus, UUID> changeStatus;

    @Autowired
    PrivacyExporter exporter;

    @Autowired
    CustomerQueries customers;

    @Autowired
    AccountQueries accounts;

    @Autowired
    PrivacyQueries privacy;

    @BeforeEach
    void arrange() {
        CustomersFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void onlyTheRegisteringSocietyRecordsAndOnlyItsResponsibleOfficerAnswers() {
        UUID customerId = member("Kamala Dissanayake", "0700000501");
        kernel.reset();

        assertThatThrownBy(() -> record.handle(new RecordDataSubjectRequest(customerId, "FORGET", null), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.kind_invalid"));
        assertThatThrownBy(() -> record.handle(
                        new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ACCESS, null), other()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.customer.not_found"));

        UUID requestId = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ACCESS, "Asked at the shop"),
                office());
        assertThat(single("DSAR_RECEIVED").subject().id()).isEqualTo(requestId);
        assertThat(kernel.committedEvents())
                .containsExactly(new DataSubjectRequestReceived(requestId, customerId, SOCIETY, "ACCESS"));
        assertThatThrownBy(() -> record.handle(
                        new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ACCESS, null), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.request_open"));
        kernel.reset();

        // No officer appointed yet; then the officer is someone else than the clerk.
        assertThatThrownBy(() -> fulfil.handle(new FulfilDataSubjectRequest(requestId, null), officeWithMfa()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.no_officer"));
        CustomersFixture.appointOfficer(superuserJdbc());
        assertThatThrownBy(() -> fulfil.handle(new FulfilDataSubjectRequest(requestId, null), officeWithMfa()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.officer_only"));
        assertThat(kernel.committedAudit()).isEmpty();

        fulfil.handle(new FulfilDataSubjectRequest(requestId, "Handed over on paper"), officer());
        PrivacyQueries.RequestView fulfilled =
                privacy.request(requestId, office()).orElseThrow();
        assertThat(fulfilled.status()).isEqualTo("FULFILLED");
        assertThat(fulfilled.exportSha256()).hasSize(64);
        assertThat(single("DSAR_FULFILLED").after().toString()).contains(fulfilled.exportSha256());
        assertThat(kernel.committedEvents())
                .containsExactly(new DataSubjectRequestFulfilled(requestId, customerId, SOCIETY, "ACCESS"));
        assertThatThrownBy(() -> fulfil.handle(new FulfilDataSubjectRequest(requestId, null), officer()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.not_received"));
    }

    @Test
    void theAccessExportHoldsEveryRowAboutTheCustomer() {
        CustomersFixture.appointOfficer(superuserJdbc());
        UUID customerId = member("Nimal Rathnayake", "0700000502");
        UUID accountId =
                open.handle(new OpenAccount(customerId, new BigDecimal("5000"), null, null, "190000000502"), office());
        charge(accountId, "700.00");
        pay.handle(payment(accountId, "200.00"), office());
        UUID requestId = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ACCESS, null), office());
        fulfil.handle(new FulfilDataSubjectRequest(requestId, null), officer());
        String atFulfilment = privacy.request(requestId, office()).orElseThrow().exportSha256();

        // The hand-over is a command of the responsible officer (wave 2, M7CR-11), audited.
        assertThatThrownBy(() -> download.handle(new DownloadAccessExport(requestId), officeWithMfa()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.officer_only"));
        kernel.reset();
        Map<String, Object> export = download.handle(new DownloadAccessExport(requestId), officer());
        assertThat(export)
                .containsOnlyKeys(
                        "customer", "phones", "consents", "tags", "accounts", "postings", "allocations", "payments");
        String text = export.toString();
        assertThat(text).contains("Nimal Rathnayake", "+94700000502", "0502", "CREDIT_ACCOUNT", "700.00", "-200.00");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) export.get("customer");
        // Every column of the customer's row, as the table has them, but the NIC's hash and key id:
        // a derived secret of the system, not the customer's data (M7CR-11, RLS-01).
        List<String> columns = superuserJdbc()
                .queryForList(
                        """
                        select column_name from information_schema.columns
                         where table_schema = 'customers' and table_name = 'customer'
                           and column_name not in ('nic_hash', 'nic_key_id')
                        """,
                        String.class);
        assertThat(rows.get(0).keySet()).containsExactlyInAnyOrderElementsOf(columns);
        String nicHash = superuserJdbc()
                .queryForObject(
                        "select nic_hash from customers.customer where customer_id = ?", String.class, customerId);
        assertThat(new String(exporter.bytes(export), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(nicHash, "nic_hash", "190000000502");
        AuditRecord downloaded = single("DSAR_EXPORT_DOWNLOADED");
        assertThat(downloaded.subject().id()).isEqualTo(requestId);
        assertThat(downloaded.after()).isEqualTo(Map.of("customerId", customerId, "matchesFulfilment", true));
        assertThat(exporter.sha256(export)).isEqualTo(atFulfilment);
        assertThat(kernel.committedEvents())
                .containsExactly(new AccessExportDownloaded(requestId, customerId, SOCIETY));
        // Nothing of the person in the audit or the events.
        for (AuditRecord audit : kernel.committedAudit()) {
            assertThat(String.valueOf(audit.after())).doesNotContain("700000502", "Nimal", "0502");
        }
        for (DomainEvent event : kernel.committedEvents()) {
            assertThat(String.valueOf(event)).doesNotContain("700000502", "Nimal");
        }
        // Not an ACCESS request, or not fulfilled: no export.
        UUID erasure = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ERASURE, null), office());
        assertThatThrownBy(() -> download.handle(new DownloadAccessExport(erasure), officer()))
                .hasMessageContaining("m7.privacy.not_access");
        UUID access = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ACCESS, null), office());
        assertThatThrownBy(() -> download.handle(new DownloadAccessExport(access), officer()))
                .hasMessageContaining("m7.privacy.not_fulfilled");
    }

    /** Wave 2, M7CR-10: what an officer types stays on the record, so no phone number or NIC goes in. */
    @Test
    void officerFreeTextWithAPhoneNumberOrANicIsRefused() {
        CustomersFixture.appointOfficer(superuserJdbc());
        UUID customerId = member("Chandra Perera", "0700000505");
        kernel.reset();
        assertThatThrownBy(() -> record.handle(
                        new RecordDataSubjectRequest(
                                customerId, RecordDataSubjectRequest.ACCESS, "Asked by phone, call 0771234567"),
                        office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.field.personal_data"));
        UUID requestId = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.CORRECTION, "Name misspelt"),
                office());
        assertThatThrownBy(() -> fulfil.handle(
                        new FulfilDataSubjectRequest(requestId, "Card 199000000505 corrected"), officer()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.field.personal_data"));
        assertThat(kernel.committedAudit()).extracting(AuditRecord::eventType).containsExactly("DSAR_RECEIVED");
    }

    @Test
    void erasureWaitsForAZeroBalanceThenAnonymisesAndKeepsTheLedger() {
        CustomersFixture.appointOfficer(superuserJdbc());
        UUID customerId = member("Upali Wickramasinghe", "0700000503");
        UUID accountId =
                open.handle(new OpenAccount(customerId, new BigDecimal("5000"), null, null, "190000000503"), office());
        charge(accountId, "900.00");
        UUID requestId = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ERASURE, null), office());
        kernel.reset();

        assertThatThrownBy(() -> fulfil.handle(new FulfilDataSubjectRequest(requestId, null), officer()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.open_balance"));
        assertThat(kernel.committedAudit()).isEmpty();

        pay.handle(payment(accountId, "900.00"), office());
        // Settled, but OPEN: every account must be CLOSED (wave 2, M7CR-09; CR-27A-1 item 3).
        assertThatThrownBy(() -> fulfil.handle(new FulfilDataSubjectRequest(requestId, null), officer()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.account_open"));
        changeStatus.handle(new ChangeAccountStatus(accountId, ChangeAccountStatus.CLOSE, "Left the area"), office());
        // Closed just now: the shops' offline sales may still arrive (customers.erasure_wait_days).
        assertThatThrownBy(() -> fulfil.handle(new FulfilDataSubjectRequest(requestId, null), officer()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.privacy.recently_closed"));
        // The clock moves: the close was ten days ago.
        superuserJdbc()
                .update(
                        "update customers.account_history set changed_at = changed_at - interval '10 days' where account_id = ?",
                        accountId);
        List<Map<String, Object>> ledgerBefore = ledger(accountId);
        kernel.reset();

        fulfil.handle(new FulfilDataSubjectRequest(requestId, "the officer's note is ignored"), officer());

        CustomerCard card = customers.card(customerId, office()).orElseThrow();
        assertThat(card.displayName()).isEqualTo("Customer");
        assertThat(card.displayNameSi()).isNull();
        assertThat(card.nicLast4()).isNull();
        assertThat(card.status()).isEqualTo("ANONYMISED");
        assertThat(card.attributes()).isEmpty();
        assertThat(card.tags()).isEmpty();
        assertThat(card.phone()).isNull();
        assertThat(card.consents()).allSatisfy(c -> assertThat(c.withdrawnAt()).isNotNull());
        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select nic_hash, nic_key_id, nic_last4, attributes::text as attributes from customers.customer where customer_id = ?",
                        customerId);
        assertThat(row.get("nic_hash")).isNull();
        assertThat(row.get("nic_key_id")).isNull();
        assertThat(row.get("attributes")).isEqualTo("{}");
        // The module's own free text about the person went with the identity (M7CR-10).
        PrivacyQueries.RequestView fulfilled =
                privacy.request(requestId, office()).orElseThrow();
        assertThat(fulfilled.outcome()).isEqualTo("ANONYMISED");
        assertThat(fulfilled.notes()).isNull();
        // The privacy scan: no phone number, NIC or the fixture's name across the module's text columns.
        for (String column : List.of(
                "notes from customers.data_subject_request",
                "outcome from customers.data_subject_request",
                "reason from customers.account_history",
                "reason from customers.account_adjustment",
                "reference from customers.doc_customer_payment",
                "phone from customers.customer_phone",
                "display_name from customers.customer")) {
            for (String text : superuserJdbc().queryForList("select " + column, String.class)) {
                if (text != null) {
                    assertThat(text).as(column).doesNotContain("Upali", "Wickramasinghe", "700000503", "190000000503");
                }
            }
        }
        assertThat(superuserJdbc()
                        .queryForList(
                                "select phone from customers.customer_phone where customer_id = ?",
                                String.class,
                                customerId))
                .containsOnly("ERASED");
        // The ledger is untouched and the balance is still the sum of the postings.
        assertThat(ledger(accountId)).isEqualTo(ledgerBefore);
        assertThat(accounts.account(accountId, office()).orElseThrow().balance())
                .isEqualByComparingTo(superuserJdbc()
                        .queryForObject(
                                "select coalesce(sum(amount), 0) from customers.account_posting where account_id = ?",
                                BigDecimal.class,
                                accountId));

        AuditRecord anonymised = single("CUSTOMER_ANONYMISED");
        assertThat(anonymised.after()).isEqualTo(Map.of("requestId", requestId));
        assertThat(kernel.committedEvents())
                .containsExactly(
                        new CustomerAnonymised(customerId, SOCIETY, requestId),
                        new DataSubjectRequestFulfilled(requestId, customerId, SOCIETY, "ERASURE"));
        // The number is free again, with no confirmation to ask for.
        assertThat(member("Someone Else", "0700000503")).isNotEqualTo(customerId);
        assertThatThrownBy(() -> record.handle(
                        new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ACCESS, null), office()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.customer.anonymised"));
    }

    @Test
    void aRefusalRecordsItsLegalGroundAndACorrectionWhatWasDone() {
        CustomersFixture.appointOfficer(superuserJdbc());
        UUID customerId = member("Sarath Kumara", "0700000504");
        UUID erasure = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.ERASURE, null), office());
        UUID correction = record.handle(
                new RecordDataSubjectRequest(customerId, RecordDataSubjectRequest.CORRECTION, "Name misspelt"),
                office());
        kernel.reset();

        assertThatThrownBy(() -> refuse.handle(new RefuseDataSubjectRequest(erasure, " "), officer()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.field.required"));
        refuse.handle(new RefuseDataSubjectRequest(erasure, "Accounting records kept seven years (J-06)"), officer());
        assertThat(single("DSAR_REFUSED").reason()).isEqualTo("Accounting records kept seven years (J-06)");
        assertThat(kernel.committedEvents())
                .containsExactly(new DataSubjectRequestRefused(erasure, customerId, SOCIETY, "ERASURE"));

        assertThatThrownBy(() -> fulfil.handle(new FulfilDataSubjectRequest(correction, null), officer()))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m7.field.required"));
        fulfil.handle(new FulfilDataSubjectRequest(correction, "Name corrected on the card"), officer());
        assertThat(privacy.requests(null, office()))
                .extracting(PrivacyQueries.RequestView::status)
                .containsExactlyInAnyOrder("REFUSED", "FULFILLED");
        assertThat(customers.card(customerId, office()).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(privacy.requests(null, other())).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private UUID member(String name, String phone) {
        return register.handle(
                new RegisterCustomer(
                        name,
                        "සිංහල නම",
                        null,
                        "si",
                        phone,
                        List.of("CREDIT_ACCOUNT", "STATEMENTS_NOTIFICATIONS"),
                        "PAPER",
                        Map.of("village", "Hettipola"),
                        List.of("regular"),
                        false),
                office());
    }

    private void charge(UUID accountId, String amount) {
        tender.handle(
                new PostAccountTender(
                        PostAccountTender.CHARGE,
                        accountId,
                        new BigDecimal(amount),
                        UUID.randomUUID(),
                        "RCT-P1",
                        1,
                        SHOP,
                        LocalDate.of(2026, 9, 2),
                        null,
                        false),
                till());
    }

    private static RecordCustomerPayment payment(UUID accountId, String amount) {
        return new RecordCustomerPayment(
                accountId, "CASH", new BigDecimal(amount), null, RecordCustomerPayment.OLDEST_FIRST, List.of());
    }

    private List<Map<String, Object>> ledger(UUID accountId) {
        return superuserJdbc()
                .queryForList(
                        """
                        select p.posting_id, p.kind, p.amount, p.document_id,
                               (select count(*) from customers.allocation a where a.payment_posting_id = p.posting_id) as allocations,
                               (select count(*) from customers.doc_customer_payment d where d.account_id = p.account_id) as payments
                          from customers.account_posting p where p.account_id = ? order by p.posting_id
                        """,
                        accountId);
    }

    private AuditRecord single(String type) {
        List<AuditRecord> found = kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(type))
                .toList();
        assertThat(found).as(type).hasSize(1);
        return found.get(0);
    }
}
