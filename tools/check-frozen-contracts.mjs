// The M1 freeze conditions (decisions of 27 September 2026, docs/PLAN_TO_M2.md Phase 4, M1-13):
// AGENTS.md says a contract (a module's api/query packages plus its OpenAPI slice) is "frozen
// once depended on", but M1-13 (#133) only wrote 1.0.0 into openapi/m1party.yaml and a header
// comment; nothing enforced it. This does.
//
// Rule: for every openapi/<module>.yaml whose info.version on origin/main is already >= 1.0.0,
// a change (against origin/main) to
//   - that slice file, or
//   - backend/app/src/main/java/lk/coopfed/knoweb/<module>/api/**
//   - backend/app/src/main/java/lk/coopfed/knoweb/<module>/query/**
// needs one of:
//   1. info.version here is greater than origin/main's (the slice's own shape changed: a real
//      version bump), or
//   2. info.x-change-request here names a file under docs/change-requests/ that exists and
//      differs from origin/main's info.x-change-request value (an api/query-only Java change,
//      recorded against a change request instead of a version bump).
//
// Rule 2 exists because a change to the api or query package (records, query interfaces) is a
// change to the contract even when the REST slice's shape does not move, and 21A/22A-style
// guides do not always ask for a version bump for that; the change request is the paper trail
// AGENTS.md already asks for ("if it changes a contract, draft a change request").
//
// A module below 1.0.0 on origin/main (still under construction) is not frozen yet and is not
// checked; hello.yaml has no version discipline at all and is excluded by having no version.
//
// Run through `make test`; standard library and git only (needs the full history: CI checks out
// with fetch-depth 0; a shallow local clone can `git fetch --unshallow` first).
//
//   node tools/check-frozen-contracts.mjs

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { compareVersions } from "./vulnerability-report.mjs";

const SLICES_DIR = "backend/app/src/main/resources/openapi";
const JAVA_ROOT = "backend/app/src/main/java/lk/coopfed/knoweb";
const CHANGE_REQUESTS_DIR = "docs/change-requests";

function git(args) {
  return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function readAtRef(ref, file) {
  try {
    return git(["show", `${ref}:${file}`]);
  } catch {
    return null; // the file does not exist at that ref
  }
}

export function infoField(yamlText, field) {
  if (yamlText == null) {
    return null;
  }

  // info: is a top-level mapping; the first line that is not blank and not indented ends it.
  // Line-based on purpose (a lookahead-based regex for "until the next top-level key" is easy
  // to get subtly wrong with the multiline flag's end-of-line anchor): no YAML parser is a
  // dependency here, but this reads it a line at a time like one would.
  const lines = yamlText.split("\n");
  const start = lines.findIndex((line) => line === "info:");
  if (start === -1) {
    return null;
  }

  const fieldPattern = new RegExp(`^\\s+${field}:\\s*["']?([^\\s"'#]+)`);
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i];
    if (line.trim() !== "" && !/^\s/.test(line)) {
      break; // the next top-level key
    }
    const match = line.match(fieldPattern);
    if (match) {
      return match[1];
    }
  }
  return null;
}

/**
 * The rule for one slice, given whether anything the freeze watches changed. Pure: no git, no
 * filesystem. `existingChangeRequests` is the set of file names under docs/change-requests/
 * (e.g. "CR-21A-4.md") that actually exist.
 */
export function problemsOfFrozenContract(sliceRelative, mainText, localText, changed, existingChangeRequests) {
  const mainVersion = infoField(mainText, "version");

  if (!mainVersion || compareVersions(mainVersion, "1.0.0") < 0) {
    return []; // not frozen yet on origin/main
  }

  if (!changed) {
    return [];
  }

  if (localText === null) {
    return [`${sliceRelative}: frozen at ${mainVersion} on origin/main and now removed`];
  }

  const localVersion = infoField(localText, "version");
  if (localVersion && compareVersions(localVersion, mainVersion) > 0) {
    return []; // a real version bump: rule 1 satisfied
  }

  const mainChangeRequest = infoField(mainText, "x-change-request");
  const localChangeRequest = infoField(localText, "x-change-request");

  if (localChangeRequest && localChangeRequest !== mainChangeRequest) {
    const crFile = `CR-${localChangeRequest.replace(/^CR-/, "")}.md`;
    if (existingChangeRequests.has(crFile)) {
      return []; // rule 2 satisfied
    }
    return [`${sliceRelative}: info.x-change-request "${localChangeRequest}" names no file docs/change-requests/${crFile}`];
  }

  return [
    `${sliceRelative}: frozen at ${mainVersion} on origin/main; the api/query package or the ` +
      `slice changed but info.version was not raised above ${mainVersion} and info.x-change-request ` +
      `was not set to a new change request. Bump info.version for a slice change, or add ` +
      `info.x-change-request: CR-... naming a file under docs/change-requests/ for an api/query-only change.`
  ];
}

function changedAgainstMain(paths) {
  const existing = paths.filter((p) => fs.existsSync(p) || readAtRef("origin/main", p) !== null);
  if (existing.length === 0) {
    return false;
  }
  try {
    const diff = git(["diff", "--name-only", "origin/main", "HEAD", "--", ...existing]);
    if (diff.trim().length > 0) {
      return true;
    }
  } catch {
    // fall through to the working-tree comparison below
  }
  // Uncommitted changes (a developer running this before committing) do not show in
  // `git diff origin/main HEAD`; check the working tree against origin/main too.
  try {
    const diff = git(["diff", "origin/main", "--", ...existing]);
    return diff.trim().length > 0;
  } catch {
    return false;
  }
}

export function problemsOfFrozenContracts() {
  if (!fs.existsSync(SLICES_DIR)) {
    return [];
  }

  const existingChangeRequests = fs.existsSync(CHANGE_REQUESTS_DIR)
    ? new Set(fs.readdirSync(CHANGE_REQUESTS_DIR))
    : new Set();

  return fs
    .readdirSync(SLICES_DIR)
    .filter((f) => f.endsWith(".yaml"))
    .flatMap((file) => {
      const module = path.basename(file, ".yaml");
      if (module === "hello" || module === "common") {
        return [];
      }

      const sliceRelative = `${SLICES_DIR}/${file}`;
      const mainText = readAtRef("origin/main", sliceRelative);
      const mainVersion = infoField(mainText, "version");

      if (!mainVersion || compareVersions(mainVersion, "1.0.0") < 0) {
        return []; // not frozen yet on origin/main: no need to even look at the Java packages
      }

      const localText = fs.existsSync(sliceRelative) ? fs.readFileSync(sliceRelative, "utf8") : null;
      const watchedPaths = [sliceRelative, `${JAVA_ROOT}/${module}/api`, `${JAVA_ROOT}/${module}/query`];

      return problemsOfFrozenContract(
        sliceRelative,
        mainText,
        localText,
        changedAgainstMain(watchedPaths),
        existingChangeRequests
      );
    });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const found = problemsOfFrozenContracts();

  if (found.length > 0) {
    found.forEach((problem) => console.error(problem));
    console.error(`\nFrozen-contract check failed: ${found.length} problem(s). AGENTS.md: a contract is frozen once depended on.`);
    process.exit(1);
  }

  console.log("Frozen-contract check passed.");
}
