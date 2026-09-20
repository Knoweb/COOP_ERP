// Pipeline check of the permission rule in AGENTS.md: "Every mutating operation carries an
// x-permission in its OpenAPI slice that matches the @CommandHandler annotation."
//
// It fails when
//   1. a slice or a handler still carries a scaffold placeholder (todo.…): `make new-module`
//      cannot know the real codes, so it writes placeholders that this check refuses;
//   2. a @CommandHandler names a permission that no operation of its module's slice carries:
//      the two were meant to be one string and have drifted apart.
//
// Run through `make test`; standard library only.

import fs from "node:fs";
import path from "node:path";

const PLACEHOLDER = "todo.";
const JAVA_ROOT = path.resolve("backend/app/src/main/java/lk/coopfed/knoweb");
const SLICES = path.resolve("backend/app/src/main/resources/openapi");

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}

const problems = [];

// x-permission values per module, from openapi/<module>.yaml
const sliceOf = {};
for (const file of fs.readdirSync(SLICES).filter((f) => f.endsWith(".yaml"))) {
  const module = path.basename(file, ".yaml");
  const permissions = [...fs.readFileSync(path.join(SLICES, file), "utf8").matchAll(/^\s*x-permission:\s*["']?([^\s"'#]+)/gm)]
    .map((m) => m[1]);
  sliceOf[module] = new Set(permissions);
  for (const permission of [...sliceOf[module]].filter((p) => p.startsWith(PLACEHOLDER))) {
    problems.push(`openapi/${file}: x-permission "${permission}" is a scaffold placeholder; use the code from the module's guide`);
  }
}

// @CommandHandler(permission = "...") per module, from the Java sources
for (const file of walk(JAVA_ROOT).filter((f) => f.endsWith(".java"))) {
  const source = fs.readFileSync(file, "utf8");
  const module = path.relative(JAVA_ROOT, file).split(path.sep)[0];
  for (const match of source.matchAll(/@CommandHandler\s*\([^)]*?permission\s*=\s*"([^"]*)"/g)) {
    const permission = match[1];
    const where = path.relative(process.cwd(), file).split(path.sep).join("/");
    if (permission.startsWith(PLACEHOLDER)) {
      problems.push(`${where}: permission "${permission}" is a scaffold placeholder; use the code from the module's guide`);
    } else if (!sliceOf[module]) {
      problems.push(`${where}: module ${module} has a command handler but no slice openapi/${module}.yaml`);
    } else if (!sliceOf[module].has(permission)) {
      problems.push(`${where}: permission "${permission}" is not the x-permission of any operation in openapi/${module}.yaml`);
    }
  }
}

// The web client names permissions too: in a module definition (requiredPermissions), in
// useHasPermission("..."), and in <RequirePermission anyOf={[...]}>. A wrong code there breaks
// nothing loudly: the navigation entry or the button is simply hidden from everybody, because
// nobody holds a permission that does not exist. So every permission-shaped string of a web
// module must be an x-permission of that module's slice.
const WEB_MODULES = path.resolve("web/src/modules");
if (fs.existsSync(WEB_MODULES)) {
  for (const entry of fs.readdirSync(WEB_MODULES, { withFileTypes: true }).filter((e) => e.isDirectory())) {
    const module = entry.name;
    const sources = walk(path.join(WEB_MODULES, module)).filter((f) => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f));
    for (const file of sources) {
      const source = fs.readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, " ").replace(/\/\/[^\n]*/g, " ");
      const where = path.relative(process.cwd(), file).split(path.sep).join("/");
      const named = [
        ...[...source.matchAll(/requiredPermissions\s*:\s*\[([^\]]*)\]/g)].flatMap((m) => [...m[1].matchAll(/"([^"]+)"/g)]),
        ...[...source.matchAll(/anyOf\s*=\s*\{\s*\[([^\]]*)\]/g)].flatMap((m) => [...m[1].matchAll(/"([^"]+)"/g)]),
        ...source.matchAll(/useHasPermission\(\s*"([^"]+)"/g)
      ].map((m) => m[1]);

      for (const permission of named) {
        if (permission.startsWith(PLACEHOLDER)) {
          problems.push(`${where}: permission "${permission}" is a scaffold placeholder; use the code from the module's guide`);
        } else if (!sliceOf[module]?.has(permission)) {
          problems.push(`${where}: permission "${permission}" is not the x-permission of any operation in openapi/${module}.yaml, so nobody holds it and what it guards is hidden from everybody`);
        }
      }
    }
  }
}

if (problems.length > 0) {
  console.error("Permission check failed:");
  for (const problem of problems) {
    console.error(`  - ${problem}`);
  }
  process.exit(1);
}
console.log(`Permission check passed: ${Object.keys(sliceOf).filter((m) => sliceOf[m].size > 0).length} slice(s) with permissions`);
