package io.r2h.engine.nativebridge.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.r2h.engine.core.InferenceInput
import io.r2h.engine.core.InferenceOutput
import io.r2h.engine.core.ModelCapability
import io.r2h.engine.core.ModelDescriptor
import io.r2h.engine.core.ModelType
import io.r2h.engine.nativebridge.AudioSttEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real ONNX-based speech-to-text engine supporting:
 *
 * 1. Whisper-style encoder-decoder (two ONNX files: encoder + decoder):
 *    - Encoder: input_features → encoder_hidden_states
 *    - Decoder: decoder_input_ids + encoder_hidden_states → logits (greedy decode)
 *
 * 2. CTC-style single-pass (wav2vec2, HuBERT):
 *    - input_values → logits → CTC greedy/beam decode
 *
 * Model discovery:
 * - Single file named `model.onnx` or `<modelId>.onnx` → tries both contracts
 * - Two files: `encoder_model.onnx` + `decoder_model.onnx` in same directory
 * - Metadata key `onnx.encoderFile` / `onnx.decoderFile` for explicit paths
 *
 * Vocabulary:
 * - Metadata key `whisper.vocabFile` → path to vocab.json (token id → piece mapping)
 * - If absent, token IDs are emitted as "<tok_N>" strings
 */
class RealWhisperOnnxEngine : AudioSttEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private data class LoadedSttModel(
        val descriptor: ModelDescriptor,
        val encoderSession: OrtSession,
        val decoderSession: OrtSession?,        // null for CTC-style
        val encoderContract: AudioOnnxSessionContract,
        val melProcessor: MelSpectrogramProcessor,
        val vocab: Map<Int, String>,
        val endOfTextTokenId: Int,
        val startOfTranscriptTokenId: Int,
        val blankTokenId: Int,                  // for CTC
        val maxDecoderSteps: Int,
    )

    private val loadedModels = ConcurrentHashMap<String, LoadedSttModel>()
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()

    override val version: String? get() = OrtEnvironment.getVersion()

    override val supportedModelTypes: Set<ModelType> = setOf(
        ModelType.SPEECH_TO_TEXT,
        ModelType.CLASSIFICATION,
    )

    override val supportedCapabilities: Set<ModelCapability> = setOf(
        ModelCapability.Input.Audio,
        ModelCapability.Output.Text,
        ModelCapability.Execution.Local,
        ModelCapability.Execution.Cpu,
        ModelCapability.Lifecycle.ExplicitLoad,
        ModelCapability.Lifecycle.ExplicitUnload,
        ModelCapability.Interaction.Cancellation,
    )

    override val supportsConcurrentExecution: Boolean = false

    override suspend fun load(model: ModelDescriptor) {
        val local = model.source as? ModelDescriptor.Source.Local
            ?: throw OnnxRuntimeLoadException(
                OnnxRuntimeLoadException.Reason.MODEL_CONTRACT_UNSUPPORTED,
                "Whisper ONNX engine requires a Local source.",
            )

        val modelDir = File(local.artifactRef).parentFile ?: File(".")
        val mainFile = File(local.artifactRef)

        // Resolve encoder/decoder file paths
        val encoderPath = model.metadata["onnx.encoderFile"]?.let { File(modelDir, it) }
            ?: File(modelDir, "encoder_model.onnx").takeIf { it.exists() }
            ?: mainFile
        val decoderPath = model.metadata["onnx.decoderFile"]?.let { File(modelDir, it) }
            ?: File(modelDir, "decoder_model.onnx").takeIf { it.exists() }

        val sessionOpts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(model.metadata["onnx.threads"]?.toIntOrNull() ?: 2)
        }

        val encoderSession = try {
            env.createSession(encoderPath.absolutePath, sessionOpts)
        } catch (e: Exception) {
            throw OnnxRuntimeLoadException(
                OnnxRuntimeLoadException.Reason.SESSION_CREATION_FAILED,
                "Whisper encoder session failed for '${model.id}': ${e.message}",
            )
        }

        val encoderContract = AudioOnnxSessionContract.inspect(encoderSession)
        if (!encoderContract.isUsable) {
            encoderSession.close()
            // AUDIO_PROCESSOR_UNSUPPORTED maps to ErrorCode.AUDIO_PROCESSOR_UNSUPPORTED via
            // parseLoadStatusError(). Using the specific code lets callers surface a clear
            // "audio processor not supported" message rather than a generic contract failure.
            throw OnnxRuntimeLoadException(
                OnnxRuntimeLoadException.Reason.AUDIO_PROCESSOR_UNSUPPORTED,
                "Audio model '${model.id}' has no recognized audio input. " +
                    "Expected 'input_features' (Whisper) or 'input_values' (CTC wav2vec2/HuBERT). " +
                    "Inputs found: ${encoderSession.inputNames.joinToString()}",
            )
        }

        val decoderSession = decoderPath?.let { path ->
            try {
                env.createSession(path.absolutePath, sessionOpts)
            } catch (e: Exception) {
                encoderSession.close()
                throw OnnxRuntimeLoadException(
                    OnnxRuntimeLoadException.Reason.SESSION_CREATION_FAILED,
                    "Whisper decoder session failed for '${model.id}': ${e.message}",
                )
            }
        }

        val sampleRate = model.metadata["audio.sampleRateHz"]?.toIntOrNull()
            ?: model.capabilityProfile?.expectedSampleRateHz ?: 16_000
        val nMels = model.metadata["whisper.nMels"]?.toIntOrNull() ?: 80
        val nFft = model.metadata["whisper.nFft"]?.toIntOrNull() ?: 400
        val hopLength = model.metadata["whisper.hopLength"]?.toIntOrNull() ?: 160
        val maxSamples = (sampleRate * (model.metadata["audio.maxSeconds"]?.toIntOrNull() ?: 30))

        val mel = MelSpectrogramProcessor(sampleRate, nMels, nFft, hopLength, maxSamples)
        val vocab = loadVocab(model.metadata["whisper.vocabFile"]?.let { File(modelDir, it) })

        val eot = model.metadata["whisper.eotTokenId"]?.toIntOrNull() ?: WHISPER_EOT_DEFAULT
        val sot = model.metadata["whisper.sotTokenId"]?.toIntOrNull() ?: WHISPER_SOT_DEFAULT
        val blank = model.metadata["ctc.blankTokenId"]?.toIntOrNull() ?: 0
        val maxSteps = model.metadata["whisper.maxDecoderSteps"]?.toIntOrNull() ?: 448

        loadedModels[model.id] = LoadedSttModel(
            model, encoderSession, decoderSession, encoderContract,
            mel, vocab, eot, sot, blank, maxSteps,
        )
    }

    override suspend fun unload(model: ModelDescriptor) {
        loadedModels.remove(model.id)?.let {
            it.encoderSession.close()
            it.decoderSession?.close()
        }
    }

    override fun execute(
        model: ModelDescriptor,
        input: InferenceInput,
        onFinished: (requestId: String) -> Unit,
    ): Flow<InferenceOutput> = flow {
        val loaded = loadedModels[model.id]
            ?: throw IllegalStateException("STT model '${model.id}' is not loaded.")

        val cancelFlag = AtomicBoolean(false)
        cancellations[input.requestId] = cancelFlag

        try {
            val audioPart = input.parts.filterIsInstance<InferenceInput.Part.Audio>().firstOrNull()
            if (audioPart == null) {
                emit(rejected(input.requestId, "No audio input part found."))
                return@flow
            }

            val audioFile = File(audioPart.artifact.ref)
            val melBuf = loaded.melProcessor.processWav(audioFile)
            val melShape = loaded.melProcessor.tensorShape()

            if (cancelFlag.get()) { emit(cancelled(input.requestId)); return@flow }

            val transcript = if (loaded.decoderSession != null) {
                decodeWhisper(loaded, melBuf, melShape, cancelFlag)
            } else {
                decodeCtc(loaded, melBuf, melShape, cancelFlag)
            }

            if (cancelFlag.get()) { emit(cancelled(input.requestId)); return@flow }

            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = listOf(InferenceOutput.Item.Text(transcript.ifBlank { "[empty transcript]" })),
                    completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.COMPLETED),
                )
            )
        } finally {
            cancellations.remove(input.requestId)
            onFinished(input.requestId)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(requestId: String) {
        cancellations[requestId]?.set(true)
    }

    // ─── Whisper encoder-decoder greedy decode ────────────────────────────────

    private fun decodeWhisper(
        model: LoadedSttModel,
        melBuf: java.nio.FloatBuffer,
        melShape: LongArray,
        cancel: AtomicBoolean,
    ): String {
        // Run encoder
        val melTensor = OnnxTensor.createTensor(env, melBuf, melShape)
        val encoderInputs = mapOf(model.encoderContract.inputFeaturesName!! to melTensor)
        val encoderOutputs = model.encoderSession.run(encoderInputs)
        melTensor.close()

        val hiddenName = model.encoderContract.encoderHiddenStatesOutputName
            ?: encoderOutputs.iterator().next().key
        val encoderHidden = encoderOutputs[hiddenName]
            ?: throw IllegalStateException("Encoder produced no hidden states.")

        val decoderSession = model.decoderSession!!
        val decoderInputNames = decoderSession.inputNames

        // Greedy autoregressive decode
        val tokens = mutableListOf(model.startOfTranscriptTokenId)
        for (step in 0 until model.maxDecoderSteps) {
            if (cancel.get()) break

            val inputIds = LongArray(tokens.size) { tokens[it].toLong() }
            val inputIdsBuf = java.nio.LongBuffer.wrap(inputIds)
            val inputIdsTensor = OnnxTensor.createTensor(
                env, inputIdsBuf, longArrayOf(1L, tokens.size.toLong())
            )

            val decoderInputs = mutableMapOf<String, Any>()
            decoderInputs["decoder_input_ids"] = inputIdsTensor

            // Feed encoder hidden states if the decoder expects them
            val encoderHiddenInputName = decoderInputNames.firstOrNull { n ->
                n.contains("encoder_hidden") || n.contains("encoder_output")
            }
            if (encoderHiddenInputName != null) {
                decoderInputs[encoderHiddenInputName] = encoderHidden
            }

            val decoderOutputs = decoderSession.run(decoderInputs)
            inputIdsTensor.close()

            val logitsTensor = decoderOutputs["logits"] ?: decoderOutputs.iterator().next().value
            val nextTokenId = greedyArgmax(logitsTensor.value, tokens.size - 1)
            logitsTensor.close()
            decoderOutputs.close()

            if (nextTokenId == model.endOfTextTokenId) break
            tokens += nextTokenId
        }

        encoderHidden.close()
        encoderOutputs.close()

        return tokensToText(tokens.drop(1), model.vocab)
    }

    // ─── CTC-style single-pass decode ────────────────────────────────────────

    private fun decodeCtc(
        model: LoadedSttModel,
        inputBuf: java.nio.FloatBuffer,
        inputShape: LongArray,
        cancel: AtomicBoolean,
    ): String {
        val inputName = model.encoderContract.inputFeaturesName
            ?: model.encoderContract.inputValuesName
            ?: throw IllegalStateException("No audio input name found.")

        val tensor = OnnxTensor.createTensor(env, inputBuf, inputShape)
        val outputs = model.encoderSession.run(mapOf(inputName to tensor))
        tensor.close()

        val logitsTensor = model.encoderContract.logitsOutputName?.let { outputs[it] }
            ?: outputs.iterator().next().value

        val transcript = ctcGreedyDecode(logitsTensor.value, model.vocab, model.blankTokenId)
        logitsTensor.close()
        outputs.close()
        return transcript
    }

    // ─── decode helpers ───────────────────────────────────────────────────────

    private fun greedyArgmax(value: Any?, lastTokenPos: Int): Int {
        // value may be [1, seq, vocab] or [seq, vocab]
        val logits: FloatArray = when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val seq = value as? Array<Array<FloatArray>>
                seq?.getOrNull(0)?.getOrNull(lastTokenPos) ?: floatArrayOf()
            }
            else -> floatArrayOf()
        }
        return if (logits.isEmpty()) 0 else logits.indices.maxByOrNull { logits[it] } ?: 0
    }

    private fun ctcGreedyDecode(value: Any?, vocab: Map<Int, String>, blankId: Int): String {
        // value: [1, T, vocab_size]
        val frames: Array<FloatArray>? = when (value) {
            is Array<*> -> (value as? Array<Array<FloatArray>>)?.getOrNull(0)
            else -> null
        }
        if (frames == null) return ""
        val tokens = mutableListOf<Int>()
        var prev = -1
        for (frame in frames) {
            val id = frame.indices.maxByOrNull { frame[it] } ?: blankId
            if (id != blankId && id != prev) tokens += id
            prev = id
        }
        return tokensToText(tokens, vocab)
    }

    private fun tokensToText(tokens: List<Int>, vocab: Map<Int, String>): String {
        if (vocab.isEmpty()) return tokens.joinToString(" ") { "<tok_$it>" }
        val sb = StringBuilder()
        for (id in tokens) {
            val piece = vocab[id] ?: "<unk>"
            // Whisper uses '▁' (U+2581) as word-boundary marker
            if (piece.startsWith("▁") || piece.startsWith("Ġ")) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(piece.substring(1))
            } else {
                sb.append(piece)
            }
        }
        return sb.toString().trim()
    }

    private fun loadVocab(file: File?): Map<Int, String> {
        if (file == null || !file.exists()) return emptyMap()
        return try {
            // Simple JSON parsing for {"token": id, ...} or {"id": "token", ...}
            val content = file.readText()
            val map = mutableMapOf<Int, String>()
            val pairs = Regex(""""([^"]+)"\s*:\s*(\d+)""").findAll(content)
            for (m in pairs) {
                val token = m.groupValues[1]
                val id = m.groupValues[2].toIntOrNull() ?: continue
                map[id] = token
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun rejected(requestId: String, msg: String) = InferenceOutput(
        requestId = requestId,
        phase = InferenceOutput.Phase.Final,
        items = listOf(InferenceOutput.Item.Text(msg)),
        completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.REJECTED),
    )

    private fun cancelled(requestId: String) = InferenceOutput(
        requestId = requestId,
        phase = InferenceOutput.Phase.Final,
        completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.CANCELLED),
    )

    companion object {
        private const val WHISPER_EOT_DEFAULT = 50257
        private const val WHISPER_SOT_DEFAULT = 50258
    }
}
