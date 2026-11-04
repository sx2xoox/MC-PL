// Compile + link check of the MCPL pack's core shader overrides against the vanilla 26.3 ones, with WebGL2 (ANGLE,
// SwiftShader) in Playwright's Chromium: no GLSL 3.30 compiler is at hand in the sandbox, so every shader is turned
// into GLSL ES 3.00 (precision, no varying locations, no separate-shader extension, a few implicit int → float
// conversions ES refuses) and compiled in the variants the game builds (RenderPipelines: cutout, glint, OIT passes,
// ...), once per combination of the MCPL feature macros (include/mcpl_preset.glsl). It checks syntax, types and the
// vertex → fragment interface of our code; it is not Minecraft's own compiler, so a pass here is no proof that the
// 26.3 client accepts it. A variant passes when it compiles and links, or (if vanilla itself does not in ES) adds no
// error vanilla does not have.
//
//   node tools/shaders/check.mjs <vanilla shaders dir>
//
// <vanilla shaders dir> = assets/minecraft/shaders of the 26.3 client jar (unzip client.jar 'assets/minecraft/shaders/*').
// Needs the playwright package (global install is fine) and its Chromium.
import { createRequire } from 'node:module';
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
let playwright;
try {
  playwright = require('playwright');
} catch {
  playwright = createRequire(join(execSync('npm root -g').toString().trim(), 'x'))('playwright');
}

const VANILLA = resolve(process.argv[2] ?? '');
const PACK = resolve(here, '../../src/main/resourcepack-src/assets/minecraft/shaders');
if (!existsSync(join(VANILLA, 'core/terrain.fsh'))) {
  console.error('usage: node tools/shaders/check.mjs <26.3 client assets/minecraft/shaders>');
  process.exit(2);
}

// the MCPL feature macros (core/pack/ShaderPreset.Feature): every combination
const FEATURES = ['MCPL_BUBBLE', 'MCPL_COLOR_GRADE', 'MCPL_WARM_LIGHT'];
const COMBOS = [];
for (let mask = 0; mask < 1 << FEATURES.length; mask++) {
  COMBOS.push(FEATURES.filter((_, i) => mask & (1 << i)));
}

const OIT = { OIT: '', OIT_WAVELET_RANK: '3', OIT_COEFF_COUNT: '8', OIT_COEFF_ATTACHMENT_COUNT: '2' };
const OIT_PASSES = [
  { ...OIT, OIT_ALPHA_ONLY: '', OIT_DEPTH_BOUNDS: '' },
  { ...OIT, OIT_ALPHA_ONLY: '', OIT_TRANSMITTANCE: '' },
  { ...OIT, OIT_ACCUMULATE: '' },
];
const CUT = { ALPHA_CUTOUT: '0.1' };
// the define sets RenderPipelines (26.3) builds each core shader with, plus the OIT passes of the translucent ones
const VARIANTS = {
  terrain: [{}, CUT, { ...CUT, MULTIDRAW_TERRAIN: '' }, ...OIT_PASSES.map(p => ({ ...p, ...CUT }))],
  block: [{}, CUT, ...OIT_PASSES.map(p => ({ ...p, ...CUT }))],
  entity: [{}, CUT, { ...CUT, PER_FACE_LIGHTING: '' }, { ...CUT, PER_FACE_LIGHTING: '', DISSOLVE: '' },
    { ...CUT, NO_OVERLAY: '', PER_FACE_LIGHTING: '' }, { NO_OVERLAY: '' },
    { ...CUT, EMISSIVE: '', NO_OVERLAY: '', NO_CARDINAL_LIGHTING: '', APPLY_TEXTURE_MATRIX: '' },
    { ...CUT, PER_FACE_LIGHTING: '', EMISSIVE: '' }, { GLINT: '' }, { GLINT: '', GLINT_SPECIAL: '' },
    { ...CUT, NO_OVERLAY: '', PER_FACE_LIGHTING: '', GLINT: '' },
    ...OIT_PASSES.map(p => ({ ...p, ...CUT, PER_FACE_LIGHTING: '' })),
    ...OIT_PASSES.map(p => ({ ...p, ...CUT, EMISSIVE: '', NO_OVERLAY: '', OIT_ADDITIVE: '' }))],
  item: [{}, CUT, { GLINT: '' }, { GLINT: '', GLINT_SPECIAL: '' }, { ...CUT, GLINT: '' },
    ...OIT_PASSES.map(p => ({ ...p, ...CUT }))],
  particle: [{}, ...OIT_PASSES],
  sky: [{}],
  clouds: [{}, ...OIT_PASSES],
  lightmap: [{}],
};

