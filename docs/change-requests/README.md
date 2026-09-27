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

### 19A (Kernel Services Implementation Guide)

- `CR-19A-1` — a signature for every published `kernel.api` type the service map names; take them from the code (listed in the CR's decision line) rather than re-deriving a second set.
- `CR-19A-2` — ticket K-03a (idempotency table, command interceptor order), split from K-03; the DDL, partitioned by day; the window as a configuration item.
- `CR-19A-3` — `GapCheckJob` checks `(source, source_seq)` density for device sources only, never for `central` (the job itself is a follow-up, `docs/PLAN_TO_M2.md` task 6.10).
- `CR-19A-4` — accepted as revised: no `x-auth: device` exemption; the sync slice already satisfies every slice rule (`/v1/sync/...` paths, a real `Idempotency-Key`, a device-marker `x-permission` value) with no special case in `tools/check-slices.mjs` or `tools/check-permissions.mjs`.
- `CR-19A-5` — see 17A above; also `GRANT SELECT, UPDATE (published_at) ON kernel.event_outbox TO app_relay` and a policy stating the relay reads every entity's events.
- `CR-19A-6` — `CommandHandler.INTERNAL` for a handler with no operation of its own, called only inside the caller's transaction; accepted with the guard rails of pull request #138.
- `CR-19A-7` — accepted as revised: the kernel keeps an upload ledger (`kernel.object_upload`) and enforces the K-09 rules once for every module that owns objects without a document, rather than generalising `document_attachment`.
- `CR-19A-8` — seven kernel decisions: a notification retry's recipient and placeholders sealed under a key outside the database (also doc 19 §7); the upload URL's validity and the verifier's schedule as configuration (§9); a nightly clean-up of failed uploads' files after a retention (§9, §12; also doc 18 part C); NFC on every String, no `I18nText` (§6); no provider adapter in the kernel (§10); a document with no location takes the calendar date in the business time zone (§13; also doc 18 part C and 24A for DISC); the A4 renderer is K-06b, before M3-10 (§6; `docs/PLAN_TO_M2.md`).

### 21A (M1 Party, Tenancy and Security Implementation Guide)

- `CR-21A-1` — accepted as revised: the permission catalogue of §3.3 verb by verb plus `gov.entity.suspend` and one read code per aggregate (the four coarse `manage` codes retired, `gov.audit.review` and its self-review pair added); AppointResponsibleOfficer keeps `gov.user.manage`; 422 for every business rule (§5, and 17A §4.3); §6 says a handler repeats a shape rule only where the command also arrives by another path. Item 2: the kernel names the Federation, `kernel.system_entity()` (kernel V0061, #142; folding `party.federation_identity` into it is `docs/PLAN_TO_M2.md` task 6.11); 17A §6.3 adopts `fed_admin`; 18 Part F lists `party.entity_party_directory` (and `party.federation_identity` until 6.11).
- `CR-21A-2` — AmendRelationshipTerms never before today; a row not yet started is corrected on its first day and becomes REPLACED (also doc 18 Part A, doc 21 §4.2).
- `CR-21A-3` — option 1: `user_role`, `role_permission` and `sod_pair` keep their audited deletes (§6; also 17A §6.2 and 18 Part F).
- `CR-21A-4` — `LocationFilter` (m1party's frozen query package) gains an optional `entityId`, needed by 22A's `listLocations(entity)`.
- `CR-21A-5` — BulkRegister is all or none (also doc 21 §5.1's "commit per row"); a till position is registered at a shop in any status.

### 22A (M2 Catalogue and Batch Implementation Guide)

- `CR-22A-1` — accepted as revised: `catalogue.tag` is keyed by `tag_id`, its code unique within its owner (`tag_code_per_owner`); `sku_tag` names the tag by id; the Federation writes governed tags; no reserved prefix for governed codes (m2catalogue V0007).
- `CR-22A-2` — accepted as revised: the thumbnail is a JPEG within doc 22's 10 KB (`m2.image.thumbnail_max_kb`), not WebP; the image audits and publishes at the attach and at the settle (`image.pending.v1`, `image.attached.v1`, `image.failed.v1`; `IMAGE_ACTIVATED`, `IMAGE_FAILED`, `IMAGE_RETIRED`).
- `CR-22A-3` — `cat.sku.view` (the reads) and `cat.tag.govern` (FEDERATION, governed tags) beside 22A's eleven codes; `cat.tag.manage` is local tags only.

### 28A (M8 Reporting Implementation Guide)

- `CR-28A-1` — cost of goods sold comes from M5's `stock.moved.v1` event, not from the receipt bundle.
