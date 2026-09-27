// Proof that the pipeline checks bite
// (17A S0-05: "a deliberate violation fails the build").
//
// Each test hands a check something broken and expects the sentence
// that reports it.
//
//   node --test tools/checks.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import {
  OWNERSHIP,
  MIGRATION_OWNERSHIP,
  problemsOfMigration,
  problemsOfMigrations
} from "./check-schema-ownership.mjs";

import {
  messageIdsUsedIn,
  problemsOfCatalogues,
  problemsOfJavaSources
} from "./check-i18n.mjs";

import {
  MODULES
} from "./new-module.mjs";

import {
  problemsOfHandlerSource,
  readSlices
} from "./check-permissions.mjs";

import {
  compareVersions,
  summarise,
  versionToMoveTo
} from "./vulnerability-report.mjs";

import {
  problemsOfFrozenContract
} from "./check-frozen-contracts.mjs";

import {
  buildIndex
} from "./progress-index.mjs";

import {
  operationsOf,
  problemsOfSlice,
  problemsOfSlices
} from "./check-slices.mjs";

const repo = path.resolve(
  path.dirname(
    fileURLToPath(import.meta.url)
  ),
  ".."
);

function tempDir() {
  return fs.mkdtempSync(
    path.join(
      os.tmpdir(),
      "coop-checks-"
    )
  );
}

function write(root, relative, content) {
  const file = path.join(
    root,
    relative
  );

  fs.mkdirSync(
    path.dirname(file),
    { recursive: true }
  );

  fs.writeFileSync(
    file,
    content
  );
}

function one(problems, pattern) {
  assert.equal(
    problems.length,
    1,
    `expected exactly one problem, got: ${JSON.stringify(
      problems,
      null,
      2
    )}`
  );

  assert.match(
    problems[0],
    pattern
  );
}

// ---- schema ownership -------------------------------------------------------

const catalogue = (sql) =>
  problemsOfMigration(
    "m2catalogue",
    "V0001__x.sql",
    sql
  );

test(
  "a migration in its own schema passes, policies and kernel functions included",
  () => {
    assert.deepEqual(
      catalogue(`
        CREATE TABLE catalogue.sku (
            id              uuid PRIMARY KEY,
            owner_entity_id uuid NOT NULL,
            category_id     uuid REFERENCES catalogue.category (id)
                ON UPDATE CASCADE
        );

        CREATE UNIQUE INDEX sku_owner_uq
            ON catalogue.sku (owner_entity_id, id);

        ALTER TABLE catalogue.sku
            ENABLE ROW LEVEL SECURITY;

        CREATE POLICY sku_own
            ON catalogue.sku
            USING (
                owner_entity_id = kernel.scope_entity()
            );

        GRANT SELECT, INSERT, UPDATE
            ON catalogue.sku
            TO app_rw;

        INSERT INTO catalogue.sku (
            id,
            owner_entity_id
        )
        VALUES (
            gen_random_uuid(),
            gen_random_uuid()
        )
        ON CONFLICT (id)
        DO UPDATE SET
            owner_entity_id = EXCLUDED.owner_entity_id;
      `),
      []
    );
  }
);

test(
  "a table in another module's schema is refused",
  () => {
    one(
      catalogue(
        "CREATE TABLE party.sku (id uuid);"
      ),
      /V0001__x\.sql:1: this statement touches schema "party"; m2catalogue owns catalogue/
    );
  }
);

test(
  "writing rows into another module's schema is refused",
  () => {
    one(
      catalogue(
        "\nUPDATE pricing.price_list SET status = 'X';"
      ),
      /:2: this statement touches schema "pricing"/
    );
  }
);

