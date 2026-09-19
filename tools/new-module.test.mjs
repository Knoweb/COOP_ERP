// Tests of tools/new-module.mjs. Run with:  node --test tools/new-module.test.mjs  (or make test)
//
// They never write into the repository, and they do not care which modules the repository
// already has: each test that needs files works in a throwaway copy of the hello template
// made in the system temp folder (see templateCopy). So they keep passing on the day M2 is
// really scaffolded. The end-to-end proof (scaffold, build, run the inherited tests) is
// `make test-scaffold`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { MODULES, ScaffoldError, namesFor, plan, problems, rename, run, sharedEdits } from "./new-module.mjs";

const REPOSITORY = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SKU = namesFor({ name: "m2catalogue", schema: "catalogue", entity: "sku" });
const SKU_ARGS = ["--name", "m2catalogue", "--schema", "catalogue", "--entity", "sku"];

const B = "backend/app/src";
const TEMPLATE_PARTS = [
  `${B}/main/java/lk/coopfed/knoweb/hello`,
  `${B}/main/resources/db/migration/hello`,
  `${B}/main/resources/seed/hello`,
  `${B}/main/resources/openapi/hello.yaml`,
  `${B}/main/resources/i18n`,
  `${B}/test/java/lk/coopfed/knoweb/hello`,
  "web/src/modules/hello",
  "web/src/router.tsx",
  "web/src/shell/i18n/messages.ts"
];

/** A fresh root holding the hello template and the shared files, and no other module. */
function templateCopy(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "new-module-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const part of TEMPLATE_PARTS) {
    fs.cpSync(path.join(REPOSITORY, part), path.join(root, part), { recursive: true });
  }
  // The two registration files of the repository may already list real modules. Take those
  // lines out, so the copy knows hello only, whatever has been scaffolded for real.
  for (const file of ["web/src/router.tsx", "web/src/shell/i18n/messages.ts"]) {
    const helloOnly = text(root, file)
      .split("\n")
      .filter((line) => !/modules\/(?!hello\/)/.test(line))
      .filter((line) => !/^\s*(\.\.\.)?(?!hello)[a-z0-9]+(Routes|Messages),\s*$/.test(line))
      .join("\n");
    fs.writeFileSync(path.join(root, file), helloOnly);
  }
  return root;
}

const exists = (root, file) => fs.existsSync(path.join(root, file));
const text = (root, file) => fs.readFileSync(path.join(root, file), "utf8");

// ---- names ----------------------------------------------------------------------------------

test("names: every spelling the renames need", () => {
  assert.deepEqual(
    { ...SKU, displayName: undefined },
    {
      name: "m2catalogue", schema: "catalogue", entity: "sku", entities: "skus",
      Entity: "Sku", Entities: "Skus", Schema: "Catalogue", displayName: undefined
    });
  assert.equal(namesFor({ name: "m1party", schema: "party", entity: "party", plural: "parties" }).entities, "parties");
});

test("names: a wrong module, schema or entity is refused with a reason", () => {
  const refused = (args, pattern) => assert.throws(() => namesFor(args), (e) => e instanceof ScaffoldError && pattern.test(e.message));
  refused({ name: "m99", schema: "catalogue", entity: "sku" }, /NAME must be one of/);
  refused({ name: "m10procurement", schema: "procurement", entity: "order" }, /NAME must be one of/);
  refused({ name: "m2catalogue", schema: "party", entity: "sku" }, /SCHEMA for m2catalogue/);
  refused({ name: "m1party", schema: "catalogue", entity: "sku" }, /SCHEMA for m1party/);
  refused({ name: "m2catalogue", schema: "catalogue" }, /ENTITY must be one lowercase word/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "Sku" }, /ENTITY must be/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "price_list" }, /ENTITY must be/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "class" }, /Java keyword/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "sku", plural: "sku" }, /PLURAL/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "greeting" }, /hello module itself/);
});

test("names: the second schema of M1 is accepted", () => {
  assert.equal(namesFor({ name: "m1party", schema: "security", entity: "role" }).Schema, "Security");
});

test("the module table agrees with the architecture tests and the Flyway configuration", () => {
  const arch = text(REPOSITORY, `${B}/test/java/lk/coopfed/knoweb/ArchitectureTests.java`);
  const flyway = text(REPOSITORY, `${B}/main/java/lk/coopfed/knoweb/config/FlywayConfig.java`);
  for (const [name, { schemas }] of Object.entries(MODULES)) {
    const owned = schemas.map((s) => `"${s}"`).join(", ");
    assert.ok(arch.includes(`Map.entry("${name}", Set.of(${owned}))`), `ArchitectureTests.SCHEMA_OWNERSHIP differs for ${name}`);
    assert.ok(flyway.includes(`classpath:db/migration/${name}"`), `FlywayConfig has no location for ${name}`);
  }
});

// ---- renaming ---------------------------------------------------------------------------------

