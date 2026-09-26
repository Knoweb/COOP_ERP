# m1party — M1 Party, Tenancy & Security

Scaffolded from the hello module by `make new-module`. This file is the living guide of the module (AGENTS.md): once code exists, it and the tests supersede the implementation guide for day-to-day work. Record every deviation from the guide here, with the reason.

Read `hello/README.md` first: the six rules it lists apply here unchanged.

One section per ticket, in the order of 21A section 10, each ending with its own deviations from the guide: trading relationships (M1-04), locations and till positions (M1-05), devices (M1-06), users and credentials (M1-07), roles and separation of duties (M1-08), external grants (M1-09). The scaffolding notes come first because a new module starts there.

## After scaffolding: what to change by hand

The copy compiles and its integration tests pass, but it is still a greeting with another name, and **`make test` fails until step 1 is done**. In this order:

1. **Permissions (the build is red until you do this).** `todo.party.party.register` and `todo.party.party.read` are placeholders; nothing can derive the real codes. Replace them, in the `@CommandHandler` and in `openapi/m1party.yaml` (the two must be the same string), with the permission codes of your guide: they look like `cat.sku.create`. The architecture tests and `tools/check-permissions.mjs` refuse any permission that still starts with `todo.`. The web module carries the same two placeholders: in `web/src/modules/m1party/module.tsx` (`requiredPermissions`: who sees the module in the navigation and may open its routes) and in `PartyPage.tsx` (who is offered the form); replace them with the same codes, or `pnpm test` stays red (`router.test.tsx`). Until 19A K-08 only the development user `fed-admin` sees a new module, because the temporary role map of `web/src/shell/auth/permissions.ts` gives that role everything and knows no other code; do not add your codes there unless you need another development user to see the module.
2. **The table.** `db/migration/m1party/V0001__party.sql` has the columns of a greeting. Replace them with the DDL of your guide (section 3). Keep the row-level security block and the narrow grants; add the location clause to `own_read` if the table has a `location_id`.
3. **The slice.** `openapi/m1party.yaml`: replace the operations with those of your guide (section 5). The slice comes first: the build generates the Java interface `PartyApi` and the request and response classes from it (package `m1party.web.generated`, never edited, never committed), and `PartyController` stops compiling until it implements what the slice says. Then run `make gen-clients` and commit the web client. Write the shape of each request in its schema (`required`, `maxLength`, `minimum` ...): the kernel enforces it and answers 400 `request.invalid`, so the handler guards business rules only (hello/README.md, "Shape in the slice, rules in the handler"). Refer to `common.yaml` for the Idempotency-Key header and the 400 and 422 responses; `OpenApiSliceRulesTest` checks the rules every slice obeys.
4. **Handler, entity, queries.** Follow the handler specifications of your guide (section 6). One handler per command, each with its audit event and domain event.
5. **Messages.** The ids `party.*` in `i18n/{en,si,ta}.json` and in `web/src/modules/m1party/party.messages.json` still carry the greeting texts. Replace the English, and have the Sinhala and Tamil translated; a missing language fails the build.
6. **Tests.** `PartyModuleIntegrationTest` proves isolation, grants, the command pipeline and the time rule for the copied table. Keep those four groups; rewrite the cases for your aggregate.
7. **Dependencies.** Add the modules your guide names to `allowedDependencies` in `package-info.java`.
8. **The event type** is `party.registered.v1`. Use the names of your guide.
9. **Screens: tokens only.** In `web/src/modules/m1party` write no hex colour and no px, rem or em literal: every colour, distance and font size is a token of `web/src/design/tokens.css` (`var(--space-2)`, `var(--color-alert-text)`), and `pnpm test` fails on a literal (`web/src/design/moduleStyle.test.ts`). Show an amount of money with `<MoneyDisplay amount={...} />` and a document state with `<StateChip />` (`web/src/shell/components`); never format or add up money in a screen, totals come from the server. Open `/_design` in the running client to see what exists.

## Trading relationships (M1-04)

`internal/relationship`: the aggregate `Relationship` (one row of `party.entity_relationship`), its four handlers and the rules they share. Read the handlers in this order: `OpenTradingRelationshipHandler` (DRAFT), `ActivateRelationshipHandler` (ACTIVE), `AmendRelationshipTermsHandler` (21A section 6.1: close the current row the day before, open the next), `SuspendRelationshipHandler`.

