# Wave 2 fix review: M7 customers and the credit book

Area key `m7-credit`. Findings reviewed: `docs/reviews/wave2-m7-credit.md` (M7CR-01 to M7CR-15; M7CR-12 is dropped and not reviewed here). Code re-read at `main` 91e550d8 on branch `review/wave2-findings`, together with doc 27 (sections 3.1 to 3.5, 4, 6.7, 7, 9.3, 9.5, 11), 27A (sections 3, 6.2 to 6.4, 7.3, 11), doc 09 (ADR-15, ADR-26, ADR-27), `docs/DECISIONS_PENDING.md` (L-05, L-06, A-06), the kernel's `ChangeLog`, `SnapshotBuilder`, `InboxGuard`, `EventConsumerDispatcher`, `IdempotencyFilter`, `OutboxWriter` and the `ArchitectureTests` write rule. Nothing was run (Docker down); every statement below rests on reading.

Reviewer: Claude Fable 5.1 (independent second opinion, 6 October 2026). No fix has been written.

## Summary

| Finding | Severity | Valid? | Fix verdict | Size | Migration? |
|---|---|---|---|---|---|
| M7CR-01 unsalted NIC hash | high | YES | DECIDE THEN BUILD (pepper keyed hash, key id, re-key job, NIC recapture command) | M | yes (V0003) |
| M7CR-02 old/new NIC forms | medium | YES | BUILD AMENDED (canonical 12-digit form; lookup over a candidate set for legacy rows) | S | no (rides on 01) |
| M7CR-03 NIC not asked on limit raise | medium | YES | BUILD AMENDED (accept `nic` on the amend, shared `NicCapture`, threshold from config in both handlers) | S | no (seed only) |
| M7CR-04 CLOSED account cannot be settled | medium | YES | DECIDE THEN BUILD (REOPEN transition CLOSED to SUSPENDED) | S | yes (history CHECK) |
| M7CR-05 deactivation guard and snapshot | low | YES | BUILD AMENDED (guard on OPEN and SUSPENDED; snapshot only ACTIVE customers; flag charges on non-ACTIVE) | S | no |
| M7CR-06 malformed tender dead-letters the receipt | medium | YES | BUILD AMENDED (nulls from the consumer, the handler flags MALFORMED like UNKNOWN; document id missing stays poison) | S | no |
| M7CR-07 check before lock | low | PARTLY | BUILD AS SUGGESTED for the reorder; DEFER the dedupe table; `seq` goes with 06 | S | no |
| M7CR-08 credits never settle charges | medium | YES | DECIDE THEN BUILD (credit-side postings allocate; invariant open − unallocated = balance) | M | no |
| M7CR-09 erasure guard | medium | YES | DECIDE THEN BUILD (every account CLOSED, locked; waiting window; flag facts on anonymised) | M | yes |
| M7CR-10 free text survives erasure | medium | YES | DECIDE THEN BUILD (redact DSAR text; reasons out of audit `after`; value guard at write; retention recorded) | M | yes (one GRANT) |
| M7CR-11 access export download | low | YES | BUILD AMENDED (download as a command: officer guard, audit; `nic_hash` out; hash claim reworded) | S | no |
| M7CR-13 one person at two societies | medium | YES | DECIDE THEN BUILD (recommend: defer the path to after L-02, record the deviation, keep the policies, build 15) | S now, L later | no now |
| M7CR-14 no change-log rows | medium | YES | BUILD AMENDED (an M7 fan-out consumer on its own events; states first, balances second) | M | no |
| M7CR-15 NIC check under RLS | low | YES | BUILD AMENDED (SECURITY DEFINER `nic_holders(hash[])`, scope check, no foreign ids) | S | yes (with 01) |

Counts: BUILD AS SUGGESTED 1 (07, part), BUILD AMENDED 7, DECIDE THEN BUILD 6, DEFER 1 (07's dedupe table; 13's cross-society path is a recommended decision), INVALID 0. No suggested fix is WRONG; two are incomplete in a way that would leave an operational dead end (01 and 03, see below) and one verifier statement is wrong on a detail (04 does need a migration if REOPEN is chosen).

---

## Fix group A: NIC identity (M7CR-01, M7CR-02, M7CR-15, with M7CR-03)

**Combined fix.** One keyed hash scheme, one canonical NIC form, one federation-wide lookup, one shared capture routine used by every command that takes a NIC, and a way for an officer to re-capture a NIC. Order of work: (1) record the pepper decision; (2) migration V0003 (`nic_key_id`, `rekey_nic` and `nic_holders` functions); (3) `NicNumbers` canonicalisation and a `NicHasher` component; (4) `NicCapture` used by OpenAccount, AmendAccountLimits and a new `RecaptureNic` command; (5) the re-key job; (6) tests (`IdentityNumbersTest`, handler tests, a Testcontainers test that the re-key job converts a seeded SHA-256 row and the lookup still finds it). Must be decided first: where the pepper lives and how it rotates (recommendation below).

### M7CR-01

1. **Valid: YES.** `NicNumbers.hash` is `SHA-256("nic:" + nic)`, `nic_last4` sits beside it (V0001:25-26, OpenAccountHandler:157-161). The candidate space the verifier computed is right: with the last four known, a new-form NIC leaves year × day-of-year (with the +500 for women) × one serial digit, about 7.3e5; SHA-256 over that is milliseconds. Reachable by anyone with SQL on the row (dump, backup, `fed_view`, `ext_view`, RLS-01) and by the ACCESS export (M7CR-11). Doc 27 says "salted hash" three times; 27A says only "hash". A constant prefix is neither.

