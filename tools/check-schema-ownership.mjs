// Rule R4 (doc 17 section 6.1; 17A section 5): a module owns its PostgreSQL schemas and no
// migration touches a schema of another module.
//
//   node tools/check-schema-ownership.mjs            checks db/migration and seed under backend/app resources
//
// What it checks, for every folder under db/migration:
//   - the folder is a known module (a misspelt or unregistered folder is never migrated by
//     FlywayConfig, and used to be skipped here in silence)
//   - every object a statement creates, alters, drops, writes to or grants on is qualified
//     with a schema (an unqualified name lands wherever the search path points)
//   - that schema is one the module owns
//   - no foreign key crosses into another module's schema (identifiers only; doc 18)
// Calling a kernel function, as the row-level security policies do (kernel.scope_entity()),
// touches nothing and is allowed.
//
// tools/checks.test.mjs proves each of these with a migration that breaks it.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { MODULES } from "./new-module.mjs";

// One list of modules and schemas for the tools: the scaffolder's. checks.test.mjs keeps it
// equal to ArchitectureTests.SCHEMA_OWNERSHIP and to what FlywayConfig migrates.
// m10procurement is a reserved package of phase 2: it owns no schema yet, so any migration
// in it fails.
export const OWNERSHIP = {
  kernel: ["kernel"],
  hello: ["hello"],
  ...Object.fromEntries(Object.entries(MODULES).map(([name, module]) => [name, module.schemas])),
  m10procurement: []
};

// The one migration that may name every schema: it creates them (17A section 6.1).
const BASELINE = "kernel/V0001__baseline.sql";

const NAME = `"?([a-z_][a-z0-9_]*)"?`;
const QUALIFIED = `${NAME}\\s*\\.\\s*"?[a-z_][a-z0-9_]*"?`;
const IF_EXISTS = `(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?`;

// Statements that name the object they touch right after the keyword. Group 1 is the schema
// when the name is qualified; the same pattern with a bare name is an unqualified object.
const OBJECT_AFTER = [
  `CREATE\\s+(?:UNLOGGED\\s+|TEMP(?:ORARY)?\\s+)?TABLE\\s+${IF_EXISTS}`,
  `ALTER\\s+TABLE\\s+${IF_EXISTS}(?:ONLY\\s+)?`,
  `DROP\\s+TABLE\\s+${IF_EXISTS}`,
  `CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+${IF_EXISTS}`,
  `DROP\\s+(?:MATERIALIZED\\s+)?VIEW\\s+${IF_EXISTS}`,
  `CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:FUNCTION|PROCEDURE)\\s+`,
  `DROP\\s+(?:FUNCTION|PROCEDURE)\\s+${IF_EXISTS}`,
  `CREATE\\s+SEQUENCE\\s+${IF_EXISTS}`,
  `CREATE\\s+TYPE\\s+`,
  `CREATE\\s+DOMAIN\\s+`,
  `COMMENT\\s+ON\\s+(?:TABLE|COLUMN|VIEW|FUNCTION|SEQUENCE|TYPE)\\s+`
];

// Data statements. They are looked for at the start of a line only: UPDATE also appears inside
// other statements (ON UPDATE CASCADE, DO UPDATE SET, GRANT ... UPDATE ON, FOR UPDATE).
const DATA_STATEMENTS = [
  `^[ \\t]*TRUNCATE\\s+(?:TABLE\\s+)?(?:ONLY\\s+)?`,
  `^[ \\t]*INSERT\\s+INTO\\s+`,
  `^[ \\t]*UPDATE\\s+(?:ONLY\\s+)?`,
  `^[ \\t]*DELETE\\s+FROM\\s+(?:ONLY\\s+)?`
];

// Statements that name their table after ON: CREATE INDEX i ON s.t, CREATE POLICY p ON s.t,
// CREATE TRIGGER ... ON s.t, ALTER POLICY, DROP POLICY.
const OBJECT_AFTER_ON = [
  `CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?${IF_EXISTS}[^;]*?\\bON\\s+(?:ONLY\\s+)?`,
  `(?:CREATE|ALTER|DROP)\\s+POLICY\\s+${IF_EXISTS}[^;]*?\\bON\\s+`,
  `CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:CONSTRAINT\\s+)?TRIGGER\\s+[^;]*?\\bON\\s+`
];

