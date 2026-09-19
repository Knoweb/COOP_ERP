// make new-module: copies the hello module as the starting point of a real module
// (17A sections 12 and 14, ticket S0-13).
//
//   make new-module NAME=m2catalogue SCHEMA=catalogue ENTITY=sku
//   node tools/new-module.mjs --name m2catalogue --schema catalogue --entity sku [--plural skus] [--dry-run]
//
// NAME    the module package, one of the nine in MODULES below
// SCHEMA  a database schema that module owns (doc 18 Part F)
// ENTITY  the first aggregate of the module, one lowercase word: sku, entity, order ...
//
// It copies the backend package, migration, OpenAPI slice, seed file, integration test and
// web module of hello, renaming as it goes, and registers the new module in the four shared
// files a module has to appear in: the three i18n catalogues, web/src/router.tsx and
// web/src/shell/i18n/messages.ts. It never overwrites anything: if a target already exists
// it stops before writing a single file.
//
// Written for Node because the build already needs Node; it uses the standard library only.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// ---- what may be scaffolded -------------------------------------------------------------
// Must agree with ArchitectureTests.SCHEMA_OWNERSHIP and FlywayConfig. m10procurement is
// absent on purpose: it is a reserved package with no schema registered yet.
export const MODULES = {
  m1party: { schemas: ["party", "security"], displayName: "M1 Party, Tenancy & Security" },
  m2catalogue: { schemas: ["catalogue"], displayName: "M2 Catalogue & Batch" },
  m3pricing: { schemas: ["pricing"], displayName: "M3 Pricing & Rules" },
  m4trading: { schemas: ["trading"], displayName: "M4 Trading Documents" },
  m5inventory: { schemas: ["inventory"], displayName: "M5 Inventory, Costing, Repack & Loss" },
  m6pos: { schemas: ["pos"], displayName: "M6 POS & Offline" },
  m7customers: { schemas: ["customers"], displayName: "M7 Customers & Payment Recording" },
  m8reporting: { schemas: ["reporting"], displayName: "M8 Reporting Pipeline" },
  m9integration: { schemas: ["integration"], displayName: "M9 Integration" }
};

const JAVA_KEYWORDS = new Set([
  "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
  "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
  "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
  "new", "package", "private", "protected", "public", "record", "return", "short", "static",
  "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
  "var", "void", "volatile", "while", "yield"
]);

export class ScaffoldError extends Error {}

const pascal = (word) => word.charAt(0).toUpperCase() + word.slice(1);

/** Checks the three inputs and works out every spelling of them that the renames need. */
export function namesFor({ name, schema, entity, plural }) {
  const module = MODULES[name];
  if (!module) {
    throw new ScaffoldError(
      `NAME must be one of: ${Object.keys(MODULES).join(", ")} (got "${name ?? ""}")`);
  }
  if (!module.schemas.includes(schema)) {
    throw new ScaffoldError(
      `SCHEMA for ${name} must be one of: ${module.schemas.join(", ")} (got "${schema ?? ""}"). ` +
      "A module writes to its own schemas only (doc 18 Part F).");
  }
  if (!/^[a-z][a-z0-9]*$/.test(entity ?? "") || JAVA_KEYWORDS.has(entity)) {
    throw new ScaffoldError(
      `ENTITY must be one lowercase word that is not a Java keyword, for example sku (got "${entity ?? ""}"). ` +
      "For a compound name such as price list, scaffold with one word and rename by hand.");
  }
  const entities = plural ?? `${entity}s`;
  if (!/^[a-z][a-z0-9]*$/.test(entities) || entities === entity) {
    throw new ScaffoldError(`PLURAL must be one lowercase word different from ENTITY (got "${entities}")`);
  }
  if (entity === "greeting" || schema === "hello") {
    throw new ScaffoldError("That would recreate the hello module itself.");
  }
  return {
    name, schema, entity, entities,
    Entity: pascal(entity), Entities: pascal(entities), Schema: pascal(schema),
    displayName: module.displayName
  };
}

/**
 * Renames one piece of text: file content or a file name. Order matters: the specific
 * spellings go first, the bare words "hello" and "greeting" last.
 */
