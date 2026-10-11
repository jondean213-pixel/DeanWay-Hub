package com.deanwaylabs.quietos.ai

/**
 * Motorola flavor: provides the real LiteRT Gemma model.
 * Phone B flavor has its own ModelProvider returning a no-op stub
 * (Phone B relays calls/texts — it doesn't run the assistant brain).
 */
object ModelProvider {
    fun create(): LocalModel = LiteRtGemmaModel()
}