test(
  "a policy, an index, a view and a function in another schema are refused",
  () => {
    const problems = catalogue(`
      CREATE POLICY p
          ON party.entity
          USING (true);

      CREATE INDEX i
          ON party.entity (id);

      CREATE OR REPLACE VIEW party.v
          AS SELECT 1;

      CREATE FUNCTION kernel.mine()
          RETURNS int
          LANGUAGE sql
          AS 'select 1';
    `);

    assert.equal(
      problems.length,
      4,
      JSON.stringify(
        problems,
        null,
        2
      )
    );
  }
);

test(
  "a grant on another module's schema is refused",
  () => {
    one(
      catalogue(
        "GRANT SELECT ON ALL TABLES IN SCHEMA party TO app_rw;"
      ),
      /names schema "party"/
    );
  }
);

test(
  "creating a schema is the baseline's job",
  () => {
    one(
      catalogue(
        "CREATE SCHEMA IF NOT EXISTS extras;"
      ),
      /names schema "extras"/
    );
  }
);

test(
  "a name without a schema is refused, with the name to write",
  () => {
    one(
      catalogue(
        "CREATE TABLE sku (id uuid);"
      ),
      /"sku" has no schema; write catalogue\.sku/
    );

    one(
      catalogue(
        "CREATE INDEX i ON sku (id);"
      ),
      /"sku" has no schema/
    );
  }
);

test(
  "a foreign key into another module is refused",
  () => {
    one(
      catalogue(
        "CREATE TABLE catalogue.sku " +
        "(id uuid, owner uuid REFERENCES party.entity (id));"
      ),
      /a foreign key into schema "party"; across modules keep the identifier only/
    );
  }
);

test(
  "dynamic SQL is read through its string literals",
  () => {
    // The one the review wrote to prove the gap: a scratch migration writing security.app_user
    // through EXECUTE format(...) passed the check.
    one(
      catalogue(`
        DO $$
        BEGIN
            EXECUTE format('UPDATE security.app_user SET status = %L', 'DISABLED');
        END
        $$;
      `),
      /:4: dynamic SQL \(EXECUTE\) touches schema "security"; m2catalogue owns catalogue/
    );

    // A statement whose literals name no schema hides what it touches (%s), and is refused.
    one(
      catalogue(`
        DO $$
        BEGIN
            EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        END
        $$;
      `),
      /dynamic SQL \(EXECUTE\) names no schema the check can read; write catalogue\.%I inside the string/
    );

    // The module's own schema, a kernel function called in the policy text, a trigger's
    // EXECUTE FUNCTION and GRANT EXECUTE all pass.
    assert.deepEqual(
      catalogue(`
        CREATE OR REPLACE FUNCTION catalogue.ensure(name text)
        RETURNS void LANGUAGE plpgsql AS $$
        BEGIN
            EXECUTE format('CREATE TABLE IF NOT EXISTS catalogue.%I (LIKE catalogue.batch)', name);
            EXECUTE format('CREATE POLICY own_read ON catalogue.%I FOR SELECT TO app_rw USING ('
                           || 'owner_entity_id = kernel.scope_entity())', name);
            EXECUTE 'ALTER TABLE catalogue.' || quote_ident(name) || ' FORCE ROW LEVEL SECURITY';
        END;
        $$;

        CREATE TRIGGER t BEFORE INSERT ON catalogue.batch
            FOR EACH ROW EXECUTE FUNCTION catalogue.keep_one();

        GRANT EXECUTE ON FUNCTION catalogue.ensure(text) TO app_rw;
      `),
      []
    );
  }
);

test(
  "comments are not statements",
  () => {
    assert.deepEqual(
      catalogue(`
        -- the old script did UPDATE party.entity here

        /*
         * and once:
         * DROP TABLE pricing.price_list;
         */

        CREATE TABLE catalogue.sku (
            id uuid
        );
      `),
      []
    );
  }
);

test(
  "a migration folder that is no known module is reported, not skipped",
  () => {
    const root = tempDir();

    write(
      root,
      "m2catalog/V0001__typo.sql",
      "CREATE TABLE party.anything (id uuid);"
    );

    one(
      problemsOfMigrations(root),
      /^m2catalog\/: not a module this project knows/
    );
  }
);

