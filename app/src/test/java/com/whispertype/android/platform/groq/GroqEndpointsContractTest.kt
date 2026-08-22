package com.whispertype.android.platform.groq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Groq endpoint/model constants. Rationale: the llama decommission
 * shipped green because nothing pinned these constants — a drifted URL or a
 * decommissioned model silently degrades every polish dial to raw ASR
 * insertion with no test failure. These assertions fail loudly instead.
 */
class GroqEndpointsContractTest {

    @Test
    fun `chat completions url is pinned to the verified endpoint`() {
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            GroqEndpoints.CHAT_COMPLETIONS_URL,
        )
    }

    @Test
    fun `chat completions url is https`() {
        assertTrue(
            GroqEndpoints.CHAT_COMPLETIONS_URL.startsWith("https://"),
            "chat completions must never dial plaintext http",
        )
    }

    @Test
    fun `chat models are non-blank and llama-free`() {
        listOf(GroqEndpoints.CHAT_MODEL, GroqEndpoints.CHAT_MODEL_FALLBACK).forEach { model ->
            assertTrue(model.isNotBlank(), "chat model must not be blank")
            assertFalse(
                model.contains("llama", ignoreCase = true),
                "llama deployments are decommissioned on Groq free tier: $model",
            )
        }
    }
}
