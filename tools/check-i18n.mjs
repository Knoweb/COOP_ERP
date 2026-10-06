// The message catalogue of the backend is complete (AGENTS.md: "every user-visible string is a
// message id with en, si, ta translations; a missing one fails the build"; doc 19 section 5.2).
//
//   node tools/check-i18n.mjs
//
// The catalogue is one folder per module, i18n/<module>/{en,si,ta}.json (28 September 2026: the
// three shared files conflicted in 23 of 223 recent merges each). The kernel's IcuMessages merges
// them at start the same way.
//
// What it checks:
//   - every id of a module is in all three files of that module, and no text is empty
//   - an id is in one module only, and no catalogue file lies outside a module folder
//   - every id the Java code answers with is in the catalogue: the first argument of
//     new ProblemException(...), of messages.t(...) and of messages.text(...). Before this, an
//     id that was in none of the three files passed, and the user saw "party.entity.duplicate"
//     as the message
//   - the three texts of an id name the same arguments: positional ({0}, {1} ...) and named
//     ({count}, {name} ...), the ones inside a plural or select included
//   - no text contains the ASCII apostrophe ('). In a message format the apostrophe is the
//     quote character: "Don't use {0}" prints "Dont use {0}" and the value never appears.
//     Write the typographic apostrophe (’) instead; it is also the correct character.
//   - the sign-in page (infra/keycloak/theme/coop/login): its three messages_*.properties hold
//     the same ids, each with a text and no ASCII apostrophe, and every msg("...") of login.ftl
//     is among them (wave 2, DEPLOY-16)
//
// tools/checks.test.mjs proves each of these with a catalogue that breaks it.
// The web client's own catalogue is checked by web/src/shell/i18n/messages.test.ts.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const LANGUAGES = ["en", "si", "ta"];

/**
 * The catalogue of every module: i18n/<module>/{en,si,ta}.json, one folder per module, so that
 * two branches adding ids to two modules never edit the same file. A language file missing from
 * a module folder reads as empty, so each of its ids is reported as missing in that language.
 */
export function readModuleCatalogues(i18nDir) {
  const modules = {};
  for (const entry of fs.readdirSync(i18nDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) {
      continue;
    }
    const dir = path.join(i18nDir, entry.name);
    modules[entry.name] = Object.fromEntries(LANGUAGES.map((language) => {
      const file = path.join(dir, `${language}.json`);
      if (!fs.existsSync(file)) {
        return [language, {}];
      }
      try {
        return [language, JSON.parse(fs.readFileSync(file, "utf8"))];
      } catch (error) {
        throw new Error(`${file} is not valid JSON: ${error.message}`);
      }
    }));
  }
  return modules;
}

/** Every module's problems, named by module, plus an id that two modules both have. */
export function problemsOfModules(modules, strayFiles = []) {
  const problems = strayFiles.map(
    (file) => `${file}: a catalogue file outside a module folder; move its ids to i18n/<module>/`
  );
  const ownerOf = new Map();
  for (const [module, catalogues] of Object.entries(modules).sort()) {
    problems.push(...problemsOfCatalogues(catalogues, `${module}/`));
    for (const id of new Set(LANGUAGES.flatMap((language) => Object.keys(catalogues[language])))) {
      if (ownerOf.has(id)) {
        problems.push(`${module}: ${id} is also in ${ownerOf.get(id)}; an id belongs to one module`);
      } else {
        ownerOf.set(id, module);
      }
    }
  }
  return problems;
}

/** The modules merged into one catalogue per language, for the check of the Java sources. */
export function mergedCatalogues(modules) {
  return Object.fromEntries(LANGUAGES.map((language) => [
    language,
    Object.assign({}, ...Object.values(modules).map((catalogues) => catalogues[language]))
  ]));
}

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

/**
 * The arguments an ICU message names, positional ({0}) and named ({count}), including the ones
 * inside the branches of a plural or select ({count, plural, one {# item of {name}} ...}); the
 * `#` of a plural branch is the argument itself, not another one. Sorted and unique, so the
 * three texts of an id can be compared as sets.
 */
export function argumentNames(text) {
  const names = new Set();
  walk(String(text), 0, names);
  return [...names].sort();
}

