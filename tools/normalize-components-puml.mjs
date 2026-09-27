import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The Spring Modulith documenter writes the Rel lines of a diagram in an order that differs from
// run to run. Sorting them makes `make check-generated` compare content, not luck. Every diagram
// of docs/modules is sorted: components.puml and each module-<name>.puml (a module with several
// dependencies, M3 since M3-04, showed the same flapping order in its own diagram).

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..");
const modulesDir = path.join(repoRoot, "docs", "modules");

for (const name of fs.readdirSync(modulesDir).filter((file) => file.endsWith(".puml")).sort()) {
  normalise(path.join(modulesDir, name));
}

function normalise(file) {
  const original = fs.readFileSync(file, "utf8");
  const normalisedNewlines = original.replace(/\r\n/g, "\n");
  const hadFinalNewline = normalisedNewlines.endsWith("\n");

  const lines = normalisedNewlines.split("\n");

  if (hadFinalNewline) {
    lines.pop();
  }

  const relationIndexes = [];
  const relations = [];

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];

    if (/^\s*Rel(?:_[A-Za-z0-9]+)?\(/.test(line)) {
      relationIndexes.push(index);
      relations.push(line);
    }
  }

  relations.sort((left, right) => {
    if (left < right) return -1;
    if (left > right) return 1;
    return 0;
  });

  for (let index = 0; index < relationIndexes.length; index += 1) {
    lines[relationIndexes[index]] = relations[index];
  }

  const output = lines.join("\n") + (hadFinalNewline ? "\n" : "");

  // Written only when the order changed, so an untouched diagram keeps its bytes.
  if (output !== normalisedNewlines && output !== original) {
    fs.writeFileSync(file, output, "utf8");
  }
}