export function rename(text, n) {
  const steps = [
    // The event type is entity-first in the guides (sku.activated.v1), without the module.
    ["hello.greeting.registered.v1", `${n.entity}.registered.v1`],
    // Places where "hello" means the module folder, not the schema.
    ["lk.coopfed.knoweb.hello", `lk.coopfed.knoweb.${n.name}`],
    ["generated/hello", `generated/${n.name}`],
    ["openapi/hello.yaml", `openapi/${n.name}.yaml`],
    ["db/migration/hello", `db/migration/${n.name}`],
    ["seed/hello", `seed/${n.name}`],
    ["modules/hello", `modules/${n.name}`],
    // The aggregate, plural before singular so "greetings" is not turned into "<entity>s".
    ["GREETING", n.entity.toUpperCase()],
    ["Greetings", n.Entities],
    ["greetings", n.entities],
    ["Greeting", n.Entity],
    ["greeting", n.entity],
    // Everything else called hello is named after the schema: the SQL schema, the URL
    // segment, message ids, the controller, the page, the routes.
    ["HELLO", n.schema.toUpperCase()],
    ["Hello", n.Schema],
    ["hello", n.schema]
  ];
  return steps.reduce((result, [from, to]) => result.split(from).join(to), text);
}

// ---- the plan: which file goes where ------------------------------------------------------

const BACKEND = "backend/app/src";
const SOURCES = [
  { from: `${BACKEND}/main/java/lk/coopfed/knoweb/hello`, to: (n) => `${BACKEND}/main/java/lk/coopfed/knoweb/${n.name}` },
  { from: `${BACKEND}/main/resources/db/migration/hello`, to: (n) => `${BACKEND}/main/resources/db/migration/${n.name}` },
  { from: `${BACKEND}/main/resources/seed/hello`, to: (n) => `${BACKEND}/main/resources/seed/${n.name}` },
  { from: `${BACKEND}/test/java/lk/coopfed/knoweb/hello`, to: (n) => `${BACKEND}/test/java/lk/coopfed/knoweb/${n.name}` },
  { from: "web/src/modules/hello", to: (n) => `web/src/modules/${n.name}` }
];
const SLICE = { from: `${BACKEND}/main/resources/openapi/hello.yaml`, to: (n) => `${BACKEND}/main/resources/openapi/${n.name}.yaml` };

// Written from a template below instead of copied: their hello text is about hello.
const NOT_COPIED = new Set(["README.md", ".gitkeep"]);
const ROOT_PACKAGE_INFO = `${BACKEND}/main/java/lk/coopfed/knoweb/hello/package-info.java`;

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}

const posix = (p) => p.split(path.sep).join("/");

/** Every file to create, as { target, content }. Reads the hello module; writes nothing. */
export function plan(root, n) {
  const files = [];
  for (const source of SOURCES) {
    const fromDir = path.join(root, source.from);
    for (const file of walk(fromDir)) {
      if (NOT_COPIED.has(path.basename(file)) || posix(path.relative(root, file)) === ROOT_PACKAGE_INFO) {
        continue;
      }
      const relative = rename(posix(path.relative(fromDir, file)), n);
      files.push({
        target: `${source.to(n)}/${relative}`,
        content: rename(read(file), n)
      });
    }
  }
  files.push({ target: SLICE.to(n), content: rename(read(path.join(root, SLICE.from)), n) });
  files.push({
    target: `${BACKEND}/main/java/lk/coopfed/knoweb/${n.name}/package-info.java`,
    content: packageInfo(n)
  });
  files.push({
    target: `${BACKEND}/main/java/lk/coopfed/knoweb/${n.name}/README.md`,
    content: moduleReadme(n)
  });
  return files;
}

const read = (file) => fs.readFileSync(file, "utf8").replace(/\r\n/g, "\n");

function packageInfo(n) {
  return `/**
 * ${n.displayName}. Scaffolded from the hello module; its implementation guide says what to
 * build, and README.md in this package is the living guide once code exists.
 *
 * <p>allowedDependencies lists every module package this one may use (17A section 4.2):
 * the kernel's api, then the api or query package of each module the guide names, for
 * example "m1party::api". The architecture tests fail on anything else.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "${n.displayName}",
        allowedDependencies = {"kernel::api"})
package lk.coopfed.knoweb.${n.name};
`;
}

