# M7 Customers & Payment Recording (back office)

The living guide of the module (AGENTS.md). The design is doc 27; what to build is 27A. This
part is the society office's: the member register, the credit account (the khata) with its
limits and states, the ledger the till's account tenders and repayments feed, repayments at the
office and their reversal, adjustments, the data-subject requests, and the customer rows of the
till's snapshot. The till's own screens (lookup, account tender, repayment at the till) are the
till track's (CR-30-1); this module gives them the snapshot and applies what they upload.

## What is built (27A tickets)

| Ticket | Built | Where |
|---|---|---|
| M7-01 scaffold, schema, seeds, permissions, RLS | yes, for the tables below | `db/migration/m7customers/V0001__customers.sql`, `V0002__limits_privacy_reversal.sql`, `V0003__keyed_nic_identity_erasure.sql`, `V0004__account_reopen.sql`, `seed/m7customers/`, `seed/m1party/permissions.yaml` (cus.*), `seed/kernel/config-items.yaml` (customers.*) |
| M7-02 customer, phones, consents, ReuseDetector, deactivate, tags, attributes | yes | `internal/customer/` |
| M7-03 accounts: open with NIC capture, limits, hard block, cap, suspend/reinstate/close | yes | `internal/account/` |
| M7-04 PostingService, Allocator, AgeingCalculator | yes (the ledger reads in `Ledger`, the writes in the handlers) | `internal/ledger/` |
| M7-05 ReceiptTenderConsumer; CPR, RecordCustomerPayment (office), CprBundleHook (till), reversal | yes | `internal/consumers/`, `internal/payment/` |
| M7-06 snapshot contributor | the contributor; the change-log fan-out is not fed (see "Deviations") | `internal/snapshot/` |
| M7-07 statements job, adjustments with SoD | adjustments; the statement is a query and a screen | `internal/account/`, `AccountQueries.statement` |
| M7-08 privacy requests, anonymiser | yes | `internal/privacy/` |
| M7-09 banking records | no | |
| M7-10 web screens | the register, the card (account, repayment, limits, states, history, adjustments, privacy request), the statement (with reversal), the privacy requests | `web/src/modules/m7customers/` |

## The rules the code keeps

- **No personal data leaves the module.** Events carry ids and amounts; audit records carry no
  name, phone number or NIC (the previous holders of a reused number: the society's own by id
  plus a count of others; an erasure's record names the request id only); an officer's reason
  travels as the audit's `reason`, never in `after`. Officer free text (a reason, a reference,
  a note, an outcome) is refused when it carries a Sri Lankan phone number or a NIC
  (`PersonalDataText`, `m7.field.personal_data`): those columns are retained after an erasure.
  A name cannot be detected and is the accepted limitation.
- **The NIC** (wave 2, `2026-10-06-wave2-keyed-hashes.md`). The number is brought to one
  canonical form (the twelve digits; an old-form card `YYDDDSSSCV|X` becomes `19YYDDD0SSSC`,
  `NicNumbers`), so the two forms of one card are one person. What is stored is
  `nic_hash = HMAC-SHA-256(pepper, SHA-256("nic:" + canonical))` with `nic_key_id`
  (`KeyedHash.keyId()`) beside it and `nic_last4` from the canonical form; the pepper is
  `coop-erp.customers.nic-pepper` (`COOP_ERP_CUSTOMERS_NIC_PEPPER`, base64 of 32 random bytes,
  the kernel's `KeyedHash` rule: no default outside a development issuer). The one HMAC input
  convention is the 64 hex characters of the plain SHA-256, never the NIC, so a row from before
  wave 2 (plain SHA-256 of the form as typed, `nic_key_id` null) is re-keyed by `NicRekeyJob`
  (`customers-nic-rekey`, every ten minutes, through `customers.rekey_nic`, the pepper never in
  SQL) without anyone knowing its NIC, and the lookup (`NicCapture`) asks
  `customers.nic_holders` for the keyed and the plain value of every form at once, so no window
  needs care. One `NicCapture` serves OpenAccount, AmendAccountLimits (the limit rising above
  `customers.nic_required_above_limit`, FEDERATION, default 0, for a customer with no NIC) and
  `RecaptureNic` (`POST /v1/customers/{id}/nic`, `cus.account.manage`, a fresh second factor, a
  reason, audit `NIC_RECAPTURED` with `nicCaptured: true` and nothing else), which is the way out
  of a row under a retired key and the ordinary path of a rotation. **Rotation**: ordinary, set
  the new pepper and let each member's next capture or RecaptureNic move their row (a row under
  the old key answers `m7.account.nic_mismatch` until then); emergency, chain the stored values
  under the new key in a one-off run of the job's convention (`HMAC(new, stored)`) and bump the
  key id. **Residual exposure**: whoever holds both the pepper and a dump (the application host)
  can still enumerate the 7.3e5 candidates behind a last four; that is the application, which
  must compare anyway. Losing the pepper makes every captured NIC unmatchable until each member
  presents the card again: back it up with the database. The duplicate check sees every society
  and names only the caller's own customer (`m7.account.nic_held` with the id;
  `m7.account.nic_held_elsewhere` with nobody).
