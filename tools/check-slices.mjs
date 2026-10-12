// Cross-slice validations brought forward from 29A section 7 (M9's bundle assembler validates
// these when it assembles doc 33; decision of 27 September 2026 is to enforce them now, in the
// build, rather than wait for M9 to be the first thing that notices a collision):
//
//   - operationId is unique across every slice (a bundle merges every slice into one document;
//     two operations sharing an id would be one lost in the merge);
//   - every mutating operation (post, put, patch, delete) declares the Idempotency-Key header
//     parameter (AGENTS.md; 29A section 7 step 2 reuses this rule for the bundle);
//   - every operation carries x-permission, GETs included (29A section 7 step 2: "x-permission
//     present on every operation").
//
// check-permissions.mjs already checks that an x-permission value is not a scaffold placeholder
// and that it matches some @CommandHandler or is a real permission of the slice; it does not
// check that every operation HAS one, which is what this file adds. Do not duplicate its rules
// here.
//
// hello.yaml is excluded: the template module's slice is scaffolding, not a contract.
//
// Run through `make test`; standard library only.
//
//   node tools/check-slices.mjs

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SLICES_DIR = "backend/app/src/main/resources/openapi";
const MUTATING_METHODS = new Set(["post", "put", "patch", "delete"]);
// The parameter itself, not a mention of it in a description or comment: the common.yaml $ref
// (as OpenApiSliceRulesTest requires) or an inline parameter named Idempotency-Key.
const IDEMPOTENCY_KEY_PATTERN =
  /^\s*-?\s*(?:\$ref:\s*["']?[^"'\s#]*#\/components\/parameters\/IdempotencyKey["']?|name:\s*["']?Idempotency-Key["']?)\s*$/m;

/**
 * Every operation of one slice, in document order. Regex-driven, like the other tools/*.mjs
 * checks: no YAML parser is a dependency here. A path key is a 2-space-indented line starting
 * with "/" (paths are the only 2-space keys that do); a method key is get/post/put/patch/delete
 * indented 4 spaces directly under it. An operation's block runs to the next line at 4 spaces
 * or less that is not blank or a comment.
 */
export function operationsOf(sliceText) {
  const lines = sliceText.split("\n");
  const operations = [];

  let currentPath = null;
  let block = null; // { method, path, startLine, lines: [] }

  const flush = () => {
    if (block) {
      operations.push(toOperation(block));
    }
    block = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // A path key may be written plain or quoted ("/v1/x":); a quoted one is not skipped.
    const pathMatch = line.match(/^ {2}["']?(\/[^\s"']*)["']?:\s*$/);
    if (pathMatch) {
      flush();
      currentPath = pathMatch[1];
      continue;
    }

    const methodMatch = line.match(/^ {4}(get|post|put|patch|delete):\s*$/);
    if (methodMatch && currentPath) {
      flush();
      block = { method: methodMatch[1], path: currentPath, startLine: i + 1, lines: [] };
      continue;
    }

    if (block) {
      const indentedDeeper = /^ {5,}\S/.test(line) || line.trim() === "" || line.trim().startsWith("#");
      if (!indentedDeeper) {
        flush();
      } else {
        block.lines.push(line);
      }
    }
  }
  flush();

  return operations;
}

function toOperation(block) {
  const text = block.lines.join("\n");
  const operationId = text.match(/^\s*operationId:\s*["']?([^\s"'#]+)/m)?.[1] ?? null;
  const permission = text.match(/^\s*x-permission:\s*["']?([^\s"'#]+)/m)?.[1] ?? null;
  const hasIdempotencyKey = IDEMPOTENCY_KEY_PATTERN.test(text);

  return {
    method: block.method,
    path: block.path,
    line: block.startLine,
    operationId,
    permission,
    hasPermission: permission !== null,
    hasIdempotencyKey
  };
}

/** The problems of one slice on its own: missing Idempotency-Key, missing x-permission. */
export function problemsOfSlice(sliceName, sliceText) {
  const problems = [];

  for (const operation of operationsOf(sliceText)) {
    const where = `openapi/${sliceName}.yaml:${operation.line} ${operation.method.toUpperCase()} ${operation.path}`;

    if (!operation.hasPermission) {
      problems.push(`${where}: no x-permission (29A section 7 step 2: every operation carries one)`);
    }

    if (MUTATING_METHODS.has(operation.method) && !operation.hasIdempotencyKey) {
      problems.push(`${where}: a mutating operation with no Idempotency-Key parameter`);
    }
  }

  return problems;
}

/**
 * The problems across every slice: each slice's own problems, plus operationId uniqueness
 * across all of them (a bundle has one namespace for every operationId; two slices sharing one
 * would collide when M9 merges them). `slices` is { sliceName: sliceText }, hello excluded by
 * the caller.
 */
export function problemsOfSlices(slices) {
  const problems = Object.entries(slices).flatMap(([name, text]) => problemsOfSlice(name, text));

  const seenAt = new Map(); // operationId -> "sliceName:line"
  for (const [name, text] of Object.entries(slices)) {
    for (const operation of operationsOf(text)) {
      if (!operation.operationId) {
        continue; // already reported as missing elsewhere if that matters; not this rule's job
      }
      const where = `openapi/${name}.yaml:${operation.line}`;
      const seen = seenAt.get(operation.operationId);
      if (seen) {
        problems.push(`${where}: operationId "${operation.operationId}" is already used at ${seen} (doc 33's bundle has one namespace for every operationId)`);
      } else {
        seenAt.set(operation.operationId, where);
      }
    }
  }

  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const slices = Object.fromEntries(
    fs
      .readdirSync(SLICES_DIR)
      .filter((f) => f.endsWith(".yaml") && f !== "hello.yaml")
      .map((f) => [path.basename(f, ".yaml"), fs.readFileSync(path.join(SLICES_DIR, f), "utf8")])
  );

  const problems = problemsOfSlices(slices);

  if (problems.length > 0) {
    problems.forEach((problem) => console.error(problem));
    console.error(`\nSlice check failed: ${problems.length} problem(s).`);
    process.exit(1);
  }

  console.log(`Slice check passed: ${Object.keys(slices).length} slice(s) checked.`);
}