function moduleReadme(n) {
  return `# ${n.name} — ${n.displayName}

Scaffolded from the hello module by \`make new-module\`. This file is the living guide of the module (AGENTS.md): once code exists, it and the tests supersede the implementation guide for day-to-day work. Record every deviation from the guide here, with the reason.

Read \`hello/README.md\` first: the six rules it lists apply here unchanged.

## After scaffolding: what to change by hand

The copy compiles and its tests pass, but it is still a greeting with another name. In this order:

1. **Permissions.** \`${n.schema}.${n.entity}.register\` and \`${n.schema}.${n.entity}.read\` are placeholders. Replace them, in the \`@CommandHandler\` and in \`openapi/${n.name}.yaml\`, with the permission codes of your guide (they look like \`cat.sku.create\`).
2. **The table.** \`db/migration/${n.name}/V0001__${n.entity}.sql\` has the columns of a greeting. Replace them with the DDL of your guide (section 3). Keep the row-level security block and the narrow grants; add the location clause to \`own_read\` if the table has a \`location_id\`.
3. **The slice.** \`openapi/${n.name}.yaml\`: replace the operations with those of your guide (section 5), then run \`make gen-clients\`.
4. **Handler, entity, queries.** Follow the handler specifications of your guide (section 6). One handler per command, each with its audit event and domain event.
5. **Messages.** The ids \`${n.schema}.*\` in \`i18n/{en,si,ta}.json\` and in \`web/src/modules/${n.name}/${n.schema}.messages.json\` still carry the greeting texts. Replace the English, and have the Sinhala and Tamil translated; a missing language fails the build.
6. **Tests.** \`${n.Schema}ModuleIntegrationTest\` proves isolation, grants, the command pipeline and the time rule for the copied table. Keep those four groups; rewrite the cases for your aggregate.
7. **Dependencies.** Add the modules your guide names to \`allowedDependencies\` in \`package-info.java\`.
8. **The event type** is \`${n.entity}.registered.v1\`. Use the names of your guide.

## Deviations from the implementation guide

None yet.
`;
}

// ---- the shared files a module registers itself in ----------------------------------------

const I18N_DIR = `${BACKEND}/main/resources/i18n`;
const ROUTER = "web/src/router.tsx";
const WEB_MESSAGES = "web/src/shell/i18n/messages.ts";
const IMPORT_MARKER = "// new-module:import";
const ENTRY_MARKER = "// new-module:entry";