test(
  "the reserved phase 2 module owns no schema yet",
  () => {
    one(
      problemsOfMigration(
        "m10procurement",
        "V0001__early.sql",
        "CREATE TABLE procurement.po (id uuid);"
      ),
      /m10procurement owns no schema yet/
    );
  }
);

test(
  "the real migrations and seeds pass",
  () => {
    execFileSync(
      process.execPath,
      [
        "tools/check-schema-ownership.mjs"
      ],
      {
        cwd: repo,
        stdio: "pipe"
      }
    );
  }
);

// ---- ownership registries --------------------------------------------------
//
// ArchitectureTests models Spring Modulith ownership.
// FlywayConfig models migration locations.
//
// M1 is intentionally:
//   logical module:      m1party -> party + security
//   migration locations: m1party -> party
//                        m1security -> security

test(
  "the tools, ArchitectureTests and FlywayConfig agree on schema ownership",
  () => {
    const sorted = (map) =>
      Object.fromEntries(
        Object
          .keys(map)
          .sort()
          .map((name) => [
            name,
            [...map[name]].sort()
          ])
      );

    const strings = (text) =>
      [
        ...text.matchAll(
          /"([^"]+)"/g
        )
      ].map(
        (match) => match[1]
      );

    const tests = fs.readFileSync(
      path.join(
        repo,
        "backend/app/src/test/java/lk/coopfed/knoweb/ArchitectureTests.java"
      ),
      "utf8"
    );

    const inTests =
      Object.fromEntries(
        [
          ...tests.matchAll(
            /Map\.entry\("([a-z0-9]+)",\s*Set\.(?:<String>)?of\(([^)]*)\)\)/g
          )
        ].map(
          (match) => [
            match[1],
            strings(match[2])
          ]
        )
      );

    assert.deepEqual(
      sorted(inTests),
      sorted(OWNERSHIP),
      "ArchitectureTests.SCHEMA_OWNERSHIP differs from the tools"
    );

    const flyway = fs.readFileSync(
      path.join(
        repo,
        "backend/app/src/main/java/lk/coopfed/knoweb/config/FlywayConfig.java"
      ),
      "utf8"
    );

    const migrated =
      Object.fromEntries(
        [
          ...flyway.matchAll(
            /"classpath:db\/migration\/([a-z0-9]+)",\s*new String\[\]\s*\{([^}]*)\}/g
          )
        ].map(
          (match) => [
            match[1],
            strings(match[2])
          ]
        )
      );

    const withSchemas =
      Object.fromEntries(
        Object
          .entries(
            MIGRATION_OWNERSHIP
          )
          .filter(
            ([, schemas]) =>
              schemas.length > 0
          )
      );

    assert.deepEqual(
      sorted(migrated),
      sorted(withSchemas),
      "FlywayConfig migrates a different list than the migration ownership registry"
    );

    assert.deepEqual(
      Object
        .keys(MODULES)
        .filter(
          (name) =>
            !(name in OWNERSHIP)
        ),
      []
    );
  }
);

// ---- message catalogue -----------------------------------------------------

const texts = (
  en,
  si = en,
  ta = en
) => ({
  en,
  si,
  ta
});

test(
  "a complete catalogue passes",
  () => {
    assert.deepEqual(
      problemsOfCatalogues(
        texts({
          "a.b":
            "Too long: the maximum is {0}",
          "a.c":
            "It’s fine"
        })
      ),
      []
    );
  }
);

test(
  "an id missing from one language is refused",
  () => {
    one(
      problemsOfCatalogues(
        texts(
          {
            "a.b": "x"
          },
          {
            "a.b": "x"
          },
          {}
        )
      ),
      /^ta\.json: no text for a\.b/
    );
  }
);

