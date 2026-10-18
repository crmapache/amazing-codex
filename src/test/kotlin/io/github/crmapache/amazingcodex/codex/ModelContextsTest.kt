package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    /**
     * A named model the cache does not know has no window: another model's, handed to it as `model_context_window`,
     * would be a guess sent to Codex as a setting, and the meter would divide by it. Only an unresolved "default"
     * still falls back to the cache's first model.
     */
    @Test
    fun `a model the cache does not name has no window of another model`() {
        val contexts = mapOf(
            "gpt-6.1-sol" to ModelContexts.Limits(500000, 872000, 475000, 828400),
            "gpt-6-astra" to ModelContexts.Limits(272000, 872000, 258400, 828400),
        )

        assertEquals(475000, ModelContexts.limitsOf(contexts, "gpt-6.1-sol")?.standardEffective)
        assertEquals(258400, ModelContexts.limitsOf(contexts, "gpt-6-astra")?.standardEffective)
        assertNull(ModelContexts.limitsOf(contexts, "my-provider-model"))
        assertEquals(contexts.values.first(), ModelContexts.limitsOf(contexts, ""))
    }

    @Test
    fun `ignores malformed model cache entries`() {
        assertTrue(ModelContexts.parse("not json").isEmpty())
        assertTrue(ModelContexts.parse("{\"models\":[{\"slug\":\"bad\"}]}").isEmpty())
    }
}
