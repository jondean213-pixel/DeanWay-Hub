package com.deanwaylabs.quietos.ai

/**
 * Phone B flavor: no AI model on this device.
 *
 * Phone B's job is taking calls/texts, running SPAM SHIELD, and
 * relaying to the Motorola — it never runs the assistant brain.
 * This stub keeps MainActivity compiling without the 21MB
 * 64-bit LiteRT native library, so the APK installs on 32-bit phones.
 */
private class NoOpModel : LocalModel {
    override val state: ModelState = ModelState.UNLOADED
    override suspend fun load(modelPath: String): LoadMetrics =
        throw UnsupportedOperationException("No AI model on Phone B")
    override suspend fun send(message: String): GenerationResult =
        throw UnsupportedOperationException("No AI model on Phone B")
    override fun close() {}
}

object ModelProvider {
    fun create(): LocalModel = NoOpModel()
}
