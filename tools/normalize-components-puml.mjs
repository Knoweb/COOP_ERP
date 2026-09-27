// Sorts the Rel lines of the module diagrams in docs/modules (make check-generated). The
// Modulith documenter writes them in an order that differs from run to run, so a diagram looked
// changed in every pull request. components.puml had it first; module-<name>.puml has it as soon
// as a module uses more than one other module (m5inventory, 27 September 2026), so every .puml
// of the directory is normalised the same way.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..");
const modulesDir = path.join(repoRoot, "docs", "modules");

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

  fs.writeFileSync(file, output, "utf8");
}

for (const name of fs.readdirSync(modulesDir).filter((f) => f.endsWith(".puml")).sort()) {
  normalise(path.join(modulesDir, name));
}
