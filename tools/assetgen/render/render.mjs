// Renders a model preview JSON (from build.py) to a PNG, Minecraft-style face shading (ported from STSV).
// Usage: node render/render.mjs <model.preview.json> <out.png>
// Needs playwright (Chromium) and three (npm install here, or THREE_MODULE=/path/to/three.module.js).
import { createRequire } from 'node:module';
import { existsSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const [input, output] = process.argv.slice(2).map(p => resolve(p));
const model = readFileSync(input, 'utf8');
const threePath = process.env.THREE_MODULE || join(here, 'node_modules', 'three', 'build', 'three.module.js');
if (!existsSync(threePath)) {
  console.error('three.js not found: run `npm install` in ' + here + ' or set THREE_MODULE');
  process.exit(1);
}
const threeUrl = pathToFileURL(threePath).href;
// the canvas is 1600 x 1000 unless the preview gives its own size (frame sheets: "width", "height")
const { width: W = 1600, height: H = 1000 } = JSON.parse(model);

const html = `<!doctype html><html><body style="margin:0;background:#1b1d22">
<canvas id="c" width="${W}" height="${H}"></canvas>
<script type="importmap">{"imports":{"three":"${threeUrl}"}}</script>
<script type="module">
import * as THREE from 'three';
const M = ${model};
const W = ${W}, H = ${H};
const renderer = new THREE.WebGLRenderer({ canvas: document.getElementById('c'), antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1); renderer.autoClear = false; renderer.setClearColor(0x1b1d22);
const loader = new THREE.TextureLoader();
const tex = {};
await Promise.all(Object.entries(M.textures).map(([k, src]) => new Promise(res => loader.load(src, t => {
  t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.SRGBColorSpace; tex[k] = t; res();
}))));
const SHADE = { up: 1.0, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6 };
function corners(f, a, b) {
  const [x1, y1, z1] = a, [x2, y2, z2] = b;
  return {
    north: [[x2,y2,z1],[x1,y2,z1],[x1,y1,z1],[x2,y1,z1]], south: [[x1,y2,z2],[x2,y2,z2],[x2,y1,z2],[x1,y1,z2]],
    east: [[x2,y2,z2],[x2,y2,z1],[x2,y1,z1],[x2,y1,z2]],  west: [[x1,y2,z1],[x1,y2,z2],[x1,y1,z2],[x1,y1,z1]],
    up: [[x1,y2,z1],[x2,y2,z1],[x2,y2,z2],[x1,y2,z2]],    down: [[x1,y1,z2],[x2,y1,z2],[x2,y1,z1],[x1,y1,z1]],
  }[f];
}
const scene = new THREE.Scene();
const box = new THREE.Box3();
for (const el of M.elements) {
  let rot = null;
  if (el.rotation) {
    const [axis, angle, origin] = el.rotation;
    const ax = { x: new THREE.Vector3(1,0,0), y: new THREE.Vector3(0,1,0), z: new THREE.Vector3(0,0,1) }[axis];
    rot = { m: new THREE.Matrix4().makeRotationAxis(ax, angle * Math.PI / 180), o: new THREE.Vector3(...origin) };
  }
  const mat = el.matrix ? new THREE.Matrix4().fromArray(el.matrix) : null;
  for (const [f, face] of Object.entries(el.faces)) {
    const c = corners(f, el.from, el.to).map(p => {
      const v = new THREE.Vector3(...p);
      if (rot) v.sub(rot.o).applyMatrix4(rot.m).add(rot.o);
      if (mat) v.applyMatrix4(mat);
      if (!el.translucent) box.expandByPoint(v);
      return [v.x, v.y, v.z];
    });
    const [u1, v1, u2, v2] = face.uv.map(n => n / 16);
    const uv = [[u1, 1 - v1], [u2, 1 - v1], [u2, 1 - v2], [u1, 1 - v2]];
    const g = new THREE.BufferGeometry();
    const idx = [0, 3, 2, 0, 2, 1];
    g.setAttribute('position', new THREE.Float32BufferAttribute(idx.flatMap(i => c[i]), 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(idx.flatMap(i => uv[i]), 2));
    const s = el.glow ? 1 : SHADE[f];
    const t = el.tint ? el.tint.map(n => n / 255) : [1, 1, 1];
    const color = new THREE.Color().setRGB(s * t[0], s * t[1], s * t[2], THREE.SRGBColorSpace);
    const material = el.translucent
      ? new THREE.MeshBasicMaterial({ map: tex[face.texture], color, side: THREE.FrontSide, transparent: true, depthWrite: false })
      : new THREE.MeshBasicMaterial({ map: tex[face.texture], color, side: THREE.FrontSide, alphaTest: 0.1 });
    const mesh = new THREE.Mesh(g, material);
    if (el.translucent) mesh.renderOrder = 1;
    scene.add(mesh);
  }
}
if (M.ground) {
  // a flat ground plate under the scene: [x1, y, z1, x2, z2] (grass green unless groundColor is given)
  const [x1, y, z1, x2, z2] = M.ground;
  const g = new THREE.PlaneGeometry(x2 - x1, z2 - z1);
  const plane = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color: new THREE.Color(M.groundColor || '#4a5a3a') }));
  plane.rotation.x = -Math.PI / 2;
  plane.position.set((x1 + x2) / 2, y - 0.02, (z1 + z2) / 2);
  scene.add(plane);
}
const center = box.getCenter(new THREE.Vector3());
const radius = box.getSize(new THREE.Vector3()).length() / 2;
const views = M.views || [
  { x: 0, y: 0, w: 1000, h: 1000, az: 38, el: 24, k: 2.6 },
  { x: 1000, y: 0, w: 600, h: 500, az: 215, el: 24, k: 2.9 },
  { x: 1000, y: 500, w: 600, h: 500, az: 95, el: 42, k: 2.9 },
];
for (const v of views) {
  // ortho: half height of an orthographic view (inventory icons); fov: vertical field of view (default 35)
  const cam = v.ortho
    ? new THREE.OrthographicCamera(-v.ortho * v.w / v.h, v.ortho * v.w / v.h, v.ortho, -v.ortho, 0.01, 2000)
    : new THREE.PerspectiveCamera(v.fov || 35, v.w / v.h, 0.01, 2000);
  // a view may aim at its own target (one object of a sheet) from a fixed distance, or sit at an explicit eye
  const c = v.target ? new THREE.Vector3(...v.target) : center;
  if (v.eye) {
    cam.position.set(...v.eye);
  } else {
    const az = v.az * Math.PI / 180, el = v.el * Math.PI / 180, d = v.dist || radius * v.k;
    cam.position.set(c.x + Math.sin(az) * Math.cos(el) * d, c.y + Math.sin(el) * d, c.z - Math.cos(az) * Math.cos(el) * d);
  }
  cam.lookAt(c);
  if (v.background) {
    renderer.setClearColor(new THREE.Color(v.background));
  } else {
    renderer.setClearColor(0x1b1d22);
  }
  renderer.setViewport(v.x, H - v.y - v.h, v.w, v.h);
  renderer.setScissor(v.x, H - v.y - v.h, v.w, v.h);
  renderer.setScissorTest(true);
  renderer.clear();
  renderer.render(scene, cam);
}
window.__done = true;
</script></body></html>`;

const tmp = join(dirname(output), '.render-' + process.pid + '.html');
writeFileSync(tmp, html);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--allow-file-access-from-files'] });
try {
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  page.on('pageerror', e => console.error('[pageerror]', e.message));
  await page.goto(pathToFileURL(tmp).href);
  await page.waitForFunction(() => window.__done === true, null, { timeout: 60000 });
  await page.locator('#c').screenshot({ path: output });
} finally {
  await browser.close();
  rmSync(tmp, { force: true });
}
console.log('render  ' + output);