test(
  "an empty text is refused",
  () => {
    one(
      problemsOfCatalogues(
        texts(
          {
            "a.b": "x"
          },
          {
            "a.b": "  "
          },
          {
            "a.b": "x"
          }
        )
      ),
      /^si\.json: the text of a\.b is empty/
    );
  }
);

test(
  "the ASCII apostrophe is refused, because a message format drops it and the value after it",
  () => {
    one(
      problemsOfCatalogues(
        texts(
          {
            "a.b":
              "Don't use {0}"
          },
          {
            "a.b":
              "x {0}"
          },
          {
            "a.b":
              "y {0}"
          }
        )
      ),
      /ASCII apostrophe/
    );
  }
);

test(
  "a Sinhala or Tamil text that a codepage turned into question marks is refused",
  () => {
    one(
      problemsOfCatalogues(
        texts(
          { "a.b": "Enter the VAT number" },
          { "a.b": "???? ??????? VAT ???? ??????" },
          { "a.b": "VAT எண்ணை உள்ளிடவும்" }
        )
      ),
      /si\.json: the text of a\.b is .* lost the script/
    );
    // A text of digits, punctuation and Latin words alone (a code, a unit) is not a lost
    // script, and a question in a real script is a question.
    assert.deepEqual(
      problemsOfCatalogues(
        texts(
          { "a.b": "VAT-12 (kg)" },
          { "a.b": "VAT-12 (kg)" },
          { "a.b": "VAT-12 (kg)" }
        )
      ),
      []
    );
    assert.deepEqual(
      problemsOfCatalogues(
        texts(
          { "a.b": "Continue?" },
          { "a.b": "ඉදිරියට යන්නද?" },
          { "a.b": "தொடரவா?" }
        )
      ),
      []
    );
  }
);

test(
  "a translation that lost its placeholder is refused",
  () => {
    one(
      problemsOfCatalogues(
        texts(
          {
            "a.b":
              "between {0} and {1}"
          },
          {
            "a.b":
              "{0} - {1}"
          },
          {
            "a.b":
              "{0}"
          }
        )
      ),
      /a\.b: the three texts do not name the same arguments: en \{0 1\}, si \{0 1\}, ta \{0\}/
    );
  }
);

test(
  "a translation that names another ICU argument, inside a plural too, is refused",
  () => {
    // The Tamil text renamed the argument: {count} became {n}, which MessageFormat leaves
    // unfilled. Found inside the plural branches, not only at the top level.
    one(
      problemsOfCatalogues(
        texts(
          { "a.b": "{count, plural, one {# item for {name}} other {# items for {name}}}" },
          { "a.b": "{count, plural, one {අයිතමය # {name}} other {අයිතම # {name}}}" },
          { "a.b": "{n, plural, one {# பொருள் {name}} other {# பொருட்கள் {name}}}" }
        )
      ),
      /a\.b: the three texts do not name the same arguments: en \{count name\}, si \{count name\}, ta \{n name\}/
    );
    // The same arguments in another order, and a select form, pass.
    assert.deepEqual(
      problemsOfCatalogues(
        texts(
          { "a.b": "{gender, select, female {She} other {They}} paid {amount, number}" },
          { "a.b": "{amount, number} {gender, select, female {ඇය} other {ඔවුන්}} ගෙව්වා" },
          { "a.b": "{gender, select, female {அவள்} other {அவர்கள்}} {amount, number} செலுத்தினார்" }
        )
      ),
      []
    );
  }
);

test(
  "message ids are found as literals and as constants of the same file, not in comments",
  () => {
    const ids =
      messageIdsUsedIn(`
        class X {
            private static final String INVALID =
                "request.invalid";

            // throw new ProblemException("in.a.comment");

            void a() {
                throw new ProblemException(
                    "party.entity.duplicate",
                    Map.of()
                );
            }

            void b() {
                throw new ProblemException(
                    INVALID
                );
            }

            String c() {
                return messages.t(
                    "party.title",
                    locale
                );
            }

            void d(String id) {
                throw new ProblemException(
                    id
                );
            }

            Messages.Text e() {
                return messages.text(
                    "party.subtitle",
                    locale
                );
            }

            String f(JsonNode node) {
                return node.asText("not.an.id");
            }
        }
      `).map(
        (found) => found.id
      );

    assert.deepEqual(
      ids,
      [
        "party.entity.duplicate",
        "request.invalid",
        "party.title",
        "party.subtitle"
      ]
    );
  }
);

