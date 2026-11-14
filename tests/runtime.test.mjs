// Tests for the shared engine runtime (assets/engine/runtime.js) and the built-in preset.
// No browser needed: the runtime runs inside a vm sandbox with a fake DOM and a fake clock.
// Run with:  node --test tests/runtime.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const RUNTIME = fs.readFileSync(path.join(here, '../app/src/main/assets/engine/runtime.js'), 'utf8');
const PRESET = fs.readFileSync(path.join(here, '../app/src/main/assets/builtin/preset.js'), 'utf8');

const flush = () => new Promise((resolve) => setImmediate(resolve));
const plain = (value) => JSON.parse(JSON.stringify(value));

/* ---------------------------------------------------------------- fake browser */

function makeEnv(scripts = {}, pixi = null) {
  const env = { now: 0, size: { w: 400, h: 800 }, scripts, lost: 0 };
  let nextId = 1;
  const rafs = new Map();
  const timers = new Map();
  const listeners = {};

  class El {
    constructor(tag) {
      this.tag = tag;
      this.children = [];
      this.parentNode = null;
      this.style = {};
      this.id = '';
      this.textContent = '';
      this.src = '';
      this.onload = null;
      this.onerror = null;
    }
    appendChild(child) {
      child.parentNode = this;
      this.children.push(child);
      if (this.onAppend) this.onAppend(child);
      return child;
    }
    removeChild(child) {
      const i = this.children.indexOf(child);
      if (i >= 0) this.children.splice(i, 1);
      child.parentNode = null;
      return child;
    }
    querySelectorAll(tag) {
      const out = [];
      const walk = (node) => { for (const c of node.children) { if (c.tag === tag) out.push(c); walk(c); } };
      walk(this);
      return out;
    }
    get clientWidth() { return env.size.w; }
    get clientHeight() { return env.size.h; }
    getContext(type) {
      if (this.tag !== 'canvas' || type !== 'webgl2') return null;
      return { getExtension: (name) => (name === 'WEBGL_lose_context' ? { loseContext: () => { env.lost += 1; } } : null) };
    }
  }

  const stage = new El('div');
  stage.id = 'stage';
  const head = new El('head');
  const body = new El('body');
  env.stage = stage;
  env.head = head;
  env.body = body;

  const fire = (type, event) => (listeners[type] || []).forEach((fn) => fn(event));
  env.fire = fire;

  head.onAppend = (el) => {
    if (el.tag !== 'script') return;
    Promise.resolve().then(() => {
      const name = String(el.src).split('?')[0];
      const code = env.scripts[name];
      if (code === undefined) { if (el.onerror) el.onerror(new Error('404')); return; }
      try { vm.runInContext(code, ctx, { filename: name }); }
      catch (e) { fire('error', { message: e.message, lineno: 1 }); }
      if (el.onload) el.onload();
    });
  };

  const sandbox = {
    console: { error() {}, log() {}, warn() {} },
    document: {
      head,
      body,
      getElementById: (id) => (id === 'stage' ? stage : body.children.find((c) => c.id === id) || null),
      createElement: (tag) => new El(tag),
    },
    devicePixelRatio: 2.5,
    requestAnimationFrame(cb) { const id = nextId++; rafs.set(id, cb); return id; },
    cancelAnimationFrame(id) { rafs.delete(id); },
    setTimeout(fn, ms, ...args) { const id = nextId++; timers.set(id, { at: env.now + (ms || 0), fn: () => fn(...args), every: 0 }); return id; },
    clearTimeout(id) { timers.delete(id); },
    setInterval(fn, ms, ...args) { const id = nextId++; timers.set(id, { at: env.now + ms, fn: () => fn(...args), every: ms }); return id; },
    clearInterval(id) { timers.delete(id); },
    addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
  };
  Object.defineProperty(sandbox, 'innerWidth', { get: () => env.size.w });
  Object.defineProperty(sandbox, 'innerHeight', { get: () => env.size.h });
  sandbox.window = sandbox;
  const ctx = vm.createContext(sandbox);
  env.sandbox = sandbox;

  env.tick = (dt = 1000 / 60) => {
    env.now += dt;
    for (const [id, t] of [...timers]) {
      if (t.at <= env.now && timers.has(id)) {
        if (t.every) t.at += t.every; else timers.delete(id);
        t.fn();
      }
    }
    const batch = [...rafs];
    rafs.clear();
    for (const [, cb] of batch) cb(env.now);
  };
  env.run = async (ms, hz = 60) => {
    const step = 1000 / hz;
    for (let t = 0; t < ms - 1e-6; t += step) { env.tick(step); await flush(); }
  };
  env.pending = () => ({ frames: rafs.size, timers: timers.size });

  if (pixi) {
    sandbox.PIXI = pixi(env);
  } else {
    vm.runInContext(`window.PIXI = { VERSION: 'test', Application: class {
      async init(o) { window.__initOptions = o; this.canvas = document.createElement('canvas'); this.renderer = { name: o.preference }; }
    } };`, ctx);
  }
  vm.runInContext(RUNTIME, ctx, { filename: 'runtime.js' });
  env.host = sandbox.__host;
  env.start = async (cfg) => { const p = env.host.start(cfg); await flush(); await flush(); return p; };
  env.status = () => plain(env.host.status());
  env.log = () => plain(sandbox.__log || []);
  return env;
}

