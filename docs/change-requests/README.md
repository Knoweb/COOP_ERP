# docs/change-requests/ — index

One file per change request, `CR-<doc>-<n>.md`, from `TEMPLATE.md`. `docs/README.md` section 7 has the full procedure and the status vocabulary (`raised → accepted | rejected → applied`); this file only tracks the gap between "accepted" and "applied" so that a document re-issue picks up everything waiting for it.

## Accepted, waiting for a re-issue

Every change request below is **accepted** (or **accepted as revised**) and already built in the repository as its decision line says; none of the affected documents has been re-issued yet. Grouped by the document a re-issue would touch first. When a document is re-issued (`docs/README.md` section 6), move its change requests out of this list and mark them `applied` with the new version.

### 00_Start_Here

- `CR-00_Start_Here-1` — the two registers (document register, glossary) are `.docx` in the requirements folder, not `.md`.

### 17A (Build Skeleton Implementation Guide)

- `CR-17A-1` — the problem document's field list (`errors`, `FieldProblem`); the message id is carried by `code`, not `type`.
- `CR-17A-2` — Spring Boot 3.5 / Spring Modulith 1.4 (17A §3 says 3.3 / 1.2); applied in the repository (#55).
- `CR-19A-5` — a login user `coop_relay` (member of `app_relay` only) and its own small data source; 17A §6's role line needs the same wording as 19A §5.
- `CR-17A-3` — the policy template's `own_*` test the class OWN, and `party_read` carries the location line on the owner's side only (also 19A §1); every write policy of `app_rw` is checked for the class by `OwnPoliciesTestTheClassIntegrationTest`.

### 18 (Core Data Model)

- `CR-18-1` — `audit_event`/`event_outbox` primary key `(received_at, id)` (a partitioned table's unique constraint must include the partition key); the four scope columns (`owner_entity_id`, `location_id`, `device_id`, `till_position_id`) are `uuid`, not a foreign key out of the kernel schema; a general rule that a kernel table never declares a foreign key into a module's schema.
- `CR-19A-2` — one sentence: the idempotency window's expiry is dropping partitions with the migrator's rights, not a `DELETE`.
- `CR-19A-3` — one cell: `event_outbox.source_seq` is dense per source for device sources; for `central` it is strictly increasing and not necessarily dense.
- `CR-18-2` — §3.7: FEDERATION_VIEW and EXTERNAL_TIMEBOXED do not read personal data of natural persons by policy (customer, phone, consent, notification contact rows); identity reaches them through an audited query or a named export; operations marked `x-federation-view: false` are excluded from a FEDERATION_VIEW session's read set (also 27 §5.2, §9.5; 21A §3; 29 §8). Wave 2, 6 Oct 2026.

### 19A (Kernel Services Implementation Guide)

- `CR-19A-1` — a signature for every published `kernel.api` type the service map names; take them from the code (listed in the CR's decision line) rather than re-deriving a second set.
- `CR-19A-2` — ticket K-03a (idempotency table, command interceptor order), split from K-03; the DDL, partitioned by day; the window as a configuration item.
- `CR-19A-3` — `GapCheckJob` checks `(source, source_seq)` density for device sources only, never for `central` (the job itself is a follow-up, `docs/PLAN_TO_M2.md` task 6.10).
- `CR-19A-4` — accepted as revised: no `x-auth: device` exemption; the sync slice already satisfies every slice rule (`/v1/sync/...` paths, a real `Idempotency-Key`, a device-marker `x-permission` value) with no special case in `tools/check-slices.mjs` or `tools/check-permissions.mjs`.
- `CR-19A-5` — see 17A above; also `GRANT SELECT, UPDATE (published_at) ON kernel.event_outbox TO app_relay` and a policy stating the relay reads every entity's events.
- `CR-19A-6` — `CommandHandler.INTERNAL` for a handler with no operation of its own, called only inside the caller's transaction; accepted with the guard rails of pull request #138.
- `CR-19A-7` — accepted as revised: the kernel keeps an upload ledger (`kernel.object_upload`) and enforces the K-09 rules once for every module that owns objects without a document, rather than generalising `document_attachment`.
- `CR-19A-8` — seven kernel decisions: a notification retry's recipient and placeholders sealed under a key outside the database (also doc 19 §7); the upload URL's validity and the verifier's schedule as configuration (§9); a nightly clean-up of failed uploads' files after a retention (§9, §12; also doc 18 part C); NFC on every String, no `I18nText` (§6); no provider adapter in the kernel (§10); a document with no location takes the calendar date in the business time zone (§13; also doc 18 part C and 24A for DISC); the A4 renderer is K-06b, before M3-10 (§6; `docs/PLAN_TO_M2.md`).
- `CR-19A-9` — identity and permissions as built: scopes resolved at request time from M1's tables, no provider mapper (§2); the kernel enforces every GET's x-permission from the slices (§3.4, one read check); the resolved set per class (EXTERNAL_TIMEBOXED from its roles while a grant runs, FEDERATION_VIEW every read); `GET /v1/session` hands the web shell its resolved set (also doc 30 §3).
- `CR-19A-10` — `kernel.api.SyncStatus` (a device's outbox state and `drained`) is a kernel contract (also 21A §6.1).
- `CR-19A-12` — §10: quiet hours defer a notification to the window's end in the recipient's entity's window; they never suppress (docs 19 §7 and 29 §6.4 govern). Wave 2, 6 Oct 2026.
- `CR-19A-13` — §5 (also doc 19 §5): counterparty delivery, a consumer registered with `party = COUNTERPARTY` runs in the OWN scope of the payload's counterparty for a central event whose document names that counterparty; the buyer's INV and CN postings travel this way. Wave 2, 6 Oct 2026.

### 21A (M1 Party, Tenancy and Security Implementation Guide)

- `CR-21A-1` — accepted as revised: the permission catalogue of §3.3 verb by verb plus `gov.entity.suspend` and one read code per aggregate (the four coarse `manage` codes retired, `gov.audit.review` and its self-review pair added); AppointResponsibleOfficer keeps `gov.user.manage`; 422 for every business rule (§5, and 17A §4.3); §6 says a handler repeats a shape rule only where the command also arrives by another path. Item 2: the kernel names the Federation, `kernel.system_entity()` (kernel V0061, #142; folding `party.federation_identity` into it is `docs/PLAN_TO_M2.md` task 6.11); 17A §6.3 adopts `fed_admin`; 18 Part F lists `party.entity_party_directory` (`party.federation_identity` became a view of the kernel's row, granted to nobody, by 6.11, m1party V0012).
- `CR-21A-2` — AmendRelationshipTerms never before today; a row not yet started is corrected on its first day and becomes REPLACED (also doc 18 Part A, doc 21 §4.2).
- `CR-21A-3` — option 1: `user_role`, `role_permission` and `sod_pair` keep their audited deletes (§6; also 17A §6.2 and 18 Part F).
- `CR-21A-4` — `LocationFilter` (m1party's frozen query package) gains an optional `entityId`, needed by 22A's `listLocations(entity)`.
- `CR-21A-5` — BulkRegister is all or none (also doc 21 §5.1's "commit per row"); a till position is registered at a shop in any status.
- `CR-21A-7` — §3.3, §6, §6.1: a credential reset, a kind change or a deactivation only within the caller's rank (MFA-flagged codes); the last user manager counted by login kind; a granted limit never above the grantor's; `limits_schema` on `inv.writeoff.approve` and `inv.adjust.approve`; the `gov.role.manage`/`gov.user.manage` pair removed; a credit limit gated at opening and activation, the activation publishing `credit_limit.changed.v1`; `RelationshipQueries.settlementRelationship`. Wave 2, 6 Oct 2026.

### 22A (M2 Catalogue and Batch Implementation Guide)

- `CR-22A-1` — accepted as revised: `catalogue.tag` is keyed by `tag_id`, its code unique within its owner (`tag_code_per_owner`); `sku_tag` names the tag by id; the Federation writes governed tags; no reserved prefix for governed codes (m2catalogue V0007).
- `CR-22A-2` — accepted as revised: the thumbnail is a JPEG within doc 22's 10 KB (`m2.image.thumbnail_max_kb`), not WebP; the image audits and publishes at the attach and at the settle (`image.pending.v1`, `image.attached.v1`, `image.failed.v1`; `IMAGE_ACTIVATED`, `IMAGE_FAILED`, `IMAGE_RETIRED`).
- `CR-22A-3` — `cat.sku.view` (the reads) and `cat.tag.govern` (FEDERATION, governed tags) beside 22A's eleven codes; `cat.tag.manage` is local tags only.

### 23A (M3 Pricing Implementation Guide)

- `CR-23A-1` — §3, §6, §7: `FIXED_PRICE > 0`, `PERCENT_OFF < 100`, a RETAIL line price `> 0`; a control price backdated by at most `pricing.control_price_backdate_days` with a REVIEW audit; the expiry markdown on the identified or FEFO-first in-date batch, never at days < 0, zero-benefit rules dropped, engine 0.2.0; `control_price` written by the Federation only in SQL, `advisory_read` the Federation's lists only, `buyer_read` ACTIVE relationships only. Wave 2, 6 Oct 2026.

### 24A (M4 Trading Documents Implementation Guide)

- `CR-24A-3` — §3, §3.1, §6, §6.3 (also doc 24 §3.6, §3.7, §3.9): a billed unit is credited once across settlement, claim and credit note; a credit note beyond what is due stays unapplied and is applied later by `ApplyCreditNote`; a claim covers only units the GRN did not record as damaged; a partial approval frees the rest; the cheque key `(seller, payer, bank, number)`; the counterparty's internal columns moved to a seller-only table; the buyer's GRN postings from `ConfirmGrn`, INV and CN BUYER lines on the seller's event, `CN GOODS BUYER` map rows; exposure per order line; `invoice_dispute.seq`. Wave 2, 6 Oct 2026.

### 25A (M5 Inventory Implementation Guide)

- `CR-25A-1` — §3, §6, §7, §10 (also doc 25 §3.3, §3.8, §7): expired lots never picked, available or priced, `dispatch_min_shelf_life_days` for deliveries; approval limits from the grant's `max_value`, failing closed; approver ≠ in-person witness; `count_line.counted_at` and value tolerances; repack yield tolerance; TRANSFER_IN and reversals at their own cost; a transfer request filled once; short pick rows re-picked at dispatch; a self-received transfer flagged; a sale with no lot posts against the newest batch. Wave 2, 6 Oct 2026.

### 27A (M7 Customers Implementation Guide)

- `CR-27A-1` — §6, §6.3, §6.4, §7.3, §11 (also doc 27 §4.2, §6.7, §7; doc 10 L-05): `REOPEN` CLOSED → SUSPENDED; credits and negative adjustments allocate like payments; the erasure guard (every account closed, locked, a waiting window) and what erasure leaves under the accounting exemption; the access export as an audited command without `nic_hash`; `nic` accepted on a limit raise; one society per person in v1 with the cross-society path sketched for after L-02. Wave 2, 6 Oct 2026.

### 28A (M8 Reporting Implementation Guide)

- `CR-28A-1` — cost of goods sold comes from M5's `stock.moved.v1` event, not from the receipt bundle.
- `CR-28A-2` — §3, §6, §7: the trade projections carry the document's `location_id` with the template's location line; `trade_document_event` keyed by `event_id`; `shop_sale_line_fact` keyed by `(receipt_id, line_no)` at the device's shop; the cache key is the whole visibility; `credit_limit_fact` and exposure from the current limit; CSV exports audited and bounded; the run worker reads in its own transaction. Wave 2, 6 Oct 2026.

### 29 (M9 Integration)

- `CR-29-1` — §3.3, §4.2, §6.2 (also 29A §3, §5, §6, §7): an open period is refused unless `provisional`; a provisional export is a first instalment, never superseded (SUPERSEDED withdrawn), with a "supplement due" signal; the file's bytes are stored (`journal_export_file`) with a format version; the buyer's side by CR-19A-13; every posting carries its document's business date. Wave 2, 6 Oct 2026.

### 30 (Frontend Foundations and UI Module Designs)

- `CR-30-1` — the till is one Kotlin Multiplatform application for Android, Windows and Linux (DR-1 revised), with automatic updates from central on every OS; also touches 26/26A and 17A.
- `CR-30-2` — §3: the shell keeps an interrupted command across a step-up only when its URL is under the API base and no key of its body matches the kernel's forbidden-field rule. Wave 2, 6 Oct 2026.

### 32 (Sync Contract)

- `CR-32-1` — §3.3, §7, §8, §9 (also doc 31 §6, doc 26 §3.5): facts are accepted below the version floor, snapshots withheld (CR-30-1 point 4); a quarantined FORBIDDEN_FIELD event stores `[removed]` for the offending values; a quarantine row is resolved (REPAIRED or DISCARDED) by an audited command, never purged by time; a revoked till keeps its outbox and resumes on reinstatement; the till's business date may be moved back by a supervisor with no session open. Wave 2, 6 Oct 2026.
