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

## Deviations from the implementation guide

- **RevokeRole is `POST /v1/security/assignments/revoke`**, not `DELETE /v1/security/assignments` (21A section 5): the kernel's idempotency check hashes the path and the body, not the query string, so a DELETE with the assignment in the query would replay one revoke for another under a reused key, and a DELETE body is poorly supported by clients.
- **Operations 21A does not list** were added because the commands of section 6 need a door: `GET /v1/security/roles/{roleId}`, `PUT .../permissions` (AmendRole), `POST .../retire`, `GET /v1/security/assignments`, and `/v1/security/sod-pairs` (list, set, remove). All carry `gov.role.manage`.
- **Three tables app_rw may delete from** (`user_role`, `role_permission`, `sod_pair`), named in `SchemaRulesIntegrationTest.DELETE_BY_DESIGN`: 21A makes revoke a delete and AmendRole a set replace; 17A section 6.2 says "no DELETE anywhere". The two documents disagree; the guide's procedure is followed for these three tables only.
- **FEDERATION-scope permissions in the Federation's own roles**: the 21A pseudocode admits them in templates only; doc 21 section 3.5 says "federation-owned roles". The Federation's own roles are federation-owned, and without them its staff could hold `gov.entity.register` only through a template.
- **Two guards beyond 21A's table, both from doc 19 section 3.2**: AssignRole checks that the grantor holds every permission of the role (assigning is granting), and a ROLE-mode pair is checked per person across their roles as well as per role.
- **Limits**: the catalogue has no `limits_schema` yet, so today no permission takes limits (`m1.role.limits_not_accepted`). The schema reader understands a small subset of JSON Schema (`properties` with `type`, `minimum`, `maximum`; `required`; nothing else admitted); there is no JSON Schema library in the version catalogue.
- **No template notification is sent**: the marker and the diff are there; a notification on `role.changed.v1` of a template is a K-10 rule for M9 to write.