const CFG = { src: '/a.js', fps: 60, maxFps: 90, resScale: 1, gpu: true, isWallpaper: false };

const good = (tag) => `
window.__log = window.__log || [];
window.__ticks = 0;
window.startAnimation = async function (ctx) {
  window.__log.push(['start${tag}', ctx.width, ctx.height, ctx.devicePixelRatio, ctx.targetFps, ctx.isWallpaper, typeof ctx.PIXI.Application, !!ctx.container]);
  const app = new ctx.PIXI.Application();
  await app.init({ width: ctx.width, height: ctx.height, resolution: ctx.devicePixelRatio });
  ctx.container.appendChild(app.canvas);
  let id = 0;
  const loop = () => { window.__ticks++; id = requestAnimationFrame(loop); };
  id = requestAnimationFrame(loop);
  return {
    resize(w, h) { window.__log.push(['resize', w, h]); },
    pause() { window.__log.push(['pause']); },
    resume() { window.__log.push(['resume']); },
    destroy() { window.__log.push(['destroy${tag}']); cancelAnimationFrame(id); }
  };
};`;

const LEAKY = `
window.__ticks = 0; window.__interval = 0; window.__timer = 0;
window.startAnimation = async function () {
  const loop = () => { window.__ticks++; requestAnimationFrame(loop); };
  requestAnimationFrame(loop);
  setInterval(() => { window.__interval++; }, 100);
  setTimeout(function again() { window.__timer++; setTimeout(again, 100); }, 100);
  return { resize() {}, pause() {}, resume() {}, destroy() { /* forgot to stop its loops */ } };
};`;

const ticks = (env) => env.sandbox.__ticks;

/* ---------------------------------------------------------------- context + renderer */

test('gives the animation exactly the documented context', async () => {
  const env = makeEnv({ '/a.js': good('A') });
  await env.start({ ...CFG, resScale: 0.5, isWallpaper: true });
  assert.equal(env.status().state, 'running');
  // width, height, devicePixelRatio (2.5 * 0.5), targetFps, isWallpaper, PIXI present, container present
  assert.deepEqual(env.log()[0], ['startA', 400, 800, 1.25, 60, true, 'function', true]);
  assert.equal(env.sandbox.devicePixelRatio, 1.25);
});

test('"max" fps reports the display refresh rate as targetFps', async () => {
  const env = makeEnv({ '/a.js': good('A') });
  await env.start({ ...CFG, fps: 0, maxFps: 90 });
  assert.equal(env.log()[0][4], 90);
});