function source(rel, roots, files) {
  if (files[rel] !== undefined) return files[rel];
  for (const root of roots) if (existsSync(join(root, rel))) return readFileSync(join(root, rel), 'utf8');
  throw new Error('missing ' + rel);
}
function expand(text, roots, files, seen) {
  return text.split('\n').map(line => {
    const m = line.match(/^\s*#include\s+<minecraft:([^>]+)>/);
    if (!m) return line;
    const rel = 'include/' + m[1];
    if (seen.has(rel)) return '';
    seen.add(rel);
    return expand(source(rel, roots, files), roots, files, seen);
  }).join('\n');
}
function toEs(text, stage, defines, roots, files) {
  const lines = text.split('\n').filter(l => !l.startsWith('#version') && !l.startsWith('#extension'));
  let body = expand(lines.join('\n'), roots, files, new Set());
  body = body.replace(/layout\(location\s*=\s*\d+\)\s*(in|out)\b/g, (all, dir) =>
    (stage === 'vert' && dir === 'out') || (stage === 'frag' && dir === 'in') ? dir : all);
  // implicit int → float conversions desktop GLSL allows and ES does not (vanilla code)
  body = body.replace('(uv / 256.0)', '(vec2(uv) / 256.0)');
  body = body.replace(/clamp\(([^,()]+), 0, /g, 'clamp($1, 0.0, ');
  body = body.replace(/linear_fog_value\(vertexDistance, 0, /g, 'linear_fog_value(vertexDistance, 0.0, ');
  body = body.replace(/averageAbsorbance == 0\)/g, 'averageAbsorbance == 0.0)');
  body = body.replace(/(?<!float)\(OIT_NUMBER_OF_DEPTH_BINS - 1\)/g, 'float(OIT_NUMBER_OF_DEPTH_BINS - 1)');
  body = body.replace(/ \/ OIT_NUMBER_OF_DEPTH_BINS;/g, ' / float(OIT_NUMBER_OF_DEPTH_BINS);');
  body = body.replace(/coefficients\[i\] = 0;/g, 'coefficients[i] = 0.0;');
  body = body.replace('isRightHalf * waveletWidth + waveletSign * ', 'float(isRightHalf) * waveletWidth + float(waveletSign) * ');
  // ES wants constant indexes into fragment output arrays (OIT transmittance pass, OIT_COEFF_ATTACHMENT_COUNT 2)
  body = body.replace('coeff[attachmentIndex][i] = coefficients[attachmentIndex * 4 + i];',
    'if (attachmentIndex == 0) { coeff[0][i] = coefficients[i]; } else { coeff[1][i] = coefficients[4 + i]; }');
  body = body.replace(/, 0, 4\.0\)/g, ', 0.0, 4.0)');
  body = body.replace(/1\.0f \/ TextureSize/g, '1.0f / vec2(TextureSize)');
  body = body.replace(/\(1 - depth\)/g, '(1.0 - depth)');
  body = body.replace(/exp2\(-power\)/g, 'exp2(float(-power))').replace(/exp2\(power \* 0\.5\)/g, 'exp2(float(power) * 0.5)');
  body = body.replace(/\(isRightHalfB \* waveletWidth \+ waveletSignB \* /g, '(float(isRightHalfB) * waveletWidth + float(waveletSignB) * ');
  body = body.replace(/differenceCoefficientB \* waveletSignB/g, 'differenceCoefficientB * float(waveletSignB)');
  body = body.replace(/differenceCoefficientA \* waveletSignA/g, 'differenceCoefficientA * float(waveletSignA)');
  body = body.replace(/shouldSampleA \? averageAbsorbance : 0;/g, 'shouldSampleA ? averageAbsorbance : 0.0;');
  body = body.replace(/depthMeasuredInBins >= OIT_NUMBER_OF_DEPTH_BINS/g, 'depthMeasuredInBins >= float(OIT_NUMBER_OF_DEPTH_BINS)');
  body = body.replace(/floor\((texCoord\.[xy]) \* 16\) \/ 15;/g, 'floor($1 * 16.0) / 15.0;');
  body = body.replace(/Position \+ \(ChunkPosition - CameraBlockPos\)/g, 'Position + vec3(ChunkPosition - CameraBlockPos)');
  const defs = Object.entries(defines).map(([k, v]) => `#define ${k} ${v}`).join('\n');
  return `#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n${defs}\n${body}`;
}

// core shaders whose vanilla vertex shader cannot be ES (clouds read a buffer texture; lightmap is drawn by
// screenquad.vsh with gl_VertexIndex): a stub that feeds the fragment inputs instead (their vertex shader is vanilla's,
// only the fragment shader is ours)
const STUB_VERTEX = new Set(['clouds', 'lightmap']);
function stubVertex(fragmentEs) {
  const outs = [...fragmentEs.matchAll(/^\s*in\s+(\w+)\s+(\w+)\s*;/gm)].map(m => ({ type: m[1], name: m[2] }));
  return '#version 300 es\nprecision highp float;\n' + outs.map(o => `out ${o.type} ${o.name};\n`).join('')
    + 'void main() {\n' + outs.map(o => `  ${o.name} = ${o.type}(0);\n`).join('') + '  gl_Position = vec4(0.0);\n}\n';
}

const packCore = readdirSync(join(PACK, 'core')).filter(f => f.endsWith('.fsh')).map(f => f.replace(/\.fsh$/, ''));
const jobs = [];
for (const name of packCore) {
  const variants = VARIANTS[name];
  if (!variants) throw new Error('no variants listed for core/' + name);
  const vshRel = 'core/' + name + '.vsh';
  for (const defines of variants) {
    const pair = (combo, roots, files) => {
      const fs = toEs(source('core/' + name + '.fsh', roots, files), 'frag', defines, roots, files);
      const vs = STUB_VERTEX.has(name) ? stubVertex(fs) : toEs(source(vshRel, roots, files), 'vert', defines, roots, files);
      return { name, defines, combo, vs, fs };
    };
    jobs.push(pair('vanilla', [VANILLA], {}));
    for (const combo of COMBOS) {
      const files = { 'include/mcpl_preset.glsl': '#ifndef MCPL_PRESET_GLSL\n#define MCPL_PRESET_GLSL\n'
          + combo.map(m => `#define ${m}\n`).join('') + '#endif\n' };
      jobs.push(pair(combo.join('+') || 'none', [PACK, VANILLA], files));
    }
  }
}

const browser = await playwright.chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader'] });
const page = await browser.newPage();
await page.setContent('<canvas id=c></canvas>');
const results = await page.evaluate(jobs => {
  const gl = document.getElementById('c').getContext('webgl2');
  return jobs.map(j => {
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s; };
    const vs = sh(gl.VERTEX_SHADER, j.vs), fs = sh(gl.FRAGMENT_SHADER, j.fs);
    const p = gl.createProgram(); gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p);
    const log = (ok, text) => ok ? '' : (text || 'failed');
    return {
      vs: log(gl.getShaderParameter(vs, gl.COMPILE_STATUS), gl.getShaderInfoLog(vs)),
      fs: log(gl.getShaderParameter(fs, gl.COMPILE_STATUS), gl.getShaderInfoLog(fs)),
      link: log(gl.getProgramParameter(p, gl.LINK_STATUS), gl.getProgramInfoLog(p)),
    };
  });
}, jobs.map(j => ({ vs: j.vs, fs: j.fs })));
await browser.close();

