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
import { MODULES, PLACEHOLDER_PERMISSION_PREFIX, ScaffoldError, namesFor, plan, pluralOf, problems, rename, run, sharedEdits } from "./new-module.mjs";

const REPOSITORY = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SKU = namesFor({ name: "m2catalogue", schema: "catalogue", entity: "sku" });
const SKU_ARGS = ["--name", "m2catalogue", "--schema", "catalogue", "--entity", "sku"];
const PRICE_LIST = namesFor({ name: "m3pricing", schema: "pricing", entity: "price_list" });
const PRICE_LIST_ARGS = ["--name", "m3pricing", "--schema", "pricing", "--entity", "price_list"];

const B = "backend/app/src";
const TEMPLATE_PARTS = [
  `${B}/main/java/lk/coopfed/knoweb/hello`,
  `${B}/main/resources/db/migration/hello`,
  `${B}/main/resources/seed/hello`,
  `${B}/main/resources/openapi/hello.yaml`,
  `${B}/main/resources/i18n`,
  `${B}/test/java/lk/coopfed/knoweb/hello`,
  "web/src/modules/hello",
  "web/src/modules/registry.ts",
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
  for (const file of ["web/src/modules/registry.ts", "web/src/shell/i18n/messages.ts"]) {
    const helloOnly = text(root, file)
      .split("\n")
      .filter((line) => !/from "(\.\/|\.\.\/\.\.\/modules\/)(?!hello\/)[a-z0-9]+\//.test(line))
      .filter((line) => !/^\s*(\.\.\.)?(?!hello)[a-z0-9]+(Module|Messages),\s*$/.test(line))
      .join("\n");
    fs.writeFileSync(path.join(root, file), helloOnly);
  }
  return root;
}

const exists = (root, file) => fs.existsSync(path.join(root, file));
const text = (root, file) => fs.readFileSync(path.join(root, file), "utf8");

// ---- names ----------------------------------------------------------------------------------

test("names: a single word is spelled the same everywhere", () => {
  for (const key of ["entity", "entityCamel", "entityKebab", "entityWords"]) {
    assert.equal(SKU[key], "sku");
  }
  assert.equal(SKU.Entity, "Sku");
  assert.equal(SKU.entities, "skus");
  assert.equal(SKU.Schema, "Catalogue");
});

test("names: a compound name gets the spelling each place needs", () => {
  const { entity, entityCamel, Entity, entityKebab, entityWords, ENTITY, entities, entitiesCamel, Entities, entitiesKebab, entitiesWords } = PRICE_LIST;
  assert.deepEqual(
    { entity, entityCamel, Entity, entityKebab, entityWords, ENTITY, entities, entitiesCamel, Entities, entitiesKebab, entitiesWords },
    {
      entity: "price_list", entityCamel: "priceList", Entity: "PriceList", entityKebab: "price-list",
      entityWords: "price list", ENTITY: "PRICE_LIST",
      entities: "price_lists", entitiesCamel: "priceLists", Entities: "PriceLists",
      entitiesKebab: "price-lists", entitiesWords: "price lists"
    });
});

test("names: the plural follows the last word, and PLURAL overrides it", () => {
  assert.equal(pluralOf("sku"), "skus");
  assert.equal(pluralOf("tax_category"), "tax_categories");
  assert.equal(pluralOf("batch"), "batches");
  assert.equal(pluralOf("delivery_note"), "delivery_notes");
  assert.equal(pluralOf("day"), "days");
  assert.equal(namesFor({ name: "m1party", schema: "party", entity: "person", plural: "people" }).Entities, "People");
});

test("names: a wrong module, schema or entity is refused with a reason", () => {
  const refused = (args, pattern) => assert.throws(() => namesFor(args), (e) => e instanceof ScaffoldError && pattern.test(e.message));
  refused({ name: "m99", schema: "catalogue", entity: "sku" }, /NAME must be one of/);
  refused({ name: "m10procurement", schema: "procurement", entity: "order" }, /NAME must be one of/);
  refused({ name: "m2catalogue", schema: "party", entity: "sku" }, /SCHEMA for m2catalogue/);
  refused({ name: "m1party", schema: "catalogue", entity: "sku" }, /SCHEMA for m1party/);
  refused({ name: "m2catalogue", schema: "catalogue" }, /ENTITY must be lowercase words/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "Sku" }, /ENTITY must be/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "price__list" }, /ENTITY must be/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "_list" }, /ENTITY must be/);
  refused({ name: "m2catalogue", schema: "catalogue", entity: "priceList" }, /ENTITY must be/);
  refused({ name: "m9integration", schema: "integration", entity: "outbound_webhook_delivery" }, /limit is 40/);
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
    'permission = "hello.greeting.register"': 'permission = "todo.catalogue.sku.register"',
    "x-permission: hello.greeting.read": "x-permission: todo.catalogue.sku.read",
    "class RegisterGreetingHandler implements Handles<RegisterGreeting, UUID>": "class RegisterSkuHandler implements Handles<RegisterSku, UUID>",
    "List<Greeting> findTop100ByOrderByCreatedAtDesc();": "List<Sku> findTop100ByOrderByCreatedAtDesc();",
    'import type { components } from "../../generated/hello";': 'import type { components } from "../../generated/m2catalogue";',
    "export const helloModule: ModuleDefinition = {": "export const catalogueModule: ModuleDefinition = {",
    "class HelloController": "class CatalogueController",
    '"hello.greeting.duplicate"': '"catalogue.sku.duplicate"',
    "openapi/hello.yaml": "openapi/m2catalogue.yaml",
    "greeting_owner_text_uq": "sku_owner_text_uq"
  };
  for (const [from, to] of Object.entries(cases)) {
    assert.equal(rename(from, SKU), to);
  }
});