test('GPU on selects WebGL, GPU off selects the Canvas renderer', async () => {
  const on = makeEnv({ '/a.js': good('A') });
  await on.start({ ...CFG, gpu: true });
  assert.equal(on.sandbox.__initOptions.preference, 'webgl');
  assert.equal(on.status().renderer, 'webgl');

  const off = makeEnv({ '/a.js': good('A') });
  await off.start({ ...CFG, gpu: false });
  assert.equal(off.sandbox.__initOptions.preference, 'canvas');
  assert.equal(off.status().renderer, 'canvas');
});

/* ---------------------------------------------------------------- fps cap */

for (const [target, hz, expected] of [[30, 60, 60], [60, 60, 120], [60, 120, 120], [60, 90, 120], [30, 120, 60], [0, 60, 120]]) {
  test(`fps cap: target ${target || 'max'} on a ${hz} Hz display runs ~${expected} frames in 2 s`, async () => {
    const env = makeEnv({ '/a.js': good('A') });
    await env.start({ ...CFG, fps: target });
    await env.run(2000, hz);
    const got = ticks(env);
    assert.ok(Math.abs(got - expected) <= Math.ceil(expected * 0.04) + 1, `expected ~${expected}, got ${got}`);
  });
}

/* ---------------------------------------------------------------- pause / resume */

test('pause freezes every frame callback, resume continues', async () => {
  const env = makeEnv({ '/a.js': good('A') });
  await env.start(CFG);
  await env.run(1000);
  const before = ticks(env);
  assert.ok(before > 50);

  env.host.pause();
  await env.run(1000);
  assert.equal(ticks(env), before, 'no frames while paused');
  assert.equal(env.status().paused, true);

  env.host.resume();
  await env.run(1000);
  assert.ok(ticks(env) - before > 50, 'frames continue after resume');
  const names = env.log().map((e) => e[0]);
  assert.ok(names.includes('pause') && names.includes('resume'));
});

test('a pause requested before the animation finished starting is still applied', async () => {
  const env = makeEnv({ '/a.js': good('A') });
  const started = env.host.start(CFG);
  env.host.pause();
  await started;
  await flush();
  await env.run(500);
  assert.equal(ticks(env), 0);
  assert.ok(env.log().some((e) => e[0] === 'pause'));
  env.host.resume();
  await env.run(500);
  assert.ok(ticks(env) > 20);
});

/* ---------------------------------------------------------------- destroy / leaks / replacement */

test('destroy stops loops the animation forgot to cancel and frees GL contexts', async () => {
  const leaky = makeEnv({ '/a.js': LEAKY });
  await leaky.start(CFG);
  await leaky.run(1000);
  assert.ok(ticks(leaky) > 0 && leaky.sandbox.__interval > 0 && leaky.sandbox.__timer > 0);
  await leaky.host.destroy();
  const snapshot = [ticks(leaky), leaky.sandbox.__interval, leaky.sandbox.__timer];
  await leaky.run(2000);
  assert.deepEqual([ticks(leaky), leaky.sandbox.__interval, leaky.sandbox.__timer], snapshot);
  assert.deepEqual(leaky.pending(), { frames: 0, timers: 0 });
  assert.equal(leaky.status().state, 'idle');

  const env = makeEnv({ '/a.js': good('A') });
  await env.start(CFG);
  assert.equal(env.stage.children.length, 1);
  await env.host.destroy();
  assert.equal(env.lost, 1, 'WEBGL_lose_context requested');
  assert.equal(env.stage.children.length, 0, 'container removed');
  assert.equal(env.head.children.length, 0, 'script element removed');
  assert.equal(env.sandbox.startAnimation, undefined);
});

test('starting a second animation destroys the first one first and never overlaps loops', async () => {
  const env = makeEnv({ '/a.js': good('A'), '/b.js': good('B') });
  await env.start({ ...CFG, src: '/a.js' });
  await env.run(500);
  await env.start({ ...CFG, src: '/b.js' });
  assert.deepEqual(env.log().map((e) => e[0]), ['startA', 'destroyA', 'startB']);
  await env.run(1000);
  assert.ok(Math.abs(ticks(env) - 60) <= 3, `a single loop runs, got ${ticks(env)}`);
  assert.equal(env.pending().frames, 1);
});

