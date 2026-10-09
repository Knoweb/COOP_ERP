import { test } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import os from "node:os";

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..");
const modulesDir = path.join(repoRoot, "docs", "modules");
const normalizerPath = path.join(toolsDir, "normalize-components-puml.mjs");

test("normalizer removes CRLF and sorts relations, writing only when needed", (t) => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "normalize-components-puml-"));
  const pumlFile = path.join(tempDir, "test-normalizer-dummy.puml");
  const adocFile = path.join(tempDir, "test-normalizer-dummy.adoc");

  t.after(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  // 1. Create a PUML file with CRLF and out-of-order relations
  const originalPuml = "@startuml\r\nA\r\nRel_2(A, B)\r\nRel_1(B, C)\r\n@enduml\r\n";
  fs.writeFileSync(pumlFile, originalPuml, "utf8");

  // 2. Create an ADOC file with CRLF
  const originalAdoc = "= Title\r\n\r\nContent\r\n";
  fs.writeFileSync(adocFile, originalAdoc, "utf8");

  const execOptions = {
    encoding: "utf8",
    env: { ...process.env, MODULE_DOCS_DIR: tempDir }
  };

  // Run normalizer
  execFileSync("node", [normalizerPath], execOptions);

  // Verify PUML
  const normalizedPuml = fs.readFileSync(pumlFile, "utf8");
  assert.equal(normalizedPuml, "@startuml\nA\nRel_1(B, C)\nRel_2(A, B)\n@enduml\n");

  // Verify ADOC
  const normalizedAdoc = fs.readFileSync(adocFile, "utf8");
  assert.equal(normalizedAdoc, "= Title\n\nContent\n");

  // 3. Idempotency: a second run leaves the bytes as they are. Compared by content hash, not by
  // modification time, which a rewrite within the same clock tick would not move.
  const hashOf = (file) => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
  const pumlHash = hashOf(pumlFile);
  const adocHash = hashOf(adocFile);

  execFileSync("node", [normalizerPath], execOptions);

  assert.equal(hashOf(pumlFile), pumlHash, "PUML file should not change once normalized");
  assert.equal(hashOf(adocFile), adocHash, "ADOC file should not change once normalized");
});

test("each diagram is sorted on its own, and a Rel line outside a diagram is left where it is", (t) => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "normalize-components-puml-"));
  t.after(() => fs.rmSync(tempDir, { recursive: true, force: true }));
  const file = path.join(tempDir, "two-diagrams.adoc");
  fs.writeFileSync(
    file,
    "Rel(z, prose)\n@startuml\nRel(b, 1)\nRel(a, 1)\n@enduml\ntext\n@startuml\nRel(d, 2)\nRel(c, 2)\n@enduml\n",
    "utf8"
  );

  execFileSync("node", [normalizerPath], { encoding: "utf8", env: { ...process.env, MODULE_DOCS_DIR: tempDir } });

  assert.equal(
    fs.readFileSync(file, "utf8"),
    "Rel(z, prose)\n@startuml\nRel(a, 1)\nRel(b, 1)\n@enduml\ntext\n@startuml\nRel(c, 2)\nRel(d, 2)\n@enduml\n"
  );
});

test("the committed diagrams of docs/modules are already normalised", (t) => {
  // A copy, so the run never edits the working tree: what the documenter committed must already
  // be in the normaliser's order, or `make check-generated` flaps.
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "normalize-components-puml-"));
  t.after(() => fs.rmSync(tempDir, { recursive: true, force: true }));
  const names = fs.readdirSync(modulesDir).filter((name) => name.endsWith(".puml") || name.endsWith(".adoc"));
  for (const name of names) {
    fs.writeFileSync(path.join(tempDir, name), fs.readFileSync(path.join(modulesDir, name), "utf8").replace(/\r\n/g, "\n"), "utf8");
  }

  execFileSync("node", [normalizerPath], { encoding: "utf8", env: { ...process.env, MODULE_DOCS_DIR: tempDir } });

  for (const name of names) {
    const committed = fs.readFileSync(path.join(modulesDir, name), "utf8").replace(/\r\n/g, "\n");
    assert.equal(fs.readFileSync(path.join(tempDir, name), "utf8"), committed, `${name} is not in the normaliser's order`);
  }
});