test("rename: each meaning of hello and greeting gets the right word", () => {
  const cases = {
    "package lk.coopfed.knoweb.hello.internal;": "package lk.coopfed.knoweb.m2catalogue.internal;",
    '@Table(schema = "hello", name = "greeting")': '@Table(schema = "catalogue", name = "sku")',
    "CREATE TABLE hello.greeting (": "CREATE TABLE catalogue.sku (",
    "/v1/hello/greetings/{id}": "/v1/catalogue/skus/{id}",
    'TYPE = "hello.greeting.registered.v1"': 'TYPE = "sku.registered.v1"',
    '"HELLO_GREETING_REGISTERED"': '"CATALOGUE_SKU_REGISTERED"',
    'permission = "hello.greeting.register"': 'permission = "catalogue.sku.register"',
    "class RegisterGreetingHandler implements Handles<RegisterGreeting, UUID>": "class RegisterSkuHandler implements Handles<RegisterSku, UUID>",
    "List<Greeting> findTop100ByOrderByCreatedAtDesc();": "List<Sku> findTop100ByOrderByCreatedAtDesc();",
    'import type { components } from "../../generated/hello";': 'import type { components } from "../../generated/m2catalogue";',
    "export const helloRoutes: RouteObject[]": "export const catalogueRoutes: RouteObject[]",
    "class HelloController": "class CatalogueController",
    '"hello.greeting.duplicate"': '"catalogue.sku.duplicate"',
    "openapi/hello.yaml": "openapi/m2catalogue.yaml",
    "greeting_owner_text_uq": "sku_owner_text_uq"
  };
  for (const [from, to] of Object.entries(cases)) {
    assert.equal(rename(from, SKU), to);
  }
});

test("plan: every hello file has a renamed twin, and nothing of hello is left inside", (t) => {
  const files = plan(templateCopy(t), SKU);
  const targets = files.map((f) => f.target);
  for (const expected of [
    `${B}/main/java/lk/coopfed/knoweb/m2catalogue/package-info.java`,
    `${B}/main/java/lk/coopfed/knoweb/m2catalogue/README.md`,
    `${B}/main/java/lk/coopfed/knoweb/m2catalogue/api/package-info.java`,
    `${B}/main/java/lk/coopfed/knoweb/m2catalogue/api/RegisterSku.java`,
    `${B}/main/java/lk/coopfed/knoweb/m2catalogue/internal/RegisterSkuHandler.java`,
    `${B}/main/java/lk/coopfed/knoweb/m2catalogue/web/CatalogueController.java`,
    `${B}/main/resources/db/migration/m2catalogue/V0001__sku.sql`,
    `${B}/main/resources/openapi/m2catalogue.yaml`,
    `${B}/main/resources/seed/m2catalogue/skus.dev.sql`,
    `${B}/test/java/lk/coopfed/knoweb/m2catalogue/CatalogueModuleIntegrationTest.java`,
    "web/src/modules/m2catalogue/CataloguePage.tsx",
    "web/src/modules/m2catalogue/catalogueApi.ts",
    "web/src/modules/m2catalogue/catalogue.messages.json",
    "web/src/modules/m2catalogue/routes.tsx"
  ]) {
    assert.ok(targets.includes(expected), `missing ${expected}`);
  }
  assert.equal(new Set(targets).size, targets.length, "two files map to the same target");
  for (const file of files.filter((f) => !f.target.endsWith("README.md"))) {
    assert.ok(!/hello|greeting/i.test(file.target), `${file.target} still has a hello name`);
    assert.ok(!/greeting/i.test(file.content), `${file.target} still mentions a greeting`);
    assert.ok(!/\bhello\b/i.test(file.content.replace(/hello module|hello\/README/gi, "")), `${file.target} still mentions hello`);
  }
});

test("plan: the module descriptor is written for the module, not copied from hello", (t) => {
  const descriptor = plan(templateCopy(t), SKU).find((f) => f.target.endsWith("m2catalogue/package-info.java"));
  assert.match(descriptor.content, /displayName = "M2 Catalogue & Batch"/);
  assert.match(descriptor.content, /allowedDependencies = \{"kernel::api"\}/);
  assert.match(descriptor.content, /package lk\.coopfed\.knoweb\.m2catalogue;/);
});

test("shared files: message ids in three languages, the route and the web catalogue", (t) => {
  const edits = Object.fromEntries(sharedEdits(templateCopy(t), SKU).map((e) => [e.target, e.content]));
  for (const language of ["en", "si", "ta"]) {
    const catalogue = JSON.parse(edits[`${B}/main/resources/i18n/${language}.json`]);
    assert.equal(catalogue["catalogue.sku.duplicate"], catalogue["hello.greeting.duplicate"]);
    assert.ok(catalogue["catalogue.title"]);
    assert.deepEqual(Object.keys(catalogue), Object.keys(catalogue).slice().sort(), `${language}.json is not sorted`);
  }
  const router = edits["web/src/router.tsx"];
  assert.match(router, /import \{ catalogueRoutes \} from "\.\/modules\/m2catalogue\/routes";\n\/\/ new-module:import/);
  assert.match(router, /\.\.\.catalogueRoutes,\n\s+\/\/ new-module:entry/);
  const messages = edits["web/src/shell/i18n/messages.ts"];
  assert.match(messages, /import catalogueMessages from "\.\.\/\.\.\/modules\/m2catalogue\/catalogue\.messages\.json";/);
  assert.match(messages, /catalogueMessages,\n\s+\/\/ new-module:entry/);
});

