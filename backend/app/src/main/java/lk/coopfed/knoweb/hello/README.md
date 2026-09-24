# hello â€” the template module

A deliberately trivial module (17A section 12): it registers greetings and lists them. Its value is that it uses every kernel interface and follows every convention, so `make new-module` can copy it and a reviewer can point at it and say "done like this". When this file and 17A disagree, this file and the tests are right; deviations from the guide are listed at the end.

## What is where

| Path | What it holds |
|---|---|
| `api/` | Published contract: `RegisterGreeting` (command), `GreetingRegistered` (event), `GreetingQueries` and `GreetingView` (read side). Other modules may use this package only. |
| `internal/` | Private: `Greeting` (entity), `GreetingRepository`, `RegisterGreetingHandler`, `GreetingQueriesImpl`. Package-private classes; nothing outside the module can reach them. |
| `web/` | `HelloController implements HelloApi`, the interface generated from the slice; one method per operation, named after the `operationId`. |
| `resources/db/migration/hello/` | `V0001__greeting.sql`: table, row-level security from the 17A section 6.3 template, grants. |
| `resources/openapi/hello.yaml` | The API slice. Written first; the Java interface and the web client are generated from it. Shared parts are in `openapi/common.yaml`. |
| `resources/seed/hello/` | Development rows loaded by `make seed`. |
| `resources/i18n/{en,si,ta}.json` | Every message id the module can show, in three languages. |
| `web/src/modules/hello/` | `HelloPage`, `helloApi.ts` (the module's calls, through the shell's `useApiClient`; a module never calls `fetch`), `hello.messages.json`, and `module.tsx`: the `ModuleDefinition` (id, routes, navigation entries as message ids, required permissions) that is listed once in `web/src/modules/registry.ts`. The shell builds the router and the navigation from that list and puts the "not allowed" screen in front of every route; the page hides the form from a user without `hello.greeting.register` and says why. **Tokens only:** a module writes no hex colour and no px, rem or em literal; every colour, distance and font size is a token of `web/src/design/tokens.css` (`var(--space-2)`, `var(--color-alert-text)`), the page takes its shape from the shell class `shell-page`, and an amount of money is shown with `<MoneyDisplay amount={...} />` (`web/src/shell/components`), never formatted or calculated in a screen. What exists is shown at `/_design` in the running client. |
| `src/test/.../hello/HelloModuleIntegrationTest` | The proof table of 17A section 12 as tests. |

## The six rules this module demonstrates

1. **A handler is guards, mutation, audit, event, in one transaction, and nothing else.** See `RegisterGreetingHandler`. If any step throws, nothing is saved; `whenTheEventCannotBePublishedNothingIsSaved` proves it.
2. **Tenant filtering is the database's job.** No query contains `where owner_entity_id = ...`. Every `@Transactional` method that touches a table takes the `ScopeContext` as a parameter; the kernel puts it on the transaction and the policies in the migration filter the rows. Forget the parameter and you get no rows, never someone else's.
3. **A guard fails with a `ProblemException` whose id is a message id.** The kernel turns it into an RFC 9457 response titled in the caller's language. Add the id to all three `i18n` files or the build fails.
4. **The owning entity comes from the scope, never from the request.**
5. **Documents and ledgers are insert-only.** The table grants `SELECT, INSERT` to `app_rw` and nothing else; the entity has no setters.
6. **A point in time is an instant, everywhere.** `timestamptz` with `DEFAULT now()` in the database, `Instant` in Java, ISO-8601 ending in `Z` in the API, and Colombo wall-clock time only on the screen (`shell/i18n/formats.ts`). Never `timestamp` without zone or `LocalDateTime` for "when it happened", and never offset arithmetic. Hibernate is pinned to UTC in `application.yml`, and the integration tests run with the JVM in `Asia/Colombo` so a zone mistake fails `theRegistrationInstantIsExactUnderANonUtcServerZone`. A till fact also stores what the till clock showed, as a second, zone-less column (the `occurred_at` and `occurred_local` pair of 19A); a greeting has no device, so it has the instant only. When code needs the time it injects `java.time.Clock` and calls `clock.instant()`; it never calls `Instant.now()` or `LocalDate.now()`, which a test cannot fix and which follow the server's zone. A business date is not the calendar date: ask `kernel.api.BusinessDate.current(locationId)`.

