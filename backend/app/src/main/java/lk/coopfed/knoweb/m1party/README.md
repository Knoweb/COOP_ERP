# m1party — M1 Party, Tenancy & Security

Scaffolded from the hello module by `make new-module`. This file is the living guide of the module (AGENTS.md): once code exists, it and the tests supersede the implementation guide for day-to-day work. Record every deviation from the guide here, with the reason.

Read `hello/README.md` first: the six rules it lists apply here unchanged.

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

## Locations and till positions (M1-05)

`internal/location`: `Location` and `TillPosition` (JPA), one handler per command, `LocationGuards` (the shared guards), `LocationFacts` (reads the device at a position and whether a shop has an operator), `SeriesHooks` (every call to the kernel's `NumberingService`), `TradingHours` (the JSON of `trading_hours`). Controller `web/LocationsController`, tag `Locations` of the slice.

- A **shop** gets its LOCATION series (GRN, WOF, CNT, RPK, XFR) when it is registered; a warehouse or an office gets none and numbers from the entity's series. Which types are per location or per till is read from the kernel's document type registry, never listed in M1.
- A **till position** gets its TILL_POSITION series (RCT, CPR) in the same transaction as the row (the "done when" of M1-05). Retiring it closes them; a closed series is never reopened and a position number is never reused.
- **SetPrimaryTill** moves the counters of the shop's location series to the device at the new primary till, through `NumberingService.holderChange`; with no device there yet (devices are M1-06) nothing moves, and the event says `holderDeviceId: null`. It also registers the shop's location series where they are missing (idempotent), so a shop from a seed gets them.
- Every command needs an OWN scope; RegisterLocation needs it entity-wide. Row-level security decides everything else: a shop-scoped caller reads, changes and adds positions to its own shop only.

## Deviations from the implementation guide

- **Permissions (M1-05).** 21A section 3.3's `prt.location.register`, `prt.location.activate` and `prt.location.primary` (MFA) are added to the catalogue beside the older `prt.location.manage`/`.view`, whose removal is `CR-21A-1` item 1's. UpdateLocation uses `prt.location.register` (21A names no update code); StartOnboarding, MarkDormant, Reactivate and ConfirmLocationConnectivity use `prt.location.activate` (doc 21 names `prt.location.onboard` and `.dormant`, which 21A's catalogue does not have).
- **Commands 21A does not list by name:** UpdateLocation (the facts of doc 21 section 7: hours, language, size band) and ConfirmLocationConnectivity (the gate of doc 21 section 3.3, "a fact, not a toggle"), both published as `location.updated.v1`, an event doc 21 section 5.3 does not list.
- **Guards added:** RetireTillPosition refuses the shop's primary till (`m1.position.is_primary`); SetPrimaryTill refuses the position that is already primary (`m1.location.primary_unchanged`). **Guards waiting:** "no open till session" (MarkDormant, RetireTillPosition) waits for M6's query; "device staged" (StartOnboarding, doc 21) waits for M1-06 and doc 31.
External grants (M1-09, package `internal/grant`):

- **Revoke has its own path.** 21A section 5 lists only `POST` and `GET /v1/security/external-grants`; RevokeExternalView is `POST /v1/security/external-grants/{grantId}/revoke`, like the entity status commands.
- **Expiry is a command.** Doc 21 section 4.6 has ACTIVE to EXPIRED "by the clock"; only a command handler writes, so the job `external-grant-expiry` sends `ExpireExternalGrant` for each ended grant. Its permission is `gov.external.grant` (the system acts for the Federation, which owns the grant); no request carries it, so the kernel checks none.
- **A grant cannot start in the past**: a missing or past `validFrom` is now. The twelve months (doc 21 DR-4, doc 10 L-07) are the configuration item `m1.external_grant.max_months`, which may shorten them and never lengthen them past the table's CHECK constraint.
- **The grantee reads its own grants** through the policy `grantee_read` on `app.user_id` (m1security V0009): that is how `ExternalGrantQueries.activeGrantedEntities` resolves an external user's entities before the user has a scope.
- **What an external caller reads here**: `security.role`, `role_permission`, `user_role` and `sod_pair` of the granted entities. Not `security.app_user` (it carries `pin_hash`), and nothing in `party` until an m1party migration adds `ext_view` there.
## Trading relationships (M1-04)

`internal/relationship`: the aggregate `Relationship` (one row of `party.entity_relationship`), its four handlers and the rules they share. Read the handlers in this order: `OpenTradingRelationshipHandler` (DRAFT), `ActivateRelationshipHandler` (ACTIVE), `AmendRelationshipTermsHandler` (21A section 6.1: close the current row the day before, open the next), `SuspendRelationshipHandler`.

- **Effective dating.** A row's terms never change once it is ACTIVE. An amendment closes the current row (`effective_to = effectiveFrom - 1 day`, the only update an ACTIVE row ever gets besides its status) and inserts the next row. The history of a pair is its rows ordered by `effective_from`; `RelationshipAmended` links each new row to the one it replaced.
- **One ACTIVE row per pair per date** (A-I3) is the exclusion constraint of `V0001`. The handlers check first (`RelationshipRules.requireNoOverlap`) so the refusal can name the row in the way, and translate the constraint's error (SQLSTATE 23P01) into the same `m1.relationship.overlap` when two clerks race.
- **Who may do what.** The seller, in its own entity-wide OWN scope, does everything; the buyer reads (policy `party_read`, in OWN or PARTY scope) and changes nothing. The seller cannot read the buyer's entity row, so the opening guards ask `party.trading_standing(entity)` (`V0007`) for the two facts they need: type and status.
- **Tier rule.** Federation to distributor, distributor to society; Federation to society only when `trade.federation_direct.enabled` is true (doc 10 F-03, default off).
- **Credit limit.** A change of the limit also needs `bil.creditlimit.change` and a second factor younger than `m1.relationship.credit_limit_mfa_max_age` (PT10M). Both are guards in the handler, because they apply only when the limit changes.
- **M3's price-list check** is the interface `api.TradePriceListCheck`: M1 publishes the question, M3 will answer it (M3 depends on M1, not the other way round). Until then `AcceptingPriceListCheck` accepts every list and says so at WARN; delete it in the pull request that gives M3's implementation.
- **Published queries.** `query.RelationshipQueries`: `lookupRelationship(seller, buyer, date)` for M3 and M4, `getRelationship`, `listRelationships(side)`.

## Deviations from the implementation guide

- **422, not 409, for an overlapping relationship** (21A section 5 shows 409): the kernel answers every broken business rule with 422 and a code (`CR-21A-1` item 3).
- **Message ids carry the module prefix** (`m1.relationship.overlap`, 21A writes `relationship.overlap`), as every M1 id does.
- **The credit-limit permission is a handler guard, not `requiresAlso`** (21A section 6): it applies only when the limit changes. Until K-03b puts a `PermissionResolver` on the server the permission part is skipped, as every permission is today; the second-factor part is enforced now.
- **Permission codes by verb** (`prt.relationship.open`, `.activate`, `.amend`, `.suspend`; `bil.creditlimit.change`) as 21A section 3.3 names them; the coarse `prt.relationship.manage` stays in the catalogue unused until the catalogue freeze (`CR-21A-1` item 1).