2. **Suggested fix: NEEDS CHANGES.** HMAC with a server-side pepper is the right primitive and the re-key trick is right: `HMAC(k, existing_sha256)` can be computed for every row without a NIC, and the new capture computes the same thing (`HMAC(k, SHA-256("nic:" + form))`). Three things are missing:
   - **Rotation is not designed.** Without a key id on the row, a second key can never be introduced. Add `nic_key_id smallint` (null = legacy plain SHA-256, 1 = first pepper); the lookup computes the hash under every key still in force and queries `nic_hash in (...)`. Rotation after a suspected leak is a Java job that chains (`HMAC(k2, stored)`) and bumps the key id, again with no NIC; ordinary rotation re-captures on the member's next visit (see RecaptureNic). Keep a plain legacy lookup (`nic_key_id is null and nic_hash = sha256`) until the re-key job reports zero legacy rows, so the window between deploy and job needs no care.
   - **The re-key cannot be a SQL migration and cannot be a handler.** The pepper must not reach SQL text (`pg_stat_statements`, statement logs). Do what `PostingPartitionMaintainer` does: a job that reads the legacy rows, computes the HMAC in Java, and writes through a `SECURITY DEFINER` function `customers.rekey_nic(p_customer uuid, p_old char(64), p_new char(64), p_key smallint)` that updates only when the row still holds `p_old` with a null key id (idempotent, restartable, and it bypasses `own_update`, which the job's system scope would not satisfy for every society). `ArchitectureTests.onlyHandlersWriteRule` counts `update`/`batchUpdate`/`execute` on `JdbcTemplate`; a `queryForObject("select customers.rekey_nic(...)")` is what the partition maintainer already does, so the rule holds.
   - **There is no command that re-captures a NIC.** Today only OpenAccount writes `nic_hash`, once. After the fix, any customer whose legacy hash cannot be matched (an old-form capture re-typed from the new card, M7CR-02; a row whose key was retired) answers `m7.account.nic_mismatch` for ever, with no way out. Add `RecaptureNic` (`cus.account.manage`, fresh second factor as a limit increase, reason, audit `NIC_RECAPTURED` with `nicCaptured: true` and nothing else) that runs the `nic_held` check and overwrites hash, last four and key id. It also carries the ordinary key rotation.
   
   Pepper placement: an application property `coop-erp.customers.nic-pepper` from the environment or secret store, with exactly the `IdempotencyFilter` behaviour (no default outside a development issuer; refuse to start otherwise). Not a `ConfigRegistry` item: that lives in the database the pepper protects. The property is M7's; the "no default outside development" check should be lifted from `IdempotencyFilter` into a small kernel helper so the two do not drift (platform pair, tiny).

3. **Best solution.** Alternatives: (a) per-row random salt: rejected twice over; the equality lookup `nic_held`/`nic_mismatch` would need one HMAC per customer row per check (hundreds of thousands federation-wide), and a salt stored beside the hash protects nothing against a 7.3e5 search, which is the actual threat. (b) Tokenisation (a vault table mapping token to NIC, or to an encrypted NIC): the plaintext or its ciphertext then exists somewhere and must be protected and backed up; the system has no business need to ever read the NIC back (confirmation uses the last four), so storing anything recoverable is the wrong direction. (c) Keeping fewer than four characters: L-06 ("hash plus last four") is an assumption with Federation legal; with a pepper the last four beside the hash is not a weakness (the attacker lacks the key), and the reuse-detection confirmation step (27A 6.1, doc 27 6.1) relies on four. Keep four. (d) Keyed hash (HMAC-SHA-256, pepper outside the database, key id on the row): recommended. Residual exposure, to record in the README: anyone holding both the pepper and a dump (the application host) can still enumerate; that is the application, which must compare anyway.

   **Verdict: DECIDE THEN BUILD. Size M. Migration yes. Risk medium:** losing the pepper makes every captured NIC unmatchable (mitigated by RecaptureNic); a wrong HMAC input convention in the re-key job silently breaks duplicate detection (mitigated by the Testcontainers test that captures, re-keys and re-finds). Demo data (`DemoCustomers.nic`) is created through OpenAccount and follows automatically.

### M7CR-02

1. **Valid: YES.** `normalise` only strips whitespace and upper-cases; `hash` hashes the typed form. `851234567V` and `198512304567` are one person and give two hashes; `V` versus `X` likewise. The conversion rule (`19` + YYDDD + `0` + SSSC, letter dropped) is the standard one; old-form cards were issued only to people born in the 1900s, so the `19` is safe.

2. **Suggested fix: NEEDS CHANGES.** "Hash both forms when it looks up" is right in spirit but under-specified: when the officer types a new-form NIC, the legacy row may hold the old form with `V` or with `X`; when they type an old-form NIC, the legacy row may hold the new form. So the lookup for a legacy row computes a candidate set (canonical; old form with V; old form with X; new form as typed), at most four hashes under the legacy scheme, and queries `nic_hash in (...)`. For rows at key id 1 and above only the canonical hash is needed. `nic_last4` must come from the canonical form (`4567`, not `567V`), so the confirmation screen shows one thing per person; legacy rows keep what they hold until re-captured.

3. **Best solution.** Canonicalise to the 12-digit form before hashing; the candidate-set lookup for legacy rows; RecaptureNic (01) to clean a legacy row when the member next presents the card. Alternative "re-capture every existing row" is the same thing done at once and is not possible without the members. Add to `IdentityNumbersTest`: old and new form of one NIC canonicalise alike, `V`/`X` alike, last four from the canonical form.

   **Verdict: BUILD AMENDED. Size S (on top of 01). Migration no. Risk low.**

### M7CR-15

1. **Valid: YES.** OpenAccountHandler:117 selects from `customers.customer` under the caller's policies; the same NIC at another society is invisible. Phone reuse goes through `customers.phone_holders` (SECURITY DEFINER) and does see every society.

2. **Suggested fix: NEEDS CHANGES.** A SECURITY DEFINER function is right, but (a) it must take the candidate hash set of 01/02 (`p_hashes char(64)[]`), (b) it must test `kernel.scope_class() = 'OWN'` inside (the RLS-03 lesson on `phone_holders`), and (c) it must not hand another society's customer id to the caller: return `(own_customer_id uuid, held_elsewhere boolean)` as RLS-03 proposes for `phone_holders`. The handler then answers `m7.account.nic_held` with the id when the holder is the caller's own customer, and a new `m7.account.nic_held_elsewhere` (i18n en/si/ta) with nothing when it is not, exactly as `ReuseDetector.guard` does for phones.

3. **Best solution.** As amended; it also gives society B the minimal, honest answer under the M7CR-13 recommendation ("this person already has an identity elsewhere; one person, one identity"), which stops the duplicate-identity path the finding describes. Nothing else to consider.

   **Verdict: BUILD AMENDED. Size S. Migration yes (same V0003 as 01). Risk low.**

### M7CR-03

1. **Valid: YES.** AmendAccountLimitsHandler reads neither `nic_hash` nor a threshold; OpenAccount hard-codes `> 0`; `customers.nic_required_above_limit` is not seeded (doc 27 section 7 lists it, default Rs 0, scope Federation).

2. **Suggested fix: NEEDS CHANGES.** The verifier offers "refuse with `m7.account.nic_required`, or accept a `nic` field". Refusing alone creates a dead end: no command other than OpenAccount captures a NIC, so a zero-limit account could never be raised. The amend must accept `nic` (optional field on `AmendAccountLimitsRequest` in `openapi/m7customers.yaml`; a backward-compatible addition, the web is the only client). Extract the capture (normalise, candidate hashes, `nic_mismatch`, `nic_held` through 15, write hash/last4/key id) into `NicCapture`, used by OpenAccount, AmendAccountLimits and RecaptureNic (01). Rule: when the limit rises and the new limit exceeds `customers.nic_required_above_limit` and the customer holds no NIC, require `nic`; cap or hard-block changes alone never ask. Seed the item in `seed/kernel/config-items.yaml` (DECIMAL, FEDERATION scope, change permission `sys.config.manage`, default 0, en/si/ta descriptions) and read it in both handlers.

3. **Best solution.** As amended. Alternative "a separate capture-NIC command only" is RecaptureNic, which exists anyway for 01; but asking the officer to run two commands to raise a limit is one obvious way too many for a counter.

   **Verdict: BUILD AMENDED. Size S. Migration no. Risk low** (an OpenAPI change regenerates the web client; existing tests that amend a zero-limit account to a positive one without a NIC will need the field).

---

## Fix group B: account states and settlement (M7CR-04, M7CR-05, M7CR-08)

**Combined fix.** A REOPEN transition; a wider deactivation guard with the snapshot restricted to ACTIVE customers; credit-side postings that settle charges through the same allocation rows as payments, with the ledger invariant "sum of open charges − unallocated credit = balance" in the property test. Order: decide 04 and 08 (both are CRs against doc 27 section 4.2 and 27A section 6.3); migration V0003 (or V0004 if group A merges first) for the `account_history` CHECK; 05 needs no decision and can go first.

### M7CR-04

1. **Valid: YES.** `TRANSITIONS` has no entry from CLOSED. A till CHARGE (PostAccountTender:164) and a till CPR (RecordTillPayment:116) post on a CLOSED account by design ("cash was taken", 27A 7.3). Afterwards the office payment (RecordCustomerPayment:102), reversal (:137), adjustment request (:70) and approval (:95) all refuse CLOSED. A second account for the pair is impossible twice over: `m7.account.exists` and `UNIQUE (customer_id, owner_entity_id)`.

2. **Suggested fix: NEEDS CHANGES (one detail).** Either option removes the dead end. The verifier says "no migration": true for "allow settling on CLOSED", false for REOPEN, because `account_history.action` has a CHECK listing four actions (V0002:20); a REOPENED row needs the constraint dropped and re-created in a new migration (never editing V0002).

3. **Best solution.** Alternatives: (a) let payments, reversals and adjustments through on a CLOSED account with a non-zero balance: touches four handlers' guards, leaves an account that is taking postings labelled CLOSED, and the "balance not zero" condition makes the state machine implicit. (b) An automatic state change when a till fact lands on a CLOSED account: convenient, but it would make central's posting change a state, against the module's own rule "limits and states change what the till accepts, not what central posts", and a state change with no officer and no reason. (c) An explicit `REOPEN` action on `ChangeAccountStatus`, from CLOSED to **SUSPENDED** (not OPEN: the till must keep refusing tenders until the officer decides to REINSTATE), reason required, history action `REOPENED`, audit `ACCOUNT_REOPENED`, event `account.reopened.v1`, permission `cus.account.manage`, no MFA (doc 27 puts MFA on open and limit increases only). The officer's path after the REVIEW `ACCOUNT_CHARGED_NOT_OPEN` or `TILL_PAYMENT_FLAGGED CLOSED_ACCOUNT` is then: reopen, record the repayment or reverse the CPR, close again. Recommended: (c).

   **Decision to record:** doc 27 section 4.2 and 27A section 6 gain `CLOSED → SUSPENDED: ReopenAccount(reason)`; CR against doc 27/27A.

   **Verdict: DECIDE THEN BUILD. Size S. Migration yes (CHECK on `account_history.action`). Risk low.** Tests: reopen from CLOSED, refuse reopen from OPEN/SUSPENDED, the full scenario (close, till charge, reopen, office payment, close).

### M7CR-05

1. **Valid: YES.** The guard is `status = 'OPEN' and balance <> 0` (DeactivateCustomerHandler:59); the snapshot filter is `c.status <> 'ANONYMISED'` (CustomerSnapshotContributor:86); PostAccountTender never reads the customer's status. A SUSPENDED debtor can be deactivated, and an INACTIVE customer keeps buying on credit at every till until something removes the row.

2. **Suggested fix: CORRECT**, choosing among its options: guard on `status in ('OPEN','SUSPENDED') and balance <> 0`; exclude `c.status <> 'ACTIVE'` from the snapshot; leave the account untouched (27A: "The account and its postings are untouched", and closing it would be a `cus.account.manage` act inside a `cus.customer.manage` command).

3. **Best solution.** As above, plus one line in PostAccountTender: a CHARGE whose customer is not ACTIVE raises a REVIEW (`ACCOUNT_CHARGED_CUSTOMER_INACTIVE`, carrying the status and the receipt number only), which is also where M7CR-09's "charge on an anonymised customer" flag lives, so one audit code with the status in it serves both. The row leaves the snapshot through the ordinary tombstone once M7CR-14 feeds the change log (a deactivation is an UPSERT whose row the contributor no longer returns; `SnapshotBuilder` turns that into a tombstone).

   **Verdict: BUILD AMENDED. Size S. Migration no. Risk low.** Reactivating a customer is not built; record it as deferred in the README rather than invent it here.

### M7CR-08

1. **Valid: YES** for the void scenario; the second scenario is, as the verifier says, only "B's 300 is not re-applied". `Ledger.openCharges` subtracts allocation rows only; only the two payment handlers write allocations; a CREDIT (refund or void) and a negative ADJUSTMENT reduce the balance and settle nothing. Ageing and "oldest open charge" then age a charge the customer no longer owes, and the next payment is allocated to it. Doc 27 section 3.3 ("ageing from unpaid charge dates using the allocation records") and 27A section 6.3 are silent on credits.

2. **Suggested fix: CORRECT with two additions.** `allocation.payment_posting_id` has no foreign key (V0001:127-134; `account_posting` is partitioned), so an allocation may cite a CREDIT or ADJUSTMENT posting without a migration. But `Ledger.unallocated` must change with it: it sums `PAYMENT` and `REVERSAL` postings and subtracts every live allocation joined to the account, so a CREDIT's allocations would be subtracted from the payments' total and the figure would go wrong. Define the credit side as every posting with `kind in ('PAYMENT','REVERSAL','CREDIT')` or (`ADJUSTMENT` and `amount < 0`); unallocated = −Σ(credit side) − Σ(live allocations of the account). Then open − unallocated = Σ charges + Σ credit side = balance holds by construction, including after reversals (the REVERSAL is positive on the credit side and its payment's allocations are undone by `allocation_reversal` rows). The statement query's `settled` CASE (AccountQueriesImpl:177-189) extends to CREDIT and negative ADJUSTMENT the same way.

3. **Best solution.** How a credit settles: a CREDIT first against the open charge with the same `document_id` (a void's `receipt.voided.v1` carries the receipt's own document id, so the voided charge is found; a refund's document is the refund receipt and will usually not match), then oldest first for what remains; a negative ADJUSTMENT oldest first (an officer who means a particular charge requests it against that charge: out of scope, noted). After a reversal, re-apply the account's still-unallocated credit oldest first in the same handler (Ledger gains `unallocatedCredits(account)` per posting), so scenario 2 self-heals. Rejected: leaving credits unallocated and documenting it, because ageing is what the society acts on when it suspends accounts for arrears (doc 27 6.3), and a 90+ bucket on a voided sale will suspend a member who owes nothing. Property test: "Σ open charges − unallocated = balance" and "Σ allocations of a charge ≤ charge" under random interleavings of charges, credits, payments, reversals and adjustments.

   **Decision to record:** 27A section 6.3 gains "a CREDIT or a negative ADJUSTMENT is allocated like a payment: a CREDIT against the open charge of the same document first, then oldest first; a reversal re-runs oldest-first allocation of the account's unallocated credit". CR against 27A.

   **Verdict: DECIDE THEN BUILD. Size M. Migration no. Risk medium** (ledger arithmetic; the property test is the safety net, and `make test` cannot run until Docker is up).

---

## Fix group C: till facts applied and flagged (M7CR-06, M7CR-07)

**Combined fix.** The consumer parses every tender field defensively and passes `null` for what it cannot read; the handler treats a tender it cannot post as a flagged fact, not a refusal; the redelivery check runs under the account lock. No decision needed.

### M7CR-06

1. **Valid: YES.** `ReceiptTenderConsumer` throws from `UUID.fromString`, `new BigDecimal` and `LocalDate.parse`; `PostAccountTenderHandler:77-86` throws `m7.tender.malformed` for a null account, amount, date, or a non-positive amount; `EventConsumerDispatcher` runs the whole consumer in one transaction, retries twice and dead-letters with `EVENT_CONSUMER_DEAD_LETTERED` naming the event, not the receipt. One bad ACCOUNT tender loses every ACCOUNT tender of that receipt, and the handler's own Javadoc promises `ACCOUNT_TENDER_UNKNOWN` for a tender naming no account. The scale gap is real: `signum() <= 0` is checked, `scale() > 2` is not, and `numeric(14,2)` rounds silently.

2. **Suggested fix: NEEDS CHANGES (placement).** "Parse each field defensively inside the loop" and "flag" are right. Where the flag is written matters: the consumer is not a `@CommandHandler`, so it must not audit; and the handler is also called by `DemoCustomers`, so the guard belongs in the handler (AGENTS.md: a rule that must hold for a command that does not arrive over HTTP is a guard). So: the consumer's `uuid`, `decimal`, `date` helpers return `null` on unparseable text (and `tenderSeq` becomes `Integer`, null when absent, see 07) instead of throwing; the handler keeps `m7.tender.malformed` only for what makes the fact unusable even as a flag (null `receiptDocumentId`, unknown `kind`), and for a null account, amount, date or seq, a non-positive amount or an amount with more than two decimals it writes a REVIEW `ACCOUNT_TENDER_MALFORMED` on `Subject.of("document", receiptDocumentId)` carrying the receipt number, which fields were missing, and the amount as text, posts nothing and returns null, as the UNKNOWN branch does. A missing `document.document_id` stays a throw: the gateway's bundle validation makes that a contract breach, and there is no subject to hang a REVIEW on. New audit code and its i18n ids in `i18n/m7customers/{en,si,ta}.json`.

3. **Best solution.** As amended; "post the rounded amount and flag" was considered for the three-decimal case and rejected, because it records an amount the till did not send; the office posts an adjustment from the REVIEW. Tests: one receipt with one good and one malformed ACCOUNT tender posts the good one and flags the other; a 100.005 amount is flagged and not posted; `kernel.committedAudit()` carries no phone or NIC.

   **Verdict: BUILD AMENDED. Size S. Migration no. Risk low.**

### M7CR-07

1. **Valid: PARTLY.** The ordering (existing-posting check at :87, lock at :101) is as described. The scenario as written is guarded: `InboxGuard.applyOnce` inserts `(consumer, event_id)` with `ON CONFLICT ... WHERE outcome = 'FAILED'` inside the delivery transaction, so a concurrent second delivery of the same event blocks on the unique key and then applies nothing. Double posting needs two distinct event ids carrying one receipt plus concurrent workers, which only a faulty till produces (and the gateway's content-hash bundle check may refuse even that; not verified). The `seq` absent → 0 collapse is confirmed.

2. **Suggested fix: CORRECT** for moving the check under the lock (two lines). The dedupe table is not worth a migration for a path the inbox already closes.

3. **Best solution.** Reorder; treat a missing `seq` as malformed (06); defer the dedupe table and record why in the README ("the inbox and the gateway's content hash are the guards; the account lock serialises the rest").

   **Verdict: BUILD AS SUGGESTED (reorder) and DEFER (dedupe table). Size S. Migration no. Risk none.**

---

## Fix group D: erasure and retention (M7CR-09, M7CR-10, M7CR-11)

**Combined fix.** A stricter, locked erasure guard with a waiting window; what the anonymiser redacts and what it leaves, written down; personal-data value guards where officers type free text; the access export download as an audited command without the derived secret. Order: decide 09 and 10 (both touch L-05 wording); migration (functions, policy, one GRANT); 11 can go first.

### M7CR-09

1. **Valid: YES.** `accounts_with_balance` is a `STABLE SECURITY DEFINER count(*)` with no lock (V0002:133-141); the anonymiser touches no `customer_account` row; the snapshot excludes ANONYMISED only at the next full snapshot (no change log, M7CR-14); PostAccountTender reads no customer status; a till may upload a two-day-old ACCOUNT sale after the erasure. The code follows 27A 6.4 exactly, so the gap is in the design too.

2. **Suggested fix: CORRECT in direction, two precisions.** (a) `FOR UPDATE` in the handler locks only the caller's society's accounts (RLS); other societies' accounts can be locked only inside the SECURITY DEFINER function, which then needs an `app_seed` UPDATE policy on `customer_account` (`FOR UPDATE` row locks are refused under RLS without one) and must be VOLATILE. Cheap, do it: `customers.lock_accounts_for_erasure(p_customer)` returning the count of accounts that are not CLOSED or have a balance, after locking them. (b) "Require every account CLOSED" is the right rule and makes the balance check almost redundant (close already needs zero balance and nothing unallocated), but keep both: a till fact under 04 can land on a CLOSED account.

3. **Best solution and what the law needs.** Under Sri Lanka's PDPA (No. 9 of 2022, ADR-27: build compliant ahead of commencement) the right to erasure yields to retention required by law and to the controller's legal claims; the society's books of account (co-operative and tax law, L-05 assumes 7 years) are such a retention. So what must be erased is the identity (names, phones, NIC hash and last four, attributes, consents' purpose history remains but is withdrawn, tags, free text about the person, M7CR-10); what must be retained is the ledger and its documents (postings, allocations, CPRs, the account row with its number and history, kernel audit rows), keyed by the opaque customer id (doc 27 6.7: "the customer reference replaced by an opaque id", which the UUID already is). The guard therefore: every account of the customer at every society CLOSED and at zero balance, locked; and no account closed within `customers.erasure_wait_days` (new FEDERATION config item, default 3: the two-day offline window plus a day; the Act's own response period for requests is longer, so this costs the member nothing). Refusal reasons: `m7.privacy.open_balance` (kept), new `m7.privacy.account_open` and `m7.privacy.recently_closed` (i18n). After erasure, a till fact on the account is still posted (never rejected) and flagged with the 05 REVIEW (status ANONYMISED); the society settles it through REOPEN (04) against the account number the receipt names. Record in the README that this is the documented limitation doc 27 3.2 already names. Rejected: refusing the till fact (AGENTS.md), and blocking erasure for ever on an account that was once used (the Act does not allow it).

   **Decision to record:** 27A 6.4 `Anonymiser.apply` precondition becomes "every account CLOSED with zero balance, locked, none closed within `customers.erasure_wait_days`"; CR against 27A; the L-05 wording for Federation legal gains the sentence on facts that arrive after erasure.

   **Verdict: DECIDE THEN BUILD. Size M. Migration yes (function, `app_seed` UPDATE policy on `customer_account`). Risk low-medium** (`PrivacyRequestsIntegrationTest` fixtures must close accounts and move the clock before erasing).

### M7CR-10

1. **Valid: YES.** The anonymiser updates `customer`, `customer_phone` (overwriting `reason` with `ERASURE`), `customer_consent`, `customer_tag` and nothing else. `data_subject_request.notes` and `outcome`, `account_history.reason`, `account_adjustment.reason`, `doc_customer_payment.reference` and the kernel document's `notes` (the reversal reason goes into both, ReverseCustomerPaymentHandler:144 and :166), and the audit `after` maps of `CUSTOMER_PHONE_CHANGED` (:103) and `CUSTOMER_DEACTIVATED` (:75) all keep officer free text. Whether personal data lands there depends on what is typed; the path is certain. The kernel's `OutboxWriter` scanner checks field names, not values, so "new number 0771234567" in `reason` passes it.

2. **Suggested fix: NEEDS CHANGES.** The verifier is right that redacting `account_history`, CPR documents and audit rows conflicts with AGENTS.md, and right that the cleaner fix is to stop the data going in. Make it concrete, in four parts:
   - **Redact what the module owns and may update:** at erasure, every `data_subject_request` of the customer gets `notes = null, outcome = 'ERASED'`, and the erasure request's own `outcome` is set by the handler to the fixed `ANONYMISED` (the command's free text is ignored for ERASURE). Needs `GRANT UPDATE (notes) ON customers.data_subject_request` (migration). The request rows themselves stay: a controller must be able to show it handled the request.
   - **Keep reasons out of the audit `after` map:** ChangePhone and DeactivateCustomer pass `reason` as the `AuditFacade.record(..., reason)` argument, as every other M7 handler does. Existing audit rows are retained (insert-only, retention).
   - **Value guard at write:** a `PersonalDataText.require(value, field)` in `CustomerGuards` refusing `m7.field.personal_data` (new i18n) when a reason, reference, note or outcome contains a Sri Lankan phone pattern (`0\d{9}`, `\+94\d{9}`, with spaces and dashes stripped) or a NIC pattern (`\d{9}[VX]`, `\d{12}`), applied in AmendAccountLimits, ChangeAccountStatus, RequestAdjustment, ChangePhone, DeactivateCustomer, RecordCustomerPayment (reference), ReverseCustomerPayment, RecordDataSubjectRequest (notes) and Fulfil (outcome). Names cannot be detected and are accepted as the retained-records limitation.
   - **Write down what erasure leaves** in the module README (ledger postings with account number and receipt numbers, CPRs with their reference, account history and adjustment reasons, kernel audit rows, the DSAR records with coded outcomes, the customer stub row) with the legal basis (L-05: accounting retention and legal claims) and the retention period `customers.retention_years` (doc 27 section 7, not seeded today; seed it as a FEDERATION item, default 7, even though the purge job is deferred to D-02).
   A privacy scan test after erasure over the module's text columns (`notes`, `outcome`, `reason`, `reference`) asserting no phone or NIC pattern, and no name of the erased fixture customer, catches regressions.

3. **Best solution.** As amended. Rejected: granting UPDATE on `account_history.reason`, `account_adjustment.reason` and `doc_customer_payment.reference` to redact them; the first two are the society's record of its own decisions and the third is on an issued document; all three are within the accounting exemption, and opening UPDATE on them weakens the insert-only guarantee that the whole system rests on. Also rejected: coded reasons only (a counter needs free text).

   **Decision to record:** the retention position above, as the engineering reading of L-05 pending Federation legal; the architect can accept it on delegation because it changes no document's wording, only records what the build already does.

   **Verdict: DECIDE THEN BUILD. Size M. Migration yes (one GRANT). Risk low.**

### M7CR-11

1. **Valid: YES.** `PrivacyQueriesImpl.accessExport` filters on kind, status and not-anonymised only; `requireResponsibleOfficer` runs in the fulfil handler, not on the download; the download is not audited; `PrivacyExporter` does `select *` on `customers.customer`, so `nic_hash` and `nic_last4` go out; the export is rebuilt, so `export_sha256` matches only until anything changes. MFA does apply to the GET (`requires_mfa` on `cus.privacy.fulfil`).

2. **Suggested fix: CORRECT, with a shape change.** A read cannot audit here: `PrivacyQueriesImpl` is `@Transactional(readOnly = true)` and `ArchitectureTests` lets only a `@CommandHandler` write. Make the download a command: `POST /v1/privacy/requests/{id}/export` (Idempotency-Key as every POST; `x-permission: cus.privacy.fulfil`) handled by `DownloadAccessExportHandler`, whose guards are the request's society, FULFILLED, kind ACCESS, customer not anonymised, `requireResponsibleOfficer`; it audits `DSAR_EXPORT_DOWNLOADED` (request id, whether the recomputed hash equals `export_sha256`) and returns the bytes; the controller streams them. Remove `nic_hash` from the export (`select` the columns by name); keep `nic_last4` (the customer's own data). On the hash: either store the export at fulfilment (27A `export_object_key`, kernel attachment store, a migration) so the hash identifies what was handed over, or document that `export_sha256` is "the hash of the export at fulfilment" and the download reports whether it still matches. Recommend the second now and the first with the kernel attachment API when the privacy screens are revisited; the audit row with `matchesFulfilment` already gives the officer the fact.

3. **Best solution.** As amended; doc 27 9.3 ("every read of a customer record outside the sales path is audited") decides the audit without a new decision.

   **Verdict: BUILD AMENDED. Size S. Migration no. Risk low** (an OpenAPI change: the GET becomes a POST; the web privacy page follows).

---

## Fix group E: snapshot change log (M7CR-14)

### M7CR-14

1. **Valid: YES.** `SnapshotBuilder.deltaTables` builds the delta from `kernel.change_log` only (:122-171); no class in m1 to m9 calls `ChangeLog.append` (grep: only kernel classes and two READMEs). A suspension, close, limit change, balance change or erasure reaches a till at its next full snapshot. The README records the deviation; the consequences (five shops each extending the full limit on a stale balance; an erased name staying on every till; a closed account taking charges, 04) are real once tills run delta sync.

2. **Suggested fix: CORRECT, with the mechanism named.** The kernel API exists and is callable by a module (`lk.coopfed.knoweb.kernel.api.ChangeLog`, `kernel.change_log_append` granted to `app_rw`, called through `queryForObject`, which the write rule permits). Two constraints shape the design: (a) `append` needs every shop of the society as a `Target`, and PostAccountTender runs in the till's shop-scoped consumer scope, where `PartyQueries.listLocations` sees only that shop; (b) 19A says a producer that fans out many rows does so "from a worker consumer of its own event, not on the hot path". So do not call `append` inside the posting handlers. Add `CustomerChangeLogFanOut`, an `@EventConsumer` (consumer `m7.snapshot`) on M7's own events (`customer.updated/phone_changed/deactivated/anonymised.v1`, `account.opened/limit_amended/suspended/reinstated/closed/reopened.v1`, and later `account.charged/credited.v1`, `customer_payment.recorded/reversed.v1`, `account.adjusted.v1`), running in the entity-wide system scope the dispatcher gives a central event, listing the society's shops and calling `ChangeLog.append` with `Change.upsert("customer", customerId)`, `urgent = true` for suspended, closed, reopened, deactivated and anonymised. Always UPSERT: when the contributor no longer returns the row (closed, anonymised, inactive), `SnapshotBuilder` emits the tombstone itself (:150-151). The 5-minute coalescing 27A asks for is not in the kernel API; a row per charge per shop is acceptable volume at 30 days' retention (`SnapshotBuilder.lastChangePerRow` dedupes per delta), so skip coalescing and note it.

3. **Best solution.** As amended. Do M7 alone, ahead of M1 and M2: it is a module-local consumer, no kernel change, and it closes the worst consequences of 04, 05 and 09. States and identity changes first (S), balance changes second (S); platform pair to confirm the consumer-scope reading of `EventConsumerDispatcher.systemScope` (location null for a central event, so `listLocations` lists the society's shops) before work starts.

   **Verdict: BUILD AMENDED. Size M. Migration no. Risk low** (a wrong target set means a till misses a delta until its next full snapshot, which is today's behaviour).

---

## Fix group F: cross-society identity (M7CR-13)

### M7CR-13

1. **Valid: YES.** Society B cannot see A's customer until B holds an account (`account_holder_read`), OpenAccount needs `customerStatus(...) for update` (needs `own_update`, which only A has), the CREDIT_ACCOUNT consent count runs under RLS, and registering the same phone at B answers `phone_held_elsewhere`. The `account_holder_read` policies are dormant. ADR-15, doc 27 3.1/3.3/9.5 and 27A's assumption all intend the cross-society account; 27A's own "if different" column names the alternative: "Restrict to the registering MPCS; second MPCS registers a duplicate identity". The gap is in a test comment and not in the README or `docs/progress/deviations`.

2. **Suggested fix: CORRECT as a pair of options**; the first is bigger than stated. The path without a definer writer exists: a SECURITY DEFINER `identity_for_account_opening(phone)` returning id, status, a masked name and whether a NIC is held (OWN scope only); B inserts its CREDIT_ACCOUNT consent and its account row (the `customer` foreign key is checked as the table owner, so B may insert a row for a customer it cannot read); `account_holder_read` then applies; the NIC check goes through `nic_holders` (15). Plus a web step and tests: L, a migration, and a privacy question.

3. **Best solution.** Alternatives: (a) build the path now; (b) record "one person, one society" as a v1 deviation, keep the dormant policies (harmless, and the design's end state), build 15 so B is told "identity held elsewhere" and the federation never gets two identities for one person, and raise a CR with the sketch above for after go-live; (c) drop `account_holder_read`. Recommend (b). Reasons: the joint-controller agreement (ADR-26, L-02) that gives B a lawful basis to read A's member's identity is "not yet commissioned" (DECISIONS_PENDING section 5); a second credit line at a non-home society is an edge case against the demo priority (fed/distributor trading, MPCS, shops first); and (b) costs a paragraph in the README, a deviation file and 15, which is needed anyway. The member at B's counter is told to use their own society or to ask it for a transfer, which is how societies work today.

   **Decision to record:** "In v1 a person holds credit at one society. `account_holder_read` stays for the designed end state. `nic_holders` and `phone_holders` answer 'held elsewhere' without identifying the holder. The cross-society opening path is a CR against 27A, to be built after L-02 is signed."

   **Verdict: DECIDE THEN BUILD (the deviation record and 15 now). Size S now; L later. Migration no now. Risk none.**

---

## Decisions to record

Each is written so it can be recorded as "accepted on the architect's delegation"; none is treated as accepted here.

1. **NIC pepper (M7CR-01).** Decision: `nic_hash` is `HMAC-SHA-256(pepper, SHA-256("nic:" + canonical NIC))`, hex; the pepper is the application property `coop-erp.customers.nic-pepper`, supplied by the environment or secret store, never a `ConfigRegistry` item, with no default outside a development issuer (the `IdempotencyFilter` rule); `nic_key_id` on the row names the key; ordinary rotation re-captures at the next visit (RecaptureNic), emergency rotation chains the stored value under the new key in a job. Legacy rows are re-keyed as `HMAC(k1, existing)` by a job through `customers.rekey_nic`. Four last characters are kept (L-06 stands). Rejected: per-row salt (breaks the equality lookup and does not help against a 7.3e5 search); tokenisation (stores something recoverable the system never needs to read); fewer than four characters (breaks the confirmation step without a privacy gain once the hash is keyed).
2. **REOPEN (M7CR-04).** Decision: `ChangeAccountStatus` gains `REOPEN`, CLOSED → SUSPENDED, reason, `cus.account.manage`, no MFA, history `REOPENED`, audit `ACCOUNT_REOPENED`, event `account.reopened.v1`; CR to doc 27 4.2 and 27A 6. Rejected: settling on a CLOSED account (implicit state); automatic reopening by a till fact (central posting would change a state).
3. **Credits settle charges (M7CR-08).** Decision: CREDIT and negative ADJUSTMENT postings allocate through `allocation` rows (CREDIT: the open charge of the same document first, then oldest first; ADJUSTMENT: oldest first); a reversal re-applies the account's unallocated credit oldest first; `unallocated` is defined over the whole credit side; the ledger property "Σ open − unallocated = balance" is tested. CR to 27A 6.3. Rejected: leaving credits unallocated (false ageing drives suspensions).
4. **Erasure guard (M7CR-09).** Decision: erasure requires every account of the customer at every society CLOSED at zero balance, locked for the transaction through a SECURITY DEFINER function, none closed within `customers.erasure_wait_days` (FEDERATION item, default 3); a till fact that lands afterwards is posted and flagged; the account is settled through REOPEN. CR to 27A 6.4; add the sentence to the L-05 wording.
5. **What erasure leaves (M7CR-10).** Decision: the identity and the module's own free text about the person are erased or redacted; postings, allocations, CPRs (including `reference`), account history and adjustment reasons, kernel audit rows and the DSAR records (with coded outcomes) are retained for `customers.retention_years` (seed, default 7, L-05) under the accounting and legal-claims exemptions; officer free text is guarded at write against phone and NIC values; reasons travel in the audit `reason` field, never in `after`. No UPDATE grant is added to history, adjustment or document columns.
6. **One society per person in v1 (M7CR-13).** As written under group F.
7. **Change log from M7 alone (M7CR-14).** Decision: M7 feeds the change log from a consumer of its own events now, states urgent, balances non-urgent, without coalescing; M1 and M2 follow their own tickets. Platform pair confirms the consumer scope only.
8. **Access export download (M7CR-11).** Decision: the download is a command (POST), officer-only, audited; `nic_hash` leaves the export; `export_sha256` means "hash at fulfilment" and the download audit says whether it still matches; storing the export under an object key waits for the kernel attachment API.

## Findings I could not settle

- **M7CR-07, the gateway's content hash.** Whether a till re-sending one receipt under a new event id is refused by the sync gateway's bundle content hash (README decision 8 mentions it for CPRs) decides whether the race is reachable at all. Settled by reading `kernel/internal/sync` `EventApplier` for `receipt.issued.v1`, or by the kernel reviewer. The reorder is right either way.
- **M7CR-14, the consumer scope of a central event.** `EventConsumerDispatcher.systemScope` builds `new Scope(ownerEntityId, message.locationId())`; if a central event's `locationId` is non-null for events published inside a shop-scoped transaction (the PostAccountTender consumer runs shop-scoped, so its `account.charged.v1` may carry the shop), `listLocations` would see one shop. Settled by reading `OutboxWriter` for how `location_id` is set on a published event, or by a Testcontainers test of the fan-out from a shop-scoped charge. If it is set, the fan-out consumer lists shops through a small SECURITY DEFINER `customers.shops_of(entity)` or an entity-wide `PartyQueries` call the platform pair provides.
- **M7CR-01, Flyway Java migrations.** Whether the repository's Flyway configuration picks up Java migrations was not checked; the recommendation uses a job plus a SQL function regardless, which needs no answer.
- **M7CR-10, the kernel audit `reason` column under retention.** Whether the kernel plans any redaction or retention handling of `kernel.audit_log.reason` is unknown; the recommendation does not depend on it (the value guard stops phone and NIC values at write; names are accepted as retained).
- **The reproduction tests.** The verifier's `Wave2M7VerifyIntegrationTest` compiles but has not run; run it before the fixes are written, and once more after, when Docker is back.
