package com.pixiwall.core

/** Version of PixiJS bundled into the APK. scripts/fetch-pixi.sh reads this line, so keep the format. */
const val PIXI_VERSION = "8.20.1"

object Prompt {

    /** The fixed technical prompt followed by the user's description. */
    fun build(description: String): String = FIXED + "\n\nAnimation request:\n" + description.trim()

    val FIXED: String = """
You are writing one JavaScript file, animation.js, for an Android app that runs PixiJS animations as live wallpapers inside Android WebView. Reply with ONLY the complete contents of animation.js: no markdown fences, no HTML, no explanation. Follow every rule below exactly.

1. FILE STRUCTURE
- A classic script, not an ES module: no import, no export, no require, no top-level await.
- It must define exactly one entry point:
  window.startAnimation = async function (context) { ... };
- Keep all other code and state inside that function or inside one self-invoking function. Do not use any global the app does not provide.

2. CONTEXT OBJECT
startAnimation receives one argument with exactly these fields:
- PIXI: the bundled PixiJS v${PIXI_VERSION} namespace. Use context.PIXI. Never load PixiJS yourself.
- container: an empty, full-screen HTMLElement. Append your canvas to it and add nothing else.
- width, height: current size in CSS pixels.
- devicePixelRatio: the pixel ratio chosen by the app. It already includes the user's rendering-resolution setting. Always use this value, never window.devicePixelRatio.
- targetFps: the frame rate the user selected (30, 60, or the display's maximum).
- isWallpaper: true when running as the live wallpaper, false in the in-app preview.

3. RETURN VALUE
The function must resolve to an object with exactly these methods (each may be synchronous or async):
- resize(width, height): called whenever the container size changes (CSS pixels).
- pause(): stop all updates and rendering work.
- resume(): continue after pause().
- destroy(): stop everything and free everything you created.

4. APPLICATION SETUP (PixiJS v8)
  const app = new PIXI.Application();
  await app.init({ width: context.width, height: context.height, resolution: context.devicePixelRatio, autoDensity: true, background: 0x000000 });
  context.container.appendChild(app.canvas);
- Always await app.init(...). Never pass options to the constructor.
- Do not pass preference, resizeTo, canvas, view or context options. The app chooses the renderer (WebGL2 with automatic WebGL1 fallback; the PixiJS Canvas renderer when the user turns GPU acceleration off) and does all sizing.
- Use app.canvas, not app.view. Use PixiJS v8 APIs only, for example graphics.rect(...).fill(...), graphics.circle(...).stroke(...), new PIXI.Text({ text, style }). The v7 API (beginFill, drawRect, endFill) does not exist.
- app.renderer.name is "webgl" or "canvas". Filters, custom shaders and advanced blend modes only work with "webgl"; check the name and skip them otherwise.

5. FRAME LOOP
- Drive every update from app.ticker.add((ticker) => { ... }). Use ticker.deltaMS (milliseconds) for time-based motion so speed does not depend on frame rate.
- The app enforces the frame-rate limit. Never set ticker.maxFPS and never animate with setInterval, setTimeout or requestAnimationFrame.
- Do not allocate objects inside the ticker callback; create them once and reuse them.

6. POINTER AND TOUCH INPUT
- Enable input with app.stage.eventMode = "static"; app.stage.hitArea = app.screen; then listen with app.stage.on("pointerdown" / "pointermove" / "pointerup", handler) and read event.global.x and event.global.y.
- The user can switch touch off, and many launchers never forward touches to wallpapers. The animation must look complete without any interaction.
- Remove every listener in destroy().

7. RESIZE
resize(width, height) must call app.renderer.resize(width, height) and then re-layout all content for the new size. Never assume a fixed size or orientation.

8. PAUSE, RESUME, DESTROY
- pause(): app.ticker.stop() and stop anything else that runs on its own.
- resume(): app.ticker.start().
- destroy(): remove ticker callbacks and event listeners, then call
  app.destroy({ removeView: true }, { children: true, texture: true, textureSource: true });
  and drop all references.
- pause() and resume() may be called repeatedly in any order. destroy() is called once and must work even right after pause().

9. MEMORY AND GPU RESOURCES
- Create textures, Graphics and Text objects once and reuse them; never create them per frame.
- Keep textures at 2048 x 2048 pixels or smaller. Prefer a few batched objects, or PIXI.ParticleContainer, over thousands of separate display objects.
- Destroy every texture, filter, render texture and display object you create once it is no longer needed, and in destroy().
- Avoid per-frame work such as parsing, string building or changing Text content every frame.

10. ANDROID WEBVIEW AND WEBGL COMPATIBILITY
- Target mobile GPUs. Custom shaders must be GLSL ES 1.00 compatible and start with "precision mediump float;" so they also run on the WebGL1 fallback.
- Do not use WebGPU, SharedArrayBuffer, Workers that need external files, or anything that needs a permission or a user gesture (camera, microphone, file pickers, fullscreen, audio autoplay).
- Feature-check optional browser APIs and wrap risky code in try/catch so one failure cannot stop the animation.

11. OFFLINE EXECUTION
- The app has no internet access. Never use CDNs, fetch, XMLHttpRequest, WebSocket, external images, fonts, audio or video, or any other URL.
- Generate everything in code or embed it inline (Graphics, Canvas 2D drawn into a texture, data: URIs). The only library available is PixiJS through context.PIXI.

12. MINIMAL VALID STRUCTURE (placeholders only)
window.startAnimation = async function (context) {
  const PIXI = context.PIXI;
  const app = new PIXI.Application();
  await app.init({ width: context.width, height: context.height, resolution: context.devicePixelRatio, autoDensity: true, background: 0x000000 });
  context.container.appendChild(app.canvas);
  const scene = new PIXI.Container();
  app.stage.addChild(scene);
  // Build the scene here.
  const update = (ticker) => {
    // Advance the scene using ticker.deltaMS.
  };
  app.ticker.add(update);
  return {
    resize(width, height) {
      app.renderer.resize(width, height);
      // Re-layout the scene here.
    },
    pause() { app.ticker.stop(); },
    resume() { app.ticker.start(); },
    destroy() {
      app.ticker.remove(update);
      app.destroy({ removeView: true }, { children: true, texture: true, textureSource: true });
    }
  };
};
""".trimIndent()
}
