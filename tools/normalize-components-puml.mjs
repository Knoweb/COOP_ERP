import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..");

const file = path.join(
  repoRoot,
  "docs",
  "modules",
  "components.puml"
);

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

const output =
  lines.join("\n") +
  (hadFinalNewline ? "\n" : "");

fs.writeFileSync(file, output, "utf8");