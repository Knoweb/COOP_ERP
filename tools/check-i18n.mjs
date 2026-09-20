// The message catalogue of the backend is complete (AGENTS.md: "every user-visible string is a
// message id with en, si, ta translations; a missing one fails the build"; doc 19 section 5.2).
//
//   node tools/check-i18n.mjs
//
// What it checks:
//   - every id is in all three files, and no text is empty
//   - every id the Java code answers with is in the catalogue: the first argument of
//     new ProblemException(...) and of messages.t(...). Before this, an id that was in none of
//     the three files passed, and the user saw "party.entity.duplicate" as the message
//   - the three texts of an id carry the same placeholders ({0}, {1} ...)
//   - no text contains the ASCII apostrophe ('). In a message format the apostrophe is the
//     quote character: "Don't use {0}" prints "Dont use {0}" and the value never appears.
//     Write the typographic apostrophe (’) instead; it is also the correct character.
//
// tools/checks.test.mjs proves each of these with a catalogue that breaks it.
// The web client's own catalogue is checked by web/src/shell/i18n/messages.test.ts.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const LANGUAGES = ["en", "si", "ta"];

export function readCatalogues(i18nDir) {
  return Object.fromEntries(LANGUAGES.map((language) => {
    const file = path.join(i18nDir, `${language}.json`);
    if (!fs.existsSync(file)) {
      throw new Error(`Missing i18n file: ${file}`);
    }
    try {
      return [language, JSON.parse(fs.readFileSync(file, "utf8"))];
    } catch (error) {
      throw new Error(`${file} is not valid JSON: ${error.message}`);
    }
  }));
}

function placeholders(text) {
  return [...new Set(String(text).match(/\{\d+/g) ?? [])].sort().join(" ").replaceAll("{", "");
}

/** Problems inside the three catalogue files themselves. */
export function problemsOfCatalogues(catalogues) {
  const problems = [];
  const ids = new Set(LANGUAGES.flatMap((language) => Object.keys(catalogues[language])));

  for (const id of [...ids].sort()) {
    for (const language of LANGUAGES) {
      const text = catalogues[language][id];
      if (text === undefined) {
        problems.push(`${language}.json: no text for ${id}`);
      } else if (typeof text !== "string" || text.trim() === "") {
        problems.push(`${language}.json: the text of ${id} is empty`);
      } else if (text.includes("'")) {
        problems.push(
          `${language}.json: the text of ${id} contains the ASCII apostrophe ('), which a message format ` +
          `reads as a quote and drops, together with any {0} after it; write ’ instead`
        );
      }
    }
    const used = LANGUAGES
      .filter((language) => typeof catalogues[language][id] === "string")
      .map((language) => [language, placeholders(catalogues[language][id])]);
    if (new Set(used.map(([, found]) => found)).size > 1) {
      problems.push(
        `${id}: the three texts do not use the same placeholders: ` +
        used.map(([language, found]) => `${language} {${found || "none"}}`).join(", ")
      );
    }
  }
  return problems;
}

function javaFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? javaFiles(full) : entry.name.endsWith(".java") ? [full] : [];
  });
}

/**
 * The message ids one Java source answers with: the first argument of new ProblemException(
 * and of .t( when it is a string literal, or a String constant declared in the same file.
 * Anything else (an id passed in from elsewhere) cannot be followed here and is left to tests.
 */
export function messageIdsUsedIn(javaSource) {
  const source = javaSource.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/\/\/[^\n]*/g, " ");
  const constants = Object.fromEntries(
    [...source.matchAll(/\bString\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g)].map((match) => [match[1], match[2]])
  );
  const ids = [];
  for (const match of source.matchAll(/(?:new\s+ProblemException|\.t)\s*\(\s*(?:"([^"]+)"|([A-Z][A-Z0-9_]*)\b)/g)) {
    const id = match[1] ?? constants[match[2]];
    if (id) {
      ids.push({ id, line: source.slice(0, match.index).split("\n").length });
    }
  }
  return ids;
}

/** Ids the code answers with that the catalogue does not have. */
export function problemsOfJavaSources(javaRoot, catalogues) {
  const known = new Set(Object.keys(catalogues.en));
  const problems = [];
  for (const file of javaFiles(javaRoot)) {
    for (const { id, line } of messageIdsUsedIn(fs.readFileSync(file, "utf8"))) {
      if (!known.has(id)) {
        problems.push(
          `${path.relative(javaRoot, file).replaceAll("\\", "/")}:${line}: the code answers with "${id}", ` +
          `which is in no catalogue; add it to en.json, si.json and ta.json`
        );
      }
    }
  }
  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const catalogues = readCatalogues(path.resolve("backend/app/src/main/resources/i18n"));
  const problems = [
    ...problemsOfCatalogues(catalogues),
    ...problemsOfJavaSources(path.resolve("backend/app/src/main/java"), catalogues)
  ];
  if (problems.length > 0) {
    problems.forEach((problem) => console.error(problem));
    console.error(`\ni18n check failed: ${problems.length} problem(s).`);
    process.exit(1);
  }
  console.log(`i18n check passed: ${Object.keys(catalogues.en).length} message id(s) present in en/si/ta`);
}
