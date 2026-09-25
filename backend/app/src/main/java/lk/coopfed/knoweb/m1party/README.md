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

## Users and credentials (M1-07)

`internal/user`: `CreateUser`, `UpdateUser`, `ResetCredential`, `DeactivateUser`, all `gov.user.manage` with MFA, all in an entity-wide OWN scope (a shop-scoped session is refused with `m1.user.entity_scope_required`). Paths under `/v1/security/users` in `openapi/m1party.yaml`; `gov.user.view` reads them.

- **The provider** is reached only through `kernel.api.IdentityProviderClient`. CreateUser writes the row PENDING, then creates the login (the platform's user id is its `uid`), then stores the subject on `provider_subject`. DeactivateUser disables the login and ends its sessions.
- **Credentials**: `ResetCredential` with `credential` PASSWORD (a one-time password from the provider), SECOND_FACTOR (the provider forgets the TOTP) or PIN (the till PIN). A PENDING or LOCKED user becomes ACTIVE with a new password or PIN (`user.activated.v1`). The temporary password goes out by a notification when M9 holds an ACTIVE rule under the key `user.temporary_password` whose audience resolves (`TemporaryPasswordDelivery`); otherwise it is answered once (`delivery: RETURNED`), marked `@JsonIgnore` so the idempotency store never keeps it, and a replay answers without it.
- **PIN policy** (`PinPolicy`, doc 19 section 2.1 and DR-5): digits only, `security.pin.length_min`/`length_max` (4 to 6, the entity may narrow, never widen), none of the last `security.pin.history_depth` (3). Argon2id through `kernel.api.PinHasher`; `pin_history` keeps the last hashes, newest first. `security.pin.lockout_attempts` and `lockout_duration` are till-visible configuration: the till counts attempts, not the backend.
- **Deactivation guards**, in order: in scope; not already deactivated; a reason; not the last holder of `gov.user.manage` entity-wide (as ActivateEntity counts them); not the entity's responsible officer. The PIN hash is cleared; assignments are kept.
- **Row-level security** (`m1security/V0012`): a shop-scoped session reads the users with an assignment at its shop or an entity-wide one, never a sibling shop's operators; an entity-wide session reads all the entity's users.
- **Events** carry the user's id twice (`appUserId` for the outbox's aggregate id, `userId` for consumers such as the kernel's permission cache) and never a credential.

## Deviations from the implementation guide

- **M1-07, user events** carry `appUserId` beside `userId` (the outbox skips `userId` when it looks for the aggregate id). `user.updated.v1` is added for the change of details, which 21A does not list.
- **M1-07, activation**: doc 21 section 4.4 activates a user on "first credential set (provider callback)"; there is no callback, so the platform activates when it issues the first password or PIN.
- **M1-07, deactivation** also refuses the entity's responsible officer (doc 21 DR-1); "no open till session" waits for M6's query, as in M1-05.
