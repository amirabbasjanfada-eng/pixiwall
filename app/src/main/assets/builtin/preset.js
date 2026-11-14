// Built-in engine test (the only preset). It follows exactly the same contract as every imported
// animation, so if it runs, PixiJS, the WebView renderer and the lifecycle all work.
// It prints the active renderer (webgl / canvas), the PixiJS version and the measured frame rate.
window.startAnimation = async function (context) {
  const PIXI = context.PIXI;
  const app = new PIXI.Application();
  await app.init({
    width: context.width,
    height: context.height,
    resolution: context.devicePixelRatio,
    autoDensity: true,
    antialias: true,
    background: 0x0b1020
  });
  context.container.appendChild(app.canvas);

  const stage = app.stage;
  stage.eventMode = 'static';
  stage.hitArea = app.screen;

  function hue(t) {
    const h = t * 6, c = 0.8, x = c * (1 - Math.abs((h % 2) - 1)), m = 0.15;
    let r = 0, g = 0, b = 0;
    if (h < 1) { r = c; g = x; }
    else if (h < 2) { r = x; g = c; }
    else if (h < 3) { g = c; b = x; }
    else if (h < 4) { g = x; b = c; }
    else if (h < 5) { r = x; b = c; }
    else { r = c; b = x; }
    return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
  }

  const TILES = 12;
  const ring = new PIXI.Container();
  stage.addChild(ring);
  const tiles = [];
  for (let i = 0; i < TILES; i++) {
    const tile = new PIXI.Graphics();
    tile.roundRect(-16, -16, 32, 32, 8).fill(hue(i / TILES));
    ring.addChild(tile);
    tiles.push(tile);
  }

  const marker = new PIXI.Graphics();
  marker.circle(0, 0, 26).stroke({ width: 3, color: 0xffffff, alpha: 0.9 });
  marker.visible = false;
  stage.addChild(marker);

  const label = new PIXI.Text({
    text: '',
    style: { fontFamily: 'monospace', fontSize: 13, fill: 0xdfe6ff, align: 'center' }
  });
  label.anchor.set(0.5, 1);
  stage.addChild(label);

  let w = context.width;
  let h = context.height;
  let angle = 0;
  let pulse = 0;
  let touching = false;
  let frames = 0;
  let elapsed = 0;
  let fps = 0;

  function describe() {
    const gl = app.renderer.context && app.renderer.context.webGLVersion;
    const kind = app.renderer.name + (gl ? ' (WebGL ' + gl + ')' : '');
    return 'PixiJS ' + PIXI.VERSION + ' - ' + kind + '\n' + fps + ' fps (target ' + context.targetFps + ')';
  }

  function layout() {
    ring.position.set(w / 2, h / 2);
    label.position.set(w / 2, h * 0.9);
    label.text = describe();
  }
  layout();

  const onTick = (ticker) => {
    const dt = ticker.deltaMS;
    angle += dt * 0.0006;
    pulse += dt * 0.004;
    const radius = Math.min(w, h) * 0.26;
    for (let i = 0; i < TILES; i++) {
      const a = angle + (i / TILES) * Math.PI * 2;
      const tile = tiles[i];
      tile.position.set(Math.cos(a) * radius, Math.sin(a) * radius);
      tile.rotation = a * 2;
      tile.scale.set(1 + 0.25 * Math.sin(pulse + i));
    }
    if (touching) { marker.scale.set(1 + 0.1 * Math.sin(pulse * 3)); }
    frames++;
    elapsed += dt;
    if (elapsed >= 500) {
      fps = Math.round((frames * 1000) / elapsed);
      frames = 0;
      elapsed = 0;
      label.text = describe();
    }
  };
  app.ticker.add(onTick);

  const onDown = (e) => { touching = true; marker.visible = true; marker.position.set(e.global.x, e.global.y); };
  const onMove = (e) => { if (touching) { marker.position.set(e.global.x, e.global.y); } };
  const onUp = () => { touching = false; marker.visible = false; };
  stage.on('pointerdown', onDown);
  stage.on('pointermove', onMove);
  stage.on('pointerup', onUp);
  stage.on('pointerupoutside', onUp);

  return {
    resize(width, height) {
      w = width;
      h = height;
      app.renderer.resize(width, height);
      layout();
    },
    pause() { app.ticker.stop(); },
    resume() { app.ticker.start(); },
    destroy() {
      app.ticker.remove(onTick);
      stage.off('pointerdown', onDown);
      stage.off('pointermove', onMove);
      stage.off('pointerup', onUp);
      stage.off('pointerupoutside', onUp);
      app.destroy({ removeView: true }, { children: true, texture: true, textureSource: true });
    }
  };
};
