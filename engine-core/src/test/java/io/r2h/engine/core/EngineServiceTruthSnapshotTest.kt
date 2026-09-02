package io.r2h.engine.core

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.r2h.engine.api.model.ClientSessionRegistration
import io.r2h.engine.api.model.ConnectionState
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.ModelValidationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class EngineServiceTruthSnapshotTest {

    private lateinit var tempDir: File
    private lateinit var runtimeRegistry: TruthTestRuntimeRegistry
    private lateinit var sessionRegistry: ClientSessionRegistry
    private lateinit var service: EngineServiceImpl

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        tempDir = Files.createTempDirectory("truth-snapshot").toFile()
        runtimeRegistry = TruthTestRuntimeRegistry()
        sessionRegistry = ClientSessionRegistry(clock = System::currentTimeMillis, staleTimeoutMs = 5_000L)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        tempDir.deleteRecursively()
    }

    @Test
    fun `installed integration with no live session stays disconnected`() {
        val descriptor = descriptorFor(modelFile("catalog.gguf"), backendKey = "truth-backend")
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = descriptorFileSize(descriptor),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                    validationError = null,
                ),
            ),
            integrations = listOf(
                InstalledIntegrationRecord(
                    displayName = "Client A",
                    packageName = "com.example.client",
                    installedIntegration = true,
                    trustedIntegration = true,
                    discoverableIntegration = true,
                ),
            ),
        )

        val snapshot = service.engineTruthSnapshot

        assertTrue(snapshot.liveClientSessions.isEmpty())
        assertEquals(ConnectionState.DISCONNECTED, snapshot.installedIntegrations.first().connectionState)
    }

    @Test
    fun `connected client session is exposed in live sessions and integration status`() {
        val descriptor = descriptorFor(modelFile("catalog.gguf"), backendKey = "truth-backend")
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = descriptorFileSize(descriptor),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            ),
            integrations = listOf(
                InstalledIntegrationRecord(
                    displayName = "Client A",
                    packageName = "com.example.client",
                    installedIntegration = true,
                    trustedIntegration = true,
                    discoverableIntegration = true,
                ),
            ),
        )

        sessionRegistry.register(
            ClientSessionRegistration(
                displayName = "Client A",
                packageName = "com.example.client",
                protocolVersion = 4,
                capabilities = listOf("GENERATE"),
            ),
            verifiedPackageName = "com.example.client",
            ownerUid = 1001,
        )

        val snapshot = service.engineTruthSnapshot

        assertEquals(1, snapshot.liveClientSessions.size)
        assertEquals(ConnectionState.CONNECTED, snapshot.installedIntegrations.first().connectionState)
    }

    @Test
    fun `preloaded model that is not active stays loaded not active`() {
        val modelFile = modelFile("loaded.gguf")
        val descriptor = descriptorFor(modelFile, backendKey = "truth-backend")
        runtimeRegistry.addRuntime(PreloadedTruthRuntime("truth-backend", setOf(descriptor.id)))
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            ),
            integrations = emptyList(),
        )

        val snapshot = service.engineTruthSnapshot
        val model = snapshot.models.first()

        assertEquals(io.r2h.engine.api.model.ModelRuntimeState.LOADED, model.runtimeState)
        assertFalse(model.ready)
    }

    @Test
    fun `active model is reported ready with actual backend id`() = runTest {
        val modelFile = modelFile("active.gguf")
        val descriptor = descriptorFor(modelFile, backendKey = "truth-backend")
        runtimeRegistry.addRuntime(LoadableTruthRuntime("truth-backend"))
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            ),
            integrations = emptyList(),
            descriptor = descriptor,
            modelPath = modelFile.absolutePath,
        )

        service.warmup(descriptor.id)
        waitFor { service.engineTruthSnapshot.runtime.activeModelId == descriptor.id }

        val snapshot = service.engineTruthSnapshot

        assertEquals("truth-backend", snapshot.runtime.activeBackendId)
        assertTrue(snapshot.runtime.readyForInference)
        assertEquals(io.r2h.engine.api.model.ModelRuntimeState.ACTIVE, snapshot.models.first().runtimeState)
    }

    @Test
    fun `load failure surfaces exact structured reason`() = runTest {
        val modelFile = modelFile("broken.gguf")
        val descriptor = descriptorFor(modelFile, backendKey = "truth-backend")
        runtimeRegistry.addRuntime(FailingTruthRuntime("truth-backend"))
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            ),
            integrations = emptyList(),
            descriptor = descriptor,
            modelPath = modelFile.absolutePath,
        )

        service.warmup(descriptor.id)
        waitFor {
            service.engineTruthSnapshot.models.first().lastError?.code == ErrorCode.SESSION_CREATION_FAILED
        }

        val model = service.engineTruthSnapshot.models.first()
        val error = model.lastError

        assertNotNull(error)
        assertEquals(ErrorStage.MODEL_LOADING, error?.stage)
        assertEquals(ErrorCode.SESSION_CREATION_FAILED, error?.code)
        assertEquals("Simulated load failure", error?.message)
    }

    @Test
    fun `descriptor without runtime stays unavailable and not ready`() {
        val modelFile = modelFile("metadata-only.gguf")
        val descriptor = descriptorFor(modelFile, backendKey = "missing-runtime")
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            ),
            integrations = emptyList(),
        )

        val snapshot = service.engineTruthSnapshot
        val model = snapshot.models.first()
        val textState = snapshot.modalityState.first { it.modality == "TEXT" }

        assertFalse(snapshot.runtime.readyForInference)
        assertFalse(textState.ready)
        assertTrue(textState.loadedModelIds.isEmpty())
        assertEquals(io.r2h.engine.api.model.ModelRuntimeState.UNLOADED, model.runtimeState)
        assertEquals(ErrorCode.MODEL_NOT_LOADABLE, model.lastError?.code)
    }

    @Test
    fun `failed runtime load keeps truth snapshot unavailable`() = runTest {
        val modelFile = modelFile("runtime-failed.gguf")
        val descriptor = descriptorFor(modelFile, backendKey = "runtime-failed")
        runtimeRegistry.addRuntime(FailingTruthRuntime("runtime-failed"))
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            ),
            integrations = emptyList(),
            descriptor = descriptor,
        )

        service.loadInstalledModel(descriptor)
        waitFor {
            service.engineTruthSnapshot.models.first().runtimeState ==
                io.r2h.engine.api.model.ModelRuntimeState.FAILED
        }

        val snapshot = service.engineTruthSnapshot
        val textState = snapshot.modalityState.first { it.modality == "TEXT" }

        assertFalse(snapshot.runtime.readyForInference)
        assertFalse(textState.ready)
        assertEquals(io.r2h.engine.api.model.ModelRuntimeState.FAILED, snapshot.models.first().runtimeState)
        assertEquals(ErrorCode.MODEL_NOT_LOADABLE, snapshot.runtime.lastError?.code)
    }

    @Test
    fun `successful runtime load exposes ready truth snapshot`() = runTest {
        val modelFile = modelFile("runtime-ready.gguf")
        val descriptor = descriptorFor(modelFile, backendKey = "runtime-ready")
        runtimeRegistry.addRuntime(LoadableTruthRuntime("runtime-ready"))
        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(
                    descriptor = descriptor,
                    fileSizeBytes = modelFile.length(),
                    installed = true,
                    validationState = ModelValidationState.VALID,
                ),
            ),
            integrations = emptyList(),
            descriptor = descriptor,
        )

        service.loadInstalledModel(descriptor)
        waitFor { service.engineTruthSnapshot.runtime.readyForInference }

        val snapshot = service.engineTruthSnapshot
        val textState = snapshot.modalityState.first { it.modality == "TEXT" }

        assertTrue(snapshot.runtime.readyForInference)
        assertTrue(textState.ready)
        assertEquals(descriptor.id, snapshot.runtime.activeModelId)
        assertEquals(listOf(descriptor.id), textState.loadedModelIds)
    }

    private fun serviceWith(
        models: List<ModelCatalogRecord>,
        integrations: List<InstalledIntegrationRecord>,
        descriptor: ModelDescriptor? = null,
        modelPath: String? = null,
    ): EngineServiceImpl {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        return EngineServiceImpl(
            scope = scope,
            runtimeRegistry = runtimeRegistry,
            taskRouter = TruthPassThroughRouter(),
            callerRateLimiter = CallerRateLimiterPort { _, _ -> CallerRateLimitResult.Allowed },
            callerValidator = CallerValidatorPort {
                CallerValidationResult.Allowed("io.r2h.engine")
            },
            modelInfoSource = {
                models.map {
                    io.r2h.engine.api.model.ModelInfo(
                        it.descriptor.id,
                        it.descriptor.displayName,
                        it.fileSizeBytes,
                        it.descriptor.backendKey,
                        false,
                    )
                }
            },
            modelCatalogSource = { models },
            integrationCatalogSource = { integrations },
            modelResolver = ModelResolverPort { modelPath },
            modelDescriptorPort = ModelDescriptorPort { descriptor },
            clientSessionRegistry = sessionRegistry,
            enginePackageName = "io.r2h.engine",
        )
    }

    private fun modelFile(name: String): File = File(tempDir, name).also { it.writeText("fake-model") }

    private fun descriptorFileSize(descriptor: ModelDescriptor): Long {
        val source = descriptor.source as ModelDescriptor.Source.Local
        return File(source.artifactRef).length()
    }

    private fun descriptorFor(
        file: File,
        backendKey: String,
        modality: Modality = Modality.TEXT,
    ): ModelDescriptor {
        return ModelDescriptor(
            id = file.nameWithoutExtension,
            displayName = file.nameWithoutExtension,
            version = "local",
            modelType = ModelType.TEXT_GENERATION,
            capabilities = setOf(
                ModelCapability.Input.Text,
                ModelCapability.Output.Text,
                ModelCapability.Interaction.Streaming,
                ModelCapability.Execution.Local,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            ),
            backendKey = backendKey,
            source = ModelDescriptor.Source.Local(
                artifactRef = file.absolutePath,
                format = file.extension,
                sizeBytes = file.length(),
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            taskTags = setOf("chat"),
            constraints = ModelDescriptor.Constraints(maxContextWindow = 2048),
            metadata = emptyMap(),
            modality = modality,
        )
    }

    // -------------------------------------------------------------------------
    // Regression tests: runtime.activeTextModelId / activeImageModelId /
    // activeAudioModelId must be populated from modalityState, not left null.
    //
    // Invariant:
    //   snapshot.runtime.activeTextModelId
    //     == snapshot.modalityState.find { it.modality == "TEXT" }?.activeModelId
    // (and likewise for IMAGE and AUDIO)
    // -------------------------------------------------------------------------

    @Test
    fun `activeTextModelId matches modalityState TEXT entry when text model is loaded`() {
        val modelFile = modelFile("text-model.gguf")
        val descriptor = descriptorFor(modelFile, backendKey = "text-backend", modality = Modality.TEXT)
        runtimeRegistry.addRuntime(PreloadedTruthRuntime("text-backend", setOf(descriptor.id)))
        service = serviceWith(
            models = listOf(ModelCatalogRecord(descriptor, modelFile.length(), true, ModelValidationState.VALID)),
            integrations = emptyList(),
        )

        val snapshot = service.engineTruthSnapshot

        val expected = snapshot.modalityState.find { it.modality == "TEXT" }?.activeModelId
        assertEquals(
            "activeTextModelId must match the TEXT modalityState entry",
            expected,
            snapshot.runtime.activeTextModelId,
        )
    }

    @Test
    fun `activeImageModelId matches modalityState IMAGE entry when image model is loaded`() {
        val modelFile = modelFile("image-model.onnx")
        val descriptor = descriptorFor(modelFile, backendKey = "image-backend", modality = Modality.IMAGE)
        runtimeRegistry.addRuntime(PreloadedTruthRuntime("image-backend", setOf(descriptor.id)))
        service = serviceWith(
            models = listOf(ModelCatalogRecord(descriptor, modelFile.length(), true, ModelValidationState.VALID)),
            integrations = emptyList(),
        )

        val snapshot = service.engineTruthSnapshot

        val expected = snapshot.modalityState.find { it.modality == "IMAGE" }?.activeModelId
        assertEquals(
            "activeImageModelId must match the IMAGE modalityState entry",
            expected,
            snapshot.runtime.activeImageModelId,
        )
    }

    @Test
    fun `activeAudioModelId matches modalityState AUDIO entry when audio model is loaded`() {
        val modelFile = modelFile("audio-model.onnx")
        val descriptor = descriptorFor(modelFile, backendKey = "audio-backend", modality = Modality.AUDIO)
        runtimeRegistry.addRuntime(PreloadedTruthRuntime("audio-backend", setOf(descriptor.id)))
        service = serviceWith(
            models = listOf(ModelCatalogRecord(descriptor, modelFile.length(), true, ModelValidationState.VALID)),
            integrations = emptyList(),
        )

        val snapshot = service.engineTruthSnapshot

        val expected = snapshot.modalityState.find { it.modality == "AUDIO" }?.activeModelId
        assertEquals(
            "activeAudioModelId must match the AUDIO modalityState entry",
            expected,
            snapshot.runtime.activeAudioModelId,
        )
    }

    @Test
    fun `all three active model id fields are null when no models are registered`() {
        service = serviceWith(models = emptyList(), integrations = emptyList())

        val snapshot = service.engineTruthSnapshot

        org.junit.Assert.assertNull(
            "activeTextModelId must be null with no catalog entries",
            snapshot.runtime.activeTextModelId,
        )
        org.junit.Assert.assertNull(
            "activeImageModelId must be null with no catalog entries",
            snapshot.runtime.activeImageModelId,
        )
        org.junit.Assert.assertNull(
            "activeAudioModelId must be null with no catalog entries",
            snapshot.runtime.activeAudioModelId,
        )
    }

    @Test
    fun `multi-modality catalog populates all three active model fields independently`() {
        val textFile = modelFile("text-multi.gguf")
        val imageFile = modelFile("image-multi.onnx")
        val audioFile = modelFile("audio-multi.onnx")

        val textDescriptor = descriptorFor(textFile, "multi-text", Modality.TEXT)
        val imageDescriptor = descriptorFor(imageFile, "multi-image", Modality.IMAGE)
        val audioDescriptor = descriptorFor(audioFile, "multi-audio", Modality.AUDIO)

        runtimeRegistry.addRuntime(PreloadedTruthRuntime("multi-text", setOf(textDescriptor.id)))
        runtimeRegistry.addRuntime(PreloadedTruthRuntime("multi-image", setOf(imageDescriptor.id)))
        runtimeRegistry.addRuntime(PreloadedTruthRuntime("multi-audio", setOf(audioDescriptor.id)))

        service = serviceWith(
            models = listOf(
                ModelCatalogRecord(textDescriptor, textFile.length(), true, ModelValidationState.VALID),
                ModelCatalogRecord(imageDescriptor, imageFile.length(), true, ModelValidationState.VALID),
                ModelCatalogRecord(audioDescriptor, audioFile.length(), true, ModelValidationState.VALID),
            ),
            integrations = emptyList(),
        )

        val snapshot = service.engineTruthSnapshot

        // Consistency: each runtime field must equal its matching modalityState entry
        assertEquals(
            snapshot.modalityState.find { it.modality == "TEXT" }?.activeModelId,
            snapshot.runtime.activeTextModelId,
        )
        assertEquals(
            snapshot.modalityState.find { it.modality == "IMAGE" }?.activeModelId,
            snapshot.runtime.activeImageModelId,
        )
        assertEquals(
            snapshot.modalityState.find { it.modality == "AUDIO" }?.activeModelId,
            snapshot.runtime.activeAudioModelId,
        )

        // Correctness: the values themselves must match the loaded model IDs
        assertEquals(textDescriptor.id, snapshot.runtime.activeTextModelId)
        assertEquals(imageDescriptor.id, snapshot.runtime.activeImageModelId)
        assertEquals(audioDescriptor.id, snapshot.runtime.activeAudioModelId)
    }

    private fun waitFor(
        timeoutMs: Long = 2_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
}

private class TruthTestRuntimeRegistry : RuntimeRegistry {
    private val runtimes = LinkedHashMap<String, BackendRuntime>()

    fun addRuntime(runtime: BackendRuntime) {
        runtimes[runtime.descriptor.key] = runtime
    }

    override fun register(runtime: BackendRuntime, policy: RegistrationPolicy): RegistrationResult {
        runtimes[runtime.descriptor.key] = runtime
        return RegistrationResult.Success(runtime, RegistrationResult.Action.ADDED)
    }

    override fun unregister(key: String): UnregistrationResult =
        if (runtimes.remove(key) != null) UnregistrationResult.Success else UnregistrationResult.NotFound

    override fun getRuntime(key: String): BackendRuntime? = runtimes[key]

    override fun getAllRuntimes(): List<BackendRuntime> = runtimes.values.toList()

    override fun resolveCompatibleRuntimes(model: ModelDescriptor): CompatibilityResolution = CompatibilityResolution.RegistryEmpty

    override fun resolveRuntimes(
        model: ModelDescriptor,
        requiredCapabilities: Set<ModelCapability>,
        locality: ExecutionLocality?,
    ): RuntimeResolution = RuntimeResolution.RegistryEmpty

    override fun isRuntimeSupported(
        runtimeKey: String,
        model: ModelDescriptor,
        requiredCapabilities: Set<ModelCapability>,
        locality: ExecutionLocality?,
    ): SupportCheckResult = SupportCheckResult.RuntimeNotFound
}

private open class BaseTruthRuntime(
    override val descriptor: RuntimeDescriptor,
) : BackendRuntime {
    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult =
        if (model.backendKey == descriptor.key) CompatibilityResult.Compatible("ok")
        else CompatibilityResult.Incompatible(listOf(CompatibilityResult.Reason.BackendKeyMismatch))

    override fun getLoadState(modelId: String): LoadState = LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult =
        LoadResult.Success(LoadState.Loaded(System.currentTimeMillis()))

    override fun execute(model: ModelDescriptor, input: InferenceInput) =
        kotlinx.coroutines.flow.emptyFlow<InferenceOutput>()

    override suspend fun cancel(requestId: String): CancellationResult = CancellationResult.RequestNotFound

    override suspend fun unloadModel(modelId: String): UnloadResult = UnloadResult.Success

    override fun getRuntimeState(): RuntimeState = RuntimeState(RuntimeState.Lifecycle.READY, 0, 1)
}

private class PreloadedTruthRuntime(
    backendKey: String,
    private val loadedModelIds: Set<String>,
) : BaseTruthRuntime(
    RuntimeDescriptor(
        key = backendKey,
        displayName = backendKey,
        supportedModelTypes = setOf(ModelType.TEXT_GENERATION),
        supportedCapabilities = setOf(
            ModelCapability.Input.Text,
            ModelCapability.Output.Text,
            ModelCapability.Execution.Local,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    ),
) {
    override fun getLoadState(modelId: String): LoadState =
        if (modelId in loadedModelIds) LoadState.Loaded(System.currentTimeMillis()) else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult = LoadResult.Success(LoadState.Loaded(System.currentTimeMillis()))
}

private class LoadableTruthRuntime(
    backendKey: String,
) : BaseTruthRuntime(
    RuntimeDescriptor(
        key = backendKey,
        displayName = backendKey,
        supportedModelTypes = setOf(ModelType.TEXT_GENERATION),
        supportedCapabilities = setOf(
            ModelCapability.Input.Text,
            ModelCapability.Output.Text,
            ModelCapability.Execution.Local,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    ),
) {
    private var loadedModelId: String? = null

    override fun getLoadState(modelId: String): LoadState =
        if (loadedModelId == modelId) LoadState.Loaded(System.currentTimeMillis()) else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult {
        loadedModelId = model.id
        return LoadResult.Success(LoadState.Loaded(System.currentTimeMillis()))
    }
}

private class FailingTruthRuntime(
    backendKey: String,
) : BaseTruthRuntime(
    RuntimeDescriptor(
        key = backendKey,
        displayName = backendKey,
        supportedModelTypes = setOf(ModelType.TEXT_GENERATION),
        supportedCapabilities = setOf(
            ModelCapability.Input.Text,
            ModelCapability.Output.Text,
            ModelCapability.Execution.Local,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    ),
) {
    override fun getLoadState(modelId: String): LoadState = LoadState.Failed(FailureReason.LOAD_FAILED, "Simulated load failure")

    override suspend fun loadModel(model: ModelDescriptor): LoadResult =
        LoadResult.Failure(FailureReason.LOAD_FAILED, "Simulated load failure")

    override fun getRuntimeState(): RuntimeState = RuntimeState(
        RuntimeState.Lifecycle.ERROR,
        lastFailure = RuntimeState.RuntimeFailure(FailureReason.LOAD_FAILED, "Simulated load failure"),
    )
}

private class TruthPassThroughRouter : TaskRouter {
    override fun route(
        input: InferenceInput,
        candidateModels: List<ModelDescriptor>,
        runtimeRegistry: RuntimeRegistry,
    ): RoutingDecision = RoutingDecision.Rejected(RoutingDecision.Reason.NO_CANDIDATE_MODELS)
}