- **Effective dating.** A row's terms never change once it is ACTIVE. An amendment closes the current row (`effective_to = effectiveFrom - 1 day`, the only update an ACTIVE row ever gets besides its status) and inserts the next row. The history of a pair is its rows ordered by `effective_from`; `RelationshipAmended` links each new row to the one it replaced.
- **One ACTIVE row per pair per date** (A-I3) is the exclusion constraint of `V0001`. The handlers check first (`RelationshipRules.requireNoOverlap`) so the refusal can name the row in the way, and translate the constraint's error (SQLSTATE 23P01) into the same `m1.relationship.overlap` when two clerks race.
- **Who may do what.** The seller, in its own entity-wide OWN scope, does everything; the buyer reads (policy `party_read`, in OWN or PARTY scope) and changes nothing. The seller cannot read the buyer's entity row, so the opening guards ask `party.trading_standing(entity)` (`V0007`) for the two facts they need: type and status.
- **Tier rule.** Federation to distributor, distributor to society; Federation to society only when `trade.federation_direct.enabled` is true (doc 10 F-03, default off).
- **Credit limit.** A change of the limit also needs `bil.creditlimit.change` and a second factor younger than `m1.relationship.credit_limit_mfa_max_age` (PT10M). Both are guards in the handler, because they apply only when the limit changes.
- **M3's price-list check** is the interface `api.TradePriceListCheck`: M1 publishes the question, M3 will answer it (M3 depends on M1, not the other way round). Until then `AcceptingPriceListCheck` accepts every list and says so at WARN; delete it in the pull request that gives M3's implementation.
- **Published queries.** `query.RelationshipQueries`: `lookupRelationship(seller, buyer, date)` for M3 and M4, `getRelationship`, `listRelationships(side)`.
- **An external caller reads relationships**: `V0007` gives `party.entity_relationship` the template's `ext_view` policy, so a regulator holding a grant (M1-09) reads the terms of the granted entity's relationships, read only.

### Deviations from the implementation guide (M1-04)

- **422, not 409, for an overlapping relationship** (21A section 5 shows 409): the kernel answers every broken business rule with 422 and a code (`CR-21A-1` item 3).
- **Message ids carry the module prefix** (`m1.relationship.overlap`, 21A writes `relationship.overlap`), as every M1 id does.
- **The credit-limit permission is a handler guard, not `requiresAlso`** (21A section 6): it applies only when the limit changes. Until K-03b puts a `PermissionResolver` on the server the permission part is skipped, as every permission is today; the second-factor part is enforced now.
- **Permission codes by verb** (`prt.relationship.open`, `.activate`, `.amend`, `.suspend`; `bil.creditlimit.change`) as 21A section 3.3 names them; the coarse `prt.relationship.manage` stays in the catalogue unused until the catalogue freeze (`CR-21A-1` item 1).

## Locations and till positions (M1-05)

