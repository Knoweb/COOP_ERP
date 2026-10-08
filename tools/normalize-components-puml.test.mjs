import { test } from "node:test";
import assert from "node:assert/strict";
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
  const originalPuml = "A\r\nRel_2(A, B)\r\nRel_1(B, C)\r\n";
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
  assert.equal(normalizedPuml, "A\nRel_1(B, C)\nRel_2(A, B)\n");

  // Verify ADOC
  const normalizedAdoc = fs.readFileSync(adocFile, "utf8");
  assert.equal(normalizedAdoc, "= Title\n\nContent\n");

  // 3. Test idempotency: running it again should not change the file timestamps/bytes
  const pumlMtime = fs.statSync(pumlFile).mtimeMs;
  const adocMtime = fs.statSync(adocFile).mtimeMs;

  execFileSync("node", [normalizerPath], execOptions);

  assert.equal(fs.statSync(pumlFile).mtimeMs, pumlMtime, "PUML file should not be modified if already normalized");
  assert.equal(fs.statSync(adocFile).mtimeMs, adocMtime, "ADOC file should not be modified if already normalized");
});
