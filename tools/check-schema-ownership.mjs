// Rule R4 (doc 17 section 6.1; 17A section 5): a module owns its PostgreSQL schemas and no
// migration touches a schema of another module.
//
//   node tools/check-schema-ownership.mjs
//
// checks db/migration and seed under backend/app resources.
//
// Logical module ownership and migration-location ownership are slightly different for M1:
//   Modulith module:       m1party -> party + security
//   Flyway locations:      m1party -> party
//                         m1security -> security
//
// What it checks, for every folder under db/migration:
//   - the folder is a known migration location
//   - every object a statement creates, alters, drops, writes to or grants on is qualified
//     with a schema
//   - that schema is one the migration location owns
//   - no foreign key crosses into another module's schema
//
// Calling a kernel function, as the row-level security policies do
// (kernel.scope_entity()), touches nothing and is allowed.
//
// tools/checks.test.mjs proves each of these with a migration that breaks it.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { MODULES } from "./new-module.mjs";

// Logical Spring Modulith module -> schema ownership.
//
// ArchitectureTests uses this map. M1 is ONE Modulith module and logically owns
// both the party and security schemas.
export const OWNERSHIP = {
  kernel: ["kernel"],
  hello: ["hello"],

  ...Object.fromEntries(
    Object.entries(MODULES).map(([name, module]) => [
      name,
      module.schemas
    ])
  ),

  // Reserved phase-2 module: no schema yet.
  m10procurement: []
};

// Flyway migration-location -> schema ownership.
//
// Normally the migration folder has the same name as the module.
// M1 is the deliberate exception: one Modulith module uses two independent
// Flyway locations/history streams because both contain V0001 migrations.
export const MIGRATION_OWNERSHIP = {
  ...OWNERSHIP,

  m1party: ["party"],
  m1security: ["security"]
};

// The one migration that may name every schema: it creates them.
const BASELINE = "kernel/V0001__baseline.sql";

const NAME = `"?([a-z_][a-z0-9_]*)"?`;
const QUALIFIED =
  `${NAME}\\s*\\.\\s*"?[a-z_][a-z0-9_]*"?`;
const IF_EXISTS =
  `(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?`;

// Statements that name the object they touch immediately after the keyword.
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

// Data statements.
//
// UPDATE appears in many other SQL constructs, therefore these patterns are
// deliberately anchored to the beginning of a line.
const DATA_STATEMENTS = [
  `^[ \\t]*TRUNCATE\\s+(?:TABLE\\s+)?(?:ONLY\\s+)?`,
  `^[ \\t]*INSERT\\s+INTO\\s+`,
  `^[ \\t]*UPDATE\\s+(?:ONLY\\s+)?`,
  `^[ \\t]*DELETE\\s+FROM\\s+(?:ONLY\\s+)?`
];

// Statements whose target table appears after ON.
const OBJECT_AFTER_ON = [
  `CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?${IF_EXISTS}[^;]*?\\bON\\s+(?:ONLY\\s+)?`,
  `(?:CREATE|ALTER|DROP)\\s+POLICY\\s+${IF_EXISTS}[^;]*?\\bON\\s+`,
  `CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:CONSTRAINT\\s+)?TRIGGER\\s+[^;]*?\\bON\\s+`
];

// Statements that explicitly name a schema.
const SCHEMA_NAMED = [
  `(?:CREATE|DROP|ALTER)\\s+SCHEMA\\s+${IF_EXISTS}${NAME}`,
  `\\bIN\\s+SCHEMA\\s+${NAME}`,
  `\\bON\\s+SCHEMA\\s+${NAME}`
];

/**
 * Comments may mention any schema; only executable SQL matters.
 */
export function stripComments(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/--[^\n]*/g, " ");
}

function lineOf(sql, index) {
  return sql.slice(0, index).split("\n").length;
}

/**
 * Return every ownership violation in one SQL file.
 *
 * Direct callers normally use logical module ownership. The directory scanner
 * passes MIGRATION_OWNERSHIP explicitly so M1's two Flyway locations remain
 * isolated from each other.
 */
