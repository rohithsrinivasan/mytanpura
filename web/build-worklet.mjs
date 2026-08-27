// Generates web/tanpura-worklet.js from src/engine.js + src/worklet-tail.js.
//
// An AudioWorklet is loaded as a single script and `import` inside worklet scope
// is not reliably supported across browsers, so the engine has to be inlined.
// Rather than duplicate the DSP, this concatenates the one source of truth and
// strips its `export` keywords.
//
// Zero dependencies, so it runs anywhere Node runs:
//
//   node web/build-worklet.mjs
//
// The output is committed so that Vercel needs no build step at all.

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const enginePath = join(here, 'src', 'engine.js');
const tailPath = join(here, 'src', 'worklet-tail.js');
const outPath = join(here, 'tanpura-worklet.js');

const engine = readFileSync(enginePath, 'utf8');
const tail = readFileSync(tailPath, 'utf8');

// The engine is written so that this is the only transformation needed: every
// export is inline on its declaration, there are no imports, and there is no
// `export default` or re-export.
for (const forbidden of [/^\s*import\s/m, /^export\s+default\s/m, /^export\s*\{/m]) {
  if (forbidden.test(engine)) {
    console.error(
      `src/engine.js contains "${forbidden}", which the worklet generator cannot strip.\n` +
        'Keep every export inline on its declaration and add no imports.',
    );
    process.exit(1);
  }
}

const stripped = engine.replace(/^export\s+/gm, '');
if (/^export\s/m.test(stripped)) {
  console.error('An export survived stripping; refusing to write a broken worklet.');
  process.exit(1);
}

const banner = `// GENERATED FILE - do not edit.
// Built from src/engine.js + src/worklet-tail.js by build-worklet.mjs.
// Run "node web/build-worklet.mjs" after changing either of those.
`;

writeFileSync(outPath, `${banner}\n${stripped}\n${tail}`, 'utf8');
console.log(`wrote ${outPath} (${(readFileSync(outPath).length / 1024).toFixed(1)} kB)`);
