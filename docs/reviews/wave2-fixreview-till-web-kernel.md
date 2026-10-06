# Wave 2 fix review: till, web, kernel (TWK-01..30)

Fix reviewer: Claude Fable 5.1, 6 October 2026. Read-only, against `main` 91e550d8 (nothing run; Docker down). Input: `docs/reviews/wave2-till-web-kernel.md` (30 live findings, none dropped) and the brief `.handover/wave2-fixreview-brief.md`. Every cited file was re-read; the design documents consulted are named per finding (docs 19, 19A, 26, 26A, 29, 30, 31, 32, CR-30-1, `docs/DECISIONS_PENDING.md`, the kernel migration README).

Two things that frame many verdicts:

- **Kernel migrations now start at `V0084`.** The demo server (#248) has applied up to `V0083`; the lane ranges of the kernel README no longer apply ("once a deployed environment exists, a migration is only added above the highest version applied"). Nothing below is a change to a merged migration.
- **`app_rw` has no DELETE on any kernel table and `SchemaRulesIntegrationTest` fails a DELETE grant.** Any "purge job" (TWK-26) is therefore a `SECURITY DEFINER` function in a migration, as `kernel.change_log_purge` (V0082) is, or a column mark, never a plain `DELETE`.

## Summary

| Id | Sev | Valid? | Fix verdict | Size | Migration |
|---|---|---|---|---|---|
| TWK-01 | high | YES | BUILD AMENDED (till checks; no manifest change; drop the "previous-version hash") | S | no |
| TWK-02 | medium | YES | DECIDE THEN BUILD (https outside local hosts; TOFU key stays, no installer pinning) | S | no |
| TWK-03 | medium | YES | BUILD AMENDED (persist; audit the lock-out; do not count an unreadable hash) | S | no (till `setting` rows) |
| TWK-04 | medium | YES | BUILD AMENDED (explicit opt-in flag, off by default; never with an operator row; no contract field) | S | no |
| TWK-05 | medium | YES | BUILD AMENDED (two PRs: outcomes and retention; instructions, REVOKE and 409 recovery) | M | till schema only |
| TWK-06 | medium | PLAUSIBLE | BUILD AS SUGGESTED | S | no |
| TWK-07 | medium | YES | BUILD AMENDED (refresh offset from heartbeats, clear on a clock jump, cap; supervised downward date) | M | no |
| TWK-08 | medium | YES | BUILD AS SUGGESTED (no operations decision needed: doc 32 section 8 already covers a lost outbox) | S | no |
| TWK-09 | low | YES, and wider than reported | DECIDE THEN BUILD (CR-30-1 point 4 contradicts doc 31 section 6: facts are always accepted) | S | no |
| TWK-10 | low | YES | BUILD AMENDED (bounds; an unreadable hash is "ask the office", not a wrong PIN) | S | no |
| TWK-11 | high | YES | BUILD AS SUGGESTED (initials; grapheme-safe) | S | no |
| TWK-12 | medium | YES | BUILD AS SUGGESTED | S | no |
| TWK-13 | medium | YES | BUILD AMENDED (web now; backend rule "RETAIL price > 0" recorded as a recommended decision) | S | no |
| TWK-14 | medium | YES | BUILD AS SUGGESTED (the shell's own convention) | S | no |
| TWK-15 | medium | YES | BUILD AS SUGGESTED (ids now; the JSX literal scan with TWK-18) | S | no |
| TWK-16 | low | YES | BUILD AMENDED (URL must start with apiBase; keep a body only when its keys pass the kernel's forbidden-word rule) | S | no |
| TWK-17 | low | YES | BUILD AS SUGGESTED | S | no |
| TWK-18 | low | YES | BUILD AMENDED (globs and scans now; Caddy headers and admin path with the deploy area; ACR value stays a demo choice) | S-M | no |
| TWK-19 | medium | YES | BUILD AS SUGGESTED | S | no |
| TWK-20 | medium | YES | BUILD AMENDED (HMAC with a config key; `hash_key_id` column; old rows age out, no re-key) | S-M | yes (V0084) |
| TWK-21 | medium | YES | BUILD AS SUGGESTED | S | no |
| TWK-22 | medium | YES (scenario one) | DECIDE THEN BUILD (defer to the window's end; recipient's entity when known) | S-M | no |
| TWK-23 | low | YES (latent) | BUILD AMENDED (location from the envelope now; the M6 close fact DEFERRED to M6's SessionHook ticket) | S | no |
| TWK-24 | medium | YES | BUILD AMENDED (bound and count every sync body; the till gzips because doc 32 already says so, no CR) | S+S | no |
| TWK-25 | medium | YES | BUILD AMENDED (a request bucket on every device operation now; the snapshot file stays K-08-F2) | S | no |
| TWK-26 | medium | PARTLY | DECIDE THEN BUILD (redact forbidden values only; resolve-then-purge, not time-based purge, because S4 says "never lost") | S + M | yes (V0085) |
| TWK-27 | low | YES | BUILD AS SUGGESTED | S | no |
| TWK-28 | low | YES (latent) | BUILD AMENDED (render semaphore now; presign predicate and sandbox DEFERRED) | S | no |
| TWK-29 | low | YES | BUILD AS SUGGESTED | S | no |
| TWK-30 | low | YES (mechanism) | DECIDE THEN BUILD (an `x-federation-view: false` mark in the slices; decide with RLS-02) | S-M | no |

Counts: BUILD AS SUGGESTED 11, BUILD AMENDED 13, DECIDE THEN BUILD 6, DEFER 0 (deferrals are inside amended verdicts), INVALID 0. No suggested fix is WRONG; several are incomplete or add a contract change that is not needed.

---

## Fix group A: what the till trusts from central (TWK-01, TWK-02, TWK-10)

One PR in `till/core` and `till/sync`, after decision D-1 (below). Order: TWK-01, then TWK-02, then TWK-10. No central change, no migration, no change request to doc 32.

### TWK-01 Snapshot freshness and continuity

1. **Valid: YES.** `SnapshotVerifier.verify` checks signature, `version`, `location_id`, `full` and table hashes (lines 36-52). `TillService.refreshSnapshot` applies whenever `delta.version != current.version` (line 174), so a validly signed older delta sets the version backwards, and a delta whose signed `since` is not the version the till holds is applied over the live map by `SnapshotState.apply` (a plain overlay), silently skipping the versions between. The manifest already signs `since` and `version` (`SnapshotManifest.manifest`, lines 70-74), so both checks need nothing new from central.
2. **Suggested fix: NEEDS CHANGES.** The till checks are right: refuse `delta.version < current.version`; for a non-full delta require `manifest.since == current.version`, else request `since=0`; a full snapshot may carry any `since`. Two parts of the suggestion should not be built:
   - *A previous-version hash in the manifest* does not fit how deltas are made. `SnapshotBuilder.build` computes the delta from the `since` the caller sent to the current version at request time; two tills at versions 8 and 9 receive two different manifests for version 10. There is no "previous manifest" to chain to. Drop it.
   - *An issue time in the manifest* protects against a replayed stale-but-newer delta only if the till also refuses manifests older than an age, and an on-path attacker who can replay can also withhold, which no manifest field prevents. Doc 32 section 5.2 already has the freshness control: "a till whose snapshot is older than a configured age (default 3 days) warns the supervisor", `pos.snapshot_staleness_days` (doc 26 section 7). The till does not implement that warning today. Build it from the till's own clock (`LAST_SNAPSHOT_AT` setting, written on a successful verified apply) rather than from a manifest field.
   - Comparing the answer's `key_id` with `signingKeyId` from enrolment is harmless and gives a clearer refusal than "not signed by central"; include it.
3. **Business view.** Alternatives: (a) till checks only (recommended); (b) add `issued_at` to the signed manifest as an additive field (a doc 32 section 5.2 and `sync.yaml` change, backward compatible, old tills ignore it) and refuse manifests older than the staleness age; (c) a manifest chain (rejected above). (a) removes the version regression and the gap, which are the data-integrity part of the finding, and works offline for two days because it adds no round trip. (b) is a small change request if the architect wants a signed time as well; it is not needed to close the finding. **Doc 32 needs no change request for the recommended fix.**

**Verdict: BUILD AMENDED. Size S. Migration: no. Risk: low** (a till already behind by more than the retention gets `full=true` from central and passes the new check; a test against `SnapshotVerificationTest` fixtures covers the three refusals).

### TWK-02 No https at enrolment

1. **Valid: YES.** `DesktopConfig.enrolDefaults` defaults to `http://localhost:8080`; `HttpCentral.enrol` posts to whatever scheme was typed; `TillService.enrol` stores `token_endpoint` and `signing_key` from the answer as given (lines 135-139). Doc 32 section 9 says "TLS 1.2 or later to the edge"; the till does not enforce it.
2. **Suggested fix: NEEDS CHANGES.** The scheme rule is right: refuse a server URL that is not `https://` unless the host is `localhost`, `127.0.0.1`, `[::1]`, or ends in `.localhost`; apply it in `TillService.enrol` (a guard, so every platform gets it) and in `DeviceIdentity` loading at start (an identity enrolled before the rule is refused with a clear message, so the trial databases need a re-enrol). Same-host rule for the token endpoint: the enrolment answer's `token_endpoint` must share the server URL's host, *or* the operator set `COOP_TILL_TOKEN_ENDPOINT` explicitly (an explicit local setting is the operator's choice, not the network's). Add `COOP_TILL_CA_FILE` for a demo server on Caddy's internal CA: a trusted-CA file for the Ktor engine, never a "trust all" switch. Pinning the signing key in the installer is not recommended (below).
3. **Business view.** The enrolment answer is trusted on first use over TLS, bound to a one-time 80-bit code the shop's manager issued minutes earlier. Alternatives: (a) https enforced, TOFU stays (recommended); (b) ship the signing key fingerprint in the installer: every key rotation then needs a new installer on 4,000 PCs before any snapshot verifies, and an installer is unsigned today (TWK-09), so the pin is only as trustworthy as the download; (c) certificate pinning of central: breaks on every certificate renewal (Let's Encrypt, 90 days). (a) matches doc 32 section 9 and doc 19 section 2.1 (TLS to the edge, OAuth2 client credentials per device) and needs **no change request**.

**Verdict: DECIDE THEN BUILD (decision D-1). Size S. Migration: no. Risk: low**; the opt-in test `DesktopStackTest` must run against `https://` or a `.localhost` name.

### TWK-10 Argon2 parameters trusted from the PHC string

1. **Valid: YES.** `Argon2PinVerifier.verify` parses `m`, `t`, `p` with `toInt()` and `getValue` (lines 41, 49-51); a malformed string throws out of `TillService.signIn`.
2. **Suggested fix: NEEDS CHANGES.** Bound the parameters (memory 8 MB to 1 GB, iterations 1 to 100, parallelism 1 to 16; 19A section 2 says m=64 MB, t=3, p=2) and catch the parse errors, but do not report them as "Wrong PIN": a wrong PIN counts toward the lock-out (TWK-03) and tells the cashier they mistyped, whereas a hash the till cannot read is central's or the snapshot's fault. Return a distinct refusal ("this operator's PIN record cannot be read; ask the office") that does not count. A per-device pepper is impossible with one snapshot per shop; a longer PIN rule is 26A's (4 to 8 digits in the screen today) and stays as designed.
3. **Business view.** Offline brute force of PIN hashes on a stolen PC is inherent to doc 26's design and bounded by Argon2id cost; the real control is the key vault (TWK-08) and short-lived operator assignments. Nothing to decide.

**Verdict: BUILD AMENDED. Size S. Migration: no. Risk: none.**

---

## Fix group B: what the till does with central's answers (TWK-05, TWK-09)

Two till PRs plus one small backend PR, after decision D-2. Order: B1 outcomes and retention, B2 instructions and recovery, B3 backend floor. Doc 32 is the specification here; the till deviates from it, so no change request for B1 and B2. B3 is a documented contradiction between doc 31 and CR-30-1.

### TWK-05 Acknowledgement dropped to one field; outbox deleted at once

1. **Valid: YES.** `HttpCentral.upload` keeps three fields of the ack; `TillService.syncOnce` calls `store.acknowledgeUpTo`, which is `deleteOutboxUpTo` (`SqlTillStore` line 59, `Till.sq` line 66). Central does return outcomes and instructions (`SyncController.uploadBatch`, lines 188-195; `DeviceAuth.revoke` on 403). Doc 32 section 3.4 ("retains them for the retention window, S8, DR-3: 7 days"), section 7 (RESEND_FROM "is why retention is not zero") and 26A section 6 (`noteOutcomes`, `handleInstructions`, `outbox.purge(ackedBefore = now - 7.days)`) all say what the till should do; it does none of it.
2. **Suggested fix: CORRECT in direction, split as follows.**
   - **B1 (outcomes, retention).** `outbox` gains `acked_at INTEGER` (SQLDelight schema; the trial databases are re-created, a `.sqm` migration is not worth writing before a pilot); `pendingOutbox` reads `acked_at IS NULL`; `acknowledgeUpTo` sets `acked_at`; a purge on start and after each sync removes rows acked longer ago than `sync.outbox.retention_days` (a till setting, default 7, overwritten from the snapshot's `config` table when central sends one; a hard-coded 7 breaks the "no hard-coded window" rule). A local `anomaly` table (`device_seq`, `event_id`, `reason`, `detail`, `noted_at`, `seen`) takes every QUARANTINED outcome; the status bar shows the unseen count; the Z-report prints "N facts refused by central, see the office". Keep the acknowledged receipt visible as issued: a quarantined sale is still a sale on paper and in the shop's cash.
   - **B2 (instructions and refusals).** `RESEND_FROM(seq)`: clear `acked_at` for rows `>= seq` that are still retained; if the oldest needed row is purged, record an anomaly ("central asked for sequences the till no longer holds") and stop. `FLOOR_NOTICE`: banner for the supervisor. `409 sync.sequence_gap` with `params.expected_seq`: if `expected_seq <= last acknowledged + 1`, resend from it (retained rows); otherwise anomaly and stop (26A pseudo-code). `403` with `params.revoke`: verify the Ed25519 signature over `DeviceAuth.canonical(type, device_id, status, issued_at)` with the enrolled key, require `device_id` equal to the till's and `issued_at` after the enrolment time; then lock the UI, delete the snapshot rows, keep `setting`, `receipt`, `till_session` and `outbox`. An unsigned or mismatched revoke is logged and ignored (the design signs it precisely so that a forged one cannot take a shop off the air).
   - FLAGGED is listed in doc 32 section 3.4 but `BatchIngestor` says a flag reaches the device later through what the module publishes, and no channel to the till exists for it. Do not invent one here; the back office sees flags. Note it in `till/README.md` deviations.
3. **Business view.** The one open question is what a revoked till does with its unsent receipts. Doc 32 section 9 says "preserved for recovery by an administrator" and section 8 has counter transfer and sequence reset, but no procedure to get rows out of a locked PC. The smallest procedure that works with what central already has: the revoked till stays locked until the administrator reinstates the device (M1 status back to ACTIVE) or issues a new enrolment code for the same device id; the till then resumes uploading its kept outbox from `last acknowledged + 1`, since the cursor at central is unchanged. For a device that is never reinstated, counter transfer records the unreceived numbers as a documented gap (section 8). Recorded as decision D-3.

**Verdict: BUILD AMENDED. Size M. Migration: till schema only, no central migration. Risk: moderate** (the retention changes `pendingCount` and the `OfflineQueueTest` fakes; the REVOKE path must be tested with a signed fixture from `TillSigner`'s format, as `TillScreensTest` already does for snapshots).

### TWK-09 Version floor refuses uploads before an updater exists

1. **Valid: YES, and the finding understates it.** `BatchIngestor.checkFloor` answers 426 after the grace (doc 31 section 6, "sync is refused"). But **CR-30-1 point 4, accepted by the architect on 28 September 2026, says: "Below the minimum version, central stops sending new snapshots, but facts are always accepted: a sale that happened is a fact."** That is also S4 of doc 32 and the AGENTS.md rule. Doc 31 section 6 and doc 32 section 3.3 step 1 are the older text that CR-30-1 says must be re-issued. The code follows the superseded text.
2. **Suggested fix: NEEDS CHANGES.** "Make a breach a flag until the updater exists" is half right; the accepted decision is permanent, not an interim. Build: after the grace, `uploadBatch` is still accepted and the ack carries `FLOOR_NOTICE` (it already does within the grace); `getSnapshot` (and `listChanges`) answer `426 sync.app_below_floor` after the grace, so the till keeps selling on its last snapshot and the staleness warning (TWK-01) tells the supervisor. The contract tests for 426 move from the batch path to the snapshot path. `m1.device.version_floor` (M1's assignment guard) is untouched. Operations: do not raise `sync.app_version_floor` above a shipped version until the updater of CR-30-1 exists; say so in `infra/deploy/README.md`.
3. **Business view.** A shop that cannot update for three weeks (doc 31: "has a bigger problem than its app version") must still get its sales into the books; withholding reference data is the pressure, refusing facts is a loss. No alternative is consistent with CR-30-1.

**Verdict: DECIDE THEN BUILD (decision D-2; the decision is already taken by CR-30-1, what is needed is to record that the code follows it and that docs 31 section 6 and 32 section 3.3 are to be re-issued). Size S. Migration: no. Risk: low.**

---

## Fix group C: till durability, clock and sign-in (TWK-03, TWK-04, TWK-06, TWK-07, TWK-08)

One or two till PRs, independent of groups A and B except that TWK-03 and TWK-10 both touch `signIn`. Order: TWK-06 and TWK-08 (one-liners), TWK-03, TWK-04, TWK-07. Decision D-4 before TWK-07's business-date part.

### TWK-03 PIN lock-out in memory

1. **Valid: YES.** `failedPins` and `lockedUntil` are fields of `TillService` (lines 95-96); `start()` does not restore them; the counter is global, not per operator.
2. **Suggested fix: CORRECT, with two additions.** Persist `pin_failures` and `pin_locked_until` in `setting` inside one transaction with the failure; compare with `now()` (the corrected clock) consistently on both sides; 26A section 8 says "5 failures -> 15-min lockout (local + audit)", so also append an `audit.*` fact to the outbox (type code from the catalogue the kernel accepts offline, `deviceAudit.offlineCapturable`) so central sees the lock-out. Keep the counter per till, not per operator: the attack is at the till, and a per-operator counter lets an attacker rotate operators.
3. **Business view.** No alternative worth the time; the policy of 26A stands.

**Verdict: BUILD AMENDED. Size S. Migration: no.** Risk: none.

### TWK-04 Trial cashier offered whenever the operator table is empty

1. **Valid: YES.** `TillApp.SignInScreen` shows the button when `c.operators.isEmpty()` (line 166); `signInTrialCashier` has no guard. The deviation is recorded (`docs/progress/deviations/2026-09-29-till-desktop-trial.md`) but nothing enforces "a pilot till never offers it".
2. **Suggested fix: NEEDS CHANGES.** A central `trial=true` field in the enrolment answer puts a trial-only switch into the sync contract for good; a build flag means a second build. Use neither: an explicit environment opt-in `COOP_TILL_TRIAL_CASHIER=true` (the `devEnrolmentCode` helper writes it into `enrol-prefill.properties`, as it writes the price book), off by default, and `signInTrialCashier` refuses when the flag is off *or* the snapshot has ever carried an operator row (a `setting` `operators_seen=true` written on the first non-empty operator table). The screen text changes to say the till has no operator and the office must assign one. Every trial receipt carries an operator id M1 does not know; that is already the case and is what makes the flag a trial-only thing.
3. **Business view.** The right end state is the M1 operator snapshot contributor with PIN hashes (doc 32 section 5.1, M1) so that no shop ever needs the stand-in; that is a module ticket, not this fix.

**Verdict: BUILD AMENDED. Size S. Migration: no. Risk: none.** No contract change.

### TWK-06 WAL without `synchronous=FULL`

1. **Valid: PLAUSIBLE, as the verifier says.** `EncryptedJvmDatabase.open` sets `journal_mode=WAL` and nothing else (lines 28-29). Whether sqlite-jdbc-crypt 3.53.2.0 compiles with `SQLITE_DEFAULT_WAL_SYNCHRONOUS=1` could not be checked. Note that `receipt.number UNIQUE` does not prevent the scenario: the receipt row and the counter are lost together, so the next sale reuses the number and inserts cleanly while the paper copy of the lost one exists.
2. **Suggested fix: CORRECT.** `properties["synchronous"] = "FULL"` (sqlite-jdbc applies pragma properties); a start-up check that `max(receipt.number) < NEXT_RECEIPT_NUMBER` and `max(outbox.device_seq) < NEXT_DEVICE_SEQ`, repairing the counter upward and writing a local anomaly (group B1's table) when it is not. The sale transaction is a few rows, so FULL costs nothing a cashier notices.
3. **Business view.** Power cuts are the design case (doc 32 conformance test "power loss on the till during issuance"). Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

### TWK-07 Clock offset never cleared; business date only forward

1. **Valid: YES.** `now()` adds `CLOCK_OFFSET_MS` for ever (lines 439-442); only an ack refreshes it, the heartbeat's `clock_offset_ms` is ignored; `openBusinessDay` writes only when `today > current` (line 451).
2. **Suggested fix: NEEDS CHANGES.** Three parts:
   - *Refresh.* Store the offset from every heartbeat answer too (`HeartbeatResponse.clockOffsetMs` is already returned; `TillService.heartbeat` reads only `snapshot_version`). The upload loop runs every 20 seconds, so a corrected PC clock is re-measured within a minute when online.
   - *Clear on a jump.* Store the raw clock reading beside the offset (`CLOCK_OFFSET_AT`). On every `now()` call, if the raw clock is before that reading, or more than a bound (configuration; say 1 hour plus the time since the reading) after it, the clock was changed by hand: drop the offset, write an anomaly, and let the next heartbeat measure again. A monotonic uptime source (doc 26 section 3.10, "device clock plus monotonic uptime") would be exact but needs a new `TillClock` port method per platform; the jump rule is enough for the trial.
   - *Cap.* Refuse to apply an offset over a bound (configuration, default 7 days): keep the raw clock, write an anomaly. Central already raises CLOCK_DRIFT past 10 minutes (`HeartbeatService`).
   - *Business date.* Allow a downward correction only when no session is open and a supervisor signs it; issued receipts and sessions keep the date they carry (immutable). Guard the upward move too: refuse to open a business date more than one day after the date of central's last `server_time` (stored from the ack or heartbeat) when the till has synced in the last 48 hours; offline longer than that, the clock is what there is.
3. **Business view.** Doc 26 section 3.5 and doc 19 section 9 make the business date forward-only *at central* (day-close is a kernel event). A till's local date is its own cache of that; correcting a wrong cache under supervision is not a business-date reversal at central, and the receipts already dated keep their dates. Recorded as decision D-4.

**Verdict: BUILD AMENDED. Size M. Migration: no.** Risk: moderate for the jump rule (a false positive drops a correct offset until the next heartbeat; acceptable).

### TWK-08 Key file not atomic; a missing key silently makes a new one

1. **Valid: YES.** `PrivateFileKeyVault`: `createFile` then `writeString` (lines 45-47); an empty file reads as "" and fails `require(raw.size == 32)` for ever. `WindowsDpapiKeyVault.databaseKey` writes with `Files.write` and generates a key whenever the file is missing, with no look at `till.db`. No DPAPI entropy.
2. **Suggested fix: CORRECT.** Temp file plus `ATOMIC_MOVE`; refuse to create a key when `till.db` exists (fail with "the database key for this till is missing; the office must re-enrol this PC"); treat an empty or short key file as corrupt with the same message; pass an application constant as DPAPI entropy (it stops another program of the same user from unsealing by accident, not on purpose; say so in the comment).
3. **Business view.** The operations question the finding flags ("does a lost key mean re-enrolment with a documented outbox loss?") is already answered by doc 32 section 8: "Till outbox lost: quarantine, ALERT; the document is not applied until the till resends a correct copy through the recovery procedure" and the audited sequence reset (built, `SequenceReset`). The fix only makes the loss visible instead of silent.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

---

## Fix group D: the web shell (TWK-11, TWK-12, TWK-15, TWK-18 guards)

One web PR. Order: TWK-11, TWK-15, TWK-12, then the guards of TWK-18 (so that the shell's new code is scanned).

### TWK-11 Display name sent to ui-avatars.com

1. **Valid: YES.** `UserMenu.tsx` lines 47 and 74 build the URL by hand with `replace(' ', '+')` (first space only, no encoding).
2. **Suggested fix: CORRECT.** A `<span aria-hidden>` with initials, escaped by React; delete both URLs. Take the initials as the first *grapheme* of the first two words (`Intl.Segmenter` with `granularity: "grapheme"`, which every supported browser has), not the first code point: a Sinhala or Tamil name's first letter is a consonant plus a vowel sign and the code point alone shows a different letter. Give the circle its colours from tokens (`var(--color-...)`), which also removes the literals TWK-18 names.
3. **Business view.** Staff names are personal data and shops run on poor links; nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

### TWK-12 Browser-local calendar in three helpers; four copies of `businessToday`

1. **Valid: YES.** `isoToday` (priceListState.ts 68), `reportView.ts`, `integrationView.ts` use the browser zone; `businessToday` lives in `m1party/relationshipView.ts`, `m4trading/tradingView.ts` and `m7customers/customersView.ts`.
2. **Suggested fix: CORRECT.** One `businessToday(now?)` in `shell/i18n/formats.ts` next to the formatters; delete the four copies and `isoToday`; `priceListState.test.ts` lines 91-92 change (they assert local-zone behaviour). The lint rule belongs with the TWK-18 guards: extend `moduleFormats.test.ts` to `.ts` files and to the pattern `new Date()` followed by `getFullYear|getMonth|getDate|toISOString().slice`.
3. **Business view.** A control price effective "today" must mean today in Colombo for every manager, wherever the browser is. Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: low.**

### TWK-15 English literals and `alert()`

1. **Valid: YES.** `SkuPage.tsx` 387, 389 (`alert`), 492, 506, 518; `CataloguePage.tsx` 46; `UserMenu.tsx` 34, 51.
2. **Suggested fix: CORRECT.** Message ids in `web/src/modules/m2catalogue/*.messages.json` and the shell's; an inline `role="alert"` paragraph as the other pages do. The Sinhala and Tamil texts follow the existing practice (a first draft marked for the translator, as `ReceiptMessages` was). The source scan: see TWK-18.
3. **Business view.** The rule is AGENTS.md's; nothing to decide. The build-rule part (a JSX literal scan) is decided below as D-9 (recommend yes).

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

### TWK-18 Guards cover less than they say; Caddy headers; admin path; empty ACR

1. **Valid: YES.** `moduleStyle.test.ts` line 49 and `moduleFormats.test.ts` line 52 glob `../modules/**` only (and `.tsx` only for formats); `UserMenu.tsx` carries `#00A651`, `#F3E6F6`, pixel literals; the Caddyfile has no security headers, proxies `/auth/*` whole, and sets `stepUpAcrValues: ""`.
2. **Suggested fix: NEEDS CHANGES (split).**
   - *Guards (this group).* Both globs gain `../shell/**`; `moduleFormats` scans `.ts` too and the `new Date()` pattern of TWK-12; a third scan for user-visible literals (JSX text nodes that start with a capital letter and contain a space, and `aria-label|alt|title|placeholder="..."` literals) with an allow-list for `aria-hidden` markup. A type-based check (`Money`/`DateOnly` brands) is the better long-term guard but is a refactor of the generated clients; not now.
   - *Caddy (deploy area, `wave2-deploy.md`).* `header` block: `X-Content-Type-Options nosniff`, `Referrer-Policy strict-origin-when-cross-origin`, `X-Frame-Options DENY` and `Content-Security-Policy "frame-ancestors 'none'"` (a full CSP needs `style-src 'unsafe-inline'` for the inline styles the polish PRs added and must be tested against the Keycloak redirects; do the frame-ancestors part now), `Strict-Transport-Security` when `CADDY_TLS` is an e-mail address (a real certificate). Admin paths: `@admin path /auth/admin/* /auth/realms/master/*` with `not remote_ip {$CADDY_ADMIN_CIDR}` answering 404; the default `127.0.0.1/32` means "through an SSH tunnel", which is what a demo operator should do. Also `request_body { max_size 8MB }` on `/api/*`, which gives TWK-24 an edge cap for free.
   - *`stepUpAcrValues: ""`.* With an empty value the step-up is a re-login, as the finding says. A value needs a Keycloak flow that honours `acr_values` (an LoA mapping in the realm). That is realm configuration, not code; it stays a demo limitation until the realm has it. Note it in `infra/deploy/README.md` and do not set a value the realm ignores.
3. **Business view.** The admin console on the public path is the one real exposure; a CIDR default that denies is the smallest safe change.

**Verdict: BUILD AMENDED. Size S (guards) + S (Caddy). Migration: no.** Risk: the literal scan will find more than the eight listed; let it list and fix in the same PR or allow-list with a reason.

---

## Fix group E: web module fixes (TWK-13, TWK-14, TWK-16, TWK-17)

One web PR; any order. Decision D-5 (zero price) and D-6 (step-up body) can follow; the web halves do not wait for them.

### TWK-13 Blank price saved as 0

1. **Valid: YES.** `ShelfListPage.tsx` adds `price: ""` (line 232) and sends `Number(row.price)` (line 84); Save is disabled only while pending (line 237); `AuthoringValidator` refuses negatives only.
2. **Suggested fix: CORRECT for the web half.** Blank or non-decimal text is "incomplete"; Save disabled while any row is incomplete; a `role="alert"` naming the rows. The backend half (`exclusiveMinimum: 0` or a guard `price_zero` for RETAIL lines) is an M3 rule.
3. **Business view.** Nothing in doc 23 or 23A makes a zero retail shelf price meaningful: a free or promotional item is a discount rule (doc 23 section 3), a gift is a write-off, and a till that sells at Rs 0.00 because a clerk forgot a cell is a stock loss with a receipt. Recommend decision D-5: a RETAIL list line's price must be greater than zero (schema `exclusiveMinimum: 0` *and* the guard, because a till or a job could set lines without HTTP). Existing published lists with a zero line: a one-off query before the guard lands; if any exist they are corrected by a new list version, never edited.

**Verdict: BUILD AMENDED (web now, backend after D-5). Size S. Migration: no. Risk: none.**

### TWK-14 Upload `catch` never rotates the Idempotency-Key

1. **Valid: YES.** `SkuPage.tsx` lines 445 and 451-455: `key.next()` on success only. Whether a 422 from the handler leaves the key claimed could not be settled from the code (the kernel README says the key is claimed inside the command transaction and the result recorded before commit, so a rolled-back handler may release it; a 400 `request.invalid` before the handler never claims). It does not matter: `shell/api/idempotency.ts` lines 12-13 state the convention, `onError: ApiProblem -> key.next()`, and the other mutations of the same page follow it.
2. **Suggested fix: CORRECT.** Rotate on `ApiProblem` only. A failed storage PUT (`"Upload to storage failed"`) is not an `ApiProblem` and the key must not rotate: the ledger renews a PENDING row's window on the same key (kernel README, CR-19A-7), so the retry asks again with the same key and gets a fresh URL.
3. **Business view.** Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

### TWK-16 Step-up keeps the whole command body in the login state and replays to the stored URL

1. **Valid: YES.** `pendingCommandOf` keeps `request.url` and the body text; `replayPendingCommand` fetches `pending.url` with the fresh bearer and no origin check (lines 45-59, 66-85). Where oidc-client-ts stores the login state (session storage by default) is library behaviour, not traced.
2. **Suggested fix: NEEDS CHANGES.** The URL check is right and has no downside: refuse (and drop) a pending command whose URL does not start with the configured `apiBase`. For the body: "keep only commands whose body is free of personal fields" needs a definition of "personal field"; use the kernel's own, `OutboxWriter.FORBIDDEN_WORDS` and `SECRET_WORDS` applied to the JSON keys (port `isForbiddenField` to `shell/api/forbiddenFields.ts`, with a test that pins it to the Java list). A command whose body has such a key is not kept; after the step-up the form is shown again with a message ("sign-in was refreshed; enter the details again"). `openAccount` (NIC) and anything with a phone are the cases; adjustments with a `reasonText` are kept (a reason is not personal data under the kernel's rule either).
3. **Business view.** Alternatives: hash-and-re-ask for every command (safe, worse for every clerk on every step-up); encrypt in storage (the key would sit beside it); allow-list (recommended). Recommend decision D-6 so that doc 30's step-up text says it.

**Verdict: BUILD AMENDED. Size S. Migration: no. Risk: low.**

### TWK-17 `about:blank` tab without `noopener`, no scheme check

1. **Valid: YES.** `InvoicePage.tsx` 78-82 (same in `CreditNotePage`, `PaymentPage`, `ReportPage`).
2. **Suggested fix: CORRECT.** `tab.opener = null` right after `window.open` (passing `noopener` in the features string makes `window.open` return null, so the handle to navigate later is lost); navigate only when `new URL(url).origin` equals the object store's public origin (from `runtimeConfig`: the pre-signed URLs are signed for `PUBLIC_URL`, so under the demo Caddy it is the app's own origin) and the protocol is `https:` or `http:`. Put the four copies into one `openServerFile(mutate)` helper in `shell/api`.
3. **Business view.** Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

---

## Fix group F: kernel notifications (TWK-20, TWK-21, TWK-22)

One backend PR with one migration (`V0084`), after decision D-7 (quiet hours) and D-8 (HMAC key rotation). Order: TWK-21 (no decision), TWK-20, TWK-22. Cross-reference: M9-08 in `wave2-m9-integration.md` is the same defect as TWK-22 and must be fixed once, here, in the kernel.

### TWK-20 Recipient hash is unsalted SHA-256

1. **Valid: YES.** `NotificationLog.hash` is `SHA-256(channel + ":" + recipient)` (lines 66-74). 10^8 Sri Lankan mobile numbers is a second's work.
2. **Suggested fix: CORRECT, with the migration made concrete.** `HMAC-SHA-256` keyed by `coop-erp.notification.recipient-hash-key` (base64, 32 bytes), the same mechanism as `coop-erp.notification.pending-key` of `PendingSeal`, with a key id derived as `PendingSeal` derives its own. Migration `V0084__notification_recipient_hash_key.sql`: `ALTER TABLE kernel.notification_log ADD COLUMN recipient_hash_key_id text` (nullable; old rows null = SHA-256 era); no change to the unique key `(rule_id, event_id, recipient_hash)` or the dedup index; `SchemaRulesIntegrationTest` is unaffected (no new grant). **Old rows cannot be re-keyed**: the clear recipient is not stored anywhere (`notification_pending` is sealed and cleared at settlement), so they age out. Consequences, both acceptable: an event replayed across the switch may queue one notification once more (the unique key no longer matches), and the hourly de-duplication does not see sends from before the switch for one hour.
3. **Business view.** Decision D-8: the key is configuration, set at deployment, rotated only in a maintenance window with the two consequences above; no re-key ever. A rotation that must keep de-duplication working would need both keys live for an hour; not worth building.

**Verdict: BUILD AMENDED. Size S-M. Migration: yes, `V0084`. Risk: low**; `NotificationsPostgresIntegrationTest` fixtures that compute the hash directly change.

### TWK-21 Provider exception message stored in `last_error` and in an audit row

1. **Valid: YES.** `NotificationService.attempt` lines 255-260.
2. **Suggested fix: CORRECT.** `last_error` and `after.error` carry `failure.getClass().getSimpleName()` plus a category (`REJECTED`, `TIMEOUT`, `AUTH`, `UNKNOWN`) mapped in the channel adapters (they know their provider's codes), never `getMessage()`; the full message goes to the application log at debug with the notification id. Audit rows already written stay as they are; they cannot be edited.
3. **Business view.** Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

### TWK-22 Quiet hours suppress instead of deferring (= M9-08)

1. **Valid: YES for scenario one.** `NotificationSuppression.reasonToSuppress` returns `QUIET_HOURS` (line 62); `NotificationService.deliver` marks SUPPRESSED before any hold (lines 150-155) and `attempt` marks SUPPRESSED and clears the hold on a retry (lines 211-216). Scenario two is mitigated by the registry pattern (`config-items.yaml` line 180), as the verifier found.
2. **Suggested fix: CORRECT.** `NotificationSuppression` returns a sealed result: `Suppress(reason)` or `Defer(until)` (the window's end on the business zone, today or tomorrow); `deliver` holds the row and sets `next_attempt_at = until` with status QUEUED (the sweep's `due` query picks it up; `next_attempt_at` exists, V0054); `attempt` does the same instead of suppressing and clearing. The 24-hour `MAX_AGE` is counted from `created_at`; with the seeded window a deferral is at most nine hours plus the three backoffs, which fits; a window longer than fifteen hours could expire a message, so refuse such a window in the registry schema (`pattern` cannot say that; a `ConfigValues` validator can). Defensively, an unparsable window is "no quiet hours" with a warning. The existing test `theKillSwitchAndTheQuietHoursSuppress` becomes "defers to the window's end".
3. **Business view and the document conflict.** Doc 19 section 7: "Per recipient quiet hours (22:00–07:00 default for SMS)". Doc 29 section 6.4: "quiet hours: deferred to 07:00". 19A section 10: "quiet hours ... checked before send" and a test named "quiet-hours suppression". The two design documents give the reasoning (a dispatch alert or a bounced cheque is wanted in the morning, not never); the guide's test name is wording, not procedure. AGENTS.md says the design's reasoning holds and a contradiction is raised, not picked silently: recommend decision D-7, defer. *Whose window*: configuration is ENTITY-scoped and M1 keeps nothing per person; the dispatcher reads the event owner's scope. Recommend: the recipient's entity when the audience resolver knows it (ROLE_AT_COUNTERPARTY gives the counterparty; add `entityId` to `NotificationAudience.Recipient`), else the owner's. In one country and one zone the difference is only between societies that configure different windows. Which templates: every SMS (the key is per channel; e-mail and in-app have no window seeded).

**Verdict: DECIDE THEN BUILD (D-7). Size S-M. Migration: no. Risk: low.**

---

## Fix group G: kernel sync hardening (TWK-24, TWK-25, TWK-26, TWK-27)

One backend PR for TWK-24, 25, 27 (no decision), one till PR for the gzip half of TWK-24, and one backend PR with migration `V0085` for TWK-26 after decision D-10.

### TWK-24 Limits measured only for gzip bodies

1. **Valid: YES.** `GzipRequestFilter.shouldNotFilter` skips a body without `Content-Encoding: gzip` (lines 76-83); `SyncController.wireBytes` is `max(0, getContentLengthLong())`, 0 for chunked; `BatchIngestor.checkShape` compares after Jackson has parsed the body. The Kotlin till sends plain JSON (`HttpCentral.upload`), so every real batch takes the unbounded path.
2. **Suggested fix: CORRECT, made concrete.** Rename the filter to `SyncBodyFilter` and run it for every body on `/v1/sync/`: read at most `limit + 1` bytes where `limit` is `sync.batch.max_bytes` for a gzip body (DR-1, "2 MB compressed") and `coop-erp.sync.max-uncompressed-bytes` (new property, default 16 MB, about the inflation ratio of a JSON batch) for a plain one; set `WIRE_BYTES` to the bytes actually read in both cases; inflate as today. `wireBytes()` in the controller then always finds the attribute. A chunked plain body is counted the same way (the filter reads the stream, not the header). Keep accepting uncompressed bodies: refusing them would break the till on the day the fix lands; the till PR makes it gzip (Ktor: `GZIPOutputStream` over the batch text, `Content-Encoding: gzip`; the heartbeat may stay plain). The edge cap of TWK-18 (`request_body max_size`) is a second line.
3. **Business view.** Doc 32 S6 and section 3.2 already say "gzip body" and "everything is gzip-compressed": the till compressing is the contract, not a change to it. **No change request to doc 32.**

**Verdict: BUILD AMENDED. Size S (backend) + S (till). Migration: no. Risk: low**; the `SyncPostgresIntegrationTest` requests that send plain JSON still pass (plain is still accepted, now counted).

### TWK-25 Only `uploadBatch` is rate-limited; full snapshot built per request

1. **Valid: YES.** `rateLimiter.admit` appears only in `uploadBatch`; `SnapshotBuilder.build` has no cache.
2. **Suggested fix: NEEDS CHANGES (split).** Now: a per-device *request* bucket beside the batch and byte buckets (`sync.rate.requests_per_minute`, a new register key in `config-items.yaml`, default 30) taken in `getSnapshot`, `listChanges`, `heartbeat` and `presignAttachment` through `rateLimiter.admitRequest(scope, deviceId)`; a snapshot also costs its answer's bytes against the hourly byte bucket (downloads are the heavy direction). The full snapshot as a pre-built file per (location, version) in object storage is **K-08-F2**, recorded on 27 September with its trigger (`docs/progress/deviations/2026-09-27-feat-k-08-sync-gateway-2.md`); do not build an in-memory cache as a stop-gap, it would be undone.
3. **Business view.** The trigger of K-08-F2 ("before a till on a real link downloads a full shared catalogue") is nearer now that a till exists; the architect may want to pull it forward, but that is scheduling, not this fix.

**Verdict: BUILD AMENDED (limiter now; file DEFERRED under K-08-F2). Size S. Migration: no** (a register key is seed, not a migration). Risk: a demo till that heartbeats every 20 seconds uses 3 of 30 per minute; fine.

### TWK-26 Quarantine stores the forbidden value; no retention

1. **Valid: PARTLY, as the verifier says.** `EventApplier.quarantine` inserts `raw` whole (line 306); no purge exists; `app_rw` has `SELECT, INSERT` only (V0080 line 154). Quarantining a HASH or FORBIDDEN_FIELD event is the specified behaviour (doc 32 section 3.3 step 6, section 7, S4); the dropped sub-claim stays dropped.
2. **Suggested fix: NEEDS CHANGES.**
   - *Redaction.* For `FORBIDDEN_FIELD` only, store the raw event with the *values* of the offending keys replaced by `"[removed]"` (walk the tree with the same `isForbiddenField` rule; keep the key so the person sees what the till sent and where). Every other reason stores raw as doc 32 says. The value of a forbidden field is never needed for repair: the repair is to not send it.
   - *Retention.* A time-based purge contradicts S4 ("quarantined and never lost") and doc 32 section 7 ("the document is not applied until the till resends a correct copy"). Build a *resolution* instead: migration `V0085__sync_quarantine_resolution.sql` adds `resolved_at timestamptz`, `resolution text CHECK (resolution IN ('REPAIRED','DISCARDED'))`, `resolution_reason text`, `raw_event` becomes nullable, plus an UPDATE grant on those four columns only (the `object_upload` pattern; `SchemaRulesIntegrationTest` pins the grant) and a transition trigger that lets a row be resolved once. A command `ResolveQuarantine` (sync slice, permission `sync.quarantine.resolve`, MPCS administrator, reason code and text, audited, `sync.quarantine.resolved.v1`) sets them. A nightly job nulls `raw_event` of rows resolved more than `sync.quarantine.raw_retention_days` (register, default 30) ago; the row itself stays as the record that a fact was refused and why. Showing quarantine counts to the till: the heartbeat answer already has room (`instructions`); defer to group B's anomaly list, which gets the count from the ack.
3. **Business view.** A quarantined receipt is a sale the shop made and central does not hold; someone at the society must look at it. The resolution path is what gives that someone a queue, and the redaction keeps the one class of event that must not be stored from being stored. Decision D-10 records the one-line clarification to doc 32 section 3.3 step 6 ("store raw, with the values of forbidden fields removed") and the resolution procedure for section 7. **Doc 32 needs a small change request** for both.

**Verdict: DECIDE THEN BUILD (D-10). Size S (redaction) + M (resolution, job, screen later). Migration: yes, `V0085`. Risk: moderate** for the UPDATE grant (the schema test must be extended to pin the four columns).

### TWK-27 A missing signing key does not stop start-up

1. **Valid: YES.** `TillSigner` lines 51-56.
2. **Suggested fix: CORRECT.** `coop-erp.sync.signing.required` (default `true`); `false` in the test resources and in the developer compose only when the bootstrap script has not written a key (the finding says compose does set one, so only the tests need it); when required and blank, throw at construction with the property names in the message.
3. **Business view.** Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: low** (every Testcontainers test context must get the override; one `application-test.yml` line).

---

## Fix group H: kernel security defaults and latent checks (TWK-19, TWK-23, TWK-28, TWK-29, TWK-30)

One backend PR for TWK-19, 23, 28, 29 (no decision); TWK-30 after decision D-11 with the RLS area.

### TWK-19 HEAD bypasses the read-permission interceptor

1. **Valid: YES.** `ReadPermissionInterceptor.preHandle` line 57 checks `"GET".equalsIgnoreCase(request.getMethod())` only; Spring MVC's `RequestMethodsRequestCondition` serves HEAD from a GET mapping (framework behaviour, documented).
2. **Suggested fix: CORRECT.** Treat `HEAD` as `GET` (look the permission up under `GET`); a contract test that sends HEAD to one GET path per slice without the permission and expects 403. OPTIONS is answered by CORS before the handler; leave it.
3. **Business view.** Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: none.**

### TWK-23 Day-close trusts `locationId` from a till's payload

1. **Valid: YES, latent.** `DayCloseTrigger` lines 42-54; the till's `Facts.sessionClosed` carries neither field, so nothing fires today.
2. **Suggested fix: NEEDS CHANGES (split).** Now: close the location the *envelope* names, never the payload's. The device event's outbox row carries `location_id` set by central from the device record (`DeviceEventWriter`), so the consumer framework can hand it over in the scope or the envelope; if the framework does not expose it yet, that is the one-line change (`EventConsumerDispatcher`), and a payload `locationId` that differs from it is logged and ignored. Catch `IllegalArgumentException` on the UUID. Later: 19A section 12 and 26A's SessionHook make `openSessionsRemaining` M6's knowledge; M6's consumer of `till_session.closed.v1` should publish a central fact (`location.sessions_closed.v1`, count from M6's own table) that the kernel consumes instead of the till's event. That is M6's ticket (its SessionHook is not built); defer it there and leave the cut-off job as the day-close until then, which is what happens today.
3. **Business view.** A till must never be able to close another shop's day; the envelope rule gives that at once. Nothing to decide for the near fix.

**Verdict: BUILD AMENDED. Size S. Migration: no. Risk: none.**

### TWK-28 `presignGetOfParty` checks owner prefix only; Chromium `--no-sandbox`; no render cap

1. **Valid: YES, latent.** `A4RenderService.presignGetOfParty` lines 187-198; `ChromiumPdf` line 60; no semaphore in the render package.
2. **Suggested fix: NEEDS CHANGES (split).** Now: a `Semaphore` sized by `render.max_concurrent` (register, default 2; a Chromium is a few hundred MB) around the Chromium call, with a 503-style `render.busy` problem when it cannot be acquired within a bound. Defer: the document-id predicate (both callers read the row first; an ArchUnit rule that only M4 and M8 call `presignGetOfParty` pins that until a third caller appears). Deployment: the worker image runs Chromium as a non-root user with the default seccomp profile; the SUID sandbox does not work in an unprivileged container, so `--no-sandbox` stays and the template lint (`th:utext`, `[(`) is the compensating control; recommend the lint now (one test over `templates/**`). Hand the image part to the deploy area.
3. **Business view.** Nothing to decide here.

**Verdict: BUILD AMENDED. Size S. Migration: no. Risk: none.**

### TWK-29 `enforce-permissions` defaults to false

1. **Valid: YES.** `application.yml` line 106.
2. **Suggested fix: CORRECT.** Default `true`; the module integration tests that run as users of their own set `false` in the test profile (the comment at lines 103-104 says why they need it); the developer compose already sets `true`.
3. **Business view.** A forgotten environment variable must fail closed. Nothing to decide.

**Verdict: BUILD AS SUGGESTED. Size S. Migration: no. Risk: low** (tests that relied on the default change their profile, not their assertions).

### TWK-30 FEDERATION_VIEW resolves every GET permission

1. **Valid: YES as mechanism; the intent is written down.** `JdbcPermissionResolver.resolve` line 74 returns `slices.readPermissions()`, following doc 18 section 3.7 and 21A section 3 as the javadoc cites. Row-level security also shows the class every row (`fed_view` policies), so narrowing permissions is defence in depth, not the boundary.
2. **Suggested fix: NEEDS CHANGES.** Not a flag in the permission table (a migration and a seed for something the slice already knows). Mark operations in the OpenAPI slices with `x-federation-view: false` (default true); `SliceOperations.readPermissions()` excludes them; `SliceOperations` is loaded from the yaml at start, so no migration and the rule lives where the permission does. Exclude: M1 user and device administration reads, the privacy-request exports, M7 customer personal-data views (statement with NIC fields, erasure requests).
3. **Business view.** RLS-02 (`wave2-rls.md`, "Federation view of member data") is the same question one layer down; decide both at once (D-11) so the permission mark and the policies agree. Recommend: the Federation reads trading, stock, finance and reporting data of its members; it does not read members' staff administration or customers' personal data.

**Verdict: DECIDE THEN BUILD (D-11, with RLS-02). Size S-M. Migration: no. Risk: low.**

---

## Decisions to record

Each is written so it can be recorded as accepted on the architect's delegation; none is treated as accepted here.

- **D-1 (TWK-02) The till enforces https and trusts central's signing key on first use at enrolment.** A server URL that is not `https://` is refused unless its host is `localhost`, `127.0.0.1`, `[::1]` or `*.localhost`; the token endpoint must share the server's host unless the operator set `COOP_TILL_TOKEN_ENDPOINT`; a CA file may be given explicitly for a private certificate. Rejected: a signing-key fingerprint in the installer (every key rotation would need a new installer on every PC, and the installer is itself unsigned until CR-30-1's release signing exists); certificate pinning (breaks on renewal). Doc 32 section 9 unchanged.
- **D-2 (TWK-09) Central accepts a till's batches below the version floor after the grace, with a FLOOR_NOTICE, and withholds snapshots and change pages (426) instead.** This is CR-30-1 point 4 applied to the code; docs 31 section 6 and 32 section 3.3 step 1 are re-issued accordingly, as CR-30-1 already requires. Rejected: 426 on batches (refuses facts; S4).
- **D-3 (TWK-05) A revoked till locks, wipes its snapshot and keeps its outbox; it resumes uploading from the kept outbox when the administrator reinstates the device or issues a new enrolment code for the same device id; a device never reinstated has its unreceived numbers recorded as a documented gap by counter transfer (doc 32 section 8).** Rejected: an export-from-the-locked-till procedure (new design, no central side).
- **D-4 (TWK-07) A till's local business date may be moved backwards by a supervisor when no session is open; sessions and receipts already issued keep the date they carry.** The till refuses to open a business date more than one day after the date of central's last `server_time` when it has synced in the last 48 hours. Rejected: forward-only for ever (a wrong clock would leave every later receipt misdated).
- **D-5 (TWK-13) A RETAIL price-list line's price is greater than zero**, enforced in the M3 slice (`exclusiveMinimum: 0`) and as a handler guard (`price_zero`). A free or promotional item is a discount rule. Existing zero lines, if any, are corrected by a new list version.
- **D-6 (TWK-16) The web shell keeps an interrupted command across a step-up only when its URL is under the configured API base and no key of its JSON body matches the kernel's forbidden-field rule; otherwise the form is asked again.** Rejected: keep everything (personal data in session storage); keep nothing (every step-up loses the clerk's work).
- **D-7 (TWK-22, M9-08) Quiet hours defer; they never suppress.** A notification due inside the window is held QUEUED with `next_attempt_at` at the window's end in the business zone, for every channel that has a window configured (SMS by default). The window is read in the recipient's entity when the audience resolver knows it, else the event owner's. Doc 19 section 7 and doc 29 section 6.4 govern; 19A section 10's "suppression" wording is corrected at re-issue. Rejected: suppress (the two design documents say otherwise; a bounced cheque unreported is a loss).
- **D-8 (TWK-20) The recipient hash is HMAC-SHA-256 under a deployment key; the key id is stored beside the hash; rows hashed before the switch are not re-keyed and age out; a key rotation is done in a maintenance window and may queue one duplicate for an event replayed across it.**
- **D-9 (TWK-15, TWK-18) The web build scans `modules/**` and `shell/**`, `.ts` and `.tsx`, for colour and size literals, browser-local date arithmetic, and user-visible string literals in JSX text and `aria-label`, `alt`, `title`, `placeholder`.** An allow-list entry needs a reason in the test file.
- **D-10 (TWK-26) A quarantined event is stored raw, except that the values of forbidden fields are replaced by `[removed]`; a quarantine row is resolved (REPAIRED or DISCARDED, with a reason) by an MPCS administrator through an audited command, and its raw event is nulled after a retention counted from resolution; the row is never deleted.** Doc 32 section 3.3 step 6 and section 7 gain this wording (small change request). Rejected: a time-based purge of unresolved rows (contradicts S4 "never lost").
- **D-11 (TWK-30, with RLS-02) Federation view reads its members' trading, stock, finance and reporting data; it does not read staff administration (users, devices, credentials), privacy-request exports or customers' personal data.** Operations are marked `x-federation-view: false` in their slices; the RLS policies of the same tables are aligned under RLS-02.

## Findings I could not settle

- **TWK-06**: whether the bundled sqlite-jdbc-crypt sets `SQLITE_DEFAULT_WAL_SYNCHRONOUS=1`. Settled by `PRAGMA synchronous` on an opened trial database, or by the library's build flags. The fix is right either way.
- **TWK-14**: whether a handler's 422 leaves the idempotency key claimed (the claim is inside the command transaction, so a rollback may release it). Settled by one integration test: same key, first request 422, second request with another body. The fix follows the shell's convention regardless.
- **TWK-16**: where oidc-client-ts keeps the login state (session storage by default). Settled by reading `oidc.ts`'s `stateStore` setting; the URL check and the allow-list hold wherever it lives.
- **TWK-19**: Spring's HEAD-to-GET mapping, as documented; one request in the contract test settles it.
- **TWK-23**: whether the consumer framework exposes the outbox row's `location_id` to a consumer. Settled by reading `EventConsumerDispatcher`; if not, the one-line change is named above.
- **TWK-24**: whether the demo Caddyfile has any body cap today (it does not; `request_body` is absent) and what Tomcat's effective limit is for a plain chunked body (none for a servlet reading the stream). Both close with the filter change.

## Two things seen in passing, outside the TWK list

- `backend/app/src/main/resources/db/migration/kernel/README.md` lines 10-18 contain unresolved merge-conflict markers (`<<<<<<< HEAD`, `=======`, `>>>>>>> origin/main`) in the "Taken so far" paragraph. Flyway ignores the file, so nothing breaks, but the kernel's migration register is unreadable. A docs-only PR; whoever takes `V0084` should fix it in the same change.
- The till's trial deviation (8) in `2026-09-29-till-desktop-trial.md` (a receipt applied before its session in the same batch, flagged `SESSION_UNKNOWN`) is an M6 ordering defect raised in #231 and not in any wave-2 area; it belongs with the M6 fix review.
