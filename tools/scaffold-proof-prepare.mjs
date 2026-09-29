// Registers the scaffolder proof's throwaway module, m0proof (schema "proof"), in the places a
// real module is registered by hand: the scaffolder's own MODULES, FlywayConfig,
// ArchitectureTests (the module list and SCHEMA_OWNERSHIP) and the integration test shards.
//
// Only `make test-scaffold` runs it, on a clean working tree that the same target resets
// (`git reset --hard && git clean -fd`) when it is done, so nothing here is ever committed.
// Until 29 September 2026 the proof scaffolded m9integration, which was then still unbuilt; with
// M1 to M9 built, no registered module is free to copy into, and m10procurement is reserved
// with no schema on purpose. A throwaway name keeps the proof exercising the scaffolder exactly
// as a developer would use it, without registering a schema anywhere real.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function edit(relative, anchor, insert, where = "after") {
  const file = path.join(root, relative);
  const raw = fs.readFileSync(file, "utf8");
  const crlf = raw.includes("\r\n");
  const text = raw.replace(/\r\n/g, "\n");
  if (!text.includes(anchor)) {
    throw new Error(`${relative}: anchor not found: ${anchor}`);
  }
  let out = where === "after" ? text.replace(anchor, anchor + insert) : text.replace(anchor, insert + anchor);
  if (crlf) {
    out = out.replace(/\n/g, "\r\n");
  }
  fs.writeFileSync(file, out);
}

edit(
  "tools/new-module.mjs",
  `  m9integration: { schemas: ["integration"], displayName: "M9 Integration" }`,
  `,\n  m0proof: { schemas: ["proof"], displayName: "Scaffolder proof (throwaway)" }`
);

edit(
  "backend/app/src/main/java/lk/coopfed/knoweb/config/FlywayConfig.java",
  `new ModuleInfo("classpath:db/migration/m9integration", new String[] {"integration"}, "integration"));\n`,
  `\n        modules.put("m0proof", new ModuleInfo("classpath:db/migration/m0proof", new String[] {"proof"}, "proof"));\n`
);

edit(
  "backend/app/src/test/java/lk/coopfed/knoweb/ArchitectureTests.java",
  `        "..m10procurement..",\n`,
  `        "..m0proof..",\n`
);

edit(
  "backend/app/src/test/java/lk/coopfed/knoweb/ArchitectureTests.java",
  `            Map.entry("m9integration", Set.of("integration")),\n`,
  `            Map.entry("m0proof", Set.of("proof")),\n`
);

edit(
  "backend/app/src/test/java/lk/coopfed/knoweb/IntegrationTestShardCoverageTest.java",
  `"lk.coopfed.knoweb.m9integration.",`,
  `\n                            "lk.coopfed.knoweb.m0proof.",`
);

console.log("scaffolder proof: m0proof registered in the throwaway working tree");
