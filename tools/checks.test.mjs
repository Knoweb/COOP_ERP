// Proof that the pipeline checks bite (17A S0-05: "a deliberate violation fails the build").
// Each test hands a check something broken and expects the sentence that reports it.
//
//   node --test tools/checks.test.mjs        (make test runs it)

import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import { OWNERSHIP, problemsOfMigration, problemsOfMigrations } from "./check-schema-ownership.mjs";
import { messageIdsUsedIn, problemsOfCatalogues, problemsOfJavaSources } from "./check-i18n.mjs";
import { MODULES } from "./new-module.mjs";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function tempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "coop-checks-"));
}

function write(root, relative, content) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

function one(problems, pattern) {
  assert.equal(problems.length, 1, `expected exactly one problem, got: ${JSON.stringify(problems, null, 2)}`);
  assert.match(problems[0], pattern);
}

// ---- schema ownership -------------------------------------------------------------------

const catalogue = (sql) => problemsOfMigration("m2catalogue", "V0001__x.sql", sql);

test("a migration in its own schema passes, policies and kernel functions included", () => {
  assert.deepEqual(catalogue(`
    CREATE TABLE catalogue.sku (
        id              uuid PRIMARY KEY,
        owner_entity_id uuid NOT NULL,
        category_id     uuid REFERENCES catalogue.category (id) ON UPDATE CASCADE
    );
    CREATE UNIQUE INDEX sku_owner_uq ON catalogue.sku (owner_entity_id, id);
    ALTER TABLE catalogue.sku ENABLE ROW LEVEL SECURITY;
    CREATE POLICY sku_own ON catalogue.sku
        USING (owner_entity_id = kernel.scope_entity());
    GRANT SELECT, INSERT, UPDATE ON catalogue.sku TO app_rw;
    INSERT INTO catalogue.sku (id, owner_entity_id) VALUES (gen_random_uuid(), gen_random_uuid())
        ON CONFLICT (id) DO UPDATE SET owner_entity_id = EXCLUDED.owner_entity_id;
  `), []);
});

test("a table in another module's schema is refused", () => {
  one(catalogue("CREATE TABLE party.sku (id uuid);"), /V0001__x\.sql:1: this statement touches schema "party"; m2catalogue owns catalogue/);
});

test("writing rows into another module's schema is refused", () => {
  one(catalogue("\nUPDATE pricing.price_list SET status = 'X';"), /:2: this statement touches schema "pricing"/);
});

test("a policy, an index, a view and a function in another schema are refused", () => {
  const problems = catalogue(`
    CREATE POLICY p ON party.entity USING (true);
    CREATE INDEX i ON party.entity (id);
    CREATE OR REPLACE VIEW party.v AS SELECT 1;
    CREATE FUNCTION kernel.mine() RETURNS int LANGUAGE sql AS 'select 1';
  `);
  assert.equal(problems.length, 4, JSON.stringify(problems, null, 2));
});

test("a grant on another module's schema is refused", () => {
  one(catalogue("GRANT SELECT ON ALL TABLES IN SCHEMA party TO app_rw;"), /names schema "party"/);
});

test("creating a schema is the baseline's job", () => {
  one(catalogue("CREATE SCHEMA IF NOT EXISTS extras;"), /names schema "extras"/);
});

test("a name without a schema is refused, with the name to write", () => {
  one(catalogue("CREATE TABLE sku (id uuid);"), /"sku" has no schema; write catalogue\.sku/);
  one(catalogue("CREATE INDEX i ON sku (id);"), /"sku" has no schema/);
});

test("a foreign key into another module is refused", () => {
  one(
    catalogue("CREATE TABLE catalogue.sku (id uuid, owner uuid REFERENCES party.entity (id));"),
    /a foreign key into schema "party"; across modules keep the identifier only/
  );
});

test("comments are not statements", () => {
  assert.deepEqual(catalogue(`
    -- the old script did UPDATE party.entity here
    /* and once:
       DROP TABLE pricing.price_list; */
    CREATE TABLE catalogue.sku (id uuid);
  `), []);
});

test("a migration folder that is no known module is reported, not skipped", () => {
  const root = tempDir();
  write(root, "m2catalog/V0001__typo.sql", "CREATE TABLE party.anything (id uuid);");
  one(problemsOfMigrations(root), /^m2catalog\/: not a module this project knows/);
});

test("the reserved phase 2 module owns no schema yet", () => {
  one(
    problemsOfMigration("m10procurement", "V0001__early.sql", "CREATE TABLE procurement.po (id uuid);"),
    /m10procurement owns no schema yet/
  );
});