test(
  "an id the code answers with but no catalogue has is refused",
  () => {
    const root = tempDir();

    write(
      root,
      "m1party/internal/H.java",
      `class H {
          void h() {
              throw new ProblemException(
                  "party.entity.duplicate"
              );
          }
      }`
    );

    one(
      problemsOfJavaSources(
        root,
        texts({
          "party.other": "x"
        })
      ),
      /m1party\/internal\/H\.java:3: the code answers with "party\.entity\.duplicate", which is in no catalogue/
    );
  }
);

test(
  "the real catalogue and sources pass",
  () => {
    execFileSync(
      process.execPath,
      [
        "tools/check-i18n.mjs"
      ],
      {
        cwd: repo,
        stdio: "pipe"
      }
    );
  }
);

// ---- vulnerability report -------------------------------------------------

test(
  "versions compare number by number, not as text",
  () => {
    assert.ok(
      compareVersions(
        "10.1.9",
        "10.1.31"
      ) < 0
    );

    assert.ok(
      compareVersions(
        "3.5.12",
        "3.5.2"
      ) > 0
    );

    assert.equal(
      compareVersions(
        "2.18.8",
        "2.18.8"
      ),
      0
    );
  }
);

test(
  "the version to move to stays on the installed major line and cures every advisory",
  () => {
    assert.equal(
      versionToMoveTo(
        "10.1.31",
        [
          "9.0.118, 10.1.55, 11.0.25",
          "10.1.58, 11.0.30",
          "9.0.107, 10.1.40"
        ]
      ),
      "10.1.58"
    );

    assert.equal(
      versionToMoveTo(
        "3.3.5",
        [
          "3.4.9, 3.5.12"
        ]
      ),
      "3.4.9"
    );

    assert.equal(
      versionToMoveTo(
        "1.0.0",
        [
          ""
        ]
      ),
      "see the advisories"
    );
  }
);

test(
  "a package is listed once, with its worst severity first",
  () => {
    const packages =
      summarise({
        Results: [
          {
            Vulnerabilities: [
              {
                PkgName: "b",
                InstalledVersion: "1.0",
                FixedVersion: "1.1",
                VulnerabilityID: "CVE-1",
                Severity: "HIGH"
              },
              {
                PkgName: "a",
                InstalledVersion: "2.0",
                FixedVersion: "2.1",
                VulnerabilityID: "CVE-2",
                Severity: "HIGH"
              },
              {
                PkgName: "b",
                InstalledVersion: "1.0",
                FixedVersion: "1.2",
                VulnerabilityID: "CVE-3",
                Severity: "CRITICAL"
              }
            ]
          }
        ]
      });

    assert.deepEqual(
      packages.map(
        (p) => [
          p.name,
          p.worst,
          p.ids.length
        ]
      ),
      [
        [
          "b",
          "CRITICAL",
          2
        ],
        [
          "a",
          "HIGH",
          1
        ]
      ]
    );
  }
);

// ---- permissions ------------------------------------------------------------

const catalogueSlice = readSlices({
  m2catalogue: "paths:\n  /v1/catalogue/skus:\n    post:\n      x-permission: cat.sku.create\n"
}).sliceOf;

const handler = (annotation) =>
  problemsOfHandlerSource(
    "Handler.java",
    "m2catalogue",
    `package x;\n\n${annotation}\npublic class Handler {}\n`,
    catalogueSlice
  );