What the module does not do, because the kernel does it for every module: idempotency (`Idempotency-Key`), building the scope, error responses, CORS.

## OpenAPI first: the slice, the interface, the controller

The slice `openapi/hello.yaml` is written first and is the only place where a path, a parameter, a status code or a JSON shape is defined. Two things are generated from it:

| Generated | By | Where | Committed |
|---|---|---|---|
| Java interface `HelloApi` and the request and response classes | the Gradle build, on every compile | `build/generated/openapi/hello`, package `hello.web.generated` | no, it is build output |
| TypeScript types for the web client | `make gen-clients` | `web/src/generated/hello.ts` | yes; `make check-generated` fails when it is stale |

`HelloController implements HelloApi`, with no mapping annotations of its own. So:

- add an operation to the slice and the build fails until the controller has it ("does not override abstract method ...");
- change a parameter or a response type and the controller stops compiling until it follows;
- a controller with hand-written mappings fails `ArchitectureTests.controllersImplementTheirGeneratedApi`.

A slice also has to obey the rules in `OpenApiSliceRulesTest`: OpenAPI 3.1, paths under `/v1/`, a unique `operationId` (it is the Java method name), one tag (it names the interface: `Hello` gives `HelloApi`), an `x-permission` on every operation, and the `Idempotency-Key` header on every mutating one. What all slices share (that header, the `Problem` document, the 400 and 422 responses) is in `openapi/common.yaml` and is referred to, never copied.

The generated methods have no room for an extra parameter, so a controller asks the kernel for the caller's scope: `currentScope.get()` (`kernel.api.CurrentScope`). Only controllers do that; handlers and queries receive the scope as a parameter, and an architecture rule fails the build for a class outside `web` that uses `CurrentScope` (a handler that did would work on the web and fail for a till sync batch or a job, where there is no request).

### Shape in the slice, rules in the handler

A request can be wrong in two ways, and each has one home.

| | Where it is written | Who checks it | Answer |
|---|---|---|---|
| **Shape**: a required field is missing, a text is longer than `maxLength`, a number is under `minimum`, an id is not a UUID, the JSON is broken | the schema in the slice, and nowhere else | the kernel, before the controller runs (`RequestValidationHandler`) | 400 `request.invalid` with an `errors` list, one entry per field: `{ field, code, message, params }`; or 400 `request.malformed` |
| **Rule**: the text is only spaces, the greeting exists already, the period is closed | a guard in the handler | the handler | 422 with the rule's own message id |

The module writes no code and no message for the first row: the codes are generic (`request.field.required`, `too_short`, `too_long`, `too_small`, `too_large`, `format`, `invalid`) and already translated. So a forgotten null check cannot become a 500: say `required` in the slice and the handler never sees the null.

A rule that must also hold when the command does not arrive over HTTP (till sync, a job) is a guard, even if the slice says it too: the slice protects the door, the guard protects the data. `hello.greeting.text_required` is the example: the slice says `minLength: 1`, and the handler still refuses a text of spaces.

## What stops a forgotten audit record

A handler that forgets `audit.record(...)` breaks nothing visible: the trail just has a hole. Four things make that hard to do, in the order you will meet them:

1. **The build fails** if a `@CommandHandler` class never calls `AuditFacade.record` and `EventPublisher.publish` (`ArchitectureTests.commandHandlersAuditAndPublish`).
2. **The build fails** if anything in a business module other than a command handler writes to the database, through a repository, an `EntityManager` or a `JdbcTemplate` (`onlyCommandHandlersWriteToTheDatabase`). A change that does not pass through a handler is a change nobody audited.
3. **The kernel stubs refuse bad calls on the first run**: an audit type that is not a catalogue code, a missing subject or scope, an event class without a versioned `TYPE`, and any call made outside the handler's transaction.
4. **Every handler test asserts what was recorded**, with the `kernel` field every integration test inherits (`KernelRecorder`): `kernel.committedAudit()` and `kernel.committedEvents()` hold what would be in the audit and outbox tables, `kernel.rolledBackAudit()` what a failed transaction took back. Take the expected audit event of each command from the handler specification in your module's implementation guide (section 6). The recorder watches the `AuditFacade` and `EventPublisher` interfaces and names no implementation: `KernelRecording` wraps whichever bean implements them, the real service runs first with all its own refusals, and what it was asked to do is recorded afterwards. So these assertions mean the same thing when 19A K-04 and K-05 replace the logging stubs with the audit table and the outbox writer, and no test changes.

The build rules prove the calls exist; only your test proves they are right. A fifth check comes with the audit catalogue of 19A K-04: every code used exists in the catalogue, and every catalogue code is used somewhere.

## What the build checks for you

`make test` runs all of these, and so does the pipeline. Each has a deliberately broken example that proves it catches what it says (`ArchitectureRulesBiteTest`, `OpenApiSliceRulesTest`, `tools/checks.test.mjs`), so when one fails on your code, read the sentence it prints: it names the class or the line and what to write instead.

| If you ... | ... this fails |
|---|---|
| depend on a module your `package-info.java` does not allow, or on another module's `internal` | `modulithStructureIsValid`, `layersAreRespected` |
| use anything of the kernel but `kernel.api` | `businessModulesDoNotAccessKernelInternals` |
| map an entity to another module's schema, to no schema, or write `@Entity` without `@Table` | `entitiesStayInTheirModuleSchema` |
| put an entity, or anything from `internal` or `web`, into your `api` package | `publishedPackagesHoldNoEntitiesAndNoInternals` |
| write an event that is not a record in `api` with a `TYPE` like `pricing.price_list.published.v1` | `domainEventsAreVersionedRecords` |
| write a handler without a permission, or leave the scaffold's `todo.` permission | `commandHandlersCarryPermissions`, `tools/check-permissions.mjs` |
| write a handler that never audits or never publishes | `commandHandlersAuditAndPublish` |
| write a handler whose `handle` is not `@Transactional` | `commandHandlersRunInOneTransaction` |
| write to the database from anything but a command handler | `onlyCommandHandlersWriteToTheDatabase` |
| call `Instant.now()`, `LocalDate.now()`, `System.currentTimeMillis()` or `new Date()` where you should use the injected `Clock` | `businessModulesReadTheTimeFromTheKernelClock` |
| write a controller with its own mappings, or read `CurrentScope` outside `web` | `controllersImplementTheirGeneratedApi`, `onlyControllersAskForTheCurrentScope` |
| break a slice rule (no permission, no tag, no Idempotency-Key, a comma inside an inline description) | `OpenApiSliceRulesTest` |
| name a permission in a handler that its slice does not have | `tools/check-permissions.mjs` |
| touch another module's schema in a migration or a seed, leave a table name without its schema, add a cross-module foreign key, or misspell the migration folder | `tools/check-schema-ownership.mjs` |
| create a table without row-level security (enabled, forced, at least one policy), or grant `DELETE` or `TRUNCATE` to `app_rw` | `SchemaRulesIntegrationTest` (`make test-int`): it reads the migrated database, so your tables are checked without registering them |
| answer with a message id that is in no catalogue, leave one language out, leave a text empty, lose a `{0}` in a translation, or type `'` instead of `â€™` | `tools/check-i18n.mjs` |
| change a slice or a module dependency without committing what it generates | `make check-generated` |
| write a hex colour or a px, rem or em literal in `web/src/modules` instead of a design token | `web/src/design/moduleStyle.test.ts` (`pnpm test` in `web/`, and the pipeline) |
| change a colour token so that a text falls under the WCAG contrast (4.5:1, large text and icons 3:1), or add a colour that no contrast pair checks | `web/src/design/tokens.test.ts` |