test('rapid restarts only run the most recent animation', async () => {
  const env = makeEnv({ '/a.js': good('A'), '/b.js': good('B') });
  env.host.start({ ...CFG, src: '/a.js' });
  const last = env.host.start({ ...CFG, src: '/b.js' });
  await last;
  await flush();
  assert.deepEqual(env.log().map((e) => e[0]), ['startB']);
});

/* ---------------------------------------------------------------- errors */

test('errors are reported, never thrown, and the engine recovers afterwards', async () => {
  const env = makeEnv({
    '/a.js': good('A'),
    '/none.js': 'window.__nothing = 1;',
    '/syntax.js': 'window.startAnimation = async function ( {',
    '/throws.js': 'window.startAnimation = async function () { throw new Error("boom"); };',
    '/bad.js': 'window.startAnimation = async function () { return { pause() {} }; };',
  });

  await env.start({ ...CFG, src: '/missing.js' });
  assert.equal(env.status().state, 'error');
  assert.match(env.status().error, /Could not load/);

  await env.start({ ...CFG, src: '/none.js' });
  assert.match(env.status().error, /did not define window\.startAnimation/);

  await env.start({ ...CFG, src: '/syntax.js' });
  assert.match(env.status().error, /did not define window\.startAnimation \(.+line 1\)/);

  await env.start({ ...CFG, src: '/throws.js' });
  assert.match(env.status().error, /boom/);

  await env.start({ ...CFG, src: '/bad.js' });
  assert.match(env.status().error, /must return \{ resize, pause, resume, destroy \}/);

  assert.ok(env.body.children.some((c) => c.id === 'error' && c.textContent.includes('must return')), 'error overlay shown');

  await env.start({ ...CFG, src: '/a.js' });
  assert.equal(env.status().state, 'running');
  assert.equal(env.status().error, '');
  assert.equal(env.body.children.find((c) => c.id === 'error').textContent, '', 'overlay cleared');
});

test('an animation that never finishes starting times out instead of blocking the engine', async () => {
  const env = makeEnv({
    '/hang.js': 'window.startAnimation = () => new Promise(() => {});',
    '/a.js': good('A'),
  });
  const first = env.host.start({ ...CFG, src: '/hang.js' });
  await flush();
  await env.run(21000);
  await first;
  assert.match(env.status().error, /timed out/);
  assert.equal(env.stage.children.length, 0, 'half-built container removed');
  await env.start({ ...CFG, src: '/a.js' });
  assert.equal(env.status().state, 'running');
});

test('an exception inside a frame is reported without stopping the engine', async () => {
  const env = makeEnv({
    '/a.js': `window.__n = 0; window.startAnimation = async function () {
      const loop = () => { window.__n++; requestAnimationFrame(loop); if (window.__n === 3) throw new Error('frame bug'); };
      requestAnimationFrame(loop);
      return { resize() {}, pause() {}, resume() {}, destroy() {} };
    };`,
  });
  await env.start(CFG);
  await env.run(500);
  assert.equal(env.status().state, 'running');
  assert.match(env.status().error, /frame bug/);
  assert.ok(env.sandbox.__n > 10, 'loop kept running');
});

/* ---------------------------------------------------------------- resize */

test('resizing the page calls resize(width, height) once with the new size', async () => {
  const env = makeEnv({ '/a.js': good('A') });
  await env.start(CFG);
  env.size = { w: 300, h: 600 };
  env.fire('resize', {});
  env.fire('resize', {});
  await env.run(200);
  assert.deepEqual(env.log().filter((e) => e[0] === 'resize'), [['resize', 300, 600]]);
});

/* ---------------------------------------------------------------- built-in preset */

