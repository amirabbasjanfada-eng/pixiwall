#!/usr/bin/env python3
"""
Optional checks of the engine page (assets/engine/index.html + runtime.js) in a REAL Chromium, with real timers,
real requestAnimationFrame pacing, a real DOM and the real Content-Security-Policy. PixiJS itself is replaced by a tiny
stub (the real file is fetched at build time), so this tests the page and the runtime, not PixiJS.

    pip install playwright && playwright install chromium
    python3 tests/browser_check.py
"""
import functools, http.server, json, pathlib, shutil, socketserver, sys, tempfile, threading, time

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENGINE = ROOT / "app/src/main/assets/engine"

PIXI_STUB = """
window.PIXI = { VERSION: 'stub', Application: class {
  async init(o) {
    window.__initOptions = o;
    this.canvas = document.createElement('canvas');
    this.renderer = { name: o.preference };
  }
} };
"""

GOOD = """
window.__ticks = 0;
window.startAnimation = async function (ctx) {
  window.__ctx = { w: ctx.width, h: ctx.height, dpr: ctx.devicePixelRatio, fps: ctx.targetFps, wall: ctx.isWallpaper };
  const app = new ctx.PIXI.Application();
  await app.init({ width: ctx.width, height: ctx.height, resolution: ctx.devicePixelRatio });
  ctx.container.appendChild(app.canvas);
  let id = requestAnimationFrame(function loop() { window.__ticks++; id = requestAnimationFrame(loop); });
  return { resize(w, h) { window.__resized = [w, h]; }, pause() {}, resume() {}, destroy() { cancelAnimationFrame(id); } };
};
"""

GL = """
window.startAnimation = async function (ctx) {
  const c = document.createElement('canvas');
  c.width = 64; c.height = 64;
  const gl = c.getContext('webgl2') || c.getContext('webgl');
  window.__gl = gl;
  ctx.container.appendChild(c);
  return { resize() {}, pause() {}, resume() {}, destroy() { /* forgets to release its GL context */ } };
};
"""

TRACKED = """
window.__live = window.__live || 0; window.__maxLive = window.__maxLive || 0;
window.__started = window.__started || 0; window.__destroyed = window.__destroyed || 0;
window.startAnimation = async function (ctx) {
  window.__started++; window.__live++; window.__maxLive = Math.max(window.__maxLive, window.__live);
  const app = new ctx.PIXI.Application();
  await app.init({ width: ctx.width, height: ctx.height });
  ctx.container.appendChild(app.canvas);
  let id = requestAnimationFrame(function loop() { id = requestAnimationFrame(loop); });
  %EXTRA%
  return { resize() {}, pause() {}, resume() {}, destroy() { window.__destroyed++; window.__live--; } };
};
"""

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + (f"   [{detail}]" if detail else ""), flush=True)


