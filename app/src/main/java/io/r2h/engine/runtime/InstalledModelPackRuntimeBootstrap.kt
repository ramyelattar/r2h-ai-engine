package io.r2h.engine.runtime

import android.content.Context
import android.util.Log
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.ModelValidationState
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.core.DefaultRuntimeRegistry
import io.r2h.engine.core.JniTextBackendRuntime
import io.r2h.engine.core.LlamaCppEmbeddingBackendRuntime
import io.r2h.engine.core.ModelCapability
import io.r2h.engine.core.ModelCatalogRecord
import io.r2h.engine.core.ModelDescriptor
import io.r2h.engine.core.ModelType
import io.r2h.engine.core.Modality
import io.r2h.engine.core.Qwen2VlMtmdBackendRuntime
import io.r2h.engine.core.RegistrationPolicy
import io.r2h.engine.core.SherpaOnnxAudioTaggingBackendRuntime
import io.r2h.engine.core.SherpaOnnxTtsBackendRuntime
import io.r2h.engine.core.TaskType
import io.r2h.engine.core.VideoYoloObjectDetectionBackendRuntime
import io.r2h.engine.core.WhisperCppSttBackendRuntime
import io.r2h.engine.core.YoloOnnxDetectionBackendRuntime
import io.r2h.engine.model.ModelPackExtractionResult
import io.r2h.engine.model.ModelPackExtractor
import io.r2h.engine.model.ModelPackManifestReader
import io.r2h.engine.model.ModelPackVerificationResult
import io.r2h.engine.model.ModelPackVerifier
import io.r2h.engine.model.QWEN_MMPROJ_ARTIFACT_PATH
import io.r2h.engine.model.QWEN_TEXT_GGUF_ARTIFACT_PATH
import io.r2h.engine.model.YOLO_ONNX_ARTIFACT_PATH
import kotlinx.coroutines.runBlocking
import java.io.File

private const val TAG = "InstalledModelPackBootstrap"
private const val QWEN_TEXT_MODEL_ID = "qwen2-vl-2b-instruct-q4-k-m"
private const val QWEN2VL_MTMD_MODEL_ID = "qwen2vl-mtmd-image-text"
private const val YOLO_ONNX_MODEL_ID = "yolo11n-detection-onnx"
private const val VIDEO_YOLO_MODEL_ID = "video-yolo11n-object-detection"
private const val TTS_MODEL_ID = "piper-tts-ar-en"
private const val TTS_ASSET_ROOT = "model-pack/models/tts/piper/voices"
private const val TTS_ENGLISH_VOICE_ID = "vits-piper-en_US-lessac-low-int8"
private const val TTS_ARABIC_VOICE_ID = "vits-piper-ar_JO-kareem-medium-int8"
private const val STT_MODEL_ID = "whisper-cpp-ggml-tiny"
private const val STT_ASSET_PATH = "model-pack/models/stt/whisper.cpp/ggml-tiny.bin"
private const val AUDIO_TAGGING_MODEL_ID = "sherpa-onnx-ced-base-audio-tagging-int8"
private const val AUDIO_TAGGING_ASSET_ROOT =
    "model-pack/models/audio/ced-base/sherpa-onnx-ced-base-audio-tagging-2024-04-19"
private const val EMBEDDING_MODEL_ID = "qwen3-embedding-0.6b-q8-gguf"
private const val EMBEDDING_ASSET_PATH = "model-pack/models/embeddings/qwen3/Qwen3-Embedding-0.6B-Q8_0.gguf"