// A small PixiJS stand-in: just the calls preset.js uses, with a real ticker driven by the runtime's frame scheduler.
function pixiMock(env) {
  const point = () => ({ x: 0, y: 0, set(x, y) { this.x = x; this.y = y; } });
  class Emitter {
    constructor() { this.h = {}; }
    on(t, f) { (this.h[t] = this.h[t] || []).push(f); return this; }
    off(t, f) { this.h[t] = (this.h[t] || []).filter((x) => x !== f); return this; }
    emit(t, e) { (this.h[t] || []).slice().forEach((f) => f(e)); }
  }
  class Container extends Emitter {
    constructor() { super(); this.children = []; this.position = point(); this.scale = point(); this.rotation = 0; this.visible = true; }
    addChild(c) { this.children.push(c); return c; }
  }
  class Graphics extends Container {
    roundRect() { return this; } circle() { return this; } fill() { return this; } stroke() { return this; }
  }
  class Text extends Container {
    constructor(o) { super(); this.text = o.text; this.anchor = point(); }
  }
  class Ticker {
    constructor() { this.fns = []; this.id = 0; this.started = false; this.last = 0; }
    tick = (ts) => {
      this.id = 0;
      if (!this.started) return;
      const dt = this.last ? ts - this.last : 16.7;
      this.last = ts;
      this.fns.slice().forEach((f) => f({ deltaMS: dt }));
      if (this.started && !this.id) this.id = env.sandbox.requestAnimationFrame(this.tick);
    };
    add(f) { this.fns.push(f); }
    remove(f) { this.fns = this.fns.filter((x) => x !== f); }
    start() { if (this.started) return; this.started = true; this.last = 0; this.id = env.sandbox.requestAnimationFrame(this.tick); }
    stop() { this.started = false; if (this.id) { env.sandbox.cancelAnimationFrame(this.id); this.id = 0; } }
  }
  class Application {
    async init(o) {
      env.initOptions = o;
      this.canvas = env.sandbox.document.createElement('canvas');
      this.screen = { width: o.width, height: o.height };
      this.stage = new Container();
      this.ticker = new Ticker();
      this.renderer = { name: o.preference, context: { webGLVersion: 2 }, resize: (w, h) => { env.resized = [w, h]; } };
      this.ticker.start();
    }
    destroy(renderer, options) { env.destroyed = [renderer, options]; this.ticker.stop(); }
  }
  return { VERSION: '8.test', Application, Container, Graphics, Text };
}

test('built-in preset runs through the real runtime: frames, touch, resize, pause, resume, destroy', async () => {
  const env = makeEnv({ '/builtin/preset.js': PRESET }, pixiMock);
  await env.start({ ...CFG, src: '/builtin/preset.js', fps: 30 });
  assert.equal(env.status().state, 'running', env.status().error);
  assert.equal(env.status().renderer, 'webgl');
  assert.equal(env.initOptions.preference, 'webgl');
  assert.equal(env.initOptions.resolution, 2.5);

  await env.run(1000);
  const afterOneSecond = env.status().frames;
  assert.ok(Math.abs(afterOneSecond - 30) <= 2, `30 fps cap applies to the ticker, got ${afterOneSecond}`);

  env.host.pause();
  await env.run(500);
  assert.equal(env.status().frames, afterOneSecond, 'paused');
  env.host.resume();
  await env.run(500);
  assert.ok(env.status().frames > afterOneSecond);

  env.size = { w: 360, h: 720 };
  env.fire('resize', {});
  await env.run(200);
  assert.deepEqual(env.resized, [360, 720]);

  await env.host.destroy();
  assert.deepEqual(plain(env.destroyed), [{ removeView: true }, { children: true, texture: true, textureSource: true }]);
  assert.deepEqual(env.pending(), { frames: 0, timers: 0 });
  assert.equal(env.status().state, 'idle');
});

test('built-in preset reacts to pointer events', async () => {
  const mock = { app: null };
  const env = makeEnv({}, (e) => {
    const P = pixiMock(e);
    const Base = P.Application;
    P.Application = class extends Base { async init(o) { await super.init(o); mock.app = this; } };
    return P;
  });
  const window = {};
  new Function('window', PRESET)(window);
  const ctl = await window.startAnimation({
    PIXI: env.sandbox.PIXI, container: { appendChild() {} }, width: 300, height: 600, devicePixelRatio: 2, targetFps: 60, isWallpaper: false,
  });
  const stage = mock.app.stage;
  stage.emit('pointerdown', { global: { x: 10, y: 20 } });
  const marker = stage.children[stage.children.length - 2];
  assert.equal(marker.visible, true);
  assert.deepEqual([marker.position.x, marker.position.y], [10, 20]);
  stage.emit('pointermove', { global: { x: 30, y: 40 } });
  assert.deepEqual([marker.position.x, marker.position.y], [30, 40]);
  stage.emit('pointerup', {});
  assert.equal(marker.visible, false);
  ctl.destroy();
  assert.equal((stage.h.pointerdown || []).length, 0, 'listeners removed');
});