test("the real migrations and seeds pass", () => {
  execFileSync(process.execPath, ["tools/check-schema-ownership.mjs"], { cwd: repo, stdio: "pipe" });
});

// ---- the four places that list modules and their schemas agree --------------------------

test("the tools, ArchitectureTests and FlywayConfig name the same modules and schemas", () => {
  const sorted = (map) => Object.fromEntries(
    Object.keys(map).sort().map((name) => [name, [...map[name]].sort()])
  );
  const strings = (text) => [...text.matchAll(/"([^"]+)"/g)].map((match) => match[1]);

  const tests = fs.readFileSync(
    path.join(repo, "backend/app/src/test/java/lk/coopfed/knoweb/ArchitectureTests.java"), "utf8");
  const inTests = Object.fromEntries(
    [...tests.matchAll(/Map\.entry\("([a-z0-9]+)",\s*Set\.(?:<String>)?of\(([^)]*)\)\)/g)]
      .map((match) => [match[1], strings(match[2])])
  );
  assert.deepEqual(sorted(inTests), sorted(OWNERSHIP), "ArchitectureTests.SCHEMA_OWNERSHIP differs from the tools");

  const flyway = fs.readFileSync(
    path.join(repo, "backend/app/src/main/java/lk/coopfed/knoweb/config/FlywayConfig.java"), "utf8");
  const migrated = Object.fromEntries(
    [...flyway.matchAll(/"classpath:db\/migration\/([a-z0-9]+)",\s*new String\[\]\{([^}]*)\}/g)]
      .map((match) => [match[1], strings(match[2])])
  );
  // FlywayConfig migrates every module that owns a schema; m10procurement owns none yet.
  const withSchemas = Object.fromEntries(Object.entries(OWNERSHIP).filter(([, schemas]) => schemas.length > 0));
  assert.deepEqual(sorted(migrated), sorted(withSchemas), "FlywayConfig migrates a different list than the tools know");

  assert.deepEqual(Object.keys(MODULES).filter((name) => !(name in OWNERSHIP)), []);
});

// ---- message catalogue ------------------------------------------------------------------

const texts = (en, si = en, ta = en) => ({ en, si, ta });

test("a complete catalogue passes", () => {
  assert.deepEqual(problemsOfCatalogues(texts({ "a.b": "Too long: the maximum is {0}", "a.c": "It’s fine" })), []);
});

test("an id missing from one language is refused", () => {
  one(problemsOfCatalogues(texts({ "a.b": "x" }, { "a.b": "x" }, {})), /^ta\.json: no text for a\.b/);
});

test("an empty text is refused", () => {
  one(problemsOfCatalogues(texts({ "a.b": "x" }, { "a.b": "  " }, { "a.b": "x" })), /^si\.json: the text of a\.b is empty/);
});

test("the ASCII apostrophe is refused, because a message format drops it and the value after it", () => {
  one(problemsOfCatalogues(texts({ "a.b": "Don't use {0}" }, { "a.b": "x {0}" }, { "a.b": "y {0}" })), /ASCII apostrophe/);
});

test("a translation that lost its placeholder is refused", () => {
  one(
    problemsOfCatalogues(texts({ "a.b": "between {0} and {1}" }, { "a.b": "{0} - {1}" }, { "a.b": "{0}" })),
    /a\.b: the three texts do not use the same placeholders: en \{0 1\}, si \{0 1\}, ta \{0\}/
  );
});

test("message ids are found as literals and as constants of the same file, not in comments", () => {
  const ids = messageIdsUsedIn(`
    class X {
        private static final String INVALID = "request.invalid";
        // throw new ProblemException("in.a.comment");
        void a() { throw new ProblemException("party.entity.duplicate", Map.of()); }
        void b() { throw new ProblemException(INVALID); }
        String c() { return messages.t("party.title", locale); }
        void d(String id) { throw new ProblemException(id); }
    }`).map((found) => found.id);
  assert.deepEqual(ids, ["party.entity.duplicate", "request.invalid", "party.title"]);
});

test("an id the code answers with but no catalogue has is refused", () => {
  const root = tempDir();
  write(root, "m1party/internal/H.java", 'class H { void h() {\n throw new ProblemException("party.entity.duplicate"); } }');
  one(
    problemsOfJavaSources(root, texts({ "party.other": "x" })),
    /m1party\/internal\/H\.java:2: the code answers with "party\.entity\.duplicate", which is in no catalogue/
  );
});

test("the real catalogue and sources pass", () => {
  execFileSync(process.execPath, ["tools/check-i18n.mjs"], { cwd: repo, stdio: "pipe" });
});