test("rename: a compound name is spelled by context", () => {
  const code = {
    '@Table(schema = "hello", name = "greeting")': '@Table(schema = "pricing", name = "price_list")',
    "Greeting greeting = Greeting.create(": "PriceList priceList = PriceList.create(",
    "repository.save(greeting);": "repository.save(priceList);",
    "UUID greetingId,": "UUID priceListId,",
    'Subject.of("greeting", greeting.getId())': 'Subject.of("price_list", priceList.getId())',
    '"HELLO_GREETING_REGISTERED"': '"PRICING_PRICE_LIST_REGISTERED"',
    'TYPE = "hello.greeting.registered.v1"': 'TYPE = "price_list.registered.v1"',
    '"hello.greeting.duplicate"': '"pricing.price_list.duplicate"',
    '@RequestMapping("/v1/hello/greetings")': '@RequestMapping("/v1/pricing/price-lists")',
    'execute("TRUNCATE hello.greeting");': 'execute("TRUNCATE pricing.price_list");',
    "const greetings = useQuery({": "const priceLists = useQuery({",
    "{greetings.data?.map((greeting) => (": "{priceLists.data?.map((priceList) => (",
    " * The greeting aggregate. Rules every entity follows:": " * The price list aggregate. Rules every entity follows:",
    "    // a greeting has no device": "    // a price list has no device",
    "class RegisterGreetingHandler": "class RegisterPriceListHandler"
  };
  for (const [from, to] of Object.entries(code)) {
    assert.equal(rename(from, PRICE_LIST, "code"), to);
  }
  const data = {
    "CREATE TABLE hello.greeting (": "CREATE TABLE pricing.price_list (",
    "    CONSTRAINT greeting_owner_text_uq UNIQUE (owner_entity_id, text_en)": "    CONSTRAINT price_list_owner_text_uq UNIQUE (owner_entity_id, text_en)",
    "-- A greeting is never changed or removed once registered": "-- A price list is never changed or removed once registered",
    "  /v1/hello/greetings/{id}:": "  /v1/pricing/price-lists/{id}:",
    "      summary: Register a greeting for the caller": "      summary: Register a price list for the caller",
    "schema: { $ref: '#/components/schemas/RegisterGreetingRequest' }": "schema: { $ref: '#/components/schemas/RegisterPriceListRequest' }",
    '    "hello.title": "Greetings",': '    "pricing.title": "Price lists",',
    '    "hello.register": "Register greeting",': '    "pricing.register": "Register price list",'
  };
  for (const [from, to] of Object.entries(data)) {
    assert.equal(rename(from, PRICE_LIST, "data"), to);
  }
  assert.equal(rename("V0001__greeting.sql", PRICE_LIST, "path"), "V0001__price_list.sql");
  assert.equal(rename("greetings.dev.sql", PRICE_LIST, "path"), "price_lists.dev.sql");
  assert.equal(rename("internal/RegisterGreetingHandler.java", PRICE_LIST, "path"), "internal/RegisterPriceListHandler.java");
});

