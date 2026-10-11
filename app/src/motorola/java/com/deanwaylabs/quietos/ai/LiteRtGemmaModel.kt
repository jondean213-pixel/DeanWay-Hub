package com.deanwaylabs.quietos.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LiteRtGemmaModel : LocalModel {
    @Volatile
    override var state: ModelState = ModelState.UNLOADED
        private set

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override suspend fun load(modelPath: String): LoadMetrics = withContext(Dispatchers.IO) {
        close()
        state = ModelState.LOADING
        val started = System.nanoTime()
        try {
            val created = Engine(EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(threadCount = 4),
                maxNumTokens = 1024
            ))
            created.initialize()

            val warmupStarted = System.nanoTime()
            val createdConversation = created.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        """You are QuietOS, a quiet on-device assistant. Answer directly and concisely. Never invent facts, experiences, actions, or memories.\n"""
                    ),
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 64,
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            )
            val warmupTimeMs = (System.nanoTime() - warmupStarted) / 1_000_000
            engine = created
            conversation = createdConversation
            state = ModelState.READY
            LoadMetrics(
                loadTimeMs = (System.nanoTime() - started) / 1_000_000,
                modelPath = modelPath,
                warmupTimeMs = warmupTimeMs
            )
        } catch (t: Throwable) {
            state = ModelState.ERROR
            throw t
        }
    }

    override suspend fun send(message: String): GenerationResult {
        val startedNs = System.nanoTime()

        GemmaMemory.answerKnownQuestion(message)?.let { known ->
            val finishedNs = System.nanoTime()
            return GenerationResult(
                text = known,
                metrics = GenerationMetrics(
                    timeToFirstChunkMs = 0,
                    totalTimeMs = (finishedNs - startedNs) / 1_000_000,
                    generationAfterFirstChunkMs = 0,
                    chunkCount = 0,
                    outputChars = known.length
                )
            )
        }
        val output = StringBuilder()
        var chunks = 0
        var firstChunkNs: Long? = null

        suspend fun collectFrom(activeConversation: Conversation, prompt: String) {
            withTimeout(180_000L) {
                activeConversation.sendMessageAsync(prompt).collect { chunk ->
                    if (firstChunkNs == null) firstChunkNs = System.nanoTime()
                    chunks++
                    output.append(chunk)
                }
            }
        }

        val firstConversation = checkNotNull(conversation) { "Local model conversation is not ready." }
        val memoryContext = GemmaMemory.relevantContext(message)
        val groundedPrompt = if (memoryContext.isBlank()) {
            message
        } else {
            memoryContext + "\nCurrent message:\n" + message
        }
        try {
            collectFrom(firstConversation, groundedPrompt)
        } catch (t: Throwable) {
            val overflow = t.message?.contains("Input token ids are too long", ignoreCase = true) == true
            if (!overflow) throw t

            firstConversation.close()
            val activeEngine = checkNotNull(engine) { "Local model engine is not ready." }
            val recovered = activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        """You are QuietOS, a quiet on-device assistant. Answer directly and concisely. Never invent facts, experiences, actions, or memories.\n"""
                    ),
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 64,
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            )
            conversation = recovered
            output.clear()
            chunks = 0
            firstChunkNs = null
            collectFrom(recovered, groundedPrompt)
        }

        if (chunks == 0) {
            firstConversation.close()
            val activeEngine = checkNotNull(engine) { "Local model engine is not ready." }
            val recovered = activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        """You are QuietOS, a quiet on-device assistant. Answer directly and concisely. Never invent facts, experiences, actions, or memories.\n"""
                    ),
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 64,
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            )
            conversation = recovered
            collectFrom(recovered, message)
        }

        // Do not send an automatic second user turn on the same LiteRT conversation.
        // LiteRT-LM owns the assistant turn produced by sendMessageAsync(); forcing a
        // continuation here can leave the native conversation history with invalid
        // user/assistant role ordering. Keep the first complete generation as the
        // authoritative response and let the next real user utterance be the next turn.

        val finishedNs = System.nanoTime()
        // Generated assistant text is output, not evidence. Never write it back into memory.
        if (chunks == 0) {
            state = ModelState.READY
            val fallback = "I hit a local generation error and reset my conversation. Please try that again."
            return GenerationResult(
                text = fallback,
                metrics = GenerationMetrics(
                    timeToFirstChunkMs = 0,
                    totalTimeMs = (finishedNs - startedNs) / 1_000_000,
                    generationAfterFirstChunkMs = 0,
                    chunkCount = 0,
                    outputChars = fallback.length
                )
            )
        }

        val firstNs = checkNotNull(firstChunkNs)
        val ttfc = (firstNs - startedNs) / 1_000_000
        val total = (finishedNs - startedNs) / 1_000_000
        return GenerationResult(
            text = output.toString(),
            metrics = GenerationMetrics(
                timeToFirstChunkMs = ttfc,
                totalTimeMs = total,
                generationAfterFirstChunkMs = (finishedNs - firstNs) / 1_000_000,
                chunkCount = chunks,
                outputChars = output.length
            )
        )
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        state = ModelState.UNLOADED
    }
}