class InstalledModelPackRuntimeBootstrap(
    context: Context,
    private val runtimeRegistry: DefaultRuntimeRegistry,
    private val manifestReader: ModelPackManifestReader = ModelPackManifestReader(context.applicationContext),
    private val extractor: ModelPackExtractor = ModelPackExtractor(context.applicationContext),
    private val verifier: ModelPackVerifier = ModelPackVerifier(),
) {
    private val appContext = context.applicationContext
    @Volatile private var catalogRecords: List<ModelCatalogRecord> = emptyList()
    @Volatile private var descriptors: Map<String, ModelDescriptor> = emptyMap()

    fun catalog(): List<ModelCatalogRecord> = catalogRecords

    fun resolveDescriptor(modelId: String): ModelDescriptor? = descriptors[modelId]

    fun bootstrapQwenText(): ModelDescriptor? {
        return try {
            val manifest = manifestReader.read()
            val artifact = manifest.findArtifact(QWEN_TEXT_GGUF_ARTIFACT_PATH)
            if (artifact == null) {
                publishInvalidCatalog(
                    descriptor = qwenDescriptor(sizeBytes = 0L),
                    error = EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.MODEL_NOT_FOUND,
                        "Qwen text artifact is missing from packaged model-pack manifest.",
                    ),
                )
                return null
            }

            val descriptor = qwenDescriptor(sizeBytes = artifact.sizeBytes)
            val extraction = extractor.extractSelected(manifest, listOf(QWEN_TEXT_GGUF_ARTIFACT_PATH))
            if (extraction !is ModelPackExtractionResult.Success) {
                val reason = (extraction as ModelPackExtractionResult.Failed).reason
                publishInvalidCatalog(
                    descriptor = descriptor,
                    error = EngineError(ErrorStage.MODEL_VALIDATION, ErrorCode.MODEL_NOT_LOADABLE, reason),
                )
                logRuntimeFailure(reason)
                return null
            }

            val extracted = extractor.extractedFileFor(QWEN_TEXT_GGUF_ARTIFACT_PATH)
            val verification = verifier.verify(extracted, artifact)
            if (verification !is ModelPackVerificationResult.Verified) {
                publishInvalidCatalog(
                    descriptor = descriptor,
                    error = EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.MODEL_NOT_LOADABLE,
                        verification.reason,
                    ),
                )
                logRuntimeFailure(verification.reason)
                return null
            }

            val readyDescriptor = qwenDescriptor(sizeBytes = extracted.length())
            val catalog = mutableListOf<ModelCatalogRecord>()
            val descriptorMap = mutableMapOf(readyDescriptor.id to readyDescriptor)
            val qwenRecord = ModelCatalogRecord(
                descriptor = readyDescriptor,
                fileSizeBytes = extracted.length(),
                installed = true,
                validationState = ModelValidationState.VALID,
            )
            catalog += qwenRecord

            bootstrapQwen2VlMtmd(manifest, extracted)?.let { qwenMtmd ->
                descriptorMap[qwenMtmd.descriptor.id] = qwenMtmd.descriptor
                catalog += qwenMtmd.record
            }

            bootstrapYoloOnnx(manifest)?.let { yolo ->
                descriptorMap[yolo.descriptor.id] = yolo.descriptor
                catalog += yolo.record
                bootstrapVideoYoloOnnx(yolo.descriptor)?.let { videoYolo ->
                    descriptorMap[videoYolo.descriptor.id] = videoYolo.descriptor
                    catalog += videoYolo.record
                }
            }
            bootstrapTtsPiper()?.let { tts ->
                descriptorMap[tts.descriptor.id] = tts.descriptor
                catalog += tts.record
            }
            bootstrapWhisperCppStt()?.let { stt ->
                descriptorMap[stt.descriptor.id] = stt.descriptor
                catalog += stt.record
            }
            bootstrapAudioTaggingCed()?.let { audio ->
                descriptorMap[audio.descriptor.id] = audio.descriptor
                catalog += audio.record
            }
            bootstrapQwen3Embedding()?.let { embedding ->
                descriptorMap[embedding.descriptor.id] = embedding.descriptor
                catalog += embedding.record
            }

            runtimeRegistry.register(JniTextBackendRuntime(), RegistrationPolicy.REPLACE_ON_DUPLICATE)
            descriptors = descriptorMap
            catalogRecords = catalog
            logRuntimeCompleted()
            readyDescriptor
        } catch (t: Throwable) {
            logRuntimeFailure(t)
            null
        }
    }

    private fun publishInvalidCatalog(descriptor: ModelDescriptor, error: EngineError) {
        descriptors = mapOf(descriptor.id to descriptor)
        catalogRecords = listOf(
            ModelCatalogRecord(
                descriptor = descriptor,
                fileSizeBytes = 0L,
                installed = false,
                validationState = ModelValidationState.INVALID,
                validationError = error,
            ),
        )
    }

    private fun qwenDescriptor(sizeBytes: Long): ModelDescriptor {
        val extracted = extractor.extractedFileFor(QWEN_TEXT_GGUF_ARTIFACT_PATH)
        return ModelDescriptor(
            id = QWEN_TEXT_MODEL_ID,
            displayName = "Qwen2-VL 2B Instruct Q4_K_M",
            version = "local-model-pack",
            modelType = ModelType.GGUF,
            capabilities = setOf(
                ModelCapability.TEXT_GENERATION,
                ModelCapability.Input.Text,
                ModelCapability.Output.Text,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
                ModelCapability.Interaction.Cancellation,
                ModelCapability.Interaction.Streaming,
            ),
            backendKey = "llama-cpp",
            modality = Modality.TEXT,
            supportedTaskTypes = setOf(
                TaskType.CHAT,
                TaskType.TEXT_GENERATION,
                TaskType.SUMMARIZATION,
                TaskType.REWRITING,
                TaskType.EXTRACTION,
                TaskType.QUESTION_ANSWERING,
            ),
            source = ModelDescriptor.Source.Local(
                artifactRef = extracted.absolutePath,
                format = "gguf",
                sizeBytes = sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxContextWindow = 512),
            metadata = mapOf(
                "modelPackArtifact" to QWEN_TEXT_GGUF_ARTIFACT_PATH,
                "bootstrapSource" to "APK_ASSET_MODEL_PACK",
            ),
        )
    }

    private fun bootstrapQwen2VlMtmd(
        manifest: io.r2h.engine.model.ModelPackManifest,
        qwenModelFile: File,
    ): BootstrappedModel? {
        return try {
            val mmprojArtifact = manifest.findArtifact(QWEN_MMPROJ_ARTIFACT_PATH)
            if (mmprojArtifact == null) {
                logRuntimeFailure(QWEN_MMPROJ_ARTIFACT_PATH)
                return null
            }
            val extraction = extractor.extractSelected(manifest, listOf(QWEN_MMPROJ_ARTIFACT_PATH))
            if (extraction !is ModelPackExtractionResult.Success) {
                val reason = (extraction as ModelPackExtractionResult.Failed).reason
                logRuntimeFailure(reason)
                return null
            }
            val mmprojFile = extractor.extractedFileFor(QWEN_MMPROJ_ARTIFACT_PATH)
            val verification = verifier.verify(mmprojFile, mmprojArtifact)
            if (verification !is ModelPackVerificationResult.Verified) {
                logRuntimeFailure(verification.reason)
                return null
            }

            val descriptor = qwen2VlMtmdDescriptor(
                modelFile = qwenModelFile,
                mmprojFile = mmprojFile,
            )
            val runtime = Qwen2VlMtmdBackendRuntime()
            runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
            val loadResult = runBlocking { runtime.loadModel(descriptor) }
            if (loadResult is io.r2h.engine.core.LoadResult.Failure) {
                logRuntimeFailure(loadResult.message)
                return null
            }
            logRuntimeCompleted()
            BootstrappedModel(
                descriptor = descriptor,
                record = ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = qwenModelFile.length() + mmprojFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            )
        } catch (t: Throwable) {
            logRuntimeFailure(t)
            null
        }
    }

    private fun qwen2VlMtmdDescriptor(
        modelFile: File,
        mmprojFile: File,
    ): ModelDescriptor =
        ModelDescriptor(
            id = QWEN2VL_MTMD_MODEL_ID,
            displayName = "Qwen2VL image+text via llama.cpp mtmd",
            version = "local-model-pack",
            modelType = ModelType.VISION,
            capabilities = setOf(
                ModelCapability.Input.Image,
                ModelCapability.Input.Text,
                ModelCapability.Input.Video,
                ModelCapability.Output.Text,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = "qwen2vl-mtmd",
            modality = Modality.IMAGE,
            supportedTaskTypes = setOf(
                TaskType.IMAGE_TEXT_MULTIMODAL,
                TaskType.IMAGE_UNDERSTANDING,
                TaskType.OCR,
                TaskType.VIDEO_FULL_ANALYSIS,
            ),
            source = ModelDescriptor.Source.Local(
                artifactRef = modelFile.absolutePath,
                format = "gguf",
                sizeBytes = modelFile.length(),
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxContextWindow = 4096, maxBatchSize = 1),
            metadata = mapOf(
                "modelPackArtifact" to QWEN_TEXT_GGUF_ARTIFACT_PATH,
                "mmprojPackArtifact" to QWEN_MMPROJ_ARTIFACT_PATH,
                "mmprojPath" to mmprojFile.absolutePath,
                "runtime" to "llama.cpp mtmd / Qwen2VL",
                "capability" to "IMAGE_TEXT_MULTIMODAL",
            ),
        )

    private fun bootstrapTtsPiper(): BootstrappedModel? {
        return try {
            val voiceRoot = File(appContext.filesDir, TTS_ASSET_ROOT)
            copyAssetTree(TTS_ASSET_ROOT, voiceRoot)
            val descriptor = ttsDescriptor(sizeBytes = voiceRoot.walkTopDown().filter { it.isFile }.map { it.length() }.sum())
            val runtime = SherpaOnnxTtsBackendRuntime(
                outputDirectory = File(appContext.filesDir, "generated/tts"),
            )
            runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
            val loadResult = runBlocking { runtime.loadModel(descriptor) }
            if (loadResult is io.r2h.engine.core.LoadResult.Failure) {
                logRuntimeFailure(loadResult.message)
            } else {
                logRuntimeCompleted()
            }
            BootstrappedModel(
                descriptor = descriptor,
                record = ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = descriptor.source.let { (it as? ModelDescriptor.Source.Local)?.sizeBytes ?: 0L },
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            )
        } catch (t: Throwable) {
            logRuntimeFailure(t)
            null
        }
    }

    private fun ttsDescriptor(sizeBytes: Long): ModelDescriptor {
        val voiceRoot = File(appContext.filesDir, TTS_ASSET_ROOT)
        return ModelDescriptor(
            id = TTS_MODEL_ID,
            displayName = "Sherpa-ONNX Piper Arabic/English TTS",
            version = "local-model-pack",
            modelType = ModelType.TEXT_TO_SPEECH,
            capabilities = setOf(
                ModelCapability.Input.Text,
                ModelCapability.Output.Audio,
                ModelCapability.Output.Text,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = "sherpa-onnx-tts",
            modality = Modality.AUDIO,
            supportedTaskTypes = setOf(TaskType.TEXT_TO_SPEECH),
            source = ModelDescriptor.Source.Local(
                artifactRef = voiceRoot.absolutePath,
                format = "sherpa-onnx-piper-dir",
                sizeBytes = sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxBatchSize = 1),
            metadata = mapOf(
                "modelPackAssetRoot" to TTS_ASSET_ROOT,
                "englishVoiceId" to TTS_ENGLISH_VOICE_ID,
                "arabicVoiceId" to TTS_ARABIC_VOICE_ID,
                "runtime" to "Sherpa-ONNX Piper",
            ),
        )
    }

    private fun bootstrapWhisperCppStt(): BootstrappedModel? {
        return try {
            val modelFile = File(appContext.filesDir, STT_ASSET_PATH)
            copyAssetTree(STT_ASSET_PATH, modelFile)
            val descriptor = sttDescriptor(sizeBytes = modelFile.length())
            val runtime = WhisperCppSttBackendRuntime()
            runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
            val loadResult = runBlocking { runtime.loadModel(descriptor) }
            if (loadResult is io.r2h.engine.core.LoadResult.Failure) {
                logRuntimeFailure(loadResult.message)
            } else {
                logRuntimeCompleted()
            }
            BootstrappedModel(
                descriptor = descriptor,
                record = ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            )
        } catch (t: Throwable) {
            logRuntimeFailure(t)
            null
        }
    }

    private fun sttDescriptor(sizeBytes: Long): ModelDescriptor {
        val modelFile = File(appContext.filesDir, STT_ASSET_PATH)
        return ModelDescriptor(
            id = STT_MODEL_ID,
            displayName = "whisper.cpp GGML Tiny STT",
            version = "local-model-pack",
            modelType = ModelType.SPEECH_TO_TEXT,
            capabilities = setOf(
                ModelCapability.Input.Audio,
                ModelCapability.Output.Text,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = "whisper-cpp",
            modality = Modality.AUDIO,
            supportedTaskTypes = setOf(TaskType.SPEECH_TO_TEXT),
            source = ModelDescriptor.Source.Local(
                artifactRef = modelFile.absolutePath,
                format = "ggml-whisper",
                sizeBytes = sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxBatchSize = 1),
            metadata = mapOf(
                "modelPackAsset" to STT_ASSET_PATH,
                "runtime" to "whisper.cpp",
                "audioRequirements" to "16kHz mono PCM WAV",
            ),
        )
    }

    private fun bootstrapAudioTaggingCed(): BootstrappedModel? {
        return try {
            val modelRoot = File(appContext.filesDir, AUDIO_TAGGING_ASSET_ROOT)
            copyAssetTree(AUDIO_TAGGING_ASSET_ROOT, modelRoot)
            val modelFile = File(modelRoot, "model.int8.onnx")
            val labelsFile = File(modelRoot, "class_labels_indices.csv")
            val descriptor = audioTaggingDescriptor(
                sizeBytes = modelFile.length(),
                modelFile = modelFile,
                labelsFile = labelsFile,
            )
            val runtime = SherpaOnnxAudioTaggingBackendRuntime()
            runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
            val loadResult = runBlocking { runtime.loadModel(descriptor) }
            if (loadResult is io.r2h.engine.core.LoadResult.Failure) {
                logRuntimeFailure(loadResult.message)
            } else {
                logRuntimeCompleted()
            }
            BootstrappedModel(
                descriptor = descriptor,
                record = ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            )
        } catch (t: Throwable) {
            logRuntimeFailure(t)
            null
        }
    }

    private fun audioTaggingDescriptor(sizeBytes: Long, modelFile: File, labelsFile: File): ModelDescriptor {
        return ModelDescriptor(
            id = AUDIO_TAGGING_MODEL_ID,
            displayName = "Sherpa-ONNX CED-base Audio Tagging INT8",
            version = "2024-04-19",
            modelType = ModelType.CLASSIFICATION,
            capabilities = setOf(
                ModelCapability.Input.Audio,
                ModelCapability.Output.Text,
                ModelCapability.Output.Labels,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = "sherpa-onnx-audio-tagging",
            modality = Modality.AUDIO,
            supportedTaskTypes = setOf(TaskType.AUDIO_ANALYSIS, TaskType.AUDIO_TAGGING, TaskType.CLASSIFICATION),
            source = ModelDescriptor.Source.Local(
                artifactRef = modelFile.absolutePath,
                format = "onnx",
                sizeBytes = sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxBatchSize = 1),
            metadata = mapOf(
                "modelPackAssetRoot" to AUDIO_TAGGING_ASSET_ROOT,
                "labelsFile" to labelsFile.absolutePath,
                "proofWavAsset" to "$AUDIO_TAGGING_ASSET_ROOT/test_wavs/1.wav",
                "runtime" to "Sherpa-ONNX CED audio tagging",
                "topK" to "5",
            ),
        )
    }

    private fun bootstrapQwen3Embedding(): BootstrappedModel? {
        return try {
            val modelFile = File(appContext.filesDir, EMBEDDING_ASSET_PATH)
            copyAssetTree(EMBEDDING_ASSET_PATH, modelFile)
            val descriptor = embeddingDescriptor(sizeBytes = modelFile.length())
            val runtime = LlamaCppEmbeddingBackendRuntime()
            runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
            val loadResult = runBlocking { runtime.loadModel(descriptor) }
            if (loadResult is io.r2h.engine.core.LoadResult.Failure) {
                logRuntimeFailure(loadResult.message)
            } else {
                logRuntimeCompleted()
            }
            BootstrappedModel(
                descriptor = descriptor,
                record = ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            )
        } catch (t: Throwable) {
            logRuntimeFailure(t)
            null
        }
    }

    private fun embeddingDescriptor(sizeBytes: Long): ModelDescriptor {
        val modelFile = File(appContext.filesDir, EMBEDDING_ASSET_PATH)
        return ModelDescriptor(
            id = EMBEDDING_MODEL_ID,
            displayName = "Qwen3 Embedding 0.6B Q8 GGUF",
            version = "local-converted-q8_0",
            modelType = ModelType.EMBEDDING,
            capabilities = setOf(
                ModelCapability.Input.Text,
                ModelCapability.Output.Embedding,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = "llama-cpp-embedding",
            modality = Modality.TEXT,
            supportedTaskTypes = setOf(TaskType.EMBEDDING),
            source = ModelDescriptor.Source.Local(
                artifactRef = modelFile.absolutePath,
                format = "gguf",
                sizeBytes = sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxContextWindow = 512, maxBatchSize = 1),
            metadata = mapOf(
                "modelPackAsset" to EMBEDDING_ASSET_PATH,
                "runtime" to "llama.cpp embeddings",
                "pooling" to "lasttoken",
                "normalization" to "l2",
                "dimension" to "1024",
                "conversionScript" to "tools/ai-engine/convert-qwen3-embedding-to-gguf.ps1",
            ),
        )
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        target.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }

    private fun bootstrapYoloOnnx(manifest: io.r2h.engine.model.ModelPackManifest): BootstrappedModel? {
        val artifact = manifest.findArtifact(YOLO_ONNX_ARTIFACT_PATH)
        if (artifact == null) {
            Log.w(TAG, "YOLO ONNX artifact is missing from packaged model-pack manifest.")
            return null
        }

        val extraction = extractor.extractSelected(manifest, listOf(YOLO_ONNX_ARTIFACT_PATH))
        if (extraction !is ModelPackExtractionResult.Success) {
            val reason = (extraction as ModelPackExtractionResult.Failed).reason
            logRuntimeFailure(reason)
            return BootstrappedModel(
                descriptor = yoloOnnxDescriptor(sizeBytes = artifact.sizeBytes),
                record = ModelCatalogRecord(
                    descriptor = yoloOnnxDescriptor(sizeBytes = artifact.sizeBytes),
                    fileSizeBytes = 0L,
                    installed = false,
                    validationState = ModelValidationState.INVALID,
                    validationError = EngineError(ErrorStage.MODEL_VALIDATION, ErrorCode.MODEL_NOT_LOADABLE, reason),
                ),
            )
        }

        val extracted = extractor.extractedFileFor(YOLO_ONNX_ARTIFACT_PATH)
        val verification = verifier.verify(extracted, artifact)
        if (verification !is ModelPackVerificationResult.Verified) {
            logRuntimeFailure(verification.reason)
            return BootstrappedModel(
                descriptor = yoloOnnxDescriptor(sizeBytes = artifact.sizeBytes),
                record = ModelCatalogRecord(
                    descriptor = yoloOnnxDescriptor(sizeBytes = artifact.sizeBytes),
                    fileSizeBytes = 0L,
                    installed = false,
                    validationState = ModelValidationState.INVALID,
                    validationError = EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.MODEL_NOT_LOADABLE,
                        verification.reason,
                    ),
                ),
            )
        }

        val descriptor = yoloOnnxDescriptor(sizeBytes = extracted.length())
        val runtime = YoloOnnxDetectionBackendRuntime()
        runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
        val loadResult = runBlocking { runtime.loadModel(descriptor) }
        if (loadResult is io.r2h.engine.core.LoadResult.Failure) {
            logRuntimeFailure(loadResult.message)
        } else {
            logRuntimeCompleted()
        }

        return BootstrappedModel(
            descriptor = descriptor,
            record = ModelCatalogRecord(
                descriptor = descriptor,
                fileSizeBytes = extracted.length(),
                installed = true,
                validationState = ModelValidationState.VALID,
            ),
        )
    }

    private fun yoloOnnxDescriptor(sizeBytes: Long): ModelDescriptor {
        val extracted = extractor.extractedFileFor(YOLO_ONNX_ARTIFACT_PATH)
        return ModelDescriptor(
            id = YOLO_ONNX_MODEL_ID,
            displayName = "YOLO11n Object Detection ONNX",
            version = "local-model-pack",
            modelType = ModelType.DETECTION,
            capabilities = setOf(
                ModelCapability.Input.Image,
                ModelCapability.Output.Labels,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = "yolo-onnx",
            modality = Modality.IMAGE,
            supportedTaskTypes = setOf(TaskType.OBJECT_DETECTION),
            source = ModelDescriptor.Source.Local(
                artifactRef = extracted.absolutePath,
                format = "onnx",
                sizeBytes = sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxBatchSize = 1),
            metadata = mapOf(
                "modelPackArtifact" to YOLO_ONNX_ARTIFACT_PATH,
                "bootstrapSource" to "APK_ASSET_MODEL_PACK",
                "capability" to "IMAGE_OBJECT_DETECTION",
            ),
        )
    }

    private fun bootstrapVideoYoloOnnx(yoloDescriptor: ModelDescriptor): BootstrappedModel? {
        return try {
            val descriptor = videoYoloDescriptor(yoloDescriptor)
            val runtime = VideoYoloObjectDetectionBackendRuntime()
            runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
            val loadResult = runBlocking { runtime.loadModel(descriptor) }
            if (loadResult is io.r2h.engine.core.LoadResult.Failure) {
                logRuntimeFailure(loadResult.message)
            } else {
                logRuntimeCompleted()
            }
            BootstrappedModel(
                descriptor = descriptor,
                record = ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = (descriptor.source as ModelDescriptor.Source.Local).sizeBytes,
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            )
        } catch (t: Throwable) {
            logRuntimeFailure(t)
            null
        }
    }

    private fun videoYoloDescriptor(yoloDescriptor: ModelDescriptor): ModelDescriptor {
        val source = yoloDescriptor.source as ModelDescriptor.Source.Local
        return ModelDescriptor(
            id = VIDEO_YOLO_MODEL_ID,
            displayName = "Video Object Detection via YOLO11n",
            version = "local-model-pack",
            modelType = ModelType.DETECTION,
            capabilities = setOf(
                ModelCapability.Input.Video,
                ModelCapability.Output.Text,
                ModelCapability.Output.Labels,
                ModelCapability.Execution.Local,
                ModelCapability.Execution.Cpu,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = "video-yolo-onnx",
            modality = Modality.IMAGE,
            supportedTaskTypes = setOf(TaskType.OBJECT_DETECTION),
            source = ModelDescriptor.Source.Local(
                artifactRef = source.artifactRef,
                format = "onnx",
                sizeBytes = source.sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            constraints = ModelDescriptor.Constraints(maxBatchSize = 1),
            metadata = mapOf(
                "delegateModelId" to yoloDescriptor.id,
                "runtime" to "Video frame extraction + YOLO ONNX",
                "capability" to "VIDEO_OBJECT_DETECTION",
            ),
        )
    }

    private data class BootstrappedModel(
        val descriptor: ModelDescriptor,
        val record: ModelCatalogRecord,
    )
}

private fun logRuntimeFailure(detail: CharSequence) {
    Log.e(
        TAG,
        PrivacySafeDiagnostics.contentEvent(
            operation = DiagnosticOperation.MODEL_RUNTIME,
            status = DiagnosticStatus.FAILED,
            outputContent = detail,
            errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
        ),
    )
}

private fun logRuntimeFailure(failure: Throwable) {
    Log.e(
        TAG,
        PrivacySafeDiagnostics.failureEvent(
            operation = DiagnosticOperation.MODEL_RUNTIME,
            errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
            failure = failure,
        ),
    )
}

private fun logRuntimeCompleted() {
    Log.i(
        TAG,
        PrivacySafeDiagnostics.contentEvent(
            operation = DiagnosticOperation.MODEL_RUNTIME,
            status = DiagnosticStatus.COMPLETED,
        ),
    )
}
