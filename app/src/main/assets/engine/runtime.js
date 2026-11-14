/*
 * PixiWall engine runtime.
 * The in-app preview and the live wallpaper load this exact page, so both run the same engine.
 *
 *   __host.start(cfg)  load cfg.src and run window.startAnimation(context); replaces a running animation
 *   __host.pause()     pause the animation and freeze every requestAnimationFrame callback
 *   __host.resume()    undo pause()
 *   __host.resize()    re-measure the container and call the animation's resize(width, height)
 *   __host.destroy()   destroy the animation and release everything it created
 *   __host.status()    small diagnostics object (used by the tests)
 *
 * cfg: { src, fps (30 | 60 | 0 = max), maxFps, resScale (0.5 - 1), gpu, isWallpaper }
 */
(function () {
  'use strict';

  var w = window;
  var realRaf = w.requestAnimationFrame.bind(w);
  var realCaf = w.cancelAnimationFrame.bind(w);
  var realSetTimeout = w.setTimeout.bind(w);
  var realClearTimeout = w.clearTimeout.bind(w);
  var realSetInterval = w.setInterval.bind(w);
  var realClearInterval = w.clearInterval.bind(w);
  var nativeDpr = Number(w.devicePixelRatio) || 1;
  var CONTRACT = ['resize', 'pause', 'resume', 'destroy'];

  var S = {
    gen: 0,            // bumped by every start() / destroy(); stale work checks it and stops
    chain: Promise.resolve(),
    cfg: null,
    inst: null,        // object returned by startAnimation
    root: null,        // element handed to the animation as context.container
    script: null,
    state: 'idle',     // idle | starting | running | error
    error: '',
    renderer: '',
    scriptError: '',
    paused: false,
    dpr: nativeDpr,
    interval: 0,       // ms between frames, 0 = no cap
    due: 0,
    gateTs: -1,
    gateSkip: false,
    frames: new Map(),
    nextFrame: 1,
    timeouts: new Set(),
    intervals: new Set(),
    frameCount: 0,
    lastFrameAt: 0,
    lastW: 0,
    lastH: 0
  };

  /* ---------- reporting ---------- */

  function message(err) {
    return err && err.message ? String(err.message) : String(err);
  }

  function overlay(text) {
    try {
      var el = document.getElementById('error');
      if (!el) {
        el = document.createElement('pre');
        el.id = 'error';
        document.body.appendChild(el);
      }
      el.textContent = text;
    } catch (e) { /* ignore */ }
  }

  function clearOverlay() {
    try {
      var el = document.getElementById('error');
      if (el) { el.textContent = ''; }
    } catch (e) { /* ignore */ }
  }

  // Non-fatal problem (for example an exception inside one frame): remember the first one and keep running.
  function report(prefix, err) {
    var text = prefix + ': ' + message(err);
    try { console.error(text, err && err.stack ? err.stack : ''); } catch (e) { /* ignore */ }
    if (!S.error) {
      S.error = text;
      overlay(text);
    }
  }

  // The animation could not start.
  function fail(prefix, err) {
    var text = prefix + ': ' + message(err);
    try { console.error(text, err && err.stack ? err.stack : ''); } catch (e) { /* ignore */ }
    S.error = text;
    S.state = 'error';
    overlay(text);
  }

  /* ---------- frame scheduler: FPS cap, pause gate, leak protection ---------- */

  // True when the frame with timestamp ts may run. All callbacks of one frame share one decision.
  function allowFrame(ts) {
    if (S.interval <= 0) { return true; }
    if (ts !== S.gateTs) {
      S.gateTs = ts;
      if (S.due === 0 || ts >= S.due - 1.5) {
        S.gateSkip = false;
        S.due = (S.due === 0 || ts - S.due > S.interval) ? ts + S.interval : S.due + S.interval;
      } else {
        S.gateSkip = true;
      }
    }
    return !S.gateSkip;
  }

  w.requestAnimationFrame = function (cb) {
    if (typeof cb !== 'function') { throw new TypeError('requestAnimationFrame needs a function'); }
    var id = S.nextFrame++;
    var entry = { cb: cb, real: 0, held: false, fire: null };
    entry.fire = function (ts) {
      if (!S.frames.has(id)) { return; }
      if (S.paused) { entry.held = true; return; }                 // frozen until resume()
      if (!allowFrame(ts)) { entry.real = realRaf(entry.fire); return; }
      S.frames.delete(id);
      S.frameCount++;
      S.lastFrameAt = Date.now();
      try { cb(ts); } catch (e) { report('Error in animation frame', e); }
    };
    S.frames.set(id, entry);
    entry.real = realRaf(entry.fire);
    return id;
  };

  w.cancelAnimationFrame = function (id) {
    var entry = S.frames.get(id);
    if (entry) {
      S.frames.delete(id);
      realCaf(entry.real);
    }
  };

  w.setTimeout = function (fn, ms) {
    if (typeof fn !== 'function') { return realSetTimeout.apply(w, arguments); }
    var extra = Array.prototype.slice.call(arguments, 2);
    var id = realSetTimeout(function () {
      S.timeouts.delete(id);
      fn.apply(w, extra);
    }, ms);
    S.timeouts.add(id);
    return id;
  };
  w.clearTimeout = function (id) {
    S.timeouts.delete(id);
    realClearTimeout(id);
  };
  w.setInterval = function (fn, ms) {
    if (typeof fn !== 'function') { return realSetInterval.apply(w, arguments); }
    var extra = Array.prototype.slice.call(arguments, 2);
    var id = realSetInterval(function () { fn.apply(w, extra); }, ms);
    S.intervals.add(id);
    return id;
  };
  w.clearInterval = function (id) {
    S.intervals.delete(id);
    realClearInterval(id);
  };

  // Cancel every loop the animation may have left behind.
  function cancelAll() {
    S.frames.forEach(function (entry) { realCaf(entry.real); });
    S.frames.clear();
    S.timeouts.forEach(function (id) { realClearTimeout(id); });
    S.timeouts.clear();
    S.intervals.forEach(function (id) { realClearInterval(id); });
    S.intervals.clear();
  }

  /* ---------- helpers ---------- */

  function withTimeout(promise, ms, label) {
    return new Promise(function (resolve, reject) {
      var done = false;
      var timer = realSetTimeout(function () {
        if (!done) { done = true; reject(new Error(label + ' timed out')); }
      }, ms);
      Promise.resolve(promise).then(function (value) {
        if (!done) { done = true; realClearTimeout(timer); resolve(value); }
      }, function (err) {
        if (!done) { done = true; realClearTimeout(timer); reject(err); }
      });
    });
  }

  function callSafe(obj, name) {
    try {
      var result = obj[name]();
      if (result && typeof result.then === 'function') {
        result.then(null, function (e) { report(name + '() failed', e); });
      }
    } catch (e) {
      report(name + '() failed', e);
    }
  }

  function measure() {
    var el = document.getElementById('stage');
    var width = Math.round((el && el.clientWidth) || w.innerWidth || 1);
    var height = Math.round((el && el.clientHeight) || w.innerHeight || 1);
    return { w: Math.max(1, width), h: Math.max(1, height) };
  }

  function collectCanvases(root) {
    try {
      return root && root.querySelectorAll ? Array.prototype.slice.call(root.querySelectorAll('canvas')) : [];
    } catch (e) {
      return [];
    }
  }

  // Ask the GPU driver to free the GL contexts right now instead of waiting for garbage collection.
  function loseContexts(canvases) {
    for (var i = 0; i < canvases.length; i++) {
      try {
        var canvas = canvases[i];
        var gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
        var ext = gl && gl.getExtension ? gl.getExtension('WEBGL_lose_context') : null;
        if (ext) { ext.loseContext(); }
      } catch (e) { /* ignore */ }
    }
  }

  /* ---------- PixiJS integration ---------- */

  // The host, not the animation, picks the renderer: WebGL (WebGL2 with automatic WebGL1 fallback)
  // when GPU acceleration is on, PixiJS' Canvas renderer when it is off.
  function patchPixi() {
    var P = w.PIXI;
    if (!P || !P.Application || !P.Application.prototype) { return; }
    var proto = P.Application.prototype;
    if (proto.__pixiwallPatched || typeof proto.init !== 'function') { return; }
    var original = proto.init;
    proto.init = function (options) {
      var o = Object.assign({}, options || {});
      o.preference = (S.cfg && S.cfg.gpu === false) ? 'canvas' : 'webgl';
      var self = this;
      return original.call(this, o).then(function (result) {
        try { S.renderer = self.renderer && self.renderer.name ? String(self.renderer.name) : ''; } catch (e) { /* ignore */ }
        return result;
      });
    };
    Object.defineProperty(proto, '__pixiwallPatched', { value: true });
  }

  function configure(cfg) {
    S.cfg = cfg;
    var scale = Number(cfg.resScale) > 0 ? Number(cfg.resScale) : 1;
    S.dpr = Math.max(0.5, Math.round(nativeDpr * scale * 100) / 100);
    try {
      Object.defineProperty(w, 'devicePixelRatio', { configurable: true, get: function () { return S.dpr; } });
    } catch (e) { /* ignore */ }
    S.interval = Number(cfg.fps) > 0 ? 1000 / Number(cfg.fps) : 0;
    S.due = 0;
    S.gateTs = -1;
    S.gateSkip = false;
  }

  function makeRoot() {
    var stage = document.getElementById('stage');
    var root = document.createElement('div');
    root.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;overflow:hidden';
    stage.appendChild(root);
    return root;
  }

  function buildContext(root, cfg) {
    var size = measure();
    return {
      PIXI: w.PIXI,
      container: root,
      width: size.w,
      height: size.h,
      devicePixelRatio: S.dpr,
      targetFps: Number(cfg.fps) > 0 ? Number(cfg.fps) : (Number(cfg.maxFps) || 60),
      isWallpaper: !!cfg.isWallpaper
    };
  }

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var el = document.createElement('script');
      el.async = false;
      el.onload = function () { resolve(); };
      el.onerror = function () { reject(new Error('Could not load the animation file')); };
      el.src = src + (src.indexOf('?') < 0 ? '?v=' : '&v=') + Date.now();
      S.script = el;
      document.head.appendChild(el);
    });
  }

  /* ---------- lifecycle ---------- */

  function teardown() {
    var inst = S.inst;
    var root = S.root;
    var canvases = collectCanvases(root);
    S.inst = null;
    S.root = null;
    var done = Promise.resolve();
    if (inst) {
      done = withTimeout(Promise.resolve().then(function () { return inst.destroy(); }), 3000, 'destroy()')
        .catch(function (e) { report('destroy() failed', e); });
    }
    return done.then(function () {
      cancelAll();
      loseContexts(canvases);
      if (root && root.parentNode) { root.parentNode.removeChild(root); }
      if (S.script && S.script.parentNode) { S.script.parentNode.removeChild(S.script); }
      S.script = null;
      S.renderer = '';
      try { delete w.startAnimation; } catch (e) { w.startAnimation = undefined; }
    });
  }

  // Clean up after a start that failed half way.
  function abortStart() {
    var root = S.root;
    S.root = null;
    cancelAll();
    loseContexts(collectCanvases(root));
    if (root && root.parentNode) { root.parentNode.removeChild(root); }
  }

  function run(cfg, gen) {
    if (gen !== S.gen) { return Promise.resolve(); }      // replaced before it began
    S.state = 'starting';
    return teardown().then(function () {
      if (gen !== S.gen) { return undefined; }
      S.error = '';
      S.scriptError = '';
      clearOverlay();
      configure(cfg);
      if (!w.PIXI) { throw new Error('PixiJS did not load'); }
      patchPixi();
      return loadScript(String(cfg.src)).then(function () {
        if (gen !== S.gen) { return undefined; }
        if (typeof w.startAnimation !== 'function') {
          throw new Error('The file did not define window.startAnimation' + (S.scriptError ? ' (' + S.scriptError + ')' : ''));
        }
        var root = makeRoot();
        S.root = root;
        var context = buildContext(root, cfg);
        S.lastW = context.width;
        S.lastH = context.height;
        return withTimeout(Promise.resolve(w.startAnimation(context)), 20000, 'startAnimation()').then(function (ctl) {
          var valid = !!ctl && CONTRACT.every(function (name) { return typeof ctl[name] === 'function'; });
          if (!valid) {
            if (ctl && typeof ctl.destroy === 'function') { callSafe(ctl, 'destroy'); }
            throw new Error('startAnimation() must return { resize, pause, resume, destroy }');
          }
          S.inst = ctl;            // a newer start()/destroy() will tear it down in order
          if (gen === S.gen) { S.state = 'running'; }
          if (S.paused) { callSafe(ctl, 'pause'); }
        });
      });
    }).catch(function (e) {
      abortStart();
      if (gen === S.gen) { fail('Could not start the animation', e); }
    });
  }

  function start(cfg) {
    var gen = ++S.gen;
    S.chain = S.chain.then(function () { return run(cfg, gen); });
    return S.chain;
  }

  function destroy() {
    S.gen++;
    S.chain = S.chain.then(function () {
      return teardown().then(function () {
        S.state = 'idle';
        S.error = '';
        clearOverlay();
      });
    }).catch(function (e) { report('destroy failed', e); });
    return S.chain;
  }

  function pause() {
    S.paused = true;
    if (S.inst) { callSafe(S.inst, 'pause'); }
  }

  function resume() {
    if (!S.paused) { return; }
    S.paused = false;
    S.due = 0;
    S.frames.forEach(function (entry) {
      if (entry.held) {
        entry.held = false;
        entry.real = realRaf(entry.fire);
      }
    });
    if (S.inst) { callSafe(S.inst, 'resume'); }
  }

  function resizeNow() {
    var inst = S.inst;
    if (!inst) { return; }
    var size = measure();
    if (size.w === S.lastW && size.h === S.lastH) { return; }
    S.lastW = size.w;
    S.lastH = size.h;
    try {
      var result = inst.resize(size.w, size.h);
      if (result && typeof result.then === 'function') {
        result.then(null, function (e) { report('resize() failed', e); });
      }
    } catch (e) {
      report('resize() failed', e);
    }
  }

  var resizeTimer = 0;
  function onResize() {
    if (resizeTimer) { realClearTimeout(resizeTimer); }
    resizeTimer = realSetTimeout(function () {
      resizeTimer = 0;
      resizeNow();
    }, 60);
  }

  function status() {
    return {
      state: S.state,
      error: S.error,
      renderer: S.renderer,
      frames: S.frameCount,
      sinceFrameMs: S.lastFrameAt ? Date.now() - S.lastFrameAt : -1,
      paused: S.paused,
      running: !!S.inst,
      dpr: S.dpr
    };
  }

  w.addEventListener('resize', onResize);
  w.addEventListener('error', function (ev) {
    if (S.state === 'starting') {
      S.scriptError = (ev && ev.message ? ev.message : 'script error') + (ev && ev.lineno ? ' (line ' + ev.lineno + ')' : '');
    }
  });
  w.addEventListener('unhandledrejection', function (ev) {
    report('Unhandled promise rejection', ev && ev.reason);
  });

  w.__host = { start: start, pause: pause, resume: resume, resize: resizeNow, destroy: destroy, status: status };
})();