test(
  "a handler with a quoted code of its slice, or with CommandHandler.INTERNAL, passes",
  () => {
    assert.deepEqual(handler('@CommandHandler(permission = "cat.sku.create")'), []);
    assert.deepEqual(handler("@CommandHandler(permission = CommandHandler.INTERNAL)"), []);
    assert.deepEqual(
      handler("@CommandHandler(permission = lk.coopfed.knoweb.kernel.api.CommandHandler.INTERNAL, requiresMfa = true)"),
      []
    );
  }
);

test(
  "a handler whose permission is a constant or an expression is refused: nothing could compare it with the slice",
  () => {
    one(handler("@CommandHandler(permission = Permissions.SKU_CREATE)"), /is not a quoted permission code/);
    one(handler('@CommandHandler(permission = "cat." + "sku.create")'), /is not a quoted permission code/);
    one(handler("@CommandHandler(permission = INTERNAL)"), /is not a quoted permission code/);
  }
);

test(
  "the internal permission written as a string is refused: the constant says what it is",
  () => {
    one(handler('@CommandHandler(permission = "internal")'), /write CommandHandler.INTERNAL/);
  }
);

test(
  "a handler whose permission no operation carries is refused",
  () => {
    one(handler('@CommandHandler(permission = "cat.sku.delete")'), /not the x-permission of any operation/);
    one(handler('@CommandHandler(permission = "todo.m2catalogue.create")'), /scaffold placeholder/);
  }
);

test(
  "an example in a comment is not read as a handler",
  () => {
    assert.deepEqual(handler("/** {@code @CommandHandler(permission = X.Y)} */"), []);
  }
);

test(
  "an operation with x-permission internal is refused: an internal command has no operation",
  () => {
    one(
      readSlices({ m2catalogue: "paths:\n  /v1/catalogue/batches:\n    post:\n      x-permission: internal\n" }).problems,
      /x-permission "internal" on an operation/
    );
    one(
      readSlices({ m2catalogue: "      x-permission: \"todo.m2catalogue.create\"\n" }).problems,
      /scaffold placeholder/
    );
  }
);

test(
  "x-permission authenticated is for a GET of the caller's own facts, never a command (CR-19A-9)",
  () => {
    assert.deepEqual(
      readSlices({ session: "paths:\n  /v1/session:\n    get:\n      x-permission: authenticated\n" }).problems,
      []
    );
    one(
      readSlices({ session: "paths:\n  /v1/session:\n    post:\n      x-permission: authenticated\n" }).problems,
      /x-permission "authenticated" on a mutating operation/
    );
    one(handler('@CommandHandler(permission = "authenticated")'), /"authenticated" on a command handler/);
  }
);

test(
  "the real slices and handlers pass",
  () => {
    execFileSync(
      process.execPath,
      [
        "tools/check-permissions.mjs"
      ],
      {
        cwd: repo,
        stdio: "pipe"
      }
    );
  }
);

// ---- frozen contracts (the M1 freeze conditions, decisions of 27 September 2026) ------------

const FROZEN = "info:\n  title: X\n  version: 1.0.0\n";
const FROZEN_WITH_CR = (cr) => `info:\n  title: X\n  version: 1.0.0\n  x-change-request: ${cr}\n`;
const UNFROZEN = "info:\n  title: X\n  version: 0.1.0\n";

test(
  "a frozen slice with nothing changed passes, whatever its content",
  () => {
    assert.deepEqual(
      problemsOfFrozenContract("openapi/m1party.yaml", FROZEN, "info:\n  version: 9.9.9\n", false, new Set()),
      []
    );
  }
);

test(
  "an unfrozen slice on origin/main is never checked",
  () => {
    assert.deepEqual(
      problemsOfFrozenContract("openapi/sync.yaml", UNFROZEN, "info:\n  version: 0.1.0\n", true, new Set()),
      []
    );
  }
);

