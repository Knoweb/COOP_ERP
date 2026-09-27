#!/usr/bin/env node
// Builds the full progress view from docs/progress/: Done entries, the Next page and Deviations,
// each folder in file-name (date) order. The view is a build output, never committed:
//   node tools/progress-index.mjs            writes build/PROGRESS.md
//   node tools/progress-index.mjs --stdout   prints it
// It also fails when an entry file is badly named or does not start with a "- " bullet, so a
// typo is found by `make progress` and the pipeline, not by the next reader.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const NAME = /^(\d{4}-\d{2}-\d{2}|0000-legacy-\d{2})-[a-z0-9][a-z0-9-]*\.md$/;

export function buildIndex(progressDir) {
  const problems = [];
  const read = (sub) => {
    const dir = path.join(progressDir, sub);
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir).filter((f) => f.endsWith('.md')).sort().map((f) => {
      const text = fs.readFileSync(path.join(dir, f), 'utf8').replace(/\s+$/, '');
      if (!NAME.test(f)) problems.push(`${sub}/${f}: name must be <yyyy-mm-dd>-<slug>.md`);
      if (!text.startsWith('- ')) problems.push(`${sub}/${f}: must start with a "- " bullet`);
      return text;
    });
  };
  const done = read('done');
  const deviations = read('deviations');
  const nextFile = path.join(progressDir, 'NEXT.md');
  const next = fs.existsSync(nextFile) ? fs.readFileSync(nextFile, 'utf8').replace(/\s+$/, '') : '';
  const text = [
    '# Progress (generated from docs/progress/, do not edit)',
    '', '## Done', '', ...done,
    '', '## Next', '', next,
    '', '## Deviations', '', ...deviations, '',
  ].join('\n');
  return { text, problems };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const { text, problems } = buildIndex(path.join(root, 'docs', 'progress'));
  if (problems.length) {
    console.error(problems.join('\n'));
    process.exit(1);
  }
  if (process.argv.includes('--stdout')) process.stdout.write(text);
  else {
    const out = path.join(root, 'build', 'PROGRESS.md');
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, text);
    console.log(`progress: ${out}`);
  }
}