test("plan: a compound name leaves no broken identifier behind", (t) => {
  for (const file of plan(templateCopy(t), PRICE_LIST)) {
    if (/\.(java|ts|tsx)$/.test(file.target)) {
      const codeLines = file.content.split("\n").filter((l) => !/^\s*(\*|\/\*|\/\/)/.test(l));
      // In code a snake_case name may appear only inside a string, never as an identifier.
      for (const line of codeLines.filter((l) => /price_list/.test(l))) {
        assert.ok(/["`].*price_list.*["`]/.test(line), file.target + ": price_list outside a string: " + line.trim());
      }
    }
    // "price listId" or "aprice list": the prose spelling attached to identifier characters.
    assert.ok(!/price list(?!s?(?![A-Za-z0-9_(]))[A-Za-z0-9_(]|[A-Za-z0-9_]price list/.test(file.content), file.target + ": prose spelling glued to code");
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
    "web/src/modules/m2catalogue/module.tsx"
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

test("plan: the web module gets a ModuleDefinition of its own: id, route, navigation label, placeholder permissions", (t) => {
  const files = plan(templateCopy(t), PRICE_LIST);
  const definition = files.find((f) => f.target === "web/src/modules/m3pricing/module.tsx").content;

  assert.match(definition, /export const pricingModule: ModuleDefinition = \{/);
  assert.match(definition, /id: "pricing"/);
  assert.match(definition, /path: "pricing",\n\s+element: <PricingPage \/>/);
  assert.match(definition, /navItems: \[\{ labelId: "pricing\.nav", to: "\/pricing" \}\]/);
  assert.match(definition, /requiredPermissions: \["todo\.pricing\.price_list\.read", "todo\.pricing\.price_list\.register"\]/);

  // The label of the navigation entry exists in the module's own catalogue, in every language.
  const catalogue = JSON.parse(files.find((f) => f.target === "web/src/modules/m3pricing/pricing.messages.json").content);
  for (const language of ["en", "si", "ta"]) {
    assert.ok(catalogue[language]["pricing.nav"], `${language}: pricing.nav is missing`);
  }
  assert.equal(catalogue.en["pricing.nav"], "Price lists");

  // The page offers the form to the holder of the same placeholder, so one replacement rule fits both files.
  const page = files.find((f) => f.target === "web/src/modules/m3pricing/PricingPage.tsx").content;
  assert.match(page, /useHasPermission\("todo\.pricing\.price_list\.register"\)/);
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
  const registry = edits["web/src/modules/registry.ts"];
  assert.match(registry, /import \{ catalogueModule \} from "\.\/m2catalogue\/module";\n\/\/ new-module:import/);
  assert.match(registry, /\n  catalogueModule,\n\s+\/\/ new-module:entry/);
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
  assert.ok(lines.some((l) => l.startsWith("would edit") && l.endsWith("registry.ts")));
  assert.ok(!exists(root, "web/src/modules/m2catalogue"));
  assert.ok(!text(root, "web/src/modules/registry.ts").includes("catalogueModule"));
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
  assert.ok(text(root, "web/src/modules/registry.ts").includes("  catalogueModule,"));
  assert.ok(JSON.parse(text(root, `${B}/main/resources/i18n/ta.json`))["catalogue.sku.duplicate"]);
  // hello itself is untouched
  assert.ok(exists(root, `${B}/main/java/lk/coopfed/knoweb/hello/internal/RegisterGreetingHandler.java`));
});

test("run: a second run for the same module is refused and changes nothing", (t) => {
  const root = templateCopy(t);
  run(root, SKU_ARGS, () => {});
  const registryAfterFirst = text(root, "web/src/modules/registry.ts");

  assert.throws(() => run(root, SKU_ARGS, () => {}), (e) => e instanceof ScaffoldError && /Nothing was written/.test(e.message));
  assert.equal(text(root, "web/src/modules/registry.ts"), registryAfterFirst);
});

test("run: two modules can be scaffolded side by side, but not with the same aggregate", (t) => {
  const root = templateCopy(t);
  run(root, SKU_ARGS, () => {});
  run(root, ["--name", "m3pricing", "--schema", "pricing", "--entity", "rate"], () => {});

  const registry = text(root, "web/src/modules/registry.ts");
  assert.ok(registry.includes("  catalogueModule,") && registry.includes("  pricingModule,"));
  assert.throws(
    () => run(root, ["--name", "m4trading", "--schema", "trading", "--entity", "sku"], () => {}),
    (e) => e instanceof ScaffoldError && /an aggregate called Sku already exists/.test(e.message));
});

test("run: an unknown argument is refused", (t) => {
  assert.throws(() => run(templateCopy(t), ["--nmae", "m2catalogue"]), ScaffoldError);
});

test("permissions: the copy carries placeholders that the build refuses", (t) => {
  const files = plan(templateCopy(t), SKU);
  const handler = files.find((f) => f.target.endsWith("RegisterSkuHandler.java"));
  const slice = files.find((f) => f.target.endsWith("openapi/m2catalogue.yaml"));
  assert.equal(PLACEHOLDER_PERMISSION_PREFIX, "todo.");
  assert.match(handler.content, /@CommandHandler\(permission = "todo\.catalogue\.sku\.register"\)/);
  assert.match(slice.content, /x-permission: todo\.catalogue\.sku\.register/);
  assert.match(slice.content, /x-permission: todo\.catalogue\.sku\.read/);
  // Message ids are not permissions and must not get the prefix.
  assert.ok(!/todo\.catalogue\.sku\.(duplicate|text_required)/.test(handler.content));
});

test("run: a compound module is written with the right file names", (t) => {
  const root = templateCopy(t);
  run(root, PRICE_LIST_ARGS, () => {});
  for (const file of [
    B + "/main/java/lk/coopfed/knoweb/m3pricing/internal/PriceList.java",
    B + "/main/java/lk/coopfed/knoweb/m3pricing/api/RegisterPriceList.java",
    B + "/main/resources/db/migration/m3pricing/V0001__price_list.sql",
    B + "/main/resources/seed/m3pricing/price_lists.dev.sql"
  ]) {
    assert.ok(exists(root, file), "missing " + file);
  }
  assert.ok(JSON.parse(text(root, B + "/main/resources/i18n/si.json"))["pricing.price_list.duplicate"]);
});