- **One person holds credit at one society in v1** (CR-27A-1 item 5). `nic_holders` and
  `phone_holders` answer "held elsewhere" without identifying the holder, so the federation never
  gets two identities for one person; the member at another society's counter is told to use
  their own society. `account_holder_read` stays for the designed end state (ADR-15); the
  cross-society opening path is sketched in CR-27A-1 and built after doc 10 L-02 is signed.
- **Member identity is the society's** (CR-18-2). `customer`, `customer_phone`,
  `customer_consent` and `data_subject_request` have no `fed_view` or `ext_view` (m7customers
  V0003); the credit book keeps both (money, no name). One layer up, `searchCustomers`,
  `getCustomer` and `listPrivacyRequests` are marked `x-federation-view: false`, so a
  FEDERATION_VIEW session does not resolve their codes (`cus.customer.view`, `cus.privacy.record`);
  the mark is per permission code, so the account and statement reads under `cus.customer.view`
  leave the Federation's set with it.
- **The ledger is insert-only.** `account_posting`, `allocation` and `allocation_reversal` are
  granted SELECT and INSERT only. The balance on `customer_account` is a cache every posting
  handler sets, in the same transaction, to the sum of the account's postings. What is settled
  of a charge is the sum of its allocation rows that no reversal undid (`Ledger.LIVE_ALLOCATION`).
- **Credits settle charges** (CR-27A-1 item 2). The charge side is a CHARGE or a positive
  ADJUSTMENT; the credit side a PAYMENT, a REVERSAL, a CREDIT (a refund or a void) or a negative
  ADJUSTMENT. Every credit but the REVERSAL writes `allocation` rows like a payment: a CREDIT
  first against the open charge of its own receipt (a void), then oldest first; a negative
  ADJUSTMENT oldest first; a reversal re-applies the account's still unallocated credits oldest
  first to the charges it opened again. `Ledger.unallocated` is defined over the whole credit
  side, so `Σ open charges − unallocated = balance` holds by construction and the ageing shows
  what is really owed (`AccountRemaindersIntegrationTest`, under random interleavings).