test(
  "a real version bump passes",
  () => {
    assert.deepEqual(
      problemsOfFrozenContract("openapi/m1party.yaml", FROZEN, "info:\n  version: 1.1.0\n", true, new Set()),
      []
    );
  }
);

test(
  "a change with no version bump and no change request is refused",
  () => {
    one(
      problemsOfFrozenContract("openapi/m1party.yaml", FROZEN, FROZEN, true, new Set()),
      /openapi\/m1party\.yaml: frozen at 1\.0\.0 on origin\/main.*info\.version was not raised/s
    );
  }
);

test(
  "a change request that names no file under docs\\change-requests is refused",
  () => {
    one(
      problemsOfFrozenContract(
        "openapi/m1party.yaml",
        FROZEN,
        FROZEN_WITH_CR("CR-21A-4"),
        true,
        new Set(["CR-21A-3.md"])
      ),
      /x-change-request "CR-21A-4" names no file docs\/change-requests\/CR-21A-4\.md/
    );
  }
);

test(
  "a change request that names a file that exists, and is new since origin/main, passes",
  () => {
    assert.deepEqual(
      problemsOfFrozenContract(
        "openapi/m1party.yaml",
        FROZEN,
        FROZEN_WITH_CR("CR-21A-4"),
        true,
        new Set(["CR-21A-4.md"])
      ),
      []
    );
  }
);

test(
  "the same change request as origin/main is not a new one: it is still refused",
  () => {
    one(
      problemsOfFrozenContract(
        "openapi/m1party.yaml",
        FROZEN_WITH_CR("CR-21A-4"),
        FROZEN_WITH_CR("CR-21A-4"),
        true,
        new Set(["CR-21A-4.md"])
      ),
      /info\.version was not raised/
    );
  }
);

test(
  "removing a frozen slice is refused",
  () => {
    one(
      problemsOfFrozenContract("openapi/m1party.yaml", FROZEN, null, true, new Set()),
      /frozen at 1\.0\.0 on origin\/main and now removed/
    );
  }
);

test(
  "the real slices and Java packages pass check-frozen-contracts.mjs",
  () => {
    execFileSync(
      process.execPath,
      [
        "tools/check-frozen-contracts.mjs"
      ],
      {
        cwd: repo,
        stdio: "pipe"
      }
    );
  }
);

// ---- cross-slice checks (29A section 7 step 2, brought forward to the build) ------------------

const slice = (operations) =>
  `openapi: 3.1.0\ninfo:\n  version: 1.0.0\npaths:\n${operations}\n`;

const operation = (opPath, method, { operationId, permission = "m.x", idempotencyKey = false } = {}) => {
  const idem = idempotencyKey ? "\n      parameters:\n        - $ref: 'common.yaml#/components/parameters/IdempotencyKey'" : "";
  return `  ${opPath}:\n    ${method}:\n      operationId: ${operationId}\n      x-permission: ${permission}${idem}\n`;
};

test(
  "operationsOf finds every operation of a slice, in order, with its permission and idempotency key",
  () => {
    const text = slice(
      operation("/v1/x", "get", { operationId: "listX" }) +
      operation("/v1/x", "post", { operationId: "createX", idempotencyKey: true })
    );

    const found = operationsOf(text).map((o) => [o.method, o.path, o.operationId, o.hasPermission, o.hasIdempotencyKey]);

    assert.deepEqual(
      found,
      [
        ["get", "/v1/x", "listX", true, false],
        ["post", "/v1/x", "createX", true, true]
      ]
    );
  }
);

test(
  "a mutating operation with no Idempotency-Key parameter is refused",
  () => {
    one(
      problemsOfSlice("m2catalogue", slice(operation("/v1/x", "post", { operationId: "createX" }))),
      /openapi\/m2catalogue\.yaml:\d+ POST \/v1\/x: a mutating operation with no Idempotency-Key parameter/
    );
  }
);