## Starting a real module from this one

```bash
make new-module NAME=m3pricing SCHEMA=pricing ENTITY=price_list   # DRY_RUN=1 to preview
```

copies everything listed under "What is where" with the names changed, registers the module in the shared files, and writes a README into the new package that lists what to change by hand, in order. The first item is the permissions: the copy carries placeholders (`todo....`) that `make test` refuses, because only the module's guide knows the real codes. Keep hello correct: whatever is wrong here is copied into every module, and `make test-scaffold` (also in CI) fails when a change to hello is something the copy can no longer build or pass.

## Running it

```bash
make up          # stack, migration, seed rows
make test        # unit and architecture tests
make test-int    # this module against a real PostgreSQL (needs Docker, not the stack)
```

Then open http://localhost:5173/hello, or `?lang=si` and `?lang=ta`. Two seeded greetings of the development MPCS appear; the one without a translation carries the EN tag.

With curl, the scope is a header until login exists:

```bash
curl -H "X-Scope-Entity: 0190f000-0000-7000-8000-000000000002" http://localhost:8080/v1/hello/greetings
```

Changed the slice? Run `make gen-clients` and commit `web/src/generated/hello.ts`. Changed the migration while Sprint 0 has no deployed database? Edit `V0001` and tell the team to `make reset`; once a database that matters exists, every change is a new `V000n` file.

## Proof table (17A section 12)

| Row | State |
|---|---|
| Boundary tests pass with a real module present | Passes: `ArchitectureTests` |
| Migration, schema, RLS and grants | Passes: isolation, federation view, no scope, UPDATE and DELETE denied |
| Command pipeline: idempotency, audit, outbox in one transaction | Passes against the 17A stubs: same key returns the same greeting and the handler runs once; one audit call and one event; rollback when the event fails. Audit and outbox *rows* arrive with 19A K-04 and K-05, which own those tables |
| OpenAPI-first controller and generated web client | Passes: the controller implements the generated `HelloApi`, the page posts a greeting and lists it |
| i18n end to end | Web passes in three languages with the EN fallback tag. The till screen is open |
| Shared engine reachable from both sides | Backend passes (`SharedEngineSmokeTest`); the till-side parity test is open |
| Two instances | Passes: the pipeline's stack-smoke job starts two backend instances behind nginx (`make up-2`), runs `make smoke TWO=1`, which posts a greeting through one instance and retries the same `Idempotency-Key` through the other and gets the same greeting back (19A K-03a: the key is claimed inside the handler's transaction, `kernel.idempotency_key`), then the Playwright suite (`make e2e`) |

## Deviations from 17A, with reasons

- **No `entityId` in `RegisterGreeting`.** The guide lists it, and the guide's own handler ignores it. A client must not name the entity it writes for.
- **Audit and event backbones log, they do not insert.** 17A section 4.3 says the Sprint 0 stubs insert into `kernel.audit_event` and `kernel.event_outbox`; 19A names those migrations (`V0002`, `V0003`), partitions them and gives them to K-04 and K-05. Creating them here would pre-empt that design, so the tests count calls instead of rows.
- **No `ext_view` policy yet.** It needs `kernel.granted_entities()` from K-01.
- **`kernel.api.CurrentScope` is an addition to the kernel contract** (S0-06): the design says the scope filter keeps the `ScopeContext` as a request attribute but names no way for a controller to read it, and a generated interface leaves no room for a scope parameter. Its 17A stub reads headers; 19A K-02 replaces the stub, not the interface.
- **Scope, idempotency, problem responses and CORS are new 17A stubs** in `kernel/internal/stub`, each naming the 19A ticket that replaces it. Module code does not change when they are replaced.
- **Web: `helloApi.ts` sends a development scope header and the language comes from `?lang=`**, until the shell has login and its API client (S0-07).
- **Testcontainers 1.21.4** overrides Spring Boot's managed 1.19, which Docker Engine 29 refuses.