- **A till's fact is never refused.** A CHARGE beyond the limit is posted with `limit_breached`
  and a REVIEW (ACCOUNT_LIMIT_BREACH), an ALERT when the account is hard blocked and the till was
  offline; a CHARGE on a SUSPENDED or CLOSED account is posted with a REVIEW
  (ACCOUNT_CHARGED_NOT_OPEN), one on an INACTIVE or ANONYMISED customer with a REVIEW
  (ACCOUNT_CHARGED_CUSTOMER_INACTIVE), an offline one above the offline cap with a REVIEW
  (ACCOUNT_OFFLINE_CAP_EXCEEDED); a tender for an account the society does not have is not
  posted and raises a REVIEW (ACCOUNT_TENDER_UNKNOWN); a tender central cannot post (no account,
  amount, business date or tender number, an amount of zero or less or with more than two
  decimals) is not posted and raises a REVIEW (ACCOUNT_TENDER_MALFORMED) naming the receipt and
  the fields, so one bad tender never takes the receipt's other tenders down or dead-letters the
  event (`ReceiptTenderConsumer` hands over null for what it cannot read; the guard is the
  handler's because the demo loader calls it too). Only a receipt with no document id or an
  unknown kind is still `m7.tender.malformed`. The redelivery check runs under the account's
  lock; a dedupe table is not built: `InboxGuard.applyOnce` and the gateway's content hash are
  the guards and the lock serialises the rest. A till's repayment is applied the same way
  (TILL_PAYMENT_FLAGGED: CLOSED_ACCOUNT posted, UNKNOWN_ACCOUNT kept without a posting).
- **Limits and states change what the till accepts, not what central posts.** A higher limit asks
  for a second factor presented within `customers.limit_increase_mfa_max_age` (doc 27 section
  4.2); a close needs a balance of zero and nothing paid in advance; a CLOSED account that a till
  still charged is reopened as SUSPENDED (`REOPEN`, `POST /v1/accounts/{id}/reopen`, history
  `REOPENED`, audit `ACCOUNT_REOPENED`, `account.reopened.v1`; CR-27A-1 item 1), settled or
  reversed, and closed again; every change is a row of `account_history` with its reason.
  Deactivation refuses a customer with a balance on an OPEN or SUSPENDED account and leaves the
  account untouched; reactivation is not built (see "Deferred").
- **Reuse detection (27A 6.1)** reads the holders of a number across societies through
  `customers.phone_holders`, which under the cross-tenant pattern
  (`2026-10-06-wave2-cross-tenant-functions.md`; `RLS_POLICY_TEMPLATE.md`, fifth case) answers an
  OWN caller its own customers' ids, whether the caller holds the number, and when each holder let
  it go; another society's customer is a row with no id. `nic_holders`, `accounts_with_balance`
  and `lock_accounts_for_erasure` follow the same pattern; `legacy_nic_rows` and `rekey_nic`
  answer the platform's FEDERATION_VIEW reader (the re-key job) alone: since V0005 the class, no
  user id and the all-zero entity of the job's scope, so a person's Federation viewer session gets
  nothing (review wave 3, M7M8M9-04); both are to be dropped once the job finds no legacy row.
- **A repayment at the office** is recorded entity-wide and numbered from the society's ENTITY
  series of CPR (`M101-CPR-0000001`); a till's CPR keeps the till's own number and series. A
  reversal is a CPR from the ENTITY series naming the original (`reversal_of`, and a REVERSES
  link in the kernel for an office CPR), a REVERSAL posting and one `allocation_reversal` row per
  allocation the payment made. `cus.payment.reverse` requires a second factor.
- **Adjustments** are asked for (`cus.account.adjust`) and posted only when another person
  approves (`cus.account.adjust_approve`, second factor): `m7.adjustment.same_person` holds the
  INSTANCE rule whatever the SoD pair table says.
- **Privacy requests** are recorded by the society that registered the customer and answered
  (fulfilled or refused) by its responsible officer only (`party.entity.responsible_officer_user_id`;
  `cus.privacy.fulfil` requires a second factor). ACCESS keeps the SHA-256 of the export at
  fulfilment; the export is handed over by the command `DownloadAccessExport`
  (`POST /v1/privacy/requests/{id}/export`, the responsible officer only, audit
  `DSAR_EXPORT_DOWNLOADED` saying whether the rows still give the hash at fulfilment), rebuilt
  from the rows while the customer is not anonymised and never carrying `nic_hash` or
  `nic_key_id` (`nic_last4` stays: the customer's own data). Storing the export under an object
  key waits for the kernel attachment API.
- **Erasure** (CR-27A-1 item 3; doc 10 L-05 as the engineering reads it, pending Federation
  legal). The guard: every account of the customer at every society CLOSED at zero balance,
  locked for the transaction through `customers.lock_accounts_for_erasure`, none closed within
  `customers.erasure_wait_days` (FEDERATION, default 3: the two-day offline window plus a day);
  refusals `m7.privacy.open_balance`, `m7.privacy.account_open`, `m7.privacy.recently_closed`.
  **Erased or redacted**: the names ("Customer"), the NIC's hash, key id and last four, the
  attributes, the tags, the consents (withdrawn), the phones (closed and replaced by `ERASED`),
  and the module's own free text about the person (`data_subject_request.notes` to null and
  `outcome` to `ERASED` on every request of the customer; the erasure request's own outcome is the
  fixed `ANONYMISED`; the officer's text is ignored). **Retained for `customers.retention_years`**
  (FEDERATION, default 7; the purge job waits for doc 10 D-02), under the Personal Data Protection
  Act's exemptions for retention a law requires and for the controller's legal claims: the
  postings, allocations and allocation reversals, the CPRs with their `reference`, the account
  row with its number and history (`account_history.reason`), the adjustment reasons, the kernel
  audit rows and the DSAR records with coded outcomes, all keyed by the opaque customer id. No
  UPDATE grant is added to history, adjustment or document columns (the society's record of its
  own decisions; an issued document). A till fact that lands after an erasure (a shop that was
  offline) is posted and flagged `ACCOUNT_CHARGED_CUSTOMER_INACTIVE`; the society settles it
  through REOPEN against the account number the receipt names: doc 27 section 3.2's documented
  limitation.
- **The snapshot** (table `customer`, row id = customer id) holds the ACTIVE customers with an
  OPEN or SUSPENDED account of the shop's society: three names, language, primary phone, account,
  limit, balance and offline cap as decimal text, hard block, status, tags. Never the NIC,
  attributes or consents; never an inactive or anonymised customer. **The change log** is fed by
  `CustomerChangeLogFanOut` (consumer `m7.snapshot`) from the module's own events, not on the
  handlers' hot path: one UPSERT of `customer / customer id` for every shop of the society
  (`SocietyShops`, an entity-wide read of M1's locations in a transaction of its own, because a
  central event published inside a till's consumer carries the shop and the dispatcher's scope
  would show that shop alone), urgent for a suspension, a close, a reopening, a deactivation and
  an erasure, quiet for a new limit or a balance; always an UPSERT (the kernel's builder emits the
  tombstone when the contributor no longer returns the row); no coalescing (a row per charge per
  shop at thirty days' retention is acceptable and the builder dedupes per delta).
- **The till's repayment** arrives as `customer_payment.issued.v1` (payload `document` and
  `payment`, see `TillPaymentConsumer`), consumer `m7.repayments`, and is allocated oldest first.

## Decisions taken on the architect's delegation (29 September 2026)

1. `cus.customer.view` (ENTITY) is the read code of the slice's queries; 27A lists no read code.
2. OpenAccount checks `cus.account.manage` and asks no fresh second factor; the step-up is on a
   raised limit (AmendAccountLimits), where doc 27 section 4.2 puts it. `cus.account.manage`
   itself does not require MFA, so suspending an account for overdue payments needs none.
3. Duplicate detection: the phone per 27A 6.1; the NIC at account opening (`m7.account.nic_held`);
   names are not a guard.
4. The demo's charges go through `PostAccountTender` with the till's system scope and
   `DEMO-KHATA-...` numbers.
5. Account numbers are per society, `A00001` upwards.
6. AmendLimit, SetHardBlock and SetOfflineCap are one command and one endpoint
   (`POST /v1/accounts/{id}/limits`, a field left out stays): one audit code and one event in 27A.
7. Suspend, reinstate and close are one handler (`ChangeAccountStatus`) with three endpoints,
   audit codes and events; none asks for a second factor (doc 27 section 4.2 marks MFA on open and
   limit increases only).
8. The till's repayment event is `customer_payment.issued.v1` (doc 32 names no CPR bundle type);
   the kernel accepts it as a plain event until `customer_payment.issued` joins the gateway's
   bundle types (content hash), a kernel change for the platform pair.
9. A till's CPR is kept in `doc_customer_payment` (origin TILL, its number, series and shop), not
   as a kernel document, as M6 keeps its receipts in `pos.receipt`.
10. Privacy: a CORRECTION kind beside ACCESS and ERASURE (FR-CUS-060 names correction); a
    correction is made through the ordinary amendments and the request records what was done.
11. The erasure guard counts every society's balances (a customer owing one society is not
    forgotten by another), through a function that answers a count only.

## Deviations from 27A

- `account_posting` has no `allocated_amount` and no UPDATE grant; the column 27A keeps is the
  sum of the allocation rows that no reversal undid, computed on read.
- `allocation` has no `reversed_at`: a reversal writes `allocation_reversal` rows.
- `account_posting.location_id` is `sold_at_location_id` and the table's policies have no location
  line: an account is the society's and a till at any of its shops reads the whole balance.
- `customer_account` has no `mpcs_entity_id`: `owner_entity_id` is the MPCS; it also carries
  `account_no`.
- `customer_tag` has `tagged_at` and `removed_at`: nothing is deleted in this system.
- `doc_customer_payment` is not partitioned, and carries origin, the till's number, series, shop
  and device, and flags; `taken_at_location_id`, not `location_id`, for the reason of postings.
- An adjustment is a row of `account_adjustment`, cited as the document of its ADJUSTMENT posting,
  not a kernel document: no document type exists for it.
- The change log is fed by M7 alone, from a consumer of its own events (wave 2, M7CR-14), without
  the five-minute coalescing 27A asks for; M1 and M2 feed it in their own tickets.
- `data_subject_request` has `notes`, `outcome` and `export_sha256` instead of
  `export_object_key`: the access export is JSON built from the rows, handed over by the audited
  command `DownloadAccessExport`.
- The account state machine has `REOPEN` (CLOSED to SUSPENDED), credits and negative adjustments
  allocate, the erasure guard requires closed accounts and an offline wait, and `AmendAccountLimits`
  takes a `nic`: CR-27A-1, accepted on the architect's delegation, 27A not re-issued yet.
- `nic_key_id` is `text` (the kernel's `KeyedHash.keyId()`, sixteen hex characters), not the
  `smallint` the keyed-hashes decision wrote: the key id is what the one kernel facility gives.
- In v1 a person holds credit at one society (CR-27A-1 item 5); 27A section 11's cross-society
  account waits for doc 10 L-02.
- `banking_record` and `banked_session` are created with their ticket.

## Deferred

Statements as A4 documents (the kernel's A4Renderer runs on the worker role only, which needs a
statement document, a consumer and a stored key: more than a screen) and the statement job; SMS
statements (M9's lane); banking (M7-09); receivables and ageing by bucket for the society; the
privacy CI gate that scans event payloads (27A section 9) beyond the assertions in the tests;
audit of the office's reads; reactivating a deactivated customer; a web screen for `RecaptureNic`
(the command and its endpoint exist); a dedupe table for the till's tenders (the inbox and the
gateway's content hash are the guards); the retention purge (doc 10 D-02); storing the access
export under an object key (the kernel attachment API); the cross-society account opening path
(CR-27A-1 item 5, after doc 10 L-02).

## Tests

`CustomerHandlersIntegrationTest`, `NicIdentityIntegrationTest` (the canonical form, the keyed
hash, a legacy row re-keyed and re-found, RecaptureNic, the NIC on a limit raise, a NIC held
elsewhere), `AccountRemaindersIntegrationTest` (limits with the step-up, states and history with
REOPEN, charges on suspended accounts and over the cap, credits that settle charges, reversal with
re-application, adjustments with SoD, the ledger invariants under random charges, credits,
payments, reversals and adjustments), `PrivacyRequestsIntegrationTest` (the officer guard, the
audited export download without the NIC's hash, erasure with its guards, the redaction and the
privacy scan, the value guard on free text, refusal and correction),
`TillRepaymentEndToEndIntegrationTest` (a till's repayment through the TillSimulator and the sync
gateway), `internal/snapshot/CustomerSnapshotContributorIntegrationTest`,
`internal/snapshot/CustomerChangeLogFanOutIntegrationTest`, `CustomerHttpIntegrationTest`,
`ReceiptTenderConsumerIntegrationTest` (a malformed tender flagged, the receipt's other tenders
posted), `AllocatorTest`, `IdentityNumbersTest`, `AgeingTest`, `DemoDataLoaderIntegrationTest`,
the web tests in `web/src/modules/m7customers/` and `web/e2e/society-credit-book.spec.ts`.