// ---- safety checks ------------------------------------------------------------------------------

test("problems: a placeholder folder counts as empty", (t) => {
  const root = templateCopy(t);
  const placeholder = path.join(root, `${B}/main/java/lk/coopfed/knoweb/m2catalogue`);
  fs.mkdirSync(placeholder, { recursive: true });
  fs.writeFileSync(path.join(placeholder, ".gitkeep"), "");

  assert.deepEqual(problems(root, SKU, plan(root, SKU)), []);
});

test("problems: a folder with real files in it is refused", (t) => {
  const root = templateCopy(t);
  const existing = path.join(root, `${B}/main/java/lk/coopfed/knoweb/m2catalogue`);
  fs.mkdirSync(existing, { recursive: true });
  fs.writeFileSync(path.join(existing, "Something.java"), "class Something {}");

  assert.match(problems(root, SKU, plan(root, SKU)).join("\n"), /m2catalogue already has files in it/);
});

test("problems: an aggregate that clashes with an import of the template is refused", (t) => {
  const root = templateCopy(t);
  // The template imports jakarta.persistence.Entity; a class called Entity cannot compile beside it.
  const entity = namesFor({ name: "m1party", schema: "party", entity: "entity", plural: "entities" });
  assert.match(problems(root, entity, plan(root, entity)).join("\n"), /called Entity would clash with an import/);

  const list = namesFor({ name: "m4trading", schema: "trading", entity: "list" });
  assert.match(problems(root, list, plan(root, list)).join("\n"), /called List would clash/);
});

// ---- the whole run ---------------------------------------------------------------------------------

test("run: a dry run reports the plan and writes nothing", (t) => {
  const root = templateCopy(t);
  const lines = [];
  run(root, [...SKU_ARGS, "--dry-run"], (l) => lines.push(l));

  assert.match(lines[0], /^Dry run: m2catalogue/);
  assert.ok(lines.some((l) => l.startsWith("would create") && l.endsWith("V0001__sku.sql")));
  assert.ok(lines.some((l) => l.startsWith("would edit") && l.endsWith("router.tsx")));
  assert.ok(!exists(root, "web/src/modules/m2catalogue"));
  assert.ok(!text(root, "web/src/router.tsx").includes("catalogueRoutes"));
});

test("run: a real run writes the module, registers it, and removes the placeholder", (t) => {
  const root = templateCopy(t);
  const placeholder = `${B}/main/resources/db/migration/m2catalogue/.gitkeep`;
  fs.mkdirSync(path.dirname(path.join(root, placeholder)), { recursive: true });
  fs.writeFileSync(path.join(root, placeholder), "");

  run(root, SKU_ARGS, () => {});

  assert.ok(exists(root, `${B}/main/java/lk/coopfed/knoweb/m2catalogue/internal/RegisterSkuHandler.java`));
  assert.ok(exists(root, `${B}/main/resources/db/migration/m2catalogue/V0001__sku.sql`));
  assert.ok(!exists(root, placeholder), "the .gitkeep placeholder should be gone");
  assert.match(text(root, `${B}/main/resources/db/migration/m2catalogue/V0001__sku.sql`), /CREATE TABLE catalogue\.sku \(/);
  assert.ok(text(root, "web/src/router.tsx").includes("...catalogueRoutes,"));
  assert.ok(JSON.parse(text(root, `${B}/main/resources/i18n/ta.json`))["catalogue.sku.duplicate"]);
  // hello itself is untouched
  assert.ok(exists(root, `${B}/main/java/lk/coopfed/knoweb/hello/internal/RegisterGreetingHandler.java`));
});

test("run: a second run for the same module is refused and changes nothing", (t) => {
  const root = templateCopy(t);
  run(root, SKU_ARGS, () => {});
  const routerAfterFirst = text(root, "web/src/router.tsx");

  assert.throws(() => run(root, SKU_ARGS, () => {}), (e) => e instanceof ScaffoldError && /Nothing was written/.test(e.message));
  assert.equal(text(root, "web/src/router.tsx"), routerAfterFirst);
});

test("run: two modules can be scaffolded side by side, but not with the same aggregate", (t) => {
  const root = templateCopy(t);
  run(root, SKU_ARGS, () => {});
  run(root, ["--name", "m3pricing", "--schema", "pricing", "--entity", "rate"], () => {});

  const router = text(root, "web/src/router.tsx");
  assert.ok(router.includes("...catalogueRoutes,") && router.includes("...pricingRoutes,"));
  assert.throws(
    () => run(root, ["--name", "m4trading", "--schema", "trading", "--entity", "sku"], () => {}),
    (e) => e instanceof ScaffoldError && /an aggregate called Sku already exists/.test(e.message));
});

test("run: an unknown argument is refused", (t) => {
  assert.throws(() => run(templateCopy(t), ["--nmae", "m2catalogue"]), ScaffoldError);
});