/** Reads one message (or one branch of a plural/select) from `from`; returns the index after it. */
function walk(text, from, names) {
  let i = from;
  while (i < text.length) {
    const ch = text[i];
    if (ch === "}") {
      return i + 1;
    }
    if (ch !== "{") {
      i++;
      continue;
    }
    // An argument: {name} or {name, type} or {name, type, style or branches}.
    const close = text.indexOf("}", i);
    const comma = text.indexOf(",", i);
    const nameEnd = comma !== -1 && (close === -1 || comma < close) ? comma : close;
    if (nameEnd === -1) {
      return text.length;
    }
    const name = text.slice(i + 1, nameEnd).trim();
    if (name !== "") {
      names.add(name);
    }
    if (nameEnd === close) {
      i = close + 1;
      continue;
    }
    const typeEnd = [text.indexOf(",", nameEnd + 1), text.indexOf("}", nameEnd + 1)]
      .filter((at) => at !== -1)
      .sort((a, b) => a - b)[0];
    const type = text.slice(nameEnd + 1, typeEnd).trim();
    if (["plural", "select", "selectordinal"].includes(type)) {
      // Branches: key {message} key {message} ... up to the closing brace of the argument.
      let j = typeEnd + 1;
      while (j < text.length && text[j] !== "}") {
        if (text[j] === "{") {
          j = walk(text, j + 1, names);
        } else {
          j++;
        }
      }
      i = j + 1;
    } else {
      // A number, date or custom style: skip to the argument's closing brace.
      let depth = 0;
      let j = i;
      for (; j < text.length; j++) {
        if (text[j] === "{") depth++;
        if (text[j] === "}" && --depth === 0) break;
      }
      i = j + 1;
    }
  }
  return i;
}

function placeholders(text) {
  return argumentNames(text).join(" ");
}

/** Problems inside the three catalogue files themselves. */
/**
 * A Sinhala or Tamil text with no letter of any script and at least one "?" is a text a
 * codepage destroyed, not a text. Digits, punctuation and Latin words (VAT, PIN) alone are
 * fine, since some texts are only those.
 */
function lostItsScript(text) {
  return text.includes("?") && !/\p{L}/u.test(text.replace(/[A-Za-z]/g, ""));
}