test(
  "a GET with no x-permission is refused: GETs are not exempt",
  () => {
    const text = slice("  /v1/x:\n    get:\n      operationId: listX\n");

    one(
      problemsOfSlice("m2catalogue", text),
      /openapi\/m2catalogue\.yaml:\d+ GET \/v1\/x: no x-permission/
    );
  }
);

test(
  "a complete slice passes on its own",
  () => {
    assert.deepEqual(
      problemsOfSlice(
        "m2catalogue",
        slice(
          operation("/v1/x", "get", { operationId: "listX" }) +
          operation("/v1/x", "post", { operationId: "createX", idempotencyKey: true })
        )
      ),
      []
    );
  }
);

test(
  // CR-19A-4: the sync slice breaks no rule check-slices.mjs enforces. It carries every
  // operation under /v1/ (so the till gets the same "everything under /v1 needs a token" rule
  // as everyone else), a real Idempotency-Key on its mutating operations, and an x-permission
  // whose value is a device marker ("sync.device") rather than a catalogue permission a role
  // could hold — check-slices.mjs only checks that x-permission is present, never what it
  // means, so this passes with no special case for sync.yaml.
  "a device-authenticated slice (like sync.yaml) needs no exemption: a marker x-permission and a real Idempotency-Key satisfy the ordinary rules",
  () => {
    assert.deepEqual(
      problemsOfSlice(
        "sync",
        slice(
          operation("/v1/sync/devices/{id}/batches", "post", {
            operationId: "uploadBatch",
            permission: "sync.device",
            idempotencyKey: true
          })
        )
      ),
      []
    );
  }
);

test(
  "the same operationId in two slices is refused, even though each slice is fine alone",
  () => {
    one(
      problemsOfSlices({
        m1party: slice(operation("/v1/a", "get", { operationId: "shared" })),
        sync: slice(operation("/v1/b", "post", { operationId: "shared", idempotencyKey: true }))
      }),
      /openapi\/sync\.yaml:\d+: operationId "shared" is already used at openapi\/m1party\.yaml:\d+/
    );
  }
);

test(
  "distinct operationIds across slices pass",
  () => {
    assert.deepEqual(
      problemsOfSlices({
        m1party: slice(operation("/v1/a", "get", { operationId: "listA" })),
        sync: slice(operation("/v1/b", "post", { operationId: "createB", idempotencyKey: true }))
      }),
      []
    );
  }
);

test(
  "the real slices pass check-slices.mjs (hello.yaml excluded)",
  () => {
    execFileSync(
      process.execPath,
      [
        "tools/check-slices.mjs"
      ],
      {
        cwd: repo,
        stdio: "pipe"
      }
    );
  }
);

// ---- progress entries (one file per entry, 28 September 2026) ----------------------------------

test(
  "progress entries join in date order under the three headings; a badly named entry is refused",
  () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "progress-"));
    fs.mkdirSync(path.join(dir, "done"));
    fs.mkdirSync(path.join(dir, "deviations"));
    fs.writeFileSync(path.join(dir, "done", "2026-09-28-feat-b.md"), "- **B.** second\n");
    fs.writeFileSync(path.join(dir, "done", "2026-09-27-feat-a.md"), "- **A.** first\n");
    fs.writeFileSync(path.join(dir, "NEXT.md"), "- the plan\n");
    fs.writeFileSync(path.join(dir, "deviations", "2026-09-27-x.md"), "- **X.** why\n");
    const good = buildIndex(dir);
    assert.deepEqual(good.problems, []);
    assert.ok(good.text.indexOf("first") < good.text.indexOf("second"));
    assert.ok(good.text.indexOf("## Next") < good.text.indexOf("## Deviations"));
    fs.writeFileSync(path.join(dir, "deviations", "Bad Name.md"), "no bullet\n");
    assert.equal(buildIndex(dir).problems.length, 2);
    assert.deepEqual(buildIndex(path.join(repo, "docs", "progress")).problems, []);
  }
);