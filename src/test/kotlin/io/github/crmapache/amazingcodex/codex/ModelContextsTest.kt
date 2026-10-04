package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelContextsTest {

    @Test
    fun `reads standard and long windows with the effective margin`() {
        val contexts = ModelContexts.parse(
            """
            {
              "models": [
                {
                  "slug": "gpt-6-astra",
                  "context_window": 272000,
                  "max_context_window": 872000,
                  "effective_context_window_percent": 95
                },
                {
                  "slug": "gpt-5.5",
                  "context_window": 272000,
                  "max_context_window": 272000,
                  "effective_context_window_percent": 95
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(ModelContexts.Limits(272000, 872000, 258400, 828400), contexts["gpt-6-astra"])
        assertEquals(272000, contexts["gpt-5.5"]?.long)
    }

    @Test
    fun `ignores malformed model cache entries`() {
        assertTrue(ModelContexts.parse("not json").isEmpty())
        assertTrue(ModelContexts.parse("{\"models\":[{\"slug\":\"bad\"}]}").isEmpty())
    }
}