export function problemsOfCatalogues(catalogues, prefix = "") {
  const problems = [];
  const ids = new Set(LANGUAGES.flatMap((language) => Object.keys(catalogues[language])));

  for (const id of [...ids].sort()) {
    for (const language of LANGUAGES) {
      const text = catalogues[language][id];
      if (text === undefined) {
        problems.push(`${prefix}${language}.json: no text for ${id}`);
      } else if (typeof text !== "string" || text.trim() === "") {
        problems.push(`${prefix}${language}.json: the text of ${id} is empty`);
      } else if (text.includes("'")) {
        problems.push(
          `${prefix}${language}.json: the text of ${id} contains the ASCII apostrophe ('), which a message format ` +
          `reads as a quote and drops, together with any {0} after it; write ’ instead`
        );
      } else if (language !== "en" && lostItsScript(text)) {
        // A Sinhala or Tamil text saved through a codepage that cannot hold it becomes a row
        // of "?" and passes every other check here. #72 shipped eight of them.
        problems.push(
          `${prefix}${language}.json: the text of ${id} is "${text}": it was saved through a codepage that lost the ` +
          `script; save the file as UTF-8 and write the text again`
        );
      }
    }
    const used = LANGUAGES
      .filter((language) => typeof catalogues[language][id] === "string")
      .map((language) => [language, placeholders(catalogues[language][id])]);
    if (new Set(used.map(([, found]) => found)).size > 1) {
      problems.push(
        `${prefix}${id}: the three texts do not name the same arguments: ` +
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
 * The message ids one Java source answers with: the first argument of new ProblemException(,
 * of .t( and of .text( when it is a string literal, or a String constant declared in the same file.
 * Anything else (an id passed in from elsewhere) cannot be followed here and is left to tests.
 */
export function messageIdsUsedIn(javaSource) {
  const source = javaSource.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/\/\/[^\n]*/g, " ");
  const constants = Object.fromEntries(
    [...source.matchAll(/\bString\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g)].map((match) => [match[1], match[2]])
  );
  const ids = [];
  for (const match of source.matchAll(/(?:new\s+ProblemException|\.t|\.text)\s*\(\s*(?:"([^"]+)"|([A-Z][A-Z0-9_]*)\b)/g)) {
    const id = match[1] ?? constants[match[2]];
    if (id) {
      ids.push({ id, line: source.slice(0, match.index).split("\n").length });
    }
  }
  return ids;
}

/**
 * The sign-in page's catalogue (wave 2, DEPLOY-16): the Keycloak theme's
 * messages/messages_{en,si,ta}.properties, KEY=text per line, # for a comment. Keycloak ships no
 * Sinhala bundle, so the theme must hold every id its page shows in all three languages.
 */
export function readThemeMessages(themeLoginDir) {
  return Object.fromEntries(LANGUAGES.map((language) => {
    const file = path.join(themeLoginDir, "messages", `messages_${language}.properties`);
    const entries = {};
    if (fs.existsSync(file)) {
      for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
        const trimmed = line.trim();
        if (trimmed === "" || trimmed.startsWith("#") || trimmed.startsWith("!")) {
          continue;
        }
        const at = trimmed.search(/[=:]/);
        const key = (at < 0 ? trimmed : trimmed.slice(0, at)).trim();
        entries[key] = at < 0 ? "" : trimmed.slice(at + 1).trim();
      }
    }
    return [language, entries];
  }));
}

/** The ids a FreeMarker template asks for: msg("id") and msg("id", ...). */
export function themeIdsUsedIn(template) {
  const source = template.replace(/<#--[\s\S]*?-->/g, " ");
  return [...new Set([...source.matchAll(/\bmsg\(\s*"([^"]+)"/g)].map((match) => match[1]))].sort();
}

/**
 * The theme's problems: an id missing from a language or with no text, an ASCII apostrophe
 * (Keycloak formats these texts with MessageFormat, where it is the quote character), and an id
 * the page's template asks for that the theme does not hold.
 */
export function problemsOfTheme(messages, templateIds = [], where = "theme") {
  const problems = [];
  const ids = new Set(LANGUAGES.flatMap((language) => Object.keys(messages[language])));
  for (const id of [...ids].sort()) {
    for (const language of LANGUAGES) {
      if (!(id in messages[language])) {
        problems.push(`${where}/messages_${language}.properties: ${id} is missing (it is in another language)`);
      } else if (messages[language][id] === "") {
        problems.push(`${where}/messages_${language}.properties: no text for ${id}`);
      } else if (messages[language][id].includes("'")) {
        problems.push(`${where}/messages_${language}.properties: ${id} contains an ASCII apostrophe; write ’`);
      }
    }
  }
  for (const id of templateIds) {
    if (!ids.has(id)) {
      problems.push(`${where}/login.ftl: msg("${id}") is in no messages_*.properties of the theme; add it to all three`);
    }
  }
  return problems;
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
          `which is in no catalogue; add it to i18n/<module>/en.json, si.json and ta.json`
        );
      }
    }
  }
  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const i18nDir = path.resolve("backend/app/src/main/resources/i18n");
  const modules = readModuleCatalogues(i18nDir);
  const strayFiles = fs.readdirSync(i18nDir).filter((name) => name.endsWith(".json"));
  const catalogues = mergedCatalogues(modules);
  const themeDir = path.resolve("infra/keycloak/theme/coop/login");
  const problems = [
    ...problemsOfModules(modules, strayFiles),
    ...problemsOfJavaSources(path.resolve("backend/app/src/main/java"), catalogues),
    ...problemsOfTheme(
      readThemeMessages(themeDir),
      themeIdsUsedIn(fs.readFileSync(path.join(themeDir, "login.ftl"), "utf8")),
      "infra/keycloak/theme/coop/login"
    )
  ];
  if (problems.length > 0) {
    problems.forEach((problem) => console.error(problem));
    console.error(`\ni18n check failed: ${problems.length} problem(s).`);
    process.exit(1);
  }
  console.log(`i18n check passed: ${Object.keys(catalogues.en).length} message id(s) present in en/si/ta, in ${Object.keys(modules).length} module folder(s)`);
}