// Statements that name a schema outright.
const SCHEMA_NAMED = [
  `(?:CREATE|DROP|ALTER)\\s+SCHEMA\\s+${IF_EXISTS}${NAME}`,
  `\\bIN\\s+SCHEMA\\s+${NAME}`,                 // GRANT ... ON ALL TABLES IN SCHEMA, ALTER DEFAULT PRIVILEGES
  `\\bON\\s+SCHEMA\\s+${NAME}`                  // GRANT USAGE ON SCHEMA
];

/** Comments say what they like; only statements are checked. */
export function stripComments(sql) {
  return sql.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/--[^\n]*/g, " ");
}

function lineOf(sql, index) {
  return sql.slice(0, index).split("\n").length;
}

/** Every violation in one migration file, as sentences a developer can act on. */
export function problemsOfMigration(module, file, rawSql, ownership = OWNERSHIP) {
  const allowed = ownership[module];
  const sql = stripComments(rawSql);
  const problems = [];
  const where = (index) => `${module}/${file}:${lineOf(sql, index)}`;

  const foreign = (schema, index, what) => {
    if (!allowed.includes(schema)) {
      problems.push(
        `${where(index)}: ${what} schema "${schema}"; ${module} owns ` +
        `${allowed.length ? allowed.join(", ") : "no schema yet"}`
      );
    }
  };

  const statements = [
    ...[...OBJECT_AFTER, ...OBJECT_AFTER_ON].map((prefix) => `\\b${prefix}`),
    ...DATA_STATEMENTS
  ];
  for (const prefix of statements) {
    const qualified = new RegExp(`${prefix}${QUALIFIED}`, "gim");
    let match;
    while ((match = qualified.exec(sql)) !== null) {
      foreign(match[1].toLowerCase(), match.index, "this statement touches");
    }
    // The same statement with a bare name: nothing says which schema it lands in.
    const bare = new RegExp(`${prefix}${NAME}(?!\\s*\\.)(?![a-z0-9_"])`, "gim");
    while ((match = bare.exec(sql)) !== null) {
      problems.push(
        `${where(match.index)}: "${match[1]}" has no schema; write ${allowed[0] ?? "<schema>"}.${match[1]}`
      );
    }
  }

  for (const pattern of SCHEMA_NAMED) {
    const named = new RegExp(pattern, "gi");
    let match;
    while ((match = named.exec(sql)) !== null) {
      foreign(match[1].toLowerCase(), match.index, "this statement names");
    }
  }

  const references = new RegExp(`\\bREFERENCES\\s+${QUALIFIED}`, "gi");
  let match;
  while ((match = references.exec(sql)) !== null) {
    const schema = match[1].toLowerCase();
    if (!allowed.includes(schema)) {
      problems.push(
        `${where(match.index)}: a foreign key into schema "${schema}"; across modules keep the ` +
        `identifier only, without REFERENCES (doc 18)`
      );
    }
  }
  return problems;
}

/** Every violation under one db/migration folder. */
export function problemsOfMigrations(migrationRoot, ownership = OWNERSHIP) {
  const problems = [];
  for (const entry of fs.readdirSync(migrationRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) {
      continue;
    }
    const module = entry.name;
    if (!(module in ownership)) {
      problems.push(
        `${module}/: not a module this project knows (${Object.keys(ownership).join(", ")}); ` +
        `FlywayConfig never runs this folder`
      );
      continue;
    }
    const folder = path.join(migrationRoot, module);
    for (const file of fs.readdirSync(folder).filter((name) => name.endsWith(".sql")).sort()) {
      if (`${module}/${file}` === BASELINE) {
        continue;
      }
      problems.push(...problemsOfMigration(module, file, fs.readFileSync(path.join(folder, file), "utf8"), ownership));
    }
  }
  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  // Seeds obey the same rule: the seed folder of a module writes to that module's schemas.
  const resources = "backend/app/src/main/resources";
  const roots = process.argv[2] ? [process.argv[2]] : [`${resources}/db/migration`, `${resources}/seed`];
  const problems = roots
    .filter((root) => fs.existsSync(root))
    .flatMap((root) => problemsOfMigrations(path.resolve(root)));
  if (problems.length > 0) {
    problems.forEach((problem) => console.error(problem));
    console.error(`\nSchema ownership check failed: ${problems.length} problem(s). Rule R4: a module's migrations touch its own schemas only.`);
    process.exit(1);
  }
  console.log("Schema ownership check passed.");
}