def main():
    site = pathlib.Path(tempfile.mkdtemp(prefix="pixiwall-site-"))
    (site / "anim").mkdir()
    shutil.copy(ENGINE / "index.html", site / "index.html")
    shutil.copy(ENGINE / "runtime.js", site / "runtime.js")
    (site / "pixi.min.js").write_text(PIXI_STUB)
    (site / "anim/good.js").write_text(GOOD)
    (site / "anim/gl.js").write_text(GL)
    (site / "anim/a.js").write_text(TRACKED.replace("%EXTRA%", ""))
    (site / "anim/slow.js").write_text(TRACKED.replace("%EXTRA%", "await new Promise(r => setTimeout(r, 150));"))
    (site / "anim/leaky.js").write_text(TRACKED.replace("%EXTRA%", "setInterval(() => {}, 30); setTimeout(function again() { setTimeout(again, 40); }, 40);"))

    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args, **kwargs):
            pass

    handler = functools.partial(Quiet, directory=str(site))
    socketserver.TCPServer.allow_reuse_address = True
    server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"

    cfg = lambda **kw: {**{"src": f"{base}/anim/good.js", "fps": 60, "maxFps": 60, "resScale": 1, "gpu": True, "isWallpaper": False}, **kw}

    with sync_playwright() as p:
        browser = p.chromium.launch(args=["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"])
        ctx = browser.new_context(device_scale_factor=2.5, viewport={"width": 400, "height": 800})

        def wait_until(page, expression, timeout=20.0):
            # poll from Python: the page's own timers/rAF are owned by the runtime under test
            deadline = time.time() + timeout
            while time.time() < deadline:
                if page.evaluate(expression):
                    return True
                time.sleep(0.05)
            return False

        def fresh():
            page = ctx.new_page()
            page.goto(f"{base}/index.html")
            assert wait_until(page, "!!(window.__host && window.PIXI)")
            return page

        def start(page, **kw):
            page.evaluate("c => { window.__host.start(c); }", cfg(**kw))
            time.sleep(0.05)
            assert wait_until(page, "window.__host.status().state !== 'starting'"), "start did not finish"

        # ---------------------------------------------------------------- offline policy (CSP)
        page = fresh()
        page.evaluate("""() => { window.__csp = []; document.addEventListener('securitypolicyviolation',
            e => window.__csp.push(e.violatedDirective.split('-')[0] + '-src ' + e.blockedURI)); }""")
        out = page.evaluate("""async () => {
            const o = {};
            o.fn = (() => { try { return new Function('return 7')() === 7; } catch (e) { return false; } })();
            try { await fetch('https://example.com/', { mode: 'no-cors' }); o.fetch = 'sent'; } catch (e) { o.fetch = 'failed'; }
            try { await fetch('/runtime.js'); o.same = 'ok'; } catch (e) { o.same = 'failed'; }
            o.ws = await new Promise(res => { try { const s = new WebSocket('wss://example.com/'); s.onerror = () => res('failed'); s.onopen = () => res('OPEN'); } catch (e) { res('failed'); } });
            o.dataImg = await new Promise(res => { const i = new Image(); i.onload = () => res('ok'); i.onerror = () => res('failed'); i.src = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'; });
            o.extImg = await new Promise(res => { const i = new Image(); i.onload = () => res('loaded'); i.onerror = () => res('failed'); i.src = 'https://example.com/x.png'; });
            const s = document.createElement('script'); s.src = 'https://example.com/x.js'; document.head.appendChild(s);
            await new Promise(r => setTimeout(r, 300));
            return o;
        }""")
        csp = page.evaluate("window.__csp")
        check("CSP: eval is allowed (PixiJS builds shaders with it)", out["fn"])
        check("CSP: same-origin requests work", out["same"] == "ok")
        check("CSP: data: images work", out["dataImg"] == "ok")
        check("CSP blocks fetch to another site", any(v.startswith("connect-src") and "example.com" in v for v in csp), str(csp))
        check("CSP blocks WebSocket to another site", any("wss://example.com" in v for v in csp) and out["ws"] != "OPEN")
        check("CSP blocks images from another site", any(v.startswith("img-src") and "example.com/x.png" in v for v in csp))
        check("CSP blocks scripts from another site", any(v.startswith("script-src") and "example.com/x.js" in v for v in csp))
        page.close()

        # ---------------------------------------------------------------- context, dpr override
        page = fresh()
        start(page, resScale=0.5, fps=30, maxFps=90, isWallpaper=True)
        c = page.evaluate("window.__ctx")
        check("context has CSS size, 2.5 x 0.5 pixel ratio, fps, isWallpaper", c == {"w": 400, "h": 800, "dpr": 1.25, "fps": 30, "wall": True}, str(c))
        check("window.devicePixelRatio is overridden in a real Chromium", page.evaluate("window.devicePixelRatio") == 1.25)
        check("GPU on selects the webgl renderer", page.evaluate("window.__host.status().renderer") == "webgl")
        start(page, gpu=False)
        check("GPU off selects the canvas renderer", page.evaluate("window.__host.status().renderer") == "canvas")
        page.close()

        # ---------------------------------------------------------------- fps cap and pause with real rAF pacing
        page = fresh()
        for target, lo, hi in [(30, 50, 70), (60, 105, 130), (0, 105, 135)]:
            start(page, fps=target)
            time.sleep(0.4)
            before = page.evaluate("window.__host.status().frames")
            time.sleep(2.0)
            frames = page.evaluate("window.__host.status().frames") - before
            check(f"real rAF: fps={target or 'max'} runs about {'30' if target == 30 else '60'} frames/s ({frames} frames in 2 s)", lo <= frames <= hi)
        start(page, fps=60)
        time.sleep(0.5)
        page.evaluate("window.__host.pause()")
        time.sleep(0.3)
        frozen = page.evaluate("window.__host.status().frames")
        time.sleep(1.0)
        check("pause freezes frame callbacks", page.evaluate("window.__host.status().frames") == frozen)
        page.evaluate("window.__host.resume()")
        time.sleep(0.8)
        check("resume continues them", page.evaluate("window.__host.status().frames") > frozen + 20)
        page.close()

        # ---------------------------------------------------------------- GPU resources
        page = fresh()
        start(page, src=f"{base}/anim/gl.js")
        has_gl = page.evaluate("window.__hasGl === undefined ? !!window.__gl : !!window.__gl")
        if has_gl:
            lost_before = page.evaluate("window.__gl.isContextLost()")
            page.evaluate("window.__host.destroy()")
            time.sleep(0.5)
            check("destroy() releases a GL context the animation forgot to free", (not lost_before) and page.evaluate("window.__gl.isContextLost()"))
        else:
            print("SKIP  GL context release (no WebGL in this headless browser)")
        check("destroy() leaves an empty container", page.evaluate("document.getElementById('stage').children.length") == 0)
        page.close()

        # ---------------------------------------------------------------- errors
        page = fresh()
        start(page, src=f"{base}/anim/missing.js")
        status = page.evaluate("window.__host.status()")
        overlay = page.evaluate("document.getElementById('error') && document.getElementById('error').textContent")
        check("a missing file is reported on screen, not thrown", status["state"] == "error" and "Could not load" in (overlay or ""), str(status["error"]))
        start(page)
        check("the engine recovers after an error", page.evaluate("window.__host.status().state") == "running" and not page.evaluate("document.getElementById('error').textContent"))
        page.close()

        # ---------------------------------------------------------------- lifecycle storm, real timers
        page = fresh()
        seed = [4242]

        def rnd(n):
            seed[0] = (seed[0] * 1103515245 + 12345) & 0x7FFFFFFF
            return seed[0] % n

        burst_js = """([ops, base]) => { const H = window.__host;
            for (const op of ops) { switch (op[0]) {
                case 'start': H.start({ src: base + '/anim/' + op[1] + '.js', fps: op[2], maxFps: 60, resScale: 1, gpu: op[3], isWallpaper: true }); break;
                case 'pause': H.pause(); break;
                case 'resume': H.resume(); break;
                case 'resize': window.dispatchEvent(new Event('resize')); break;
                case 'destroy': H.destroy(); break; } } }"""
        for _ in range(160):
            ops = []
            for _ in range(1 + rnd(4)):
                k = rnd(6)
                if k in (0, 1):
                    ops.append(["start", ["a", "slow", "leaky", "missing"][rnd(4)], [0, 30, 60][rnd(3)], rnd(2) == 0])
                elif k == 2:
                    ops.append(["pause"])
                elif k == 3:
                    ops.append(["resume"])
                elif k == 4:
                    ops.append(["resize"])
                elif rnd(4) == 0:
                    ops.append(["destroy"])
            page.evaluate(burst_js, [ops, base])
            time.sleep((10 + rnd(60)) / 1000)
        page.evaluate("() => { window.__host.resume(); window.__host.destroy(); }")
        settled = wait_until(page, "window.__host.status().state === 'idle'", timeout=30)
        time.sleep(0.4)
        st = page.evaluate("({ live: window.__live, maxLive: window.__maxLive, started: window.__started, destroyed: window.__destroyed, state: window.__host.status().state, stage: document.getElementById('stage').children.length })")
        check("storm: the engine settles after the final destroy", settled, str(st))
        check("storm: animations really started", st["started"] > 15, str(st))
        check("storm: never more than one animation alive", st["maxLive"] <= 1, f"max {st['maxLive']}")
        check("storm: every started animation was destroyed", st["started"] == st["destroyed"] and st["live"] == 0)
        check("storm: engine idle and container empty afterwards", st["state"] == "idle" and st["stage"] == 0)
        before = page.evaluate("window.__host.status().frames")
        time.sleep(0.8)
        check("storm: no loop left running after the final destroy", page.evaluate("window.__host.status().frames") == before)
        page.close()

        browser.close()
    server.shutdown()
    shutil.rmtree(site, ignore_errors=True)

    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