/** The edited content of the five shared files, as { target, content }. */
export function sharedEdits(root, n) {
  const edits = [];

  // Backend message ids: every hello id gets a twin under the new prefix, texts unchanged.
  for (const language of ["en", "si", "ta"]) {
    const target = `${I18N_DIR}/${language}.json`;
    const catalogue = JSON.parse(read(path.join(root, target)));
    for (const [id, text] of Object.entries(catalogue)) {
      if (id.startsWith("hello.")) {
        catalogue[rename(id, n)] = text;
      }
    }
    const sorted = Object.fromEntries(Object.entries(catalogue).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
    edits.push({ target, content: `${JSON.stringify(sorted, null, 2)}\n` });
  }

  edits.push({
    target: ROUTER,
    content: insertBeforeMarkers(read(path.join(root, ROUTER)), ROUTER, {
      [IMPORT_MARKER]: `import { ${n.schema}Routes } from "./modules/${n.name}/routes";`,
      [ENTRY_MARKER]: `      ...${n.schema}Routes,`
    })
  });
  edits.push({
    target: WEB_MESSAGES,
    content: insertBeforeMarkers(read(path.join(root, WEB_MESSAGES)), WEB_MESSAGES, {
      [IMPORT_MARKER]: `import ${n.schema}Messages from "../../modules/${n.name}/${n.schema}.messages.json";`,
      [ENTRY_MARKER]: `  ${n.schema}Messages,`
    })
  });
  return edits;
}

function insertBeforeMarkers(content, file, linesByMarker) {
  let result = content;
  for (const [marker, line] of Object.entries(linesByMarker)) {
    const lines = result.split("\n");
    const at = lines.findIndex((l) => l.includes(marker));
    if (at < 0) {
      throw new ScaffoldError(`${file} has lost its "${marker}" comment; new-module adds its line above it.`);
    }
    lines.splice(at, 0, line);
    result = lines.join("\n");
  }
  return result;
}

// ---- safety checks --------------------------------------------------------------------------

/** A folder that holds nothing but a .gitkeep is a placeholder, not an existing module. */
function isPlaceholder(dir) {
  const entries = fs.readdirSync(dir);
  return entries.length === 0 || (entries.length === 1 && entries[0] === ".gitkeep");
}

export function problems(root, n, files) {
  const found = [];
  const targetDirs = [...SOURCES.map((s) => s.to(n))];
  for (const dir of targetDirs) {
    const full = path.join(root, dir);
    if (fs.existsSync(full) && !isPlaceholder(full)) {
      found.push(`${dir} already has files in it`);
    }
  }
  for (const file of files) {
    if (fs.existsSync(path.join(root, file.target))) {
      found.push(`${file.target} already exists`);
    }
  }
  // Spring names beans after their class: two modules with an entity of the same name would
  // both define <Entity>QueriesImpl and the application would not start.
  const javaRoot = path.join(root, `${BACKEND}/main/java/lk/coopfed/knoweb`);
  const clash = walk(javaRoot).find((f) => path.basename(f) === `${n.Entity}QueriesImpl.java`);
  if (clash) {
    found.push(`an aggregate called ${n.Entity} already exists: ${posix(path.relative(root, clash))}`);
  }
  // The copied files import classes such as jakarta.persistence.Entity and java.util.List. An
  // aggregate with the same simple name (ENTITY=entity, ENTITY=list) would not compile.
  const imported = new Set();
  for (const source of SOURCES) {
    for (const file of walk(path.join(root, source.from)).filter((f) => f.endsWith(".java"))) {
      for (const match of read(file).matchAll(/^import (?:static )?[\w.]+\.(\w+);/gm)) {
        imported.add(match[1]);
      }
    }
  }
  for (const candidate of [n.Entity, n.Entities, n.Schema]) {
    if (imported.has(candidate)) {
      found.push(
        `a class called ${candidate} would clash with an import of the same name in the template; ` +
        "choose another word (for M1, for example, ENTITY=party) and rename by hand later");
    }
  }
  return found;
}

// ---- command line -----------------------------------------------------------------------------

function parseArgs(argv) {
  const args = { dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    if (flag === "--dry-run") {
      args.dryRun = true;
    } else if (["--name", "--schema", "--entity", "--plural"].includes(flag)) {
      args[flag.slice(2)] = argv[++i] || undefined;
    } else {
      throw new ScaffoldError(`Unknown argument: ${flag}`);
    }
  }
  return args;
}

export function run(root, argv, log = console.log) {
  const args = parseArgs(argv);
  const n = namesFor(args);
  const files = plan(root, n);
  const issues = problems(root, n, files);
  if (issues.length > 0) {
    throw new ScaffoldError(`Nothing was written, because:\n  - ${issues.join("\n  - ")}`);
  }
  const edits = sharedEdits(root, n);

  if (args.dryRun) {
    log(`Dry run: ${n.name} (schema ${n.schema}, aggregate ${n.Entity}). Nothing is written.`);
  }
  for (const file of files) {
    log(`${args.dryRun ? "would create" : "created"}  ${file.target}`);
  }
  for (const edit of edits) {
    log(`${args.dryRun ? "would edit  " : "edited  "}  ${edit.target}`);
  }
  if (args.dryRun) {
    return n;
  }

  for (const source of SOURCES) {
    const keep = path.join(root, source.to(n), ".gitkeep");
    if (fs.existsSync(keep)) {
      fs.rmSync(keep);
    }
  }
  for (const file of [...files, ...edits]) {
    const full = path.join(root, file.target);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, file.content, "utf8");
  }

  log("");
  log(`Module ${n.name} scaffolded. Next:`);
  log("  1. make gen-clients      (the web module imports a client that does not exist yet)");
  log("  2. make test && make test-int");
  log(`  3. work through backend/app/src/main/java/lk/coopfed/knoweb/${n.name}/README.md`);
  return n;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  try {
    run(root, process.argv.slice(2));
  } catch (error) {
    if (error instanceof ScaffoldError) {
      console.error(`new-module: ${error.message}`);
      console.error("Usage: make new-module NAME=m2catalogue SCHEMA=catalogue ENTITY=sku [PLURAL=skus]");
      process.exit(2);
    }
    throw error;
  }
}