`internal/location`: `Location` and `TillPosition` (JPA), one handler per command, `LocationGuards` (the shared guards), `LocationFacts` (reads the device at a position and whether a shop has an operator), `SeriesHooks` (every call to the kernel's `NumberingService`), `TradingHours` (the JSON of `trading_hours`). Controller `web/LocationsController`, tag `Locations` of the slice.

- A **shop** gets its LOCATION series (GRN, WOF, CNT, RPK, XFR) when it is registered; a warehouse or an office gets none and numbers from the entity's series. Which types are per location or per till is read from the kernel's document type registry, never listed in M1.
- A **till position** gets its TILL_POSITION series (RCT, CPR) in the same transaction as the row (the "done when" of M1-05). Retiring it closes them; a closed series is never reopened and a position number is never reused.
- **SetPrimaryTill** moves the counters of the shop's location series to the device at the new primary till, through `NumberingService.holderChange`; with no device at the position (a device is enrolled and assigned by the M1-06 commands) nothing moves, and the event says `holderDeviceId: null`. It also registers the shop's location series where they are missing (idempotent), so a shop from a seed gets them.
- Every command needs an OWN scope; RegisterLocation needs it entity-wide. Row-level security decides everything else: a shop-scoped caller reads, changes and adds positions to its own shop only.
- **An external caller reads locations and positions**: `V0005` gives `party.location` and `party.till_position` the template's `ext_view` policy, so a regulator holding a grant (M1-09) reads the granted entity's shops, read only. (The header of `m1security/V0009`, written before `V0005` merged, says the party schema has no `ext_view`; it is a merged migration and stays as it is. This file is the record.)

### Deviations from the implementation guide (M1-05)

- **Permissions.** 21A section 3.3's `prt.location.register`, `prt.location.activate` and `prt.location.primary` (MFA) are added to the catalogue beside the older `prt.location.manage`/`.view`, whose removal is `CR-21A-1` item 1's. UpdateLocation uses `prt.location.register` (21A names no update code); StartOnboarding, MarkDormant, Reactivate and ConfirmLocationConnectivity use `prt.location.activate` (doc 21 names `prt.location.onboard` and `.dormant`, which 21A's catalogue does not have).
- **Commands 21A does not list by name:** UpdateLocation (the facts of doc 21 section 7: hours, language, size band) and ConfirmLocationConnectivity (the gate of doc 21 section 3.3, "a fact, not a toggle"), both published as `location.updated.v1`, an event doc 21 section 5.3 does not list.
- **Guards added:** RetireTillPosition refuses the shop's primary till (`m1.position.is_primary`); SetPrimaryTill refuses the position that is already primary (`m1.location.primary_unchanged`). **Guards waiting:** "no open till session" (MarkDormant, RetireTillPosition) waits for M6's query; "device staged" (StartOnboarding, doc 21) waits for the staging record of doc 31: M1-06 has merged and enrols a device at a location, but the Federation's staging table of doc 31 is not here, so `StartLocationOnboardingHandler` does not check it yet.

## Devices (M1-06)

`internal/device`: the device aggregate and its five commands (enrol, assign to a position, suspend, reinstate, retire), done when scenario 6.6 of doc 21 (replacing a faulty till) runs.

- **Package.** The device aggregate and its handlers are in `internal/device`, not in `internal/location` beside Location and TillPosition as 21A section 4 lists them. They read positions and locations through `DevicePlaces` (plain SQL under the caller's row-level security) and write only `party.device`.
- **A device has a location** (`party.device.location_id`, `m1party/V0009`): 21A gives it none, and without one its policies could not keep a shop-scoped user to its own shop's devices. A device is enrolled at a location and is assigned only to a position of that location. `V0009` also gives `party.device` the template's `ext_view` policy.
- **A suspended device keeps its position** until a replacement is assigned there ("keeps position for return", 21A section 6). The replacement of flow 6.6 is therefore an assignment to a position held by a SUSPENDED device; that device is the "previous device" of the 21A pseudocode, read from the database rather than sent in the request. A position held by an ACTIVE device is occupied.
- **Drained or loss recorded.** The drained check asks `kernel.api.SyncStatus`, which since K-08 reads the gateway's cursor and the device's last heartbeat: drained when the last heartbeat reported nothing pending, no batch is in flight and central holds everything the device holds acknowledged; a device that never synced or never reported is not drained, so its replacement needs `outboxLossRecorded`. Recording the loss writes a `DEVICE_OUTBOX_LOSS_RECORDED` ALERT and sets `outboxLossRecorded` on `device.position_changed.v1` for the gateway's sequence reset.
- **The revoke** is its own event, `device.revoked.v1`, published on suspension and on retirement; `device.reinstated.v1` lifts it.
- **Version floor** is the configuration item `m1.device.version_floor` (federation-wide, default "0").
- **Enrolment** is by the owning entity in its own scope, with the staging reference in the command and the audit record; the Federation staging table of doc 31 and the device credential (K-02, K-08) are not here.

## Users and credentials (M1-07)

`internal/user`: `CreateUser`, `UpdateUser`, `ResetCredential`, `DeactivateUser`, all `gov.user.manage` with MFA, all in an entity-wide OWN scope (a shop-scoped session is refused with `m1.user.entity_scope_required`). Paths under `/v1/security/users` in `openapi/m1party.yaml`; `gov.user.view` reads them.

- **The provider** is reached only through `kernel.api.IdentityProviderClient`. CreateUser writes the row PENDING, then creates the login (the platform's user id is its `uid`), then stores the subject on `provider_subject`. DeactivateUser disables the login and ends its sessions.
- **Credentials**: `ResetCredential` with `credential` PASSWORD (a one-time password from the provider), SECOND_FACTOR (the provider forgets the TOTP) or PIN (the till PIN). A PENDING or LOCKED user becomes ACTIVE with a new password or PIN (`user.activated.v1`). The temporary password goes out by a notification when M9 holds an ACTIVE rule under the key `user.temporary_password` whose audience resolves (`TemporaryPasswordDelivery`); otherwise it is answered once (`delivery: RETURNED`), marked `@JsonIgnore` so the idempotency store never keeps it, and a replay answers without it.
- **PIN policy** (`PinPolicy`, doc 19 section 2.1 and DR-5): digits only, `security.pin.length_min`/`length_max` (4 to 6, the entity may narrow, never widen), none of the last `security.pin.history_depth` (3). Argon2id through `kernel.api.PinHasher`; `pin_history` keeps the last hashes, newest first. `security.pin.lockout_attempts` and `lockout_duration` are till-visible configuration: the till counts attempts, not the backend.
- **Deactivation guards**, in order: in scope; not already deactivated; a reason; not the last holder of `gov.user.manage` entity-wide (as ActivateEntity counts them); not the entity's responsible officer. The PIN hash is cleared; assignments are kept.
- **Row-level security** (`m1security/V0012`): a shop-scoped session reads the users with an assignment at its shop or an entity-wide one, never a sibling shop's operators; an entity-wide session reads all the entity's users.
- **Events** carry the user's id twice (`appUserId` for the outbox's aggregate id, `userId` for consumers such as the kernel's permission cache) and never a credential.

### Deviations from the implementation guide (M1-07)

- **User events** carry `appUserId` beside `userId` (the outbox skips `userId` when it looks for the aggregate id). `user.updated.v1` is added for the change of details, which 21A does not list.
- **Activation**: doc 21 section 4.4 activates a user on "first credential set (provider callback)"; there is no callback, so the platform activates when it issues the first password or PIN.
- **Deactivation** also refuses the entity's responsible officer (doc 21 DR-1); "no open till session" waits for M6's query, as in M1-05.

## Roles, assignments and separation-of-duties pairs (M1-08)

| Where | What |
|---|---|
| `api/` | Commands `CreateRole`, `AmendRole`, `RetireRole`, `AssignRole`, `RevokeRole`, `SetSodPair`, `RemoveSodPair` and the value `RolePermission` (a code and its limits); events `role.changed.v1` (`RoleChanged`), `role.assigned.v1`, `role.revoked.v1` (they carry `userId`, which is what the kernel's permission cache reads to forget that user) and `sod_pair.changed.v1`. |
| `query/SecurityQueries` | `listRoles`, `getRole`, `getRoleDiff` (the template drift diff), `listSodPairs`, `listAssignments`; implemented in `internal/queries/SecurityQueriesImpl`, read under the caller's row-level security. |
| `internal/security/role/` | One handler per command (all `gov.role.manage`, MFA); `RoleGuards` (the guards they share, in 21A's order); `RoleRules` (the guardrails as plain functions, so the property tests can run them thousands of times); `SecurityRecords` (the reads). |
| `web/` | `RolesController`, `AssignmentsController`, `SodPairsController`. |
| `db/migration/m1security/V0010` | The three DELETEs M1 performs, the Federation's template policies, `security.role_assignment_count`. |

The guardrails, as the handlers apply them: roles are authored entity-wide in the OWN class; nobody grants a permission they do not hold, on a role or by assigning one (doc 19 section 3.2); FEDERATION-scope permissions only in federation-owned roles (templates and the Federation's own roles); no role, and no person through two roles, holds both halves of a pair in ROLE mode; limits are checked against the permission's `limits_schema`; an entity never loses its last holder of `gov.user.manage` (by revoke or by amending the role); a role is retired only when nobody holds it; an entity adds pairs and raises them to ROLE mode, never lowers a federation default, and cannot raise a pair while a role or a person already holds both.

Template drift (doc 19 DR-4, "notify and offer diff"; assumes doc 10 E-04): a clone keeps `template_role_id` and `template_version_seen`; when the Federation amends the template its version rises, the clone's `templateUpdated` marker shows and `GET /v1/security/roles/{roleId}/diff` lists what the two disagree on. Nothing is pushed into the clone: the administrator amends the role with `adoptTemplateVersion` once they agree.

The property tests (`RoleGuardrailsPropertyPostgresIntegrationTest`, the two properties of 21A section 9 through the real handlers) keep their own model of what has been arranged and accepted, and say from it which message id each generated command must get; the guard under test is never its own oracle.

### Deviations from the implementation guide (M1-08)

- **RevokeRole is `POST /v1/security/assignments/revoke`**, not `DELETE /v1/security/assignments` (21A section 5): the kernel's idempotency check hashes the path and the body, not the query string, so a DELETE with the assignment in the query would replay one revoke for another under a reused key, and a DELETE body is poorly supported by clients.
- **Operations 21A does not list** were added because the commands of section 6 need a door: `GET /v1/security/roles/{roleId}`, `PUT .../permissions` (AmendRole), `POST .../retire`, `GET /v1/security/assignments`, and `/v1/security/sod-pairs` (list, set, remove). All carry `gov.role.manage`.
- **Three tables app_rw may delete from** (`user_role`, `role_permission`, `sod_pair`), named in `SchemaRulesIntegrationTest.DELETE_BY_DESIGN`: 21A makes revoke a delete and AmendRole a set replace; 17A section 6.2 says "no DELETE anywhere". The two documents disagree; the guide's procedure is followed for these three tables only.
- **FEDERATION-scope permissions in the Federation's own roles**: the 21A pseudocode admits them in templates only; doc 21 section 3.5 says "federation-owned roles". The Federation's own roles are federation-owned, and without them its staff could hold `gov.entity.register` only through a template.
- **Two guards beyond 21A's table, both from doc 19 section 3.2**: AssignRole checks that the grantor holds every permission of the role (assigning is granting), and a ROLE-mode pair is checked per person across their roles as well as per role.
- **Limits**: the catalogue has no `limits_schema` yet, so today no permission takes limits (`m1.role.limits_not_accepted`). The schema reader understands a small subset of JSON Schema (`properties` with `type`, `minimum`, `maximum`; `required`; nothing else admitted); there is no JSON Schema library in the version catalogue.
- **No template notification is sent**: the marker and the diff are there; a notification on `role.changed.v1` of a template is a K-10 rule for M9 to write.

## External grants (M1-09)

`internal/grant`: the time-boxed read-only access of a regulator or auditor, doc 21 section 4.6 and flow 6.5, as 21A section 6 specifies it. `POST /v1/security/external-grants`, `POST /v1/security/external-grants/{grantId}/revoke` and `GET /v1/security/external-grants`, all `gov.external.grant` with MFA, Federation only.

- **Revoke has its own path.** 21A section 5 lists only `POST` and `GET /v1/security/external-grants`; RevokeExternalView is `POST /v1/security/external-grants/{grantId}/revoke`, like the entity status commands.
- **Expiry is a command.** Doc 21 section 4.6 has ACTIVE to EXPIRED "by the clock"; only a command handler writes, so the job `external-grant-expiry` sends `ExpireExternalGrant` for each ended grant. Its permission is `gov.external.grant` (the system acts for the Federation, which owns the grant); no request carries it, so the kernel checks none.
- **A grant cannot start in the past**: a missing or past `validFrom` is now. The twelve months (doc 21 DR-4, doc 10 L-07) are the configuration item `m1.external_grant.max_months`, which may shorten them and never lengthen them past the table's CHECK constraint.
- **The grantee reads its own grants** through the policy `grantee_read` on `app.user_id` (m1security V0009): that is how `ExternalGrantQueries.activeGrantedEntities` resolves an external user's entities before the user has a scope.
- **What an external caller reads**: in `security`, the `role`, `role_permission`, `user_role` and `sod_pair` rows of the granted entities (m1security `V0009`); in `party`, the `location` and `till_position` rows (m1party `V0005`), the `entity_relationship` rows (`V0007`) and the `device` rows (`V0009`) of the granted entities, each through the template's `ext_view` policy. Not `security.app_user` (it carries `pin_hash`), and not `party.entity` yet (noted by the review of the RLS matrix, #119). The header of `m1security/V0009` says the party schema has no `ext_view`: it was written before `m1party/V0005` merged, and a merged migration is never edited; this file is the record.
