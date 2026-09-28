# M7 Customers & Payment Recording (back office)

The living guide of the module (AGENTS.md). The design is doc 27; what to build is 27A. This
first part is the society office's: the member register, the credit account (the khata), the
ledger the till's account tenders feed, and repayments at the office. The till's side (lookup,
account tender, repayment at the till, the CPR bundle) comes with the till (CR-30-1).

## What is built (27A tickets)

| Ticket | Built | Where |
|---|---|---|
| M7-01 scaffold, schema, seeds, permissions, RLS | yes, for the tables below | `db/migration/m7customers/V0001__customers.sql`, `seed/m7customers/`, `seed/m1party/permissions.yaml` (cus.*), `seed/kernel/config-items.yaml` (customers.*) |
| M7-02 customer, phones, consents, ReuseDetector, deactivate, tags, attributes | yes | `internal/customer/` |
| M7-03 accounts: open with NIC capture | open only; limits, hard block, cap, suspend/reinstate/close are later | `internal/account/OpenAccountHandler` |
| M7-04 PostingService, Allocator, AgeingCalculator | yes (the ledger reads in `Ledger`, the writes in the handlers) | `internal/ledger/` |
| M7-05 ReceiptTenderConsumer; CPR, RecordCustomerPayment (office) | yes; CprBundleHook (till) and reversal are later | `internal/consumers/`, `internal/payment/` |
| M7-06 snapshot contributor | no (with the till) | |
| M7-07 statements job, adjustments | no; the statement is a query and a screen | `AccountQueries.statement` |
| M7-08 privacy requests, anonymiser | no (see "Deferred") | |
| M7-09 banking records | no | |
| M7-10 web screens | the register, the card with the account and repayment, the statement | `web/src/modules/m7customers/` |

## The rules the code keeps

- **No personal data leaves the module.** Events carry ids and amounts; audit records carry no
  name, phone number or NIC (the previous holders of a reused number by id only). The NIC is
  never stored: its SHA-256 hash (`nic_hash`, to find a second registration of the same person)
  and its last four characters (for an officer to confirm an identity).
- **The ledger is insert-only.** `account_posting` and `allocation` are granted SELECT and INSERT
  only. The balance on `customer_account` is a cache every posting handler sets, in the same
  transaction, to the sum of the account's postings. What is settled of a charge is the sum of
  its allocation rows.
- **A till's fact is never refused.** A CHARGE beyond the limit is posted with
  `limit_breached` and a REVIEW (ACCOUNT_LIMIT_BREACH), an ALERT when the account is hard blocked
  and the till was offline; a tender for an account the society does not have is not posted and
  raises a REVIEW (ACCOUNT_TENDER_UNKNOWN) naming the receipt.
- **Reuse detection (27A 6.1)** reads the holders of a number across societies through
  `customers.phone_holders` (ids only). A holder of this society is refused naming the customer,
  one of another society is refused naming nobody, a holder who let the number go within
  `customers.phone_reuse_window_months` must be confirmed as another person (`confirmedIdentity`).
- **A repayment at the office** is recorded entity-wide and numbered from the society's ENTITY
  series of CPR (`M101-CPR-0000001`); the type's TILL_POSITION scope is the till's.

## Decisions taken on the architect's delegation (29 September 2026)

1. `cus.customer.view` (ENTITY) is the read code of the slice's queries; 27A lists no read code.
2. OpenAccount checks `cus.account.manage` but asks no fresh second factor yet; the step-up comes
   with the limit screens (AmendLimit), where 27A's MFA on a raised limit belongs.
3. Duplicate detection: the phone per 27A 6.1; the NIC at account opening (`m7.account.nic_held`:
   one person, one NIC); names are not a guard (many members share "K. Perera"), the register
   screen shows the members already registered under a similar name while the officer types.
4. `account_posting` and `doc_customer_payment` depart from 27A's DDL, see "Deviations".
5. The demo's charges go through `PostAccountTender`, the handler the receipt tender consumer
   feeds, with the till's system scope; they carry `DEMO-KHATA-...` numbers since no M6 receipt
   stands behind them until the till writes ACCOUNT tenders.
6. Account numbers are per society, `A00001` upwards.

## Deviations from 27A

- `account_posting` has no `allocated_amount` and no UPDATE grant; the column 27A keeps is the
  sum of the allocation rows, computed on read.
- `account_posting.location_id` is `sold_at_location_id` and the table's policies have no location
  line: an account is the society's and a till at any of its shops reads the whole balance.
- `customer_account` has no `mpcs_entity_id`: `owner_entity_id` is the MPCS (the column RLS
  and the RLS matrix know); it also carries `account_no`.
- `customer_tag` has `tagged_at` and `removed_at`: nothing is deleted in this system.
- `doc_customer_payment` is not partitioned (one extension row per receipt, like M4's).
- `data_subject_request`, `banking_record` and `banked_session` are created with their tickets.
- The identity visible to another society holding an account (27A 3) is built as a policy
  (`account_holder_read`); the path for a second society to open an account for a customer
  another society registered comes with the till's lookup.

## Deferred

Limits, hard block, offline cap, suspend, reinstate and close (M7-03); the CprBundleHook and
ReverseCustomerPayment (M7-05); the snapshot contributor and change-log fan-out (M7-06);
statements as documents and SMS, adjustments with SoD (M7-07); privacy requests and the
anonymiser (M7-08: the anonymiser needs the DSAR workflow and its responsible-officer guard
around it, so it is not cheap without them); banking (M7-09); the privacy CI gate that scans
event payloads (27A section 9) beyond the assertions in the handler tests.

## Tests

`CustomerHandlersIntegrationTest` (every guard, reuse detection, NIC, limit breach, unknown
account, oldest-first and specific allocation, overpayment, the ledger property under a random
sequence, RLS between two societies), `CustomerHttpIntegrationTest` (the slice end to end, 400 and
404), `ReceiptTenderConsumerIntegrationTest`, `AllocatorTest`, `IdentityNumbersTest`,
`AgeingTest`, `DemoDataLoaderIntegrationTest` (the demo's members and credit book), the web
tests in `web/src/modules/m7customers/` and `web/e2e/society-credit-book.spec.ts`.
