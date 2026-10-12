import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The Spring Modulith documenter writes the Rel lines of a diagram in an order that differs from
// run to run. Sorting them makes `make check-generated` compare content, not luck. Every diagram
// of docs/modules is sorted: components.puml and each module-<name>.puml (a module with several
// dependencies, M3 since M3-04, showed the same flapping order in its own diagram).

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..");
const modulesDir = process.env.MODULE_DOCS_DIR || path.join(repoRoot, "docs", "modules");

for (const name of fs.readdirSync(modulesDir).filter((file) => file.endsWith(".puml") || file.endsWith(".adoc")).sort()) {
  normalise(path.join(modulesDir, name));
}

/** Sorts the lines at `indexes` among themselves, in place; every other line keeps its place. */
function sortInPlace(lines, indexes) {
  const sorted = indexes.map((index) => lines[index]).sort((left, right) => (left < right ? -1 : left > right ? 1 : 0));
  indexes.forEach((index, position) => {
    lines[index] = sorted[position];
  });
}

function normalise(file) {
  const original = fs.readFileSync(file, "utf8");
  const normalisedNewlines = original.replace(/\r\n/g, "\n");
  const hadFinalNewline = normalisedNewlines.endsWith("\n");

  const lines = normalisedNewlines.split("\n");

  if (hadFinalNewline) {
    lines.pop();
  }

  // Each diagram (@startuml ... @enduml) is sorted on its own, so lines never move from one
  // diagram to another; a Rel-looking line outside any diagram (prose of an .adoc) is left alone.
  let relationIndexes = null; // the Rel line positions of the diagram being read; null outside one
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (/^\s*@startuml\b/.test(line)) {
      relationIndexes = [];
    } else if (/^\s*@enduml\b/.test(line)) {
      sortInPlace(lines, relationIndexes ?? []);
      relationIndexes = null;
    } else if (relationIndexes !== null && /^\s*Rel(?:_[A-Za-z0-9]+)?\(/.test(line)) {
      relationIndexes.push(index);
    }
  }

  const output = lines.join("\n") + (hadFinalNewline ? "\n" : "");

  // Written only when the output differs from original (either relations order changed, or line endings were fixed).
  if (output !== original) {
    fs.writeFileSync(file, output, "utf8");
  }
}