export function problemsOfMigration(
  module,
  file,
  rawSql,
  ownership = OWNERSHIP
) {
  const allowed = ownership[module];

  if (!allowed) {
    return [
      `${module}/${file}: ${module} is not a known schema owner`
    ];
  }

  const sql = stripComments(rawSql);
  const problems = [];

  const where = (index) =>
    `${module}/${file}:${lineOf(sql, index)}`;

  const foreign = (schema, index, what) => {
    if (!allowed.includes(schema)) {
      problems.push(
        `${where(index)}: ${what} schema "${schema}"; ${module} owns ` +
        `${allowed.length
          ? allowed.join(", ")
          : "no schema yet"}`
      );
    }
  };

  const statements = [
    ...[...OBJECT_AFTER, ...OBJECT_AFTER_ON]
      .map((prefix) => `\\b${prefix}`),
    ...DATA_STATEMENTS
  ];

  for (const prefix of statements) {
    const qualified =
      new RegExp(`${prefix}${QUALIFIED}`, "gim");

    let match;

    while ((match = qualified.exec(sql)) !== null) {
      foreign(
        match[1].toLowerCase(),
        match.index,
        "this statement touches"
      );
    }

    // Same statement but with a bare object name.
    const bare = new RegExp(
      `${prefix}${NAME}(?!\\s*\\.)(?![a-z0-9_"])`,
      "gim"
    );

    while ((match = bare.exec(sql)) !== null) {
      problems.push(
        `${where(match.index)}: "${match[1]}" has no schema; ` +
        `write ${allowed[0] ?? "<schema>"}.${match[1]}`
      );
    }
  }

  for (const pattern of SCHEMA_NAMED) {
    const named = new RegExp(pattern, "gi");

    let match;

    while ((match = named.exec(sql)) !== null) {
      foreign(
        match[1].toLowerCase(),
        match.index,
        "this statement names"
      );
    }
  }

  const references =
    new RegExp(`\\bREFERENCES\\s+${QUALIFIED}`, "gi");

  let match;

  while ((match = references.exec(sql)) !== null) {
    const schema = match[1].toLowerCase();

    if (!allowed.includes(schema)) {
      problems.push(
        `${where(match.index)}: a foreign key into schema "${schema}"; ` +
        `across modules keep the identifier only, without REFERENCES (doc 18)`
      );
    }
  }

  return problems;
}

/**
 * Return every violation under one db/migration or seed root.
 */
export function problemsOfMigrations(
  migrationRoot,
  ownership = MIGRATION_OWNERSHIP
) {
  const problems = [];

  for (
    const entry of fs.readdirSync(
      migrationRoot,
      { withFileTypes: true }
    )
  ) {
    if (!entry.isDirectory()) {
      continue;
    }

    const module = entry.name;

    if (!(module in ownership)) {
      problems.push(
        `${module}/: not a module this project knows ` +
        `(${Object.keys(ownership).join(", ")}); ` +
        `FlywayConfig never runs this folder`
      );

      continue;
    }

    const folder = path.join(
      migrationRoot,
      module
    );

    const files = fs
      .readdirSync(folder)
      .filter((name) => name.endsWith(".sql"))
      .sort();

    for (const file of files) {
      if (`${module}/${file}` === BASELINE) {
        continue;
      }

      problems.push(
        ...problemsOfMigration(
          module,
          file,
          fs.readFileSync(
            path.join(folder, file),
            "utf8"
          ),
          ownership
        )
      );
    }
  }

  return problems;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) ===
  fileURLToPath(import.meta.url)
) {
  const resources =
    "backend/app/src/main/resources";

  const roots = process.argv[2]
    ? [process.argv[2]]
    : [
      `${resources}/db/migration`,
      `${resources}/seed`
    ];

  const problems = roots
    .filter((root) => fs.existsSync(root))
    .flatMap((root) =>
      problemsOfMigrations(path.resolve(root))
    );

  if (problems.length > 0) {
    problems.forEach((problem) =>
      console.error(problem)
    );

    console.error(
      `\nSchema ownership check failed: ` +
      `${problems.length} problem(s). ` +
      `Rule R4: a module's migrations touch its own schemas only.`
    );

    process.exit(1);
  }

  console.log(
    "Schema ownership check passed."
  );
}