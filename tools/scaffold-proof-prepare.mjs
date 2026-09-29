// Frees the name m9integration for the scaffolder proof, in the proof's throwaway working tree.
//
// `make test-scaffold` proves that `make new-module` still produces a module that builds and
// passes. It copies hello into m9integration, the name whose schema ("integration") the database
// roles, grants and SchemaRulesIntegrationTest already know. Until 29 September 2026 that module
// was unbuilt; now it is built, so this script first removes the built M9 from the throwaway tree
// (its own folders and files, and its two lines in the web registry and message index) so that
// the scaffolder finds the name free again, exactly as before M9 existed.
//
// Only `make test-scaffold` runs it, on a clean working tree that the same target resets
// (`git reset --hard && git clean -fd`) when it is done, so nothing here is ever committed.
// Everything else that names M9 (FlywayConfig, ArchitectureTests, the CI shards, permissions and
// demo grants in the seeds) names the module the scaffolder re-creates, or a code/table that is
// simply unused while the copy is in place.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const removed = [
  "backend/app/src/main/java/lk/coopfed/knoweb/m9integration",
  "backend/app/src/test/java/lk/coopfed/knoweb/m9integration",
  "backend/app/src/main/resources/db/migration/m9integration",
  "backend/app/src/main/resources/seed/m9integration",
  "backend/app/src/main/resources/i18n/m9integration",
  "backend/app/src/main/resources/openapi/m9integration.yaml",
  "web/src/modules/m9integration",
  "web/src/generated/m9integration.ts",
  "docs/modules/module-m9integration.adoc",
  "docs/modules/module-m9integration.puml"
];

for (const relative of removed) {
  fs.rmSync(path.join(root, relative), { recursive: true, force: true });
}

// The web registry and the message index name the built module; the scaffolder adds its own
// lines for the copy, so the built module's lines go first.
function dropLines(relative, lines) {
  const file = path.join(root, relative);
  const raw = fs.readFileSync(file, "utf8");
  const eol = raw.includes("\r\n") ? "\r\n" : "\n";
  const kept = raw.split(/\r?\n/).filter((line) => !lines.some((drop) => line.trim() === drop));
  if (kept.length !== raw.split(/\r?\n/).length - lines.length) {
    throw new Error(`${relative}: expected to drop ${lines.length} line(s) naming the built M9`);
  }
  fs.writeFileSync(file, kept.join(eol));
}

dropLines("web/src/modules/registry.ts", [
  `import { integrationModule } from "./m9integration/module";`,
  `integrationModule,`
]);
dropLines("web/src/shell/i18n/messages.ts", [
  `import integrationMessages from "../../modules/m9integration/integration.messages.json" with { type: "json" };`,
  `integrationMessages,`
]);

console.log("scaffolder proof: the built m9integration is removed from the throwaway working tree");