const errors = log => new Set(log.split('\n').map(l => l.replace(/^(ERROR|WARNING): 0:\d+: /, '').trim()).filter(Boolean));
let failed = 0, clean = 0, vanillaBroken = 0, base = null;
for (let i = 0; i < jobs.length; i++) {
  const job = jobs[i], result = results[i];
  if (job.combo === 'vanilla') {
    base = result;
    if (result.vs || result.fs || result.link) {
      vanillaBroken++;
      if (process.env.VERBOSE) console.log(`vanilla core/${job.name} ${JSON.stringify(job.defines)}: ${[result.vs, result.fs, result.link].join(" | ").replace(/\n/g, " ")}`);
    }
    continue;
  }
  const label = `core/${job.name} ${JSON.stringify(job.defines)} [${job.combo}]`;
  if (!result.vs && !result.fs && !result.link) {
    clean++;
    continue;
  }
  const extra = [];
  for (const stage of ['vs', 'fs', 'link']) {
    const known = errors(base[stage]);
    for (const e of errors(result[stage])) if (!known.has(e)) extra.push(stage + ': ' + e);
  }
  if (extra.length) {
    failed++;
    console.log('FAIL ' + label + '\n  ' + extra.join('\n  '));
  }
}
const total = jobs.filter(j => j.combo !== 'vanilla').length;
console.log(`${total} MCPL variants (${packCore.join(', ')} x ${COMBOS.length} feature combinations): ${clean} compile and link,`
  + ` ${total - clean - failed} only with vanilla's own ES errors, ${failed} with new errors;`
  + ` vanilla variants that do not compile as ES: ${vanillaBroken}`);
process.exit(failed ? 1 : 0);
