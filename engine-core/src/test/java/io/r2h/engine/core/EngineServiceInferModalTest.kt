package io.r2h.engine.core

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.r2h.engine.api.IModalInferenceCallback
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModalInferenceResult
import io.r2h.engine.api.model.ModelValidationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Unit tests for [EngineServiceImpl.inferModal] covering:
 * - Security rejection for unauthorized callers
 * - MODEL_NOT_FOUND when the descriptor is absent
 * - NO_ACTIVE_RUNTIME when backend key has no registered runtime
 * - MODEL_NOT_LOADED when the model hasn't been loaded
 * - REQUEST_INVALID when the model's supportedTaskTypes doesn't include the requested task
 * - Correct task routing for SPEECH_TO_TEXT (audio) and IMAGE_UNDERSTANDING (image) requests
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineServiceInferModalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ─── Security ─────────────────────────────────────────────────────────────

    @Test
    fun `inferModal delivers PERMISSION_DENIED for unauthorized caller`() {
        val service = serviceWith(
            callerValidator = CallerValidatorPort { CallerValidationResult.Denied("not trusted") },
        )
        val errors = mutableListOf<EngineError>()
        service.inferModal(audioRequest(), collectingCallback(onError = { errors += it }))
        assertEquals(1, errors.size)
        assertEquals(ErrorCode.PERMISSION_DENIED, errors.first().code)
    }

    // ─── Model resolution ─────────────────────────────────────────────────────

    @Test
    fun `inferModal delivers MODEL_NOT_FOUND when descriptor is missing`() {
        val service = serviceWith(descriptor = null)
        val errors = mutableListOf<EngineError>()
        service.inferModal(audioRequest(), collectingCallback(onError = { errors += it }))
        assertEquals(1, errors.size)
        assertEquals(ErrorCode.MODEL_NOT_FOUND, errors.first().code)
    }

    @Test
    fun `inferModal delivers NO_ACTIVE_RUNTIME when backend key has no runtime`() {
        val modelFile = tempFolder.newFile("whisper.onnx")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio-missing")
        val service = serviceWith(descriptor = descriptor)   // registry has no "onnx-audio-missing"
        val errors = mutableListOf<EngineError>()
        service.inferModal(
            audioRequest(modelId = descriptor.id),
            collectingCallback(onError = { errors += it }),
        )
        assertEquals(1, errors.size)
        assertEquals(ErrorCode.NO_ACTIVE_RUNTIME, errors.first().code)
    }

    @Test
    fun `inferModal delivers MODEL_NOT_LOADED when model is not in loaded state`() {
        val modelFile = tempFolder.newFile("whisper.onnx")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio")
        val runtime = CapturingRuntime(backendKey = "onnx-audio", loadedModelIds = emptySet())
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))
        val errors = mutableListOf<EngineError>()
        service.inferModal(
            audioRequest(modelId = descriptor.id),
            collectingCallback(onError = { errors += it }),
        )
        assertEquals(1, errors.size)
        assertEquals(ErrorCode.MODEL_NOT_LOADED, errors.first().code)
    }

    // ─── Task authorization ───────────────────────────────────────────────────

    @Test
    fun `inferModal rejects task not in model supportedTaskTypes`() {
        val modelFile = tempFolder.newFile("whisper-tiny.onnx")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio",
            supportedTaskTypes = setOf(TaskType.SPEECH_TO_TEXT))
        val runtime = CapturingRuntime(backendKey = "onnx-audio", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        val errors = mutableListOf<EngineError>()
        service.inferModal(
            request(modelId = descriptor.id, modality = "AUDIO", taskType = "CLASSIFICATION"),
            collectingCallback(onError = { errors += it }),
        )

        assertEquals(1, errors.size)
        assertEquals(ErrorCode.REQUEST_INVALID, errors.first().code)
        // Runtime should NOT have received any execution call
        assertEquals(0, runtime.capturedInputs.size)
    }

    @Test
    fun `inferModal accepts task that is in model supportedTaskTypes`() {
        val modelFile = tempFolder.newFile("whisper-tiny.onnx")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio",
            supportedTaskTypes = setOf(TaskType.SPEECH_TO_TEXT))
        val runtime = CapturingRuntime(backendKey = "onnx-audio", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        val errors = mutableListOf<EngineError>()
        service.inferModal(
            request(modelId = descriptor.id, modality = "AUDIO", taskType = "SPEECH_TO_TEXT", inputRefs = listOf("test://audio.wav")),
            collectingCallback(onError = { errors += it }),
        )

        assertEquals("Unexpected errors: $errors", 0, errors.size)
    }

    @Test
    fun `inferModal allows any task when model supportedTaskTypes is empty`() {
        val modelFile = tempFolder.newFile("generic.onnx")
        // supportedTaskTypes = emptySet() → no restriction
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image",
            supportedTaskTypes = emptySet())
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        val errors = mutableListOf<EngineError>()
        service.inferModal(
            request(modelId = descriptor.id, modality = "IMAGE", taskType = "CLASSIFICATION", inputRefs = listOf("test://image.jpg")),
            collectingCallback(onError = { errors += it }),
        )

        assertEquals("Unexpected errors: $errors", 0, errors.size)
    }

    // ─── Task routing ─────────────────────────────────────────────────────────

    @Test
    fun `SPEECH_TO_TEXT request routes InferenceInput_SpeechToText task to runtime`() {
        val modelFile = tempFolder.newFile("whisper-base.onnx")
        val wavFile = tempFolder.newFile("audio.wav")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio")
        val runtime = CapturingRuntime(backendKey = "onnx-audio", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        service.inferModal(
            request(
                modelId = descriptor.id,
                modality = "AUDIO",
                taskType = "SPEECH_TO_TEXT",
                inputRefs = listOf(wavFile.absolutePath),
            ),
            collectingCallback(),
        )

        waitFor { runtime.capturedInputs.isNotEmpty() }
        assertEquals(InferenceInput.Task.SpeechToText, runtime.capturedInputs.first().task)
        val audioPart = runtime.capturedInputs.first().parts
            .filterIsInstance<InferenceInput.Part.Audio>()
        assertEquals(1, audioPart.size)
        assertEquals(wavFile.absolutePath, audioPart.first().artifact.ref)
    }

    @Test
    fun `IMAGE_UNDERSTANDING request routes InferenceInput_ImageUnderstanding task to runtime`() {
        val modelFile = tempFolder.newFile("vit-base.onnx")
        val imageFile = tempFolder.newFile("photo.jpg")
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image")
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        service.inferModal(
            request(
                modelId = descriptor.id,
                modality = "IMAGE",
                taskType = "IMAGE_UNDERSTANDING",
                inputRefs = listOf(imageFile.absolutePath),
            ),
            collectingCallback(),
        )

        waitFor { runtime.capturedInputs.isNotEmpty() }
        assertEquals(InferenceInput.Task.ImageUnderstanding, runtime.capturedInputs.first().task)
        val imagePart = runtime.capturedInputs.first().parts
            .filterIsInstance<InferenceInput.Part.Image>()
        assertEquals(1, imagePart.size)
        assertEquals(imageFile.absolutePath, imagePart.first().artifact.ref)
    }

    @Test
    fun `CLASSIFICATION request routes InferenceInput_Classification task to runtime`() {
        val modelFile = tempFolder.newFile("resnet50.onnx")
        val imageFile = tempFolder.newFile("cat.jpg")
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image",
            supportedTaskTypes = setOf(TaskType.CLASSIFICATION))
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        service.inferModal(
            request(
                modelId = descriptor.id,
                modality = "IMAGE",
                taskType = "CLASSIFICATION",
                inputRefs = listOf(imageFile.absolutePath),
            ),
            collectingCallback(),
        )

        waitFor { runtime.capturedInputs.isNotEmpty() }
        assertEquals(InferenceInput.Task.Classification, runtime.capturedInputs.first().task)
    }

    // ─── Part D: Hardening guards ─────────────────────────────────────────────

    @Test
    fun `inferModal rejects IMAGE request targeting TEXT model with REQUEST_INVALID`() {
        val modelFile = tempFolder.newFile("llm.onnx")
        // TEXT model but caller sends modality = "IMAGE"
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image")
            .copy(modelType = ModelType.TEXT_GENERATION, modality = Modality.TEXT)
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        val errors = mutableListOf<EngineError>()
        service.inferModal(
            request(modelId = descriptor.id, modality = "IMAGE", taskType = "IMAGE_UNDERSTANDING"),
            collectingCallback(onError = { errors += it }),
        )

        assertEquals(1, errors.size)
        assertEquals(ErrorCode.REQUEST_INVALID, errors.first().code)
        // Runtime must not have received any execution
        assertEquals(0, runtime.capturedInputs.size)
    }

    @Test
    fun `inferModal rejects AUDIO request with empty inputRefs with REQUEST_INVALID`() {
        val modelFile = tempFolder.newFile("whisper.onnx")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio")
            .copy(modality = Modality.AUDIO)
        val runtime = CapturingRuntime(backendKey = "onnx-audio", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        val errors = mutableListOf<EngineError>()
        service.inferModal(
            request(modelId = descriptor.id, modality = "AUDIO", taskType = "SPEECH_TO_TEXT",
                inputRefs = emptyList()),  // no audio file provided
            collectingCallback(onError = { errors += it }),
        )

        assertEquals(1, errors.size)
        assertEquals(ErrorCode.REQUEST_INVALID, errors.first().code)
        assertEquals(0, runtime.capturedInputs.size)
    }

    @Test
    fun `inferModal rejects IMAGE request with empty inputRefs with REQUEST_INVALID`() {
        val modelFile = tempFolder.newFile("vit.onnx")
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image")
            .copy(modality = Modality.IMAGE)
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = setOf(descriptor.id))
        val service = serviceWith(descriptor = descriptor, runtimes = listOf(runtime))

        val errors = mutableListOf<EngineError>()
        service.inferModal(
            request(modelId = descriptor.id, modality = "IMAGE", taskType = "IMAGE_UNDERSTANDING",
                inputRefs = emptyList()),  // no image file provided
            collectingCallback(onError = { errors += it }),
        )

        assertEquals(1, errors.size)
        assertEquals(ErrorCode.REQUEST_INVALID, errors.first().code)
        assertEquals(0, runtime.capturedInputs.size)
    }

    // ─── Truth snapshot — per-modality active model tracking ─────────────────

    @Test
    fun `truth snapshot IMAGE modality reflects loaded model from image runtime`() {
        val modelFile = tempFolder.newFile("vit-base.onnx")
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image")
            .copy(modality = Modality.IMAGE)
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = setOf(descriptor.id))
        val service = serviceWithCatalog(
            catalogRecords = listOf(
                ModelCatalogRecord(descriptor, 0L, installed = true, validationState = ModelValidationState.VALID)
            ),
            runtimes = listOf(runtime),
        )

        val snapshot = service.getEngineTruthSnapshot()
        val imageState = snapshot.modalityState.find { it.modality == "IMAGE" }

        assertNotNull("IMAGE modality state must be present", imageState)
        assertEquals(descriptor.id, imageState!!.activeModelId)
        assertEquals(listOf(descriptor.id), imageState.loadedModelIds)
        assertTrue(imageState.ready)
        assertEquals("onnx-image", imageState.runtimeKey)
        assertNull(imageState.lastError)
    }

    @Test
    fun `truth snapshot AUDIO modality reflects loaded model from audio runtime`() {
        val modelFile = tempFolder.newFile("whisper-tiny.onnx")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio")
            .copy(modality = Modality.AUDIO)
        val runtime = CapturingRuntime(backendKey = "onnx-audio", loadedModelIds = setOf(descriptor.id))
        val service = serviceWithCatalog(
            catalogRecords = listOf(
                ModelCatalogRecord(descriptor, 0L, installed = true, validationState = ModelValidationState.VALID)
            ),
            runtimes = listOf(runtime),
        )

        val snapshot = service.getEngineTruthSnapshot()
        val audioState = snapshot.modalityState.find { it.modality == "AUDIO" }

        assertNotNull("AUDIO modality state must be present", audioState)
        assertEquals(descriptor.id, audioState!!.activeModelId)
        assertEquals(listOf(descriptor.id), audioState.loadedModelIds)
        assertTrue(audioState.ready)
        assertEquals("onnx-audio", audioState.runtimeKey)
    }

    @Test
    fun `truth snapshot IMAGE modality is empty when model is not loaded`() {
        val modelFile = tempFolder.newFile("vit-base.onnx")
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image")
            .copy(modality = Modality.IMAGE)
        // Runtime returns NotLoaded for all models
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = emptySet())
        val service = serviceWithCatalog(
            catalogRecords = listOf(
                ModelCatalogRecord(descriptor, 0L, installed = true, validationState = ModelValidationState.VALID)
            ),
            runtimes = listOf(runtime),
        )

        val snapshot = service.getEngineTruthSnapshot()
        val imageState = snapshot.modalityState.find { it.modality == "IMAGE" }

        assertNotNull(imageState)
        assertNull("activeModelId should be null when nothing is loaded", imageState!!.activeModelId)
        assertEquals(emptyList<String>(), imageState.loadedModelIds)
    }

    // ─── Part H: Client-readable task types ──────────────────────────────────

    @Test
    fun `truth snapshot IMAGE modality exposes supportedTaskTypes from loaded model descriptor`() {
        val modelFile = tempFolder.newFile("resnet50.onnx")
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image",
            supportedTaskTypes = setOf(TaskType.IMAGE_UNDERSTANDING, TaskType.CLASSIFICATION))
            .copy(modality = Modality.IMAGE)
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = setOf(descriptor.id))
        val service = serviceWithCatalog(
            catalogRecords = listOf(
                ModelCatalogRecord(descriptor, 0L, installed = true, validationState = ModelValidationState.VALID)
            ),
            runtimes = listOf(runtime),
        )

        val snapshot = service.getEngineTruthSnapshot()
        val imageState = snapshot.modalityState.find { it.modality == "IMAGE" }

        assertNotNull(imageState)
        assertTrue(
            "IMAGE state must include IMAGE_UNDERSTANDING; got ${imageState!!.supportedTaskTypes}",
            imageState.supportedTaskTypes.contains("IMAGE_UNDERSTANDING"),
        )
        assertTrue(
            "IMAGE state must include CLASSIFICATION; got ${imageState.supportedTaskTypes}",
            imageState.supportedTaskTypes.contains("CLASSIFICATION"),
        )
    }

    @Test
    fun `truth snapshot IMAGE supportedTaskTypes is empty when no model is loaded`() {
        val modelFile = tempFolder.newFile("vit-small.onnx")
        val descriptor = imageDescriptor(modelFile, backendKey = "onnx-image",
            supportedTaskTypes = setOf(TaskType.IMAGE_UNDERSTANDING))
            .copy(modality = Modality.IMAGE)
        // Runtime reports nothing loaded
        val runtime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = emptySet())
        val service = serviceWithCatalog(
            catalogRecords = listOf(
                ModelCatalogRecord(descriptor, 0L, installed = true, validationState = ModelValidationState.VALID)
            ),
            runtimes = listOf(runtime),
        )

        val snapshot = service.getEngineTruthSnapshot()
        val imageState = snapshot.modalityState.find { it.modality == "IMAGE" }

        assertNotNull(imageState)
        assertTrue(
            "supportedTaskTypes must be empty when nothing is loaded; got ${imageState!!.supportedTaskTypes}",
            imageState.supportedTaskTypes.isEmpty(),
        )
    }

    @Test
    fun `truth snapshot AUDIO modality exposes SPEECH_TO_TEXT when whisper model is loaded`() {
        val modelFile = tempFolder.newFile("whisper-base.onnx")
        val descriptor = audioDescriptor(modelFile, backendKey = "onnx-audio",
            supportedTaskTypes = setOf(TaskType.SPEECH_TO_TEXT))
            .copy(modality = Modality.AUDIO)
        val runtime = CapturingRuntime(backendKey = "onnx-audio", loadedModelIds = setOf(descriptor.id))
        val service = serviceWithCatalog(
            catalogRecords = listOf(
                ModelCatalogRecord(descriptor, 0L, installed = true, validationState = ModelValidationState.VALID)
            ),
            runtimes = listOf(runtime),
        )

        val snapshot = service.getEngineTruthSnapshot()
        val audioState = snapshot.modalityState.find { it.modality == "AUDIO" }

        assertNotNull(audioState)
        assertEquals(
            listOf("SPEECH_TO_TEXT"),
            audioState!!.supportedTaskTypes,
        )
    }

    @Test
    fun `truth snapshot IMAGE and AUDIO modalities are independent of text active model`() {
        // Regression: buildModalityState must not bleed the single orchestrator activeModelId
        // across modalities — each runtime must be queried independently.
        val textFile = tempFolder.newFile("qwen2.onnx")
        val imageFile = tempFolder.newFile("vit-base.onnx")
        val textDescriptor = imageDescriptor(textFile, backendKey = "onnx")
            .copy(modelType = ModelType.TEXT_GENERATION, modality = Modality.TEXT)
        val imageDescriptor = imageDescriptor(imageFile, backendKey = "onnx-image")
            .copy(modality = Modality.IMAGE)
        // Text model is loaded; image model is not
        val textRuntime = CapturingRuntime(backendKey = "onnx", loadedModelIds = setOf(textDescriptor.id))
        val imageRuntime = CapturingRuntime(backendKey = "onnx-image", loadedModelIds = emptySet())
        val service = serviceWithCatalog(
            catalogRecords = listOf(
                ModelCatalogRecord(textDescriptor, 0L, installed = true, validationState = ModelValidationState.VALID),
                ModelCatalogRecord(imageDescriptor, 0L, installed = true, validationState = ModelValidationState.VALID),
            ),
            runtimes = listOf(textRuntime, imageRuntime),
        )

        val snapshot = service.getEngineTruthSnapshot()
        val textState = snapshot.modalityState.find { it.modality == "TEXT" }
        val imageState = snapshot.modalityState.find { it.modality == "IMAGE" }

        assertNotNull(textState)
        assertNotNull(imageState)
        assertEquals("text model should be active", textDescriptor.id, textState!!.activeModelId)
        assertNull("image modality should have no active model", imageState!!.activeModelId)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun serviceWith(
        callerValidator: CallerValidatorPort = CallerValidatorPort { CallerValidationResult.Allowed("io.r2h.engine") },
        descriptor: ModelDescriptor? = null,
        runtimes: List<CapturingRuntime> = emptyList(),
    ): EngineServiceImpl {
        val registry = ModalInferenceTestRegistry(runtimes)
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        return EngineServiceImpl(
            scope = scope,
            runtimeRegistry = registry,
            taskRouter = NoOpTaskRouter(),
            callerRateLimiter = CallerRateLimiterPort { _, _ -> CallerRateLimitResult.Allowed },
            callerValidator = callerValidator,
            modelDescriptorPort = ModelDescriptorPort { descriptor },
            enginePackageName = "io.r2h.engine",
        )
    }

    private fun serviceWithCatalog(
        catalogRecords: List<ModelCatalogRecord>,
        runtimes: List<CapturingRuntime> = emptyList(),
    ): EngineServiceImpl {
        val registry = ModalInferenceTestRegistry(runtimes)
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        return EngineServiceImpl(
            scope = scope,
            runtimeRegistry = registry,
            taskRouter = NoOpTaskRouter(),
            callerRateLimiter = CallerRateLimiterPort { _, _ -> CallerRateLimitResult.Allowed },
            callerValidator = CallerValidatorPort { CallerValidationResult.Allowed("io.r2h.engine") },
            modelCatalogSource = { catalogRecords },
            enginePackageName = "io.r2h.engine",
        )
    }

    private fun audioRequest(modelId: String = "whisper-tiny"): ModalInferenceRequest =
        request(modelId = modelId, modality = "AUDIO", taskType = "SPEECH_TO_TEXT")

    private fun request(
        modelId: String,
        modality: String,
        taskType: String,
        inputRefs: List<String> = emptyList(),
        textPrompt: String? = null,
    ) = ModalInferenceRequest(
        requestId = UUID.randomUUID().toString(),
        modelId = modelId,
        modality = modality,
        taskType = taskType,
        inputRefs = inputRefs,
        textPrompt = textPrompt,
    )

    private fun audioDescriptor(
        file: File,
        backendKey: String,
        supportedTaskTypes: Set<TaskType> = setOf(TaskType.SPEECH_TO_TEXT),
    ) = ModelDescriptor(
        id = file.nameWithoutExtension,
        displayName = file.nameWithoutExtension,
        version = "local",
        modelType = ModelType.SPEECH_TO_TEXT,
        capabilities = setOf(
            ModelCapability.Input.Audio,
            ModelCapability.Output.Text,
            ModelCapability.Interaction.Cancellation,
            ModelCapability.Execution.Local,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        backendKey = backendKey,
        source = ModelDescriptor.Source.Local(
            artifactRef = file.absolutePath,
            format = "onnx",
        ),
        executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
        supportedTaskTypes = supportedTaskTypes,
        metadata = emptyMap(),
    )

    private fun imageDescriptor(
        file: File,
        backendKey: String,
        supportedTaskTypes: Set<TaskType> = setOf(TaskType.IMAGE_UNDERSTANDING, TaskType.CLASSIFICATION),
    ) = ModelDescriptor(
        id = file.nameWithoutExtension,
        displayName = file.nameWithoutExtension,
        version = "local",
        modelType = ModelType.VISION,
        capabilities = setOf(
            ModelCapability.Input.Image,
            ModelCapability.Output.Text,
            ModelCapability.Interaction.Cancellation,
            ModelCapability.Execution.Local,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        backendKey = backendKey,
        source = ModelDescriptor.Source.Local(
            artifactRef = file.absolutePath,
            format = "onnx",
        ),
        executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
        supportedTaskTypes = supportedTaskTypes,
        metadata = emptyMap(),
    )

    private fun collectingCallback(
        onResult: (ModalInferenceResult) -> Unit = {},
        onError: (EngineError) -> Unit = {},
    ): IModalInferenceCallback = object : IModalInferenceCallback.Stub() {
        override fun onResult(result: ModalInferenceResult) { onResult(result) }
        override fun onError(error: EngineError) { onError(error) }
    }

    private fun waitFor(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
}

// ─── Test doubles ─────────────────────────────────────────────────────────────

private class ModalInferenceTestRegistry(runtimes: List<CapturingRuntime>) : RuntimeRegistry {
    private val map: Map<String, CapturingRuntime> = runtimes.associateBy { it.descriptor.key }

    override fun register(runtime: BackendRuntime, policy: RegistrationPolicy) =
        RegistrationResult.Success(runtime, RegistrationResult.Action.ADDED)

    override fun unregister(key: String) = UnregistrationResult.NotFound
    override fun getRuntime(key: String): BackendRuntime? = map[key]
    override fun getAllRuntimes(): List<BackendRuntime> = map.values.toList()
    override fun resolveCompatibleRuntimes(model: ModelDescriptor) = CompatibilityResolution.RegistryEmpty
    override fun resolveRuntimes(model: ModelDescriptor, requiredCapabilities: Set<ModelCapability>, locality: ExecutionLocality?) = RuntimeResolution.RegistryEmpty
    override fun isRuntimeSupported(runtimeKey: String, model: ModelDescriptor, requiredCapabilities: Set<ModelCapability>, locality: ExecutionLocality?) = SupportCheckResult.RuntimeNotFound
}

private class CapturingRuntime(
    backendKey: String,
    private val loadedModelIds: Set<String>,
) : BackendRuntime {

    val capturedInputs = mutableListOf<InferenceInput>()

    override val descriptor = RuntimeDescriptor(
        key = backendKey,
        displayName = backendKey,
        supportedModelTypes = setOf(ModelType.VISION, ModelType.SPEECH_TO_TEXT, ModelType.CLASSIFICATION),
        supportedCapabilities = setOf(
            ModelCapability.Input.Image,
            ModelCapability.Input.Audio,
            ModelCapability.Output.Text,
            ModelCapability.Execution.Local,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    )

    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult =
        if (model.backendKey == descriptor.key) CompatibilityResult.Compatible("ok")
        else CompatibilityResult.Incompatible(listOf(CompatibilityResult.Reason.BackendKeyMismatch))

    override fun getLoadState(modelId: String): LoadState =
        if (modelId in loadedModelIds) LoadState.Loaded(System.currentTimeMillis()) else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor) =
        LoadResult.Success(LoadState.Loaded(System.currentTimeMillis()))

    override fun execute(model: ModelDescriptor, input: InferenceInput) = kotlinx.coroutines.flow.flow<InferenceOutput> {
        capturedInputs += input
        emit(InferenceOutput(
            requestId = input.requestId,
            phase = InferenceOutput.Phase.Final,
            items = listOf(InferenceOutput.Item.Text("ok")),
            completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.COMPLETED),
        ))
    }

    override suspend fun cancel(requestId: String) = CancellationResult.RequestNotFound
    override suspend fun unloadModel(modelId: String) = UnloadResult.Success
    override fun getRuntimeState() = RuntimeState(RuntimeState.Lifecycle.READY, 0, 1)
}

private class NoOpTaskRouter : TaskRouter {
    override fun route(input: InferenceInput, candidateModels: List<ModelDescriptor>, runtimeRegistry: RuntimeRegistry) =
        RoutingDecision.Rejected(RoutingDecision.Reason.NO_CANDIDATE_MODELS)
}