/* ---------------------------------------------------------------- diagnostics */

test('status reports the age of the last frame, so a stalled animation can be spotted in the logs', async () => {
  const env = makeEnv({ '/a.js': good('A') });
  await env.start(CFG);
  assert.equal(env.status().sinceFrameMs, -1, 'no frame yet');
  await env.run(200);
  assert.ok(env.status().sinceFrameMs >= 0);
  assert.ok(env.status().frames > 5);
});

/* ---------------------------------------------------------------- lifecycle storm */

// The wallpaper engine can be hit by a burst of lifecycle events (the system's picker animates its preview,
// the screen turns off and on, settings change). Whatever the order, there must never be two animations alive,
// a loop left behind, or an instance that was started but never destroyed.
test('lifecycle storm: random overlapping start/pause/resume/resize/destroy never leaves two instances or a leaked loop', async () => {
  const tracked = (extra) => `
    window.__live = window.__live || 0; window.__maxLive = window.__maxLive || 0;
    window.__started = window.__started || 0; window.__destroyed = window.__destroyed || 0;
    window.startAnimation = async function (ctx) {
      window.__started++; window.__live++; window.__maxLive = Math.max(window.__maxLive, window.__live);
      const app = new ctx.PIXI.Application();
      await app.init({ width: ctx.width, height: ctx.height });
      ctx.container.appendChild(app.canvas);
      let id = requestAnimationFrame(function loop() { id = requestAnimationFrame(loop); });
      ${extra}
      return { resize() {}, pause() {}, resume() {}, destroy() { window.__destroyed++; window.__live--; } };
    };`;
  const env = makeEnv({
    '/a.js': tracked(''),
    '/b.js': tracked(''),
    '/leaky.js': tracked('setInterval(function () {}, 50); setTimeout(function again() { setTimeout(again, 70); }, 70);'),
    // a slow start stays "in flight" while the next lifecycle event arrives: this is what exposes overlapping starts
    '/slow.js': tracked('await new Promise(function (resolve) { setTimeout(resolve, 120); });'),
  });
  let seed = 12345;
  const rnd = (n) => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed % n; };
  const sources = ['/a.js', '/b.js', '/leaky.js', '/slow.js', '/slow.js', '/missing.js'];

  for (let round = 0; round < 400; round++) {
    const burst = 1 + rnd(4);
    for (let i = 0; i < burst; i++) {       // several calls with no waiting in between: they overlap
      switch (rnd(6)) {
        case 0:
        case 1: env.host.start({ ...CFG, src: sources[rnd(sources.length)], fps: [0, 30, 60][rnd(3)], gpu: rnd(2) === 0 }); break;
        case 2: env.host.pause(); break;
        case 3: env.host.resume(); break;
        case 4: env.size = { w: 300 + rnd(200), h: 600 + rnd(400) }; env.fire('resize', {}); break;
        default: if (rnd(4) === 0) env.host.destroy();
      }
    }
    await flush();
    await env.run(20 + rnd(200));
  }
  env.host.resume();
  await env.host.destroy();
  await flush();
  await env.run(1000);

  const w = env.sandbox;
  assert.ok(w.__started > 20, `the storm really started animations (${w.__started})`);
  assert.equal(w.__started, w.__destroyed, 'every instance that started was destroyed');
  assert.equal(w.__live, 0);
  assert.ok(w.__maxLive <= 1, `never more than one animation alive at a time, saw ${w.__maxLive}`);
  assert.deepEqual(env.pending(), { frames: 0, timers: 0 }, 'no loop or timer left behind');
  assert.equal(env.status().state, 'idle');
});
