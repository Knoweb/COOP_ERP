// Pipeline check of the permission rule in AGENTS.md: "Every mutating operation carries an
// x-permission in its OpenAPI slice that matches the @CommandHandler annotation."
//
// It fails when
//   1. a slice or a handler still carries a scaffold placeholder (todo.…): `make new-module`
//      cannot know the real codes, so it writes placeholders that this check refuses;
//   2. a @CommandHandler names a permission that no operation of its module's slice carries:
//      the two were meant to be one string and have drifted apart;
//   3. a @CommandHandler's permission is neither a quoted code nor CommandHandler.INTERNAL: a
//      constant or an expression is a value this check cannot read, so it would escape rule 2;
//   4. an operation of a slice carries x-permission "internal": an internal command has no
//      operation (CR-19A-6), and nobody holds a permission of that name;
//   5. x-permission "authenticated" (any signed-in principal; CR-19A-9) is on anything but a
//      GET, or on a @CommandHandler: it is for a read of the caller's own facts (the session)
//      and never lets a command through.
//
// Run through `make test`; standard library only. The functions are exported for
// tools/checks.test.mjs.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { operationsOf } from "./check-slices.mjs";

export const PLACEHOLDER = "todo.";

/** The one x-permission that is no code of the catalogue: any signed-in principal, on a GET (kernel SliceOperations.AUTHENTICATED). */
export const AUTHENTICATED = "authenticated";

/** What CommandHandler.INTERNAL holds (kernel.api.CommandHandler). */
export const INTERNAL = "internal";

/** The ways of writing CommandHandler.INTERNAL in a handler's annotation that this check accepts. */
const INTERNAL_CONSTANT = new Set(["CommandHandler.INTERNAL", "lk.coopfed.knoweb.kernel.api.CommandHandler.INTERNAL"]);

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}

/**
 * The x-permission values of each slice, and what is wrong with them.
 *
 * @param slices {Object<string, string>} module name to the text of openapi/<module>.yaml
 * @returns {{sliceOf: Object<string, Set<string>>, problems: string[]}}
 */
export function readSlices(slices) {
  const sliceOf = {};
  const problems = [];
  for (const [module, text] of Object.entries(slices)) {
    const permissions = [...text.matchAll(/^\s*x-permission:\s*["']?([^\s"'#]+)/gm)].map((m) => m[1]);
    sliceOf[module] = new Set(permissions);
    for (const operation of operationsOf(text)) {
      if (operation.permission === AUTHENTICATED && operation.method !== "get") {
        problems.push(
          `openapi/${module}.yaml:${operation.line} ${operation.method.toUpperCase()} ${operation.path}: x-permission "${AUTHENTICATED}"`
            + ` on a mutating operation; it admits any signed-in principal and is for a read of the caller's own facts (CR-19A-9)`
        );
      }
    }
    for (const permission of sliceOf[module]) {
      if (permission.startsWith(PLACEHOLDER)) {
        problems.push(`openapi/${module}.yaml: x-permission "${permission}" is a scaffold placeholder; use the code from the module's guide`);
      } else if (permission === INTERNAL) {
        problems.push(
          `openapi/${module}.yaml: x-permission "${INTERNAL}" on an operation; an internal command has no operation`
            + ` of its own (CR-19A-6): give the operation the permission code of its guide`
        );
      }
    }
  }
  return { sliceOf, problems };
}

/** The Java source without its comments (block and whole-line), so a javadoc example is not read as code. */
function withoutComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/^\s*\/\/[^\n]*$/gm, " ");
}

/**
 * What is wrong with the @CommandHandler permissions of one Java source.
 *
 * @param where   the file, as it is reported
 * @param module  the module the file belongs to (first package below lk.coopfed.knoweb)
 * @param source  the Java source
 * @param sliceOf the x-permission values of each slice, from {@link readSlices}
 */
export function problemsOfHandlerSource(where, module, source, sliceOf) {
  const problems = [];
  for (const match of withoutComments(source).matchAll(/@CommandHandler\s*\(([^)]*)\)/g)) {
    // Everything up to the next attribute: a permission code has no comma, so a value that is not
    // one quoted string (a concatenation, a constant) is read whole and refused below.
    const value = /\bpermission\s*=\s*([^,]+)/.exec(match[1])?.[1]?.trim();
    if (value === undefined) {
      problems.push(`${where}: @CommandHandler without a permission`);
      continue;
    }
    // An internal command (permission = CommandHandler.INTERNAL) has no operation of its own:
    // another module's handler calls it inside a command that carries the x-permission.
    if (INTERNAL_CONSTANT.has(value)) {
      continue;
    }
    if (!value.startsWith('"') || !/^"[^"\\]*"$/.test(value)) {
      problems.push(
        `${where}: permission = ${value} is not a quoted permission code; write the code itself`
          + ` ("cat.sku.create"), or CommandHandler.INTERNAL for an internal command, so that this check can read it`
      );
      continue;
    }
    const permission = value.slice(1, -1);
    if (permission === INTERNAL) {
      problems.push(`${where}: permission "${INTERNAL}" written as a string; write CommandHandler.INTERNAL`);
    } else if (permission === AUTHENTICATED) {
      problems.push(`${where}: permission "${AUTHENTICATED}" on a command handler; a command needs a permission of the catalogue (CR-19A-9)`);
    } else if (permission.startsWith(PLACEHOLDER)) {
      problems.push(`${where}: permission "${permission}" is a scaffold placeholder; use the code from the module's guide`);
    } else if (!sliceOf[module]) {
      problems.push(`${where}: module ${module} has a command handler but no slice openapi/${module}.yaml`);
    } else if (!sliceOf[module].has(permission)) {
      problems.push(`${where}: permission "${permission}" is not the x-permission of any operation in openapi/${module}.yaml`);
    }
  }
  return problems;
}

/**
 * The web client names permissions too: in a module definition (requiredPermissions), in
 * useHasPermission("..."), and in <RequirePermission anyOf={[...]}>. A wrong code there breaks
 * nothing loudly: the navigation entry or the button is simply hidden from everybody, because
 * nobody holds a permission that does not exist. So every permission-shaped string of a web
 * module must be an x-permission of that module's slice, or, for a screen that also reads another
 * module's API (the GRN card shows the stock M5 moved), of that other module's slice: the code
 * must be one an operation carries, whichever slice carries it.
 */
function problemsOfWebModules(webModules, sliceOf) {
  const problems = [];
  if (!fs.existsSync(webModules)) {
    return problems;
  }
  for (const entry of fs.readdirSync(webModules, { withFileTypes: true }).filter((e) => e.isDirectory())) {
    const module = entry.name;
    const sources = walk(path.join(webModules, module)).filter((f) => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f));
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
        } else if (!sliceOf[module]?.has(permission) && !Object.values(sliceOf).some((codes) => codes.has(permission))) {
          problems.push(`${where}: permission "${permission}" is not the x-permission of any operation in openapi/${module}.yaml or another slice, so nobody holds it and what it guards is hidden from everybody`);
        }
      }
    }
  }
  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const javaRoot = path.resolve("backend/app/src/main/java/lk/coopfed/knoweb");
  const slicesDir = path.resolve("backend/app/src/main/resources/openapi");

  const slices = Object.fromEntries(
    fs.readdirSync(slicesDir)
      .filter((f) => f.endsWith(".yaml"))
      .map((f) => [path.basename(f, ".yaml"), fs.readFileSync(path.join(slicesDir, f), "utf8")])
  );
  const { sliceOf, problems } = readSlices(slices);

  for (const file of walk(javaRoot).filter((f) => f.endsWith(".java"))) {
    const module = path.relative(javaRoot, file).split(path.sep)[0];
    const where = path.relative(process.cwd(), file).split(path.sep).join("/");
    problems.push(...problemsOfHandlerSource(where, module, fs.readFileSync(file, "utf8"), sliceOf));
  }

  problems.push(...problemsOfWebModules(path.resolve("web/src/modules"), sliceOf));

  if (problems.length > 0) {
    console.error("Permission check failed:");
    for (const problem of problems) {
      console.error(`  - ${problem}`);
    }
    process.exit(1);
  }
  console.log(`Permission check passed: ${Object.keys(sliceOf).filter((m) => sliceOf[m].size > 0).length} slice(s) with permissions`);
}
