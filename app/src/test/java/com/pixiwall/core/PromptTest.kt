package com.pixiwall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    @Test
    fun descriptionIsAppendedAtTheEnd() {
        val text = Prompt.build("  something calm  ")
        assertTrue(text.startsWith(Prompt.FIXED))
        assertTrue(text.endsWith("\n\nAnimation request:\nsomething calm"))
        assertEquals(1, Regex("Animation request:").findAll(text).count())
    }

    @Test
    fun fixedPromptCoversEveryRequiredTopic() {
        val required = listOf(
            "window.startAnimation = async function (context)",
            "PIXI", "container", "width, height", "devicePixelRatio", "targetFps", "isWallpaper",
            "app.canvas", "app.ticker", "pointerdown", "event.global",
            "resize(width, height)", "pause()", "resume()", "destroy()",
            "app.destroy(", "WebGL1", "GLSL ES 1.00", "WebView", "offline", PIXI_VERSION,
        )
        for (needle in required) {
            assertTrue("missing: $needle", Prompt.FIXED.contains(needle, ignoreCase = true))
        }
    }

    @Test
    fun fixedPromptHasNoUnexpandedTemplatesAndNoAnimationIdea() {
        assertFalse(Prompt.FIXED.contains("\${"))
        for (idea in listOf("snow", "rain", "fire", "stars", "galaxy", "particles of", "bouncing", "fireworks")) {
            assertFalse("prompt must not suggest an animation: $idea", Prompt.FIXED.contains(idea, ignoreCase = true))
        }
    }
}
