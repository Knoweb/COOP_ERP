import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The documenter writes the Rel lines of its PlantUML diagrams in an order that differs from run
// to run. Sorting them in place makes `make check-generated` compare content, not luck. Every
// diagram of docs/modules is sorted: components.puml and each module-<name>.puml, whose Rel lines
// move the same way once a module depends on more than one other.

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..");
const dir = path.join(repoRoot, "docs", "modules");

for (const name of fs.readdirSync(dir).filter((entry) => entry.endsWith(".puml")).sort()) {
  normalise(path.join(dir, name));
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

  if (output !== normalisedNewlines) {
    fs.writeFileSync(file, output, "utf8");
  }
}
