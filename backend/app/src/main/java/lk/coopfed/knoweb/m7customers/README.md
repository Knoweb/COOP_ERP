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
| M7-01 scaffold, schema, seeds, permissions, RLS | yes, for the tables below | `db/migration/m7customers/V0001__customers.sql`, `V0002__limits_privacy_reversal.sql`, `seed/m7customers/`, `seed/m1party/permissions.yaml` (cus.*), `seed/kernel/config-items.yaml` (customers.*) |
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
  name, phone number or NIC (the previous holders of a reused number by id only; an erasure's
  record names the request id only). The NIC is never stored: its SHA-256 hash (`nic_hash`, to
  find a second registration of the same person) and its last four characters (for an officer
  to confirm an identity).
- **The ledger is insert-only.** `account_posting`, `allocation` and `allocation_reversal` are
  granted SELECT and INSERT only. The balance on `customer_account` is a cache every posting
  handler sets, in the same transaction, to the sum of the account's postings. What is settled
  of a charge is the sum of its allocation rows that no reversal undid (`Ledger.LIVE_ALLOCATION`).
- **A till's fact is never refused.** A CHARGE beyond the limit is posted with `limit_breached`
  and a REVIEW (ACCOUNT_LIMIT_BREACH), an ALERT when the account is hard blocked and the till was
  offline; a CHARGE on a SUSPENDED or CLOSED account is posted with a REVIEW
  (ACCOUNT_CHARGED_NOT_OPEN), an offline one above the offline cap with a REVIEW
  (ACCOUNT_OFFLINE_CAP_EXCEEDED); a tender for an account the society does not have is not
  posted and raises a REVIEW (ACCOUNT_TENDER_UNKNOWN). A till's repayment is applied the same way
  (TILL_PAYMENT_FLAGGED: CLOSED_ACCOUNT posted, UNKNOWN_ACCOUNT kept without a posting).
- **Limits and states change what the till accepts, not what central posts.** A higher limit asks
  for a second factor presented within `customers.limit_increase_mfa_max_age` (doc 27 section
  4.2); a close needs a balance of zero and nothing paid in advance; every change is a row of
  `account_history` with its reason.
- **Reuse detection (27A 6.1)** reads the holders of a number across societies through
  `customers.phone_holders` (ids only).
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
  `cus.privacy.fulfil` requires a second factor). ACCESS keeps the SHA-256 of the export handed
  over; the export is rebuilt on download while the customer is not anonymised. ERASURE waits
  until no account of the customer at any society has a balance (`customers.accounts_with_balance`,
  ids and counts only), then anonymises: "Customer", no other names, NIC, attributes or tags,
  phones closed and replaced by `ERASED`, consents withdrawn; postings, allocations, payment
  receipts and the account stay (retention).
- **The snapshot** (table `customer`, row id = customer id) holds the customers with an OPEN or
  SUSPENDED account of the shop's society: three names, language, primary phone, account, limit,
  balance and offline cap as decimal text, hard block, status, tags. Never the NIC, attributes or
  consents; never an anonymised customer.
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
- The snapshot contributor feeds no change log (27A: "customer.*, account.* ... UPSERT;
  account.suspended urgent"): M1's and M2's contributors do not either yet, so a till gets the
  customer rows with its full snapshot. The fan-out, with the urgent flag on a suspension, comes
  with the till's delta sync.
- `data_subject_request` has `notes`, `outcome` and `export_sha256` instead of
  `export_object_key`: the access export is JSON built from the rows, handed over by download.
- `banking_record` and `banked_session` are created with their ticket.

## Deferred

Statements as A4 documents (the kernel's A4Renderer runs on the worker role only, which needs a
statement document, a consumer and a stored key: more than a screen) and the statement job; SMS
statements (M9's lane); banking (M7-09); receivables and ageing by bucket for the society; the
privacy CI gate that scans event payloads (27A section 9) beyond the assertions in the tests; the
change-log fan-out to the tills (above); audit of the office's reads.

## Tests

`CustomerHandlersIntegrationTest`, `AccountRemaindersIntegrationTest` (limits with the step-up,
states and history, charges on suspended accounts and over the cap, reversal, adjustments with
SoD, the ledger property with reversals and adjustments), `PrivacyRequestsIntegrationTest` (the
officer guard, the access export, erasure with its guard and the ledger kept, refusal and
correction), `TillRepaymentEndToEndIntegrationTest` (a till's repayment through the TillSimulator
and the sync gateway), `internal/snapshot/CustomerSnapshotContributorIntegrationTest`,
`CustomerHttpIntegrationTest`, `ReceiptTenderConsumerIntegrationTest`, `AllocatorTest`,
`IdentityNumbersTest`, `AgeingTest`, `DemoDataLoaderIntegrationTest`, the web tests in
`web/src/modules/m7customers/` and `web/e2e/society-credit-book.spec.ts`.
