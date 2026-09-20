// Turns the CSV of the JaCoCo report into a short Markdown table: line and branch coverage per
// module (the package under lk.coopfed.knoweb) and in total. The pipeline appends it to the run
// summary. Locally, after `make coverage`:
//
//   node tools/coverage-summary.mjs backend/app/build/reports/jacoco/test/jacocoTestReport.csv
//
// A report, not a gate. Coverage floors are set per module by its implementation guide.

import fs from "node:fs";

const file = process.argv[2];
if (!file || !fs.existsSync(file)) {
  console.error(`coverage-summary: no report at ${file ?? "(no path given)"}; run make coverage first`);
  process.exit(1);
}

const [header, ...rows] = fs.readFileSync(file, "utf8").trim().split(/\r?\n/).map((line) => line.split(","));
const column = (name) => header.indexOf(name);
const totals = new Map();

for (const row of rows) {
  const pkg = row[column("PACKAGE")];
  const module = pkg.replace(/^lk\.coopfed\.knoweb\.?/, "").split(".")[0] || "(application)";
  const sum = totals.get(module) ?? { lineHit: 0, lineMiss: 0, branchHit: 0, branchMiss: 0 };
  sum.lineHit += Number(row[column("LINE_COVERED")]);
  sum.lineMiss += Number(row[column("LINE_MISSED")]);
  sum.branchHit += Number(row[column("BRANCH_COVERED")]);
  sum.branchMiss += Number(row[column("BRANCH_MISSED")]);
  totals.set(module, sum);
}

const percent = (hit, miss) => (hit + miss === 0 ? "n/a" : `${Math.round((100 * hit) / (hit + miss))}%`);
const all = { lineHit: 0, lineMiss: 0, branchHit: 0, branchMiss: 0 };

console.log("### Backend test coverage (unit and integration tests together)\n");
console.log("| Module | Lines | Branches |");
console.log("|---|---|---|");
for (const [module, sum] of [...totals].sort(([a], [b]) => a.localeCompare(b))) {
  console.log(`| ${module} | ${percent(sum.lineHit, sum.lineMiss)} | ${percent(sum.branchHit, sum.branchMiss)} |`);
  for (const key of Object.keys(all)) {
    all[key] += sum[key];
  }
}
console.log(`| **total** | **${percent(all.lineHit, all.lineMiss)}** | **${percent(all.branchHit, all.branchMiss)}** |`);
console.log("\nA report, not a gate: floors are set per module by its implementation guide.");
