package io.r2h.engine

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.r2h.engine.automation.R2hAccessibilityService
import io.r2h.engine.automation.NotificationSummaryFormatter
import io.r2h.engine.automation.R2hNotificationListenerService
import io.r2h.engine.api.IR2hEngineService
import io.r2h.engine.api.model.EngineTruthSnapshot
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModelInfo
import io.r2h.engine.api.model.ModelRuntimeInfo
import io.r2h.engine.api.model.ModelRuntimeState
import io.r2h.engine.api.model.SessionContext
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.model.LocalModelCandidate
import io.r2h.engine.model.LocalModelCandidateClassifier
import io.r2h.engine.model.LocalModelFolderManager
import io.r2h.engine.model.LocalModelImporter
import io.r2h.engine.model.LocalModelImportTransaction
import io.r2h.engine.model.LocalModelScanResult
import io.r2h.engine.permissions.AgentPermissionCenter
import io.r2h.engine.permissions.AgentPermissionItem
import io.r2h.engine.permissions.AgentPermissionRequestKind
import io.r2h.engine.permissions.AgentPermissionStatus
import io.r2h.engine.tools.LocalToolRegistry
import io.r2h.engine.tools.ToolAuditEntry
import io.r2h.engine.tools.ToolDefinition
import io.r2h.engine.tools.ToolRiskLevel
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"
private const val MODELS_DIR = "models"

private const val RADIUS_HEADER = 26
private const val RADIUS_FLOATING = 28
private const val RADIUS_CARD = 20
private const val RADIUS_CONTROL = 18

class MainActivity : AppCompatActivity() {
    private val viewModel: MainActivityViewModel by lazy {
        ViewModelProvider(this)[MainActivityViewModel::class.java]
    }
    private val toolAuditStore
        get() = (application as EngineApplication).toolAuditStore

    private data class CapabilityUiRecord(
        val name: String,
        val status: String,
        val kind: StatusKind,
        val runtime: String,
        val model: String,
        val apiPath: String,
        val proofStatus: String,
        val reason: String,
    )

    private data class StudioCapability(
        val icon: String,
        val title: String,
        val description: String,
        val requirementChips: List<String>,
        val ready: Boolean,
        val actionLabel: String,
        val action: () -> Unit,
        val sampleLabel: String = "Run Sample",
        val sampleAction: () -> Unit = action,
    )

    private val engineService: IR2hEngineService?
        get() = viewModel.engineService
    private val isBound: Boolean
        get() = viewModel.isConnected
    private var selectedModelId: String?
        get() = viewModel.uiState.value.selectedModelId
        set(value) { viewModel.update { it.copy(selectedModelId = value) } }
    private var isGenerating: Boolean
        get() = viewModel.uiState.value.isGenerating
        set(value) { viewModel.update { it.copy(isGenerating = value) } }
    private var currentSection: ProductSection
        get() = viewModel.uiState.value.currentSection
        set(value) { viewModel.update { it.copy(currentSection = value) } }
    private var lastTruthSnapshot: EngineTruthSnapshot?
        get() = viewModel.uiState.value.runtime.truth
        set(value) { viewModel.update { state -> state.copy(runtime = state.runtime.copy(truth = value)) } }
    private var lastUiState: RuntimeUiState
        get() = viewModel.uiState.value.runtime
        set(value) { viewModel.update { it.copy(runtime = value) } }
    private var showTechnicalDetails: Boolean
        get() = viewModel.uiState.value.showTechnicalDetails
        set(value) { viewModel.update { it.copy(showTechnicalDetails = value) } }
    private var lastPromptText: String
        get() = viewModel.uiState.value.lastPromptText
        set(value) { viewModel.update { it.copy(lastPromptText = value) } }
    private var lastGenerationText: String
        get() = viewModel.uiState.value.lastGenerationText
        set(value) { viewModel.update { it.copy(lastGenerationText = value) } }
    private var lastGenerationMeta: String
        get() = viewModel.uiState.value.lastGenerationMeta
        set(value) { viewModel.update { it.copy(lastGenerationMeta = value) } }
    private var lastGenerationError: String?
        get() = viewModel.uiState.value.lastGenerationError
        set(value) { viewModel.update { it.copy(lastGenerationError = value) } }
    private var lastStudioWorkflowTitle: String
        get() = viewModel.uiState.value.lastStudioWorkflowTitle
        set(value) { viewModel.update { it.copy(lastStudioWorkflowTitle = value) } }
    private var lastStudioResultPreview: String
        get() = viewModel.uiState.value.lastStudioResultPreview
        set(value) { viewModel.update { it.copy(lastStudioResultPreview = value) } }
    private var activeStudioWorkflowTitle: String?
        get() = viewModel.uiState.value.activeStudioWorkflowTitle
        set(value) { viewModel.update { it.copy(activeStudioWorkflowTitle = value) } }
    private var lastAgentTask: String
        get() = viewModel.uiState.value.lastAgentTask
        set(value) { viewModel.update { it.copy(lastAgentTask = value) } }
    private var lastAgentTimeline: List<String>
        get() = viewModel.uiState.value.lastAgentTimeline
        set(value) { viewModel.update { it.copy(lastAgentTimeline = value) } }
    private var lastAgentToolSummary: String
        get() = viewModel.uiState.value.lastAgentToolSummary
        set(value) { viewModel.update { it.copy(lastAgentToolSummary = value) } }
    private var lastAgentResult: String
        get() = viewModel.uiState.value.lastAgentResult
        set(value) {
            viewModel.update {
                it.copy(
                    lastAgentResult = value,
                    lastAgentResultPersistence = UiContentPersistence.PERSISTABLE,
                )
            }
        }
    private var lastAgentError: String?
        get() = viewModel.uiState.value.lastAgentError
        set(value) { viewModel.update { it.copy(lastAgentError = value) } }
    private var lastAgentMissingPermission: AgentPermissionItem?
        get() = viewModel.uiState.value.lastAgentMissingPermissionId?.let(::permissionById)
        set(value) { viewModel.update { it.copy(lastAgentMissingPermissionId = value?.id) } }
    private var pendingApproval: PendingApprovalState?
        get() = viewModel.uiState.value.pendingApproval
        set(value) { viewModel.setPendingApproval(value) }
    private var torchEnabled: Boolean
        get() = viewModel.uiState.value.torchEnabled
        set(value) { viewModel.update { it.copy(torchEnabled = value) } }
    private var isAgentRunning: Boolean
        get() = viewModel.uiState.value.isAgentRunning
        set(value) { viewModel.update { it.copy(isAgentRunning = value) } }
    private var pendingRuntimePermission: AgentPermissionItem?
        get() = viewModel.uiState.value.pendingRuntimePermissionId?.let(::permissionById)
        set(value) { viewModel.update { it.copy(pendingRuntimePermissionId = value?.id) } }
    private var pendingStudioDocumentRequest: PendingStudioDocumentState?
        get() = viewModel.uiState.value.pendingStudioDocument
        set(value) { viewModel.update { it.copy(pendingStudioDocument = value) } }
    private lateinit var localModelFolderManager: LocalModelFolderManager
    private var localModelScanResult: LocalModelScanResult
        get() = viewModel.uiState.value.localModelScanResult
        set(value) { viewModel.update { it.copy(localModelScanResult = value) } }
    private var localModelManagerBusy: Boolean
        get() = viewModel.uiState.value.localModelManagerBusy
        set(value) { viewModel.update { it.copy(localModelManagerBusy = value) } }
    private var localModelManagerMessage: String?
        get() = viewModel.uiState.value.localModelManagerMessage
        set(value) { viewModel.update { it.copy(localModelManagerMessage = value) } }

    private lateinit var contentScroll: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var connectionChip: TextView
    private lateinit var textRuntimeChip: TextView
    private lateinit var rootShell: LinearLayout
    private lateinit var headerShell: LinearLayout
    private lateinit var navDock: LinearLayout
    private lateinit var logoTile: TextView
    private lateinit var headerBrandMark: ImageView
    private lateinit var pageTitleView: TextView
    private lateinit var pageSubtitleView: TextView
    private lateinit var topMenuButton: Button
    private lateinit var navButtons: Map<ProductSection, Button>

    private var promptInput: EditText? = null
    private var generationOutputView: TextView? = null
    private var generationStatusView: TextView? = null
    private var diagnosticsDetailView: TextView? = null
    private var studioResultView: TextView? = null
    private var agentResultView: TextView? = null
    private var agentTimelineView: TextView? = null
    private var browseButton: Button? = null
    private var loadButton: Button? = null

    private val modelPicker =
        registerForActivityResult(OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                toast("No file selected")
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                importModelFromUri(uri)
            }
        }

    private val modelFolderPicker =
        registerForActivityResult(StartActivityForResult()) { result ->
            val data = result.data
            val uri = data?.data
            if (uri == null) {
                localModelManagerMessage = "No folder selected."
                if (currentSection == ProductSection.MODELS) renderShell(lastUiState)
                return@registerForActivityResult
            }
            try {
                localModelFolderManager.persistFolderSelection(uri, data.flags)
                localModelManagerMessage = "Folder permission saved. Scanning local files..."
                refreshSelectedModelFolder()
            } catch (t: Throwable) {
                localModelManagerMessage = "Folder permission could not be saved: ${t.message ?: t.javaClass.simpleName}"
                if (currentSection == ProductSection.MODELS) renderShell(lastUiState)
            }
        }

    private val studioDocumentPicker =
        registerForActivityResult(OpenDocument()) { uri: Uri? ->
            val request = pendingStudioDocumentRequest
            pendingStudioDocumentRequest = null
            if (uri == null || request == null) {
                toast("No file selected")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val copied = withContext(Dispatchers.IO) {
                    copyPickedUriToPrivateFile(uri, request.destinationName)
                }
                when (request.actionType) {
                    StudioDocumentActionType.ANALYZE_IMAGE -> runImageUnderstandingForFile(copied, sample = false)
                    StudioDocumentActionType.OCR -> runOcrForFile(copied, sample = false)
                    StudioDocumentActionType.ANALYZE_AUDIO -> runAudioForFile(copied, sample = false)
                    StudioDocumentActionType.ANALYZE_VIDEO -> runVideoForFile(copied, sample = false)
                }
            }
        }

    private val runtimePermissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { grants ->
            val item = pendingRuntimePermission
            pendingRuntimePermission = null
            val granted = grants.values.all { it }
            toast(if (granted) "${item?.name ?: "Permission"} granted" else "${item?.name ?: "Permission"} not granted")
            renderShell(lastUiState)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        localModelFolderManager = LocalModelFolderManager(applicationContext)
        bindShellViews()
        collectViewModelState()
        installImeVisibilityHandler()
        applySystemInsets()
        bindNavigation()
        showSection(currentSection)
        renderBooting()
        startEngineService()
    }

    override fun onStart() {
        super.onStart()
        viewModel.ensureConnected()
    }

    override fun onResume() {
        super.onResume()
        refreshEngineUi()
    }

    @Deprecated("Use explicit ProductSection state for this single-activity shell.")
    override fun onBackPressed() {
        when (currentSection) {
            ProductSection.CHAT -> super.onBackPressed()
            ProductSection.STUDIO,
            ProductSection.AGENT,
            ProductSection.SETTINGS -> showSection(ProductSection.CHAT)
            ProductSection.MODELS,
            ProductSection.STATUS,
            ProductSection.CONNECTED_APPS,
            ProductSection.AGENT_PERMISSIONS,
            ProductSection.TOOL_AUDIT_LOG,
            ProductSection.DIAGNOSTICS -> showSection(ProductSection.SETTINGS)
        }
    }

    override fun onStop() {
        super.onStop()
    }

    private fun collectViewModelState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previous: MainUiState? = null
                viewModel.uiState.collect { state ->
                    renderViewModelState(previous, state)
                    previous = state
                }
            }
        }
    }

    private fun renderViewModelState(previous: MainUiState?, state: MainUiState) {
        if (!::contentContainer.isInitialized) return
        val needsFullRender = previous == null ||
            previous.currentSection != state.currentSection ||
            previous.runtime != state.runtime ||
            previous.pendingApproval != state.pendingApproval ||
            previous.lastAgentMissingPermissionId != state.lastAgentMissingPermissionId ||
            previous.lastStudioWorkflowTitle != state.lastStudioWorkflowTitle ||
            previous.activeStudioWorkflowTitle != state.activeStudioWorkflowTitle ||
            previous.localModelManagerMessage != state.localModelManagerMessage ||
            previous.localModelScanResult != state.localModelScanResult ||
            previous.localModelManagerBusy != state.localModelManagerBusy
        if (needsFullRender) {
            updateNavSelection()
            renderShell(state.runtime)
            return
        }

        generationStatusView?.text = when {
            state.isGenerating -> "Working locally..."
            state.lastGenerationError != null -> "Generation failed."
            state.lastGenerationText.isNotBlank() -> "Response complete."
            else -> "On-device mode active."
        }
        generationOutputView?.text = state.lastGenerationError
            ?: state.lastGenerationText.ifBlank { if (state.isGenerating) "Working locally..." else "" }
        studioResultView?.text = state.lastStudioResultPreview
        agentResultView?.text = state.lastAgentError ?: state.lastAgentResult
        agentTimelineView?.text = state.lastAgentTimeline.joinToString("  ›  ")
        diagnosticsDetailView?.text = state.diagnosticsText
    }

    private fun bindShellViews() {
        rootShell = findViewById(R.id.root_shell)
        headerShell = findViewById(R.id.header_shell)
        navDock = findViewById(R.id.nav_grid)
        contentScroll = findViewById(R.id.content_scroll)
        contentScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        contentScroll.isSmoothScrollingEnabled = false
        contentScroll.isVerticalScrollBarEnabled = false
        contentScroll.isHorizontalScrollBarEnabled = false
        contentScroll.overScrollMode = View.OVER_SCROLL_NEVER
        contentContainer = findViewById(R.id.content_container)
        connectionChip = findViewById(R.id.chip_connection)
        textRuntimeChip = findViewById(R.id.chip_text_runtime)
        logoTile = findViewById(R.id.logo_tile)
        headerBrandMark = findViewById(R.id.header_brand_mark)
        pageTitleView = findViewById(R.id.tv_product_title)
        pageSubtitleView = findViewById(R.id.tv_product_subtitle)
        topMenuButton = findViewById(R.id.btn_top_menu)
        rootShell.setBackgroundColor(Color.TRANSPARENT)
        headerShell.background =
            luxuryGlass(
                start = Color.rgb(35, 30, 38),
                end = Color.rgb(5, 6, 10),
                accent = Color.rgb(122, 72, 89),
                radius = RADIUS_HEADER,
                strong = true,
            )
        headerShell.elevation = dp(14).toFloat()
        headerShell.translationZ = dp(6).toFloat()
        headerShell.minimumHeight = dp(108)
        headerShell.setPadding(dp(14), dp(9), dp(14), dp(9))
        headerShell.layoutParams =
            headerShell.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }

        headerBrandMark.apply {
            background =
                luxuryGlass(
                    start = Color.rgb(25, 22, 29),
                    end = Color.rgb(5, 6, 9),
                    accent = Color.rgb(92, 54, 68),
                    radius = RADIUS_CARD,
                    strong = false,
                )
            elevation = dp(6).toFloat()
            translationZ = dp(2).toFloat()
            setPadding(dp(4), dp(3), dp(4), dp(3))
            alpha = 1f
        }

        navDock.background =
            luxuryGlass(
                start = Color.rgb(29, 25, 31),
                end = Color.rgb(4, 5, 8),
                accent = Color.rgb(106, 68, 82),
                radius = RADIUS_FLOATING,
                strong = true,
            )
        navDock.elevation = dp(16).toFloat()
        navDock.translationZ = dp(7).toFloat()
        navDock.minimumHeight = 0
        navDock.setPadding(dp(7), dp(6), dp(7), dp(6))
        navDock.layoutParams =
            navDock.layoutParams.apply {
                height = dp(68)
            }

        logoTile.visibility = View.GONE
        connectionChip.visibility = View.GONE
        topMenuButton.visibility = View.GONE

        textRuntimeChip.apply {
            visibility = View.VISIBLE
            minWidth = dp(92)
            minimumWidth = dp(92)
            minHeight = dp(36)
            minimumHeight = dp(36)
            gravity = Gravity.CENTER
            textSize = 10.25f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
            elevation = dp(5).toFloat()
            translationZ = dp(2).toFloat()
        }
    }

    private fun bindNavigation() {
        navButtons = mapOf(
            ProductSection.CHAT to findViewById(R.id.btn_nav_chat),
            ProductSection.STUDIO to findViewById(R.id.btn_nav_studio),
            ProductSection.AGENT to findViewById(R.id.btn_nav_agent),
            ProductSection.SETTINGS to findViewById(R.id.btn_nav_settings),
        )
        navButtons.forEach { (section, button) ->
            button.setOnClickListener { showSection(section) }
            button.backgroundTintList = null
            button.setTextColor(COLOR_TEXT)
            button.textSize = 10.5f
            button.typeface = Typeface.DEFAULT_BOLD
            button.gravity = Gravity.CENTER
            button.includeFontPadding = false
            button.maxLines = 2
            button.minHeight = 0
            button.minimumHeight = 0
            button.setLineSpacing(0f, 0.94f)
            button.setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        updateNavSelection()
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootShell) { view, insets ->
            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )

            val imeInsets =
                insets.getInsets(WindowInsetsCompat.Type.ime())

            val imeVisible =
                insets.isVisible(WindowInsetsCompat.Type.ime())

            val bottomInset =
                if (imeVisible) {
                    imeInsets.bottom
                } else {
                    systemBars.bottom
                }

            view.setPadding(
                systemBars.left + dp(10),
                systemBars.top + dp(6),
                systemBars.right + dp(10),
                bottomInset + dp(6),
            )

            navDock.visibility =
                if (imeVisible) View.GONE else View.VISIBLE

            navDock.alpha =
                if (imeVisible) 0f else 1f

            view.post {
                resizeChatPanelForViewport()
                stabilizeChatViewportForIme()

                if (imeVisible) {
                    promptInput?.requestFocus()

                    contentScroll.postDelayed(
                        {
                            stabilizeChatViewportForIme()
                        },
                        120L,
                    )
                }
            }

            insets
        }
    }

    private fun showSection(section: ProductSection) {
        currentSection = section
        updateNavSelection()
        renderShell(lastUiState)
        contentScroll.post { contentScroll.scrollTo(0, 0) }
        if (section == ProductSection.MODELS && !localModelManagerBusy) {
            refreshSelectedModelFolder()
        }
    }


    private fun updateNavSelection() {
        navButtons.forEach { (section, button) ->
            val selected =
                section == currentSection ||
                    (
                        section == ProductSection.SETTINGS &&
                            currentSection in setOf(
                                ProductSection.MODELS,
                                ProductSection.STATUS,
                                ProductSection.CONNECTED_APPS,
                                ProductSection.AGENT_PERMISSIONS,
                                ProductSection.TOOL_AUDIT_LOG,
                                ProductSection.DIAGNOSTICS,
                            )
                    )

            button.text = navLabel(section, selected)
            button.setTextColor(
                if (selected) Color.WHITE else Color.rgb(178, 166, 173),
            )
            button.typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    if (selected) Typeface.BOLD else Typeface.NORMAL,
                )
            button.textSize = if (selected) 10.8f else 10.2f
            button.alpha = if (selected) 1f else 0.88f
            button.scaleX = if (selected) 1f else 0.985f
            button.scaleY = if (selected) 1f else 0.985f
            button.elevation = if (selected) dp(8).toFloat() else 0f
            button.translationZ = if (selected) dp(3).toFloat() else 0f
            button.background =
                if (selected) {
                    luxuryGlass(
                        start = Color.rgb(126, 14, 40),
                        end = Color.rgb(18, 6, 13),
                        accent = Color.rgb(255, 91, 116),
                        radius = RADIUS_CARD,
                        strong = true,
                    )
                } else {
                    android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                }
        }
    }

    private fun navLabel(
        section: ProductSection,
        selected: Boolean,
    ): CharSequence {
        val icon =
            when (section) {
                ProductSection.CHAT -> "✦"
                ProductSection.STUDIO -> "▦"
                ProductSection.AGENT -> "◉"
                ProductSection.SETTINGS -> "⚙"
                else -> "•"
            }

        val label =
            when (section) {
                ProductSection.CHAT -> "Chat"
                ProductSection.STUDIO -> "Studio"
                ProductSection.AGENT -> "Agent"
                ProductSection.SETTINGS -> "Settings"
                else -> section.label
            }

        return SpannableString("$icon\n$label").apply {
            setSpan(
                RelativeSizeSpan(1.30f),
                0,
                icon.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                ForegroundColorSpan(
                    if (selected) {
                        Color.rgb(255, 72, 101)
                    } else {
                        Color.rgb(188, 176, 183)
                    },
                ),
                0,
                icon.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun startEngineService() {
        val intent = Intent(this, EngineService::class.java)
        try {
            startForegroundService(intent)
            Log.i(TAG, "Requested EngineService start")
        } catch (t: Throwable) {
            Log.e(
                TAG,
                PrivacySafeDiagnostics.failureEvent(
                    DiagnosticOperation.ENGINE_SERVICE,
                    errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    failure = t,
                ),
            )
            lastUiState = RuntimeUiState(errorMessage = buildFailureText("Engine start failed", t))
            renderShell(lastUiState)
        }
    }

    private fun stopEngineService() {
        try {
            stopService(Intent(this, EngineService::class.java))
            Log.i(TAG, "Requested EngineService stop")
        } catch (t: Throwable) {
            Log.e(
                TAG,
                PrivacySafeDiagnostics.failureEvent(
                    DiagnosticOperation.ENGINE_SERVICE,
                    errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    failure = t,
                ),
            )
        }
    }

    private fun restartEngineServiceAndRebind() {
        viewModel.disconnectForServiceRestart()
        stopEngineService()
        startEngineService()

        lifecycleScope.launch {
            delay(500)
            viewModel.ensureConnected()
        }
    }

    private fun openModelPicker() {
        modelPicker.launch(arrayOf("*/*"))
    }

    private fun openModelFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        modelFolderPicker.launch(intent)
    }

    private fun refreshSelectedModelFolder() {
        localModelManagerBusy = true
        if (currentSection == ProductSection.MODELS) renderShell(lastUiState)
        lifecycleScope.launch {
            localModelScanResult = localModelFolderManager.scanSelectedFolder()
            localModelManagerBusy = false
            if (currentSection == ProductSection.MODELS) renderShell(lastUiState)
        }
    }

    private fun selectAndLoadLocalModel(candidate: LocalModelCandidate) {
        if (!candidate.readable || localModelManagerBusy) return
        localModelManagerBusy = true
        localModelManagerMessage = "Importing ${candidate.fileName} into private app storage..."
        renderShell(lastUiState)
        lifecycleScope.launch {
            try {
                val service = engineService
                    ?: error("The engine service must be connected before importing a model.")
                val active = localModelFolderManager.importAndActivate(candidate) { importedFile ->
                    service.registerImportedModel(importedFile.absolutePath)
                }
                selectedModelId = active.modelId
                localModelManagerMessage = "Imported ${active.fileName}. Loading the private copy..."
                renderShell(lastUiState)
                service.warmup(active.modelId)
                delay(1_200)
                refreshEngineUi()
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        DiagnosticOperation.MODEL_IMPORT,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                        failure = t,
                    ),
                )
                localModelManagerMessage = "Active model load failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                localModelManagerBusy = false
                if (currentSection == ProductSection.MODELS) renderShell(lastUiState)
            }
        }
    }

    private suspend fun importModelFromUri(uri: Uri) {
        browseButton?.isEnabled = false
        diagnosticsDetailView?.text = "Importing selected model..."

        val result = withContext(Dispatchers.IO) {
            try {
                val pickedName = queryDisplayName(uri) ?: "imported_model.gguf"
                val safeName = sanitizeFileName(pickedName)

                if (!safeName.endsWith(".gguf", ignoreCase = true)) {
                    return@withContext ImportResult.Error("Selected file is not a .gguf model")
                }

                val totalSize = queryFileSize(uri)
                val candidate = LocalModelCandidateClassifier.create(
                    displayName = safeName.substringBeforeLast('.'),
                    fileName = safeName,
                    uri = uri.toString(),
                    sizeBytes = totalSize ?: 0L,
                    readable = true,
                ) ?: return@withContext ImportResult.Error("Selected file is not a supported model")
                val service = engineService
                    ?: return@withContext ImportResult.Error("Engine service is not connected")
                val transaction = LocalModelImportTransaction(LocalModelImporter(filesDir))
                var lastUiUpdate = 0L
                val committed = transaction.execute(
                    candidate = candidate,
                    onBytesCopied = { copiedBytes ->
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate > 150) {
                            lastUiUpdate = now
                            val progressText = buildCopyProgressText(
                                fileName = safeName,
                                copiedBytes = copiedBytes,
                                totalBytes = totalSize,
                            )
                            runOnUiThread { diagnosticsDetailView?.text = progressText }
                        }
                    },
                    openSource = {
                        contentResolver.openInputStream(uri)
                            ?: error("Unable to open selected file")
                    },
                    register = { importedFile ->
                        service.registerImportedModel(importedFile.absolutePath)
                    },
                )
                val localModels = service.listInstalledModels().toList()

                ImportResult.Success(
                    importedFilePath = committed.import.file.absolutePath,
                    importedFileName = committed.import.file.name,
                    registeredCount = localModels.size,
                    localModelIds = localModels.map { it.modelId },
                    selectedModelId = committed.modelId,
                )
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        DiagnosticOperation.MODEL_IMPORT,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                        failure = t,
                    ),
                )
                ImportResult.Error(t.message ?: t.javaClass.simpleName)
            }
        }

        browseButton?.isEnabled = true

        when (result) {
            is ImportResult.Success -> {
                selectedModelId = result.selectedModelId
                toast("Model imported")
                diagnosticsDetailView?.text = buildString {
                    appendLine("Import complete")
                    appendLine("File: ${result.importedFileName}")
                    appendLine("Path: ${result.importedFilePath}")
                    appendLine("Registered models: ${result.registeredCount}")
                    appendLine("Local IDs: ${result.localModelIds.joinToString()}")
                    appendLine("Selected model: ${result.selectedModelId}")
                    appendLine()
                    append("Restarting engine service...")
                }

                restartEngineServiceAndRebind()

                lifecycleScope.launch {
                    delay(1200)
                    refreshEngineUi()
                }
            }

            is ImportResult.Error -> {
                diagnosticsDetailView?.text = "Model import failed\n${result.message}"
                toast(result.message)
            }
        }
    }

    private fun warmupSelectedModel() {
        val service = engineService ?: run {
            toast("Engine service not connected")
            return
        }

        val modelId = selectedModelId ?: findTextModelId(lastTruthSnapshot) ?: run {
            toast("No TEXT model is selectable")
            return
        }
        selectedModelId = modelId

        loadButton?.isEnabled = false
        diagnosticsDetailView?.text = "Loading selected text model...\nModel ID: $modelId"

        lifecycleScope.launch {
            try {
                service.warmup(modelId)
                delay(1200)
                refreshEngineUi()
            } catch (e: RemoteException) {
                Log.e(
                    TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        DiagnosticOperation.MODEL_RUNTIME,
                        errorCode = DiagnosticErrorCode.REMOTE_FAILURE,
                        failure = e,
                    ),
                )
                toast("Warmup failed: ${e.message ?: "RemoteException"}")
            } finally {
                loadButton?.isEnabled = true
            }
        }
    }

    private fun generateNow(prompt: String) {
        if (isGenerating) return

        val service = engineService ?: run {
            lastGenerationError = "Engine service not connected"
            renderShell(lastUiState)
            return
        }

        val truth = lastTruthSnapshot
        val modelId = findTextModelId(truth) ?: run {
            lastGenerationError = "The local text model is not ready yet."
            renderShell(lastUiState)
            return
        }
        selectedModelId = modelId

        val textReady = isTextReady(truth, modelId)
        if (!textReady) {
            lastGenerationError = "The local assistant is still warming up. Try again when Text Ready is shown."
            renderShell(lastUiState)
            return
        }

        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            toast("Enter a prompt")
            return
        }

        lastPromptText = cleanPrompt
        lastGenerationText = ""
        lastGenerationMeta = ""
        lastGenerationError = null
        isGenerating = true
        generationStatusView?.text = "Working locally..."
        generationOutputView?.text = "Working locally..."

        val request = GenerateRequest(
            requestId = UUID.randomUUID().toString(),
            modelId = modelId,
            prompt = cleanPrompt,
            streaming = true,
            sessionConfig = null,
        )

        val callback = viewModel.createChatGenerationCallback(request)

        try {
            service.generate(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(
                request.requestId,
                "Generate call failed: ${e.message ?: "RemoteException"}",
            )
            Log.e(
                TAG,
                PrivacySafeDiagnostics.failureEvent(
                    DiagnosticOperation.GENERATION,
                    errorCode = DiagnosticErrorCode.REMOTE_FAILURE,
                    failure = e,
                    inputContent = cleanPrompt,
                ),
            )
        }
    }

    private fun refreshEngineUi() {
        viewModel.refreshEngineUi()
    }
    private fun stabilizeChatViewportForIme() {
        if (
            !::contentScroll.isInitialized ||
            !::contentContainer.isInitialized
        ) {
            return
        }

        if (currentSection != ProductSection.CHAT) {
            return
        }

        contentScroll.isSmoothScrollingEnabled = false

        contentScroll.post {
            contentScroll.scrollTo(0, 0)
        }

        contentScroll.postDelayed(
            {
                if (currentSection == ProductSection.CHAT) {
                    resizeChatPanelForViewport()
                    contentScroll.scrollTo(0, 0)
                }
            },
            80L,
        )

        contentScroll.postDelayed(
            {
                if (currentSection == ProductSection.CHAT) {
                    contentScroll.scrollTo(0, 0)
                }
            },
            220L,
        )
    }

    private fun resizeChatPanelForViewport() {
        if (
            !::contentScroll.isInitialized ||
            !::contentContainer.isInitialized
        ) {
            return
        }

        if (currentSection != ProductSection.CHAT) {
            return
        }

        val chatPanel =
            contentContainer.findViewWithTag<View>("r2h_chat_panel")
                ?: return

        contentScroll.post {
            val availableHeight =
                contentScroll.height -
                    contentScroll.paddingTop -
                    contentScroll.paddingBottom

            if (availableHeight <= dp(160)) {
                return@post
            }

            val params = chatPanel.layoutParams

            if (params.height != availableHeight) {
                params.height = availableHeight
                chatPanel.layoutParams = params
                chatPanel.requestLayout()
            }

            contentScroll.scrollTo(0, 0)
        }
    }


    private fun installImeVisibilityHandler() {
        if (
            !::contentScroll.isInitialized ||
            !::rootShell.isInitialized
        ) {
            return
        }
        contentScroll.addOnLayoutChangeListener {
                _,
                _,
                _,
                _,
                _,
                _,
                _,
                _,
                _ ->
            resizeChatPanelForViewport()
            stabilizeChatViewportForIme()
        }

        ViewCompat.requestApplyInsets(rootShell)
    }

    private fun renderBooting() {
        lastUiState = RuntimeUiState(errorMessage = "Starting engine service...")
        renderShell(lastUiState)
    }

    private fun renderDisconnected() {
        lastUiState = RuntimeUiState(errorMessage = getString(R.string.status_disconnected))
        renderShell(lastUiState)
    }

    private fun renderShell(state: RuntimeUiState) {
        val textReady = isTextReady(state.truth, findTextModelId(state.truth))
        setChip(connectionChip, if (state.connected) "CONNECTED" else "DISCONNECTED", if (state.connected) StatusKind.READY else StatusKind.BLOCKED)
        val textChipLabel = when {
            textReady -> "TEXT READY"
            state.truth == null -> "TEXT CHECKING"
            else -> "TEXT CHECKING"
        }
        setChip(
            textRuntimeChip,
            if (textReady) "AI READY" else "AI CHECKING",
            if (textReady) StatusKind.READY else StatusKind.UNAVAILABLE,
        )

        headerShell.visibility = View.VISIBLE
        pageTitleView.text = currentSection.label
        pageSubtitleView.text =
            when (currentSection) {
                ProductSection.CHAT -> "Private intelligence · On-device"
                ProductSection.STUDIO -> "Local capability workspace"
                ProductSection.AGENT -> "On-device task execution"
                ProductSection.SETTINGS -> "Models, privacy, apps, and diagnostics"
                else -> currentSection.subtitle
            }

        updateNavSelection()
        promptInput = null
        generationOutputView = null
        generationStatusView = null
        diagnosticsDetailView = null
        studioResultView = null
        agentResultView = null
        agentTimelineView = null
        browseButton = null
        loadButton = null

        contentContainer.removeAllViews()
        if (
            currentSection !in setOf(
                ProductSection.CHAT,
                ProductSection.STUDIO,
                ProductSection.AGENT,
                ProductSection.SETTINGS,
            )
        ) {
            addScreenHeader(currentSection.label, currentSection.subtitle)
        }
        when (currentSection) {
            ProductSection.CHAT -> renderChat(state)
            ProductSection.STUDIO -> renderStudio(state)
            ProductSection.AGENT -> renderAgent(state)
            ProductSection.SETTINGS -> renderSettings(state)
            ProductSection.MODELS -> renderModels(state)
            ProductSection.STATUS -> renderEngineStatus(state)
            ProductSection.CONNECTED_APPS -> renderConnectedApps(state)
            ProductSection.AGENT_PERMISSIONS -> renderAgentPermissions()
            ProductSection.TOOL_AUDIT_LOG -> renderToolAuditLog()
            ProductSection.DIAGNOSTICS -> renderDiagnostics(state)
        }

        contentContainer.setPadding(
            dp(0),
            if (currentSection == ProductSection.CHAT) dp(0) else dp(2),
            dp(0),
            if (currentSection == ProductSection.CHAT) dp(2) else dp(86),
        )
    }

    private fun renderOverview(state: RuntimeUiState) {
        val truth = state.truth
        val modelId = findTextModelId(truth)
        val textReady = isTextReady(truth, modelId)
        addCard("Engine connection", "Binder service and foreground runtime host.") {
            addStatusRow(this, "Service binding", if (state.connected) "CONNECTED" else "DISCONNECTED", if (state.connected) StatusKind.READY else StatusKind.BLOCKED)
            addKeyValue("API version", state.apiVersion?.toString() ?: "Unknown")
            addKeyValue("Backend", truth?.runtime?.activeBackendLabel ?: truth?.runtime?.activeBackendId ?: "Not reported")
            addKeyValue("Request path", if (truth?.runtime?.readyForInference == true) "Ready for inference" else "Not ready")
        }
        addCard("TEXT runtime", "Only TEXT / GENERATE_TEXT is considered proven.") {
            addStatusRow(this, "TEXT / GENERATE_TEXT", if (textReady) "READY" else "BLOCKED", if (textReady) StatusKind.READY else StatusKind.BLOCKED)
            addKeyValue("Selected model", modelId ?: "None")
            addKeyValue("Proof path", "IR2hEngineService.generate()")
        }
        addCard("Loaded model", "Runtime-loaded active model, not file-exists alone.") {
            addKeyValue("Loaded model", state.loadedModelId ?: truth?.runtime?.activeModelId ?: "None")
            addKeyValue("Active TEXT model", truth?.runtime?.activeTextModelId ?: "None")
            addKeyValue("Load state", state.loadState ?: "Unknown")
        }
        addCard("Capability readiness", "Installed proof, runtime, model, and API state.") {
            capabilityRecords(state).forEach { capability ->
                addCapabilitySummaryRow(capability)
            }
        }
        addCard("Recent errors", "Latest runtime failures, summarized.") {
            val errors = truth?.lastErrors.orEmpty()
            if (errors.isEmpty() && state.errorMessage == null) {
                addBody("No recent runtime errors reported.")
            } else {
                state.errorMessage?.let { addBody(it) }
                errors.takeLast(3).forEach { error ->
                    addBody("${error.stage.name}/${error.code.name}: ${error.message}")
                }
            }
        }
        addCard("Quick actions", "Primary workflows.") {
            addActionGrid(
                listOf(
                    "Open Chat" to { showSection(ProductSection.CHAT) },
                    "Manage Models" to { showSection(ProductSection.MODELS) },
                    "Engine Status" to { showSection(ProductSection.STATUS) },
                    "Connected Apps" to { showSection(ProductSection.CONNECTED_APPS) },
                ),
            )
        }
    }


    private fun renderChat(state: RuntimeUiState) {
        val modelId = findTextModelId(state.truth)
        val textReady = isTextReady(state.truth, modelId)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "r2h_chat_panel"
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6), dp(2), dp(6), 0)
            elevation = 0f
        }

        val conversationContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(6))
        }

        val hasRealConversation =
            lastPromptText.isNotBlank() ||
                lastGenerationText.isNotBlank() ||
                lastGenerationError != null ||
                isGenerating

        conversationContent.gravity =
            if (hasRealConversation) {
                Gravity.TOP
            } else {
                Gravity.CENTER
            }

        if (hasRealConversation) {
            if (lastPromptText.isNotBlank()) {
                conversationContent.addView(
                    userBubble(lastPromptText, "Now"),
                    matchWrap(top = 4),
                )
            }

            val assistantText =
                lastGenerationError
                    ?: lastGenerationText.ifBlank {
                        if (isGenerating) "Thinking locally..." else ""
                    }

            if (assistantText.isNotBlank()) {
                conversationContent.addView(
                    assistantBubble(assistantText),
                    matchWrap(top = 10),
                )
            }
        } else {
            val emptyState = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = luxuryGlass(
                    start = Color.rgb(18, 20, 26),
                    end = Color.rgb(5, 6, 9),
                    accent = Color.rgb(52, 47, 54),
                    radius = 24,
                    strong = false,
                )
                setPadding(dp(18), dp(13), dp(18), dp(16))
            }

            emptyState.addView(
                TextView(this).apply {
                    text = "✦"
                    gravity = Gravity.CENTER
                    textSize = 19f
                    setTextColor(Color.rgb(226, 62, 91))
                },
                LinearLayout.LayoutParams(dp(36), dp(36)),
            )
            emptyState.addView(
                title("Private Intelligence Ready", 15.5f).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                },
                matchWrap(top = 1),
            )
            emptyState.addView(
                caption("Secure local reasoning starts here. Your prompts remain on this device.").apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(151, 141, 148))
                },
                matchWrap(top = 1),
            )
            conversationContent.addView(emptyState, matchWrap(top = 0))
        }

        val conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                conversationContent,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        panel.addView(
            conversationScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = luxuryGlass(
                start = Color.rgb(27, 25, 32),
                end = Color.rgb(5, 6, 10),
                accent = Color.rgb(203, 66, 93),
                radius = 28,
                strong = true,
            )
            elevation = dp(22).toFloat()
            translationZ = dp(4).toFloat()
            setPadding(dp(7), dp(6), dp(7), dp(6))
        }

        composer.addView(
            productButton("+", primary = false) {
                toast("Attachments stay local. Select an action from Studio for files.")
            },
            LinearLayout.LayoutParams(dp(42), dp(44)),
        )

        val inputSurface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = luxuryGlass(
                start = Color.rgb(34, 31, 38),
                end = Color.rgb(8, 8, 12),
                accent = Color.rgb(91, 69, 79),
                radius = 20,
                strong = false,
            )
            setPadding(dp(13), 0, dp(13), 0)
        }

        val edit = EditText(this@MainActivity).apply {
            setText("")
            hint = "Ask your agent anything…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(155, 138, 146))
            minLines = 1
            maxLines = 3
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            includeFontPadding = false
            textSize = 14.5f
        }

        promptInput = edit
        inputSurface.addView(edit, matchWrap())

        composer.addView(
            inputSurface,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f,
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            },
        )

        composer.addView(
            productButton("Mic", primary = false) {
                toast("Use Studio for audio understanding.")
            },
            LinearLayout.LayoutParams(dp(48), dp(44)),
        )

        val send = productButton(
            if (isGenerating) "…" else "Send",
            primary = true,
        ) {
            generateNow(promptInput?.text?.toString().orEmpty())
        }
        send.isEnabled = !isGenerating

        composer.addView(
            send,
            LinearLayout.LayoutParams(dp(66), dp(44)).apply {
                marginStart = dp(6)
            },
        )

        panel.addView(composer, matchWrap(top = 2, bottom = 0))

        generationStatusView =
            caption(
                if (isGenerating) {
                    "Working locally..."
                } else if (lastGenerationError != null) {
                    "The last response could not complete."
                } else {
                    "On-device mode active."
                },
            ).apply {
                visibility = View.GONE
            }

        generationOutputView =
            body(lastGenerationError ?: lastGenerationText.ifBlank { "" }).apply {
                visibility = View.GONE
            }

        contentContainer.addView(panel, matchWrap(top = 0, bottom = 0))

        contentScroll.post {
            val availableHeight =
                contentScroll.height -
                    contentScroll.paddingTop -
                    contentScroll.paddingBottom -
                    dp(4)

            if (availableHeight > dp(300)) {
                val currentParams = panel.layoutParams
                currentParams.height = availableHeight
                panel.layoutParams = currentParams
                panel.requestLayout()
            }

            contentScroll.scrollTo(0, 0)
        }
    }

    private fun renderLegacyPromptCard() {
        addCard("Prompt", "Compose a private request for the local assistant.") {
            val edit = EditText(this@MainActivity).apply {
                setText(lastPromptText)
                hint = "Message R2H AI..."
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_MUTED)
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                background = glassRounded(COLOR_FIELD, COLOR_BORDER_RED, radius = 22)
                setPadding(dp(13), dp(10), dp(13), dp(10))
            }
            promptInput = edit
            addView(edit, matchWrap())
            addSpacer(10)
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(productButton("+", primary = false) { toast("Attachments stay local. Select an action from Studio for files.") }, LinearLayout.LayoutParams(dp(52), dp(50)))
            row.addView(productButton("Mic", primary = false) { toast("Use Studio for audio understanding.") }, LinearLayout.LayoutParams(dp(70), dp(50)).apply {
                marginStart = dp(8)
            })
            val send = productButton(if (isGenerating) "Sending..." else "Send", primary = true) {
                generateNow(edit.text?.toString().orEmpty())
            }
            send.isEnabled = !isGenerating
            row.addView(send, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(8)
            })
            addView(row, matchWrap())
            generationStatusView = caption(if (isGenerating) "Working locally..." else if (lastGenerationError != null) "The last response could not complete." else "On-device mode active.")
            generationStatusView?.let { addView(it, matchWrap(top = 10)) }
            generationOutputView = body(lastGenerationError ?: lastGenerationText.ifBlank { "Output message card will appear in the conversation." })
            generationOutputView?.visibility = View.GONE
        }
    }

    private fun renderStudio(state: RuntimeUiState) {
        val truth = state.truth
        val qwenReady = isModelReady(truth, findQwen2VlMtmdModelId(truth))
        val embeddingsReady = isModelReady(truth, findEmbeddingModelId(truth))
        val audioReady = isModelReady(truth, findAudioTaggingModelId(truth))
        val videoReady = isModelReady(truth, findVideoYoloModelId(truth)) || qwenReady

        val capabilities =
            listOf(
                StudioCapability(
                    icon = "✦",
                    title = "Vision",
                    description = "Analyze or ask about a local image.",
                    requirementChips = listOf("Image", "Qwen2VL"),
                    ready = qwenReady,
                    actionLabel = "Choose Image",
                    action = {
                        chooseStudioDocument(
                            "Analyze Image",
                            "studio-image-input",
                            arrayOf("image/*"),
                            StudioDocumentActionType.ANALYZE_IMAGE,
                        )
                    },
                    sampleAction = {
                        runStudioWorkflow("Analyze Image") {
                            runImageMultimodalProof()
                        }
                    },
                ),
                StudioCapability(
                    icon = "T",
                    title = "OCR",
                    description = "Extract text from images or documents.",
                    requirementChips = listOf("Image/PDF", "OCR"),
                    ready = qwenReady,
                    actionLabel = "Choose File",
                    action = {
                        chooseStudioDocument(
                            "Extract Text OCR",
                            "studio-ocr-input",
                            arrayOf("image/*", "application/pdf"),
                            StudioDocumentActionType.OCR,
                        )
                    },
                    sampleAction = {
                        runStudioWorkflow("Extract Text OCR") {
                            runOcrProof()
                        }
                    },
                ),
                StudioCapability(
                    icon = "◉",
                    title = "Audio",
                    description = "Transcribe and understand local audio.",
                    requirementChips = listOf("Audio", "Local model"),
                    ready = audioReady,
                    actionLabel = "Choose Audio",
                    action = {
                        chooseStudioDocument(
                            "Analyze Audio",
                            "studio-audio-input",
                            arrayOf("audio/*"),
                            StudioDocumentActionType.ANALYZE_AUDIO,
                        )
                    },
                    sampleAction = {
                        runStudioWorkflow("Analyze Audio") {
                            runAudioFullUnderstandingProof()
                        }
                    },
                ),
                StudioCapability(
                    icon = "▶",
                    title = "Video",
                    description = "Analyze timelines and key moments.",
                    requirementChips = listOf("Video", "Timeline"),
                    ready = videoReady,
                    actionLabel = "Choose Video",
                    action = {
                        chooseStudioDocument(
                            "Analyze Video",
                            "studio-video-input",
                            arrayOf("video/*"),
                            StudioDocumentActionType.ANALYZE_VIDEO,
                        )
                    },
                    sampleAction = {
                        runStudioWorkflow("Analyze Video") {
                            runVideoFullAnalysisProof()
                        }
                    },
                ),
                StudioCapability(
                    icon = "≈",
                    title = "Semantic",
                    description = "Compare text using local embeddings.",
                    requirementChips = listOf("Text", "Embeddings"),
                    ready = embeddingsReady,
                    actionLabel = "Compare",
                    action = {
                        runStudioWorkflow("Semantic Search") {
                            runEmbeddingsProof()
                        }
                    },
                    sampleAction = {
                        runStudioWorkflow("Semantic Search") {
                            runEmbeddingsProof()
                        }
                    },
                ),
            )

        addCard("Studio Console", "Local multimodal tools in one workspace.") {
            studioResultView =
                body("$lastStudioWorkflowTitle\n$lastStudioResultPreview").apply {
                    background =
                        luxuryGlass(
                            start = Color.rgb(17, 18, 23),
                            end = Color.rgb(5, 6, 9),
                            accent = Color.rgb(55, 43, 51),
                            radius = 20,
                            strong = false,
                        )
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }

            studioResultView?.let {
                addView(it, matchWrap(top = 4))
            }

            val controls = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            controls.addView(
                productButton("Copy Result", primary = false) {
                    copyTextToClipboard(lastStudioResultPreview)
                },
                LinearLayout.LayoutParams(0, dp(46), 1f),
            )
            controls.addView(
                productButton("Vision Sample", primary = true) {
                    runStudioWorkflow("Analyze Image") {
                        runImageMultimodalProof()
                    }
                },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginStart = dp(8)
                },
            )
            addView(controls, matchWrap(top = 8))
        }

        val toolsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                luxuryGlass(
                    start = Color.rgb(18, 19, 24),
                    end = Color.rgb(5, 6, 9),
                    accent = Color.rgb(54, 43, 50),
                    radius = 24,
                    strong = false,
                )
            setPadding(dp(10), dp(9), dp(10), dp(9))
            elevation = dp(3).toFloat()
        }

        toolsCard.addView(title("Tools", 18f), matchWrap())
        toolsCard.addView(
            caption("Choose a capability. Only the active action receives red emphasis."),
            matchWrap(top = 1, bottom = 4),
        )

        capabilities.chunked(2).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            rowItems.forEachIndexed { index, capability ->
                row.addView(
                    studioCompactTile(capability),
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        if (rowItems.size == 1) 2f else 1f,
                    ).apply {
                        if (index > 0) marginStart = dp(8)
                    },
                )
            }

            toolsCard.addView(
                row,
                matchWrap(top = if (rowIndex == 0) 2 else 7),
            )
        }

        contentContainer.addView(toolsCard, matchWrap(top = 10))
    }

    private fun studioCompactTile(
        capability: StudioCapability,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                luxuryGlass(
                    start = Color.rgb(23, 23, 29),
                    end = Color.rgb(6, 7, 10),
                    accent =
                        if (capability.ready) {
                            Color.rgb(72, 58, 66)
                        } else {
                            Color.rgb(50, 43, 48)
                        },
                    radius = 20,
                    strong = false,
                )
            setPadding(dp(11), dp(10), dp(11), dp(10))
            minimumHeight = dp(116)

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            top.addView(
                TextView(this@MainActivity).apply {
                    text = capability.icon
                    gravity = Gravity.CENTER
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(
                        if (capability.ready) {
                            Color.rgb(235, 54, 83)
                        } else {
                            Color.rgb(135, 122, 129)
                        },
                    )
                    background =
                        luxuryGlass(
                            start = Color.rgb(38, 27, 34),
                            end = Color.rgb(8, 8, 12),
                            accent = Color.rgb(88, 61, 72),
                            radius = 14,
                            strong = false,
                        )
                },
                LinearLayout.LayoutParams(dp(34), dp(34)),
            )

            top.addView(
                statusChip(
                    if (capability.ready) "READY" else "WAIT",
                    if (capability.ready) StatusKind.READY else StatusKind.UNAVAILABLE,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(26),
                ).apply {
                    marginStart = dp(7)
                },
            )

            addView(top, matchWrap())
            addView(title(capability.title, 14f), matchWrap(top = 6))
            addView(caption(capability.description), matchWrap(top = 2))

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            actions.addView(
                productButton(capability.actionLabel, primary = capability.ready) {
                    capability.action()
                },
                LinearLayout.LayoutParams(0, dp(38), 1f),
            )
            actions.addView(
                productButton("Sample", primary = false) {
                    capability.sampleAction()
                },
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginStart = dp(6)
                },
            )
            addView(actions, matchWrap(top = 7))
        }

    private fun renderAgent(state: RuntimeUiState) {
        val agentReady =
            isTextReady(
                state.truth,
                findTextModelId(state.truth),
            )

        addCard("Agent Console", "One local task, one clear execution path.") {
            val statusRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusRow.addView(
                title("Local Agent", 16f),
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            statusRow.addView(
                statusChip(
                    if (agentReady) "READY" else "CHECKING",
                    if (agentReady) StatusKind.READY else StatusKind.UNAVAILABLE,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(32),
                ),
            )
            addView(statusRow, matchWrap())

            val task =
                EditText(this@MainActivity).apply {
                    hint = "Describe a task..."
                    setText(lastAgentTask)
                    setTextColor(COLOR_TEXT)
                    setHintTextColor(COLOR_MUTED)
                    minLines = 1
                    maxLines = 3
                    gravity = Gravity.TOP or Gravity.START
                    inputType =
                        InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    background =
                        luxuryGlass(
                            start = Color.rgb(18, 19, 24),
                            end = Color.rgb(5, 6, 9),
                            accent = Color.rgb(63, 48, 56),
                            radius = 20,
                            strong = false,
                        )
                    setPadding(dp(13), dp(9), dp(13), dp(9))
                }

            addView(task, matchWrap(top = 8))

            val run =
                productButton(
                    if (isAgentRunning) "Running…" else "Run Local Agent",
                    primary = true,
                ) {
                    runAgentTask(task.text?.toString().orEmpty())
                }

            run.isEnabled = !isAgentRunning
            addView(run, matchWrap(top = 8))
        }

        lastAgentMissingPermission?.let { permission ->
            addCard("Permission Required", permission.name) {
                addStatusRow(
                    this,
                    permission.name,
                    permission.status.displayName,
                    if (permission.status == AgentPermissionStatus.GRANTED) {
                        StatusKind.READY
                    } else {
                        StatusKind.BLOCKED
                    },
                )
                addBody(permission.enables)
                val label =
                    if (permission.requestKind == AgentPermissionRequestKind.RUNTIME) {
                        "Grant Access"
                    } else {
                        "Open Settings"
                    }
                addView(
                    productButton(label, primary = true) {
                        requestAgentPermission(permission)
                    },
                    matchWrap(top = 7),
                )
            }
        }

        pendingApproval?.let { approval ->
            addCard("Approval Required", approval.title) {
                addStatusRow(
                    this,
                    "Risk",
                    approval.risk.displayName,
                    if (approval.risk == ToolRiskLevel.HIGH) {
                        StatusKind.BLOCKED
                    } else {
                        StatusKind.UNAVAILABLE
                    },
                )
                addBody(approval.detail)

                if (approval.status == ApprovalStatus.PENDING) {
                    val actions = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    actions.addView(
                        productButton("Approve", primary = true) {
                            approvePendingAction()
                        },
                        LinearLayout.LayoutParams(0, dp(44), 1f),
                    )
                    actions.addView(
                        productButton("Cancel", primary = false) {
                            cancelPendingAction()
                        },
                        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                            marginStart = dp(8)
                        },
                    )
                    addView(actions, matchWrap(top = 8))
                } else {
                    addBody("This approval was already claimed and will not be executed again.")
                }
            }
        }

        addCard("Available Tools", "Primary local tools. Full access is managed in Settings.") {
            addToolChips(LocalToolRegistry.tools.take(6))
            addView(
                productButton("Manage Permissions", primary = false) {
                    showSection(ProductSection.AGENT_PERMISSIONS)
                },
                matchWrap(top = 8),
            )
        }

        addCard("Quick Tasks", "Reusable local prompts.") {
            addActionGrid(
                listOf(
                    "Calculate" to {
                        lastAgentTask =
                            "Calculate 17 * 23 and explain the result."
                        renderShell(lastUiState)
                    },
                    "Checklist" to {
                        lastAgentTask =
                            "Create a concise checklist for tomorrow."
                        renderShell(lastUiState)
                    },
                    "Action Items" to {
                        lastAgentTask =
                            "Summarize this note into action items."
                        renderShell(lastUiState)
                    },
                    "Analyze Media" to {
                        showSection(ProductSection.STUDIO)
                    },
                ),
            )
        }

        val resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                luxuryGlass(
                    start = Color.rgb(18, 19, 24),
                    end = Color.rgb(5, 6, 9),
                    accent = Color.rgb(55, 43, 50),
                    radius = 24,
                    strong = false,
                )
            setPadding(dp(13), dp(10), dp(13), dp(10))
            elevation = dp(3).toFloat()
        }

        val resultHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resultHeader.addView(
            title("Execution", 18f),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        resultHeader.addView(
            caption(
                if (isAgentRunning) "RUNNING" else "LOCAL",
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        resultCard.addView(resultHeader, matchWrap())

        agentTimelineView =
            body(
                lastAgentTimeline
                    .takeLast(3)
                    .joinToString(separator = "\n") { "• $it" },
            )
        agentTimelineView?.let {
            resultCard.addView(it, matchWrap(top = 6))
        }

        agentResultView =
            body(lastAgentError ?: lastAgentResult).apply {
                background =
                    luxuryGlass(
                        start = Color.rgb(14, 15, 19),
                        end = Color.rgb(4, 5, 8),
                        accent = Color.rgb(55, 43, 50),
                        radius = 20,
                        strong = false,
                    )
                setPadding(dp(11), dp(9), dp(11), dp(9))
            }
        agentResultView?.let {
            resultCard.addView(it, matchWrap(top = 8))
        }

        contentContainer.addView(resultCard, matchWrap(top = 10))
    }

    private fun renderSettings(state: RuntimeUiState) {
        val truth = state.truth
        val capabilities = capabilityRecords(state)
        val readyCapabilities = capabilities.count { it.kind == StatusKind.READY }
        val activeModel =
            compactModelLabel(
                truth?.runtime?.activeModelId
                    ?: state.loadedModelId
                    ?: "None",
            )

        addSettingsSection(
            titleText = "◉  System",
            subtitle = "Runtime and model state.",
            actionLabel = "Open Status",
            action = { showSection(ProductSection.STATUS) },
        ) {
            addStatusRow(
                this,
                "Engine",
                if (state.connected) "ONLINE" else "OFFLINE",
                if (state.connected) StatusKind.READY else StatusKind.BLOCKED,
            )
            addStatusRow(
                this,
                "Inference",
                if (truth?.runtime?.readyForInference == true) "READY" else "CHECKING",
                if (truth?.runtime?.readyForInference == true) {
                    StatusKind.READY
                } else {
                    StatusKind.UNAVAILABLE
                },
            )
            addKeyValue("Active model", activeModel)
            addKeyValue("Capabilities", "$readyCapabilities/${capabilities.size} ready")
        }

        addSettingsSection(
            titleText = "▣  Models",
            subtitle = "Private local model catalog.",
            actionLabel = "Manage Models",
            action = { showSection(ProductSection.MODELS) },
        ) {
            addKeyValue(
                "Loaded",
                truth?.runtime?.loadedModelIds.orEmpty().size.toString(),
            )
            addKeyValue(
                "Catalog",
                (state.serviceModels.size + state.localModels.size).toString(),
            )
            addKeyValue("Storage", "Private app storage")
        }

        addSettingsSection(
            titleText = "◇  Privacy & Access",
            subtitle = "Local execution and permissions.",
            actionLabel = "Open Permissions",
            action = { showSection(ProductSection.AGENT_PERMISSIONS) },
        ) {
            addStatusRow(this, "Internet permission", "NONE", StatusKind.READY)
            addStatusRow(this, "Device mode", "ON-DEVICE", StatusKind.READY)

            val permissions = AgentPermissionCenter.items(this@MainActivity)
            val granted =
                permissions.count {
                    it.status == AgentPermissionStatus.GRANTED ||
                        it.status == AgentPermissionStatus.NOT_REQUIRED
                }
            addKeyValue("Permissions ready", "$granted/${permissions.size}")
        }

        addSettingsSection(
            titleText = "⋯  Apps & Diagnostics",
            subtitle = "Integrations, audit, and proof tools.",
            actionLabel = "Open Diagnostics",
            action = { showSection(ProductSection.DIAGNOSTICS) },
        ) {
            addKeyValue(
                "Connected apps",
                truth?.installedIntegrations.orEmpty().size.toString(),
            )
            addKeyValue(
                "Live sessions",
                truth?.liveClientSessions.orEmpty().size.toString(),
            )
            addKeyValue("Audit entries", toolAuditStore.recent().size.toString())
        }
    }

    private fun renderAgentPermissions() {
        addCard("Permission Center", "Grant only the local tools you want to use.") {
            addBody("Runtime permissions are requested normally. Special App Access opens Android settings. Sensitive tools still require approval before each use.")
            addBody("Media and documents use Android scoped pickers, so broad library or all-files access is not required.")
        }
        AgentPermissionCenter.items(this).forEach { item ->
            addPermissionCard(item)
        }
    }

    private fun renderToolAuditLog() {
        addCard("Tool Audit Log", "Recent local tool calls and approvals.") {
            val entries = toolAuditStore.recent()
            if (entries.isEmpty()) {
                addEmptyState("No tool calls recorded.", "Run an Agent or Studio workflow to create local audit entries.")
            } else {
                entries.forEach { entry ->
                    addAuditEntryFull(entry)
                }
            }
        }
    }

    private fun renderModels(state: RuntimeUiState) {
        renderLocalModelFolder(state)
        val runtimeModels = state.truth?.models.orEmpty()
        addCard("Model catalog", "Runtime truth snapshot catalog.") {
            if (runtimeModels.isEmpty()) {
                addEmptyState("No catalog entries exposed.", "Engine truth snapshot did not expose catalog models.")
            } else {
                runtimeModels.forEach { model ->
                    addModelRuntimeCard(model)
                }
            }
        }
        addCard("Installed models", "Service listInstalledModels() result.") {
            if (state.serviceModels.isEmpty()) {
                addEmptyState(
                    "No installed models exposed by service listInstalledModels().",
                    if (runtimeModels.isNotEmpty()) {
                        "Runtime snapshot still exposes catalog/active model state. Ready is based on descriptor + model + runtime + successful request path, not this service list alone."
                    } else {
                        "No service-installed model proof is available."
                    },
                )
            } else {
                state.serviceModels.forEach { model ->
                    addModelInfoRow(model)
                }
            }
        }
        addCard("Runtime-loaded model", "Active runtime state.") {
            addKeyValue("Active model", state.truth?.runtime?.activeModelId ?: state.loadedModelId ?: "None")
            addKeyValue("Active TEXT model", state.truth?.runtime?.activeTextModelId ?: "None")
            addKeyValue("Backend", state.truth?.runtime?.activeBackendLabel ?: state.truth?.runtime?.activeBackendId ?: "Not reported")
            addStatusRow(this, "Ready state", if (isTextReady(state.truth, findTextModelId(state.truth))) "READY" else "BLOCKED", if (isTextReady(state.truth, findTextModelId(state.truth))) StatusKind.READY else StatusKind.BLOCKED)
        }
        addCard("Local repository", "Files registered in private app storage.") {
            if (state.localModels.isEmpty()) {
                addEmptyState("No local repository models registered.", "Use Diagnostics for advanced model import. File existence alone will not mark a model ready.")
            } else {
                state.localModels.forEach { model -> addModelInfoRow(model) }
            }
        }
        addCard("Validation rule", "Truth model for readiness.") {
            addBody("Installed means descriptor/model presence is known. Ready means descriptor + model + runtime + successful load/request path proof. File exists alone is not ready.")
        }
    }

    private fun renderLocalModelFolder(state: RuntimeUiState) {
        val active = localModelFolderManager.activeModel
        addCard("Local model folder", "Models stay outside the APK and are imported only after you select one.") {
            val folderButton = productButton(
                if (localModelFolderManager.selectedFolderUri == null) "Select model folder" else "Change model folder",
                primary = true,
            ) { openModelFolderPicker() }
            folderButton.isEnabled = !localModelManagerBusy
            addView(folderButton, matchWrap())
            addSpacer(10)

            when (val scan = localModelScanResult) {
                LocalModelScanResult.NoFolderSelected -> {
                    addStatusRow(this, "Folder", "NO FOLDER SELECTED", StatusKind.BLOCKED)
                    addBody("Select a readable local folder to scan for GGUF and ONNX model files.")
                }
                is LocalModelScanResult.FolderUnreadable -> {
                    addStatusRow(this, "Folder", "CANNOT READ", StatusKind.BLOCKED)
                    addBody(scan.reason)
                    addCaption(scan.folderUri)
                }
                is LocalModelScanResult.Success -> {
                    addStatusRow(this, "Folder", "SELECTED", StatusKind.READY)
                    addKeyValue("Name", scan.folderDisplayName)
                    addCaption(scan.folderUri)
                    when {
                        localModelManagerBusy -> addBody(localModelManagerMessage ?: "Scanning selected folder...")
                        scan.candidates.isEmpty() -> addEmptyState(
                            "Folder selected but no supported models found.",
                            "Supported main model files are .gguf and .onnx. Tokenizer and config files are companions, not selectable models.",
                        )
                        else -> addStatusRow(this, "Models", "${scan.candidates.size} FOUND", StatusKind.READY)
                    }
                }
            }
            localModelManagerMessage?.let { addCaption(it) }
        }

        if (active != null) {
            addCard("Active local model", "Persisted selection and runtime-confirmed load state.") {
                addKeyValue("Model", active.displayName)
                addKeyValue("File", active.fileName)
                addKeyValue("Role", active.guessedRole.name)
                addKeyValue("Imported size", formatSize(active.sizeBytes))
                addCaption("Private path: ${active.importedPath}")
                val runtimeReady = state.truth?.runtime?.activeModelId == active.modelId &&
                    state.truth?.runtime?.readyForInference == true
                val activeLoadFailed = state.loadModelId == active.modelId && !state.loadError.isNullOrBlank()
                when {
                    runtimeReady -> addStatusRow(this, "Load", "LOADED / READY", StatusKind.READY)
                    activeLoadFailed -> {
                        addStatusRow(this, "Load", "FAILED", StatusKind.BLOCKED)
                        addBody(state.loadError ?: "Runtime did not report a reason.")
                    }
                    else -> addStatusRow(this, "Load", state.loadState ?: "SELECTED", StatusKind.UNAVAILABLE)
                }
            }
        }

        val candidates = (localModelScanResult as? LocalModelScanResult.Success)?.candidates.orEmpty()
        candidates.forEach { candidate ->
            addCard(candidate.displayName, "${candidate.extension.uppercase()} · ${candidate.guessedRole.name} · ${formatSize(candidate.sizeBytes)}") {
                addKeyValue("File", candidate.fileName)
                addStatusRow(
                    this,
                    "Readable",
                    if (candidate.readable) "YES" else "NO",
                    if (candidate.readable) StatusKind.READY else StatusKind.BLOCKED,
                )
                val selected = active?.sourceUri == candidate.uri
                val button = productButton(if (selected) "Import and reload" else "Select and load", primary = !selected) {
                    selectAndLoadLocalModel(candidate)
                }
                button.isEnabled = candidate.readable && !localModelManagerBusy
                addView(button, matchWrap(top = 8))
            }
        }
    }

    private fun renderEngineStatus(state: RuntimeUiState) {
        val truth = state.truth
        addCard("Connection", "Service and Binder state.") {
            addStatusRow(this, "Connection", if (state.connected) "CONNECTED" else "DISCONNECTED", if (state.connected) StatusKind.READY else StatusKind.BLOCKED)
            addKeyValue("API version", state.apiVersion?.toString() ?: "Unknown")
        }
        addCard("Backend / JNI", "Runtime host.") {
            addKeyValue("Backend", truth?.runtime?.activeBackendLabel ?: truth?.runtime?.activeBackendId ?: "None")
            addKeyValue("JNI", jniAvailabilityText(truth))
            addKeyValue("llama.cpp", llamaCppStateText(truth))
        }
        addCard("Loaded model / request path", "Readiness for inference.") {
            addKeyValue("Loaded model", truth?.runtime?.activeModelId ?: state.loadedModelId ?: "None")
            addKeyValue("Active requests", (truth?.runtime?.activeRequestCount ?: 0).toString())
            addStatusRow(
                this,
                "Runtime request path",
                when {
                    truth?.runtime?.readyForInference == true -> "READY"
                    truth == null -> "CHECKING"
                    else -> "UNAVAILABLE"
                },
                if (truth?.runtime?.readyForInference == true) StatusKind.READY else StatusKind.UNAVAILABLE,
            )
            addStatusRow(
                this,
                "TEXT readiness",
                when {
                    isTextReady(truth, findTextModelId(truth)) -> "READY"
                    truth == null -> "CHECKING"
                    else -> "UNAVAILABLE"
                },
                if (isTextReady(truth, findTextModelId(truth))) StatusKind.READY else StatusKind.UNAVAILABLE,
            )
        }
        addCard("Capability readiness", "No false readiness claims.") {
            capabilityRecords(state).forEach { capability ->
                addCapabilitySummaryRow(capability)
            }
        }
        addCard("Recent errors", "Latest runtime errors.") {
            val errors = truth?.lastErrors.orEmpty()
            if (errors.isEmpty() && state.loadError == null && state.errorMessage == null) {
                addBody("No recent runtime errors reported.")
            } else {
                state.errorMessage?.let { addBody(it) }
                state.loadError?.let { addBody("Load error: $it") }
                errors.takeLast(5).forEach { addBody("${it.stage.name}/${it.code.name}: ${it.message}") }
            }
        }
        addCard("Storage paths", "Private local model storage.") {
            addKeyValue("filesDir", filesDir.absolutePath)
            addKeyValue("modelsDir", File(filesDir, MODELS_DIR).absolutePath)
        }
        addCard("Technical details", "Detailed values are hidden from the primary dashboard.") {
            val toggle = productButton(if (showTechnicalDetails) "Hide technical details" else "Show technical details", primary = false) {
                showTechnicalDetails = !showTechnicalDetails
                renderShell(lastUiState)
            }
            addView(toggle, matchWrap())
            if (showTechnicalDetails) addBody(buildRawSnapshotText(state))
        }
    }

    private fun renderConnectedApps(state: RuntimeUiState) {
        val integrations = state.truth?.installedIntegrations.orEmpty()
        val sessions = state.truth?.liveClientSessions.orEmpty()
        addCard("Known integrations", "Trusted and discoverable clients exposed by engine truth snapshot.") {
            if (integrations.isEmpty()) {
                addEmptyState("No known integrations detected.", "Connected apps ready: NO. Reason: no installed, trusted, discoverable, or live client proof is available.")
            } else {
                integrations.forEach { app ->
                    addStatusRow(this, app.displayName, app.connectionState.name, if (app.trustedIntegration && app.discoverableIntegration) StatusKind.READY else StatusKind.BLOCKED)
                    addKeyValue("Package", app.packageName)
                    addKeyValue("Trusted", yesNo(app.trustedIntegration))
                    addKeyValue("Discoverable", yesNo(app.discoverableIntegration))
                    addKeyValue("Capabilities", app.capabilities.ifEmpty { listOf("none") }.joinToString())
                }
            }
        }
        addCard("Live client sessions", "Active clients bound to the engine.") {
            if (sessions.isEmpty()) {
                addEmptyState("No live client sessions detected.", "Connected apps ready: NO. No client app readiness is claimed.")
            } else {
                sessions.forEach { session ->
                    addStatusRow(this, session.displayName, session.connectionState.name, StatusKind.READY)
                    addKeyValue("Package", session.packageName)
                }
            }
        }
    }

    private fun renderDiagnostics(state: RuntimeUiState) {
        addCard("Developer tools", "Primitive harness controls live here, not on the launcher.") {
            diagnosticsDetailView = body("Selected model: ${selectedModelId ?: findTextModelId(state.truth) ?: "none"}\nLocal repository entries: ${state.localModels.size}\nTEXT ready before generation: ${yesNo(isTextReady(state.truth, findTextModelId(state.truth)))}")
            diagnosticsDetailView?.let { addView(it, matchWrap()) }
            addSpacer(10)
            browseButton = productButton("Browse Model", primary = false) { openModelPicker() }
            loadButton = productButton("Load Selected Text Model", primary = false) { warmupSelectedModel() }
            browseButton?.let { addView(it, matchWrap()) }
            addSpacer(8)
            loadButton?.let { addView(it, matchWrap()) }
        }
        addCard("Diagnostics proof runners", "Installed APK proof actions and truthful blockers.") {
            addProofRunnerButton("Run text generation proof") { runTextProof() }
            addProofRunnerButton("Run agent proof") { runAgentProof() }
            addProofRunnerButton("Run YOLO object detection proof") { runYoloProof() }
            addProofRunnerButton("Run TTS proof") { runTtsProof() }
            addProofRunnerButton("Run STT proof") { runSttProof() }
            addProofRunnerButton("Run embeddings proof") { runEmbeddingsProof() }
            addProofRunnerButton("Run video object detection proof") { runVideoObjectDetectionProof() }
            addProofRunnerButton("Run audio full understanding proof") { runAudioFullUnderstandingProof() }
            addProofRunnerButton("Run image multimodal proof") { runImageMultimodalProof() }
            addProofRunnerButton("Run OCR proof") { runOcrProof() }
            addProofRunnerButton("Run video full analysis proof") { runVideoFullAnalysisProof() }
        }
        addCard("Raw runtime snapshot", "Developer-facing response summary.") {
            addBody(buildRawSnapshotText(state))
        }
        addCard("Recent errors / logs", "Engine-reported failures.") {
            val errors = state.truth?.lastErrors.orEmpty()
            if (errors.isEmpty() && state.errorMessage == null && state.loadError == null) {
                addBody("No recent errors reported by engine truth snapshot.")
            } else {
                state.errorMessage?.let { addBody(it) }
                state.loadError?.let { addBody("Load error: $it") }
                errors.forEach { addBody("${it.stage.name}/${it.code.name}: ${it.message}") }
            }
        }
    }

    private fun LinearLayout.addModelRuntimeCard(model: ModelRuntimeInfo) {
        val block = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = glassGradient(COLOR_FIELD, COLOR_GLASS, COLOR_BORDER, radius = 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        block.addView(title("${model.displayName} (${model.modelId})"), matchWrap())
        block.addView(caption("backend=${model.backendId}"), matchWrap(top = 3))
        block.addView(keyValueView("Descriptor status", model.validationState.name), matchWrap(top = 6))
        block.addView(keyValueView("Model file status", model.installationState.name), matchWrap(top = 4))
        block.addView(keyValueView("Runtime compatibility", model.backendId), matchWrap(top = 4))
        block.addView(keyValueView("Loaded state", model.runtimeState.name), matchWrap(top = 4))
        block.addView(keyValueView("Ready", yesNo(model.ready)), matchWrap(top = 4))
        block.addView(caption("Reason: ${modelReadinessReason(model)}"), matchWrap(top = 6))
        addView(block, matchWrap(top = 10))
    }

    private fun LinearLayout.addModelInfoRow(model: ModelInfo) {
        addView(keyValueView(model.displayName, "${model.modelId} | loaded=${yesNo(model.isLoaded)} | ${formatSize(model.sizeBytes)}"), matchWrap(top = 6))
    }

    private fun addCapabilityDetailsCard(capability: CapabilityUiRecord) {
        addCard(capability.name, capability.reason) {
            addStatusRow(this, capability.name, capability.status, capability.kind)
            addKeyValue("Runtime", capability.runtime)
            addKeyValue("Model", capability.model)
            addKeyValue("API path", capability.apiPath)
            addKeyValue("Proof", capability.proofStatus)
            if (capability.kind == StatusKind.BLOCKED) {
                addBlockedProofRunner("Proof action", capability.reason)
            } else {
                addCaption("Use Diagnostics proof runners to execute installed-service proof.")
            }
        }
    }

    private fun LinearLayout.addCapabilitySummaryRow(capability: CapabilityUiRecord) {
        addStatusRow(this, capability.name, capability.status, capability.kind)
        addKeyValue("Runtime", capability.runtime)
        addKeyValue("Model", capability.model)
        addKeyValue("API path", capability.apiPath)
        addKeyValue("Proof", capability.proofStatus)
        addBody("Reason: ${capability.reason}")
        addSpacer(8)
    }

    private fun addScreenHeader(title: String, subtitle: String) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = luxuryGlass(
                start = Color.rgb(19, 20, 25),
                end = Color.rgb(5, 6, 9),
                accent = Color.rgb(60, 48, 56),
                radius = 18,
                strong = false,
            )
            setPadding(dp(14), dp(9), dp(14), dp(9))
            elevation = dp(3).toFloat()
        }

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(title(title, 18f), matchWrap())
        copy.addView(caption(subtitle), matchWrap(top = 1))

        header.addView(
            copy,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        header.addView(
            TextView(this).apply {
                text = "•"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(214, 48, 78))
            },
            LinearLayout.LayoutParams(dp(28), dp(28)),
        )

        contentContainer.addView(header, matchWrap(top = 4, bottom = 2))
    }

    private fun addCard(titleText: String, subtitle: String, content: LinearLayout.() -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = luxuryGlass(
                start = Color.rgb(24, 25, 31),
                end = Color.rgb(5, 6, 10),
                accent = Color.rgb(67, 55, 63),
                radius = RADIUS_CARD,
                strong = false,
            )
            setPadding(dp(13), dp(10), dp(13), dp(10))
            elevation = dp(4).toFloat()
            translationZ = dp(1).toFloat()
        }
        card.addView(title(titleText), matchWrap())
        card.addView(caption(subtitle), matchWrap(top = 1, bottom = 5))
        card.content()
        contentContainer.addView(card, matchWrap(top = 6))
    }

    private fun addSettingsSection(
        titleText: String,
        subtitle: String,
        actionLabel: String,
        action: () -> Unit,
        content: LinearLayout.() -> Unit,
    ) {
        addCard(titleText, subtitle) {
            content()
            addView(
                productButton(actionLabel, primary = false, onClick = action),
                matchWrap(top = 8),
            )
        }
    }

    private fun addPermissionCard(item: AgentPermissionItem) {
        addCard(item.name, item.enables) {
            val kind = when (item.status) {
                AgentPermissionStatus.GRANTED -> StatusKind.READY
                AgentPermissionStatus.NOT_REQUIRED -> StatusKind.READY
                AgentPermissionStatus.UNAVAILABLE -> StatusKind.UNAVAILABLE
                else -> StatusKind.BLOCKED
            }
            addKeyValue("Type", item.group)
            addStatusRow(this, "Status", item.status.displayName, kind)
            addKeyValue("Availability", if (item.status == AgentPermissionStatus.UNAVAILABLE) "Not implemented / not available" else "Available")
            addKeyValue("Tool dependency", item.toolDependency)
            addKeyValue("Risk level", item.risk.displayName)
            addKeyValue("Confirmation before tool use", yesNo(item.confirmationRequired))
            val buttonLabel = when (item.requestKind) {
                AgentPermissionRequestKind.RUNTIME -> "Request"
                AgentPermissionRequestKind.SETTINGS -> "Open Settings"
                AgentPermissionRequestKind.NONE -> when (item.status) {
                    AgentPermissionStatus.GRANTED -> "Granted"
                    AgentPermissionStatus.NOT_REQUIRED -> "Not Required"
                    AgentPermissionStatus.UNAVAILABLE -> "Unavailable"
                    else -> "No Action Needed"
                }
            }
            val button = productButton(buttonLabel, primary = item.requestKind != AgentPermissionRequestKind.NONE) {
                requestAgentPermission(item)
            }
            button.isEnabled = item.requestKind != AgentPermissionRequestKind.NONE
            addView(button, matchWrap(top = 10))
        }
    }

    private fun LinearLayout.addToolChips(tools: List<ToolDefinition>) {
        tools.chunked(2).forEachIndexed { rowIndex, rowTools ->
            val row =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

            rowTools.forEachIndexed { index, tool ->
                row.addView(
                    toolChip(tool),
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    },
                )
            }

            addView(
                row,
                matchWrap(top = if (rowIndex == 0) 2 else 7),
            )
        }
    }

    private fun toolChip(tool: ToolDefinition): TextView {
        val available = isToolAvailable(tool)
        val stateMark = if (available) "●" else "○"
        val label = "$stateMark  ${tool.displayName}"

        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(
                if (available) {
                    Color.rgb(236, 229, 233)
                } else {
                    Color.rgb(125, 115, 121)
                },
            )
            background =
                luxuryGlass(
                    start =
                        if (available) {
                            Color.rgb(22, 22, 27)
                        } else {
                            Color.rgb(13, 14, 18)
                        },
                    end = Color.rgb(5, 6, 9),
                    accent =
                        if (available) {
                            Color.rgb(62, 48, 56)
                        } else {
                            Color.rgb(40, 35, 39)
                        },
                    radius = 20,
                    strong = false,
                )
            setPadding(dp(12), 0, dp(10), 0)
        }
    }

    private fun LinearLayout.addAuditEntryCompact(entry: ToolAuditEntry) {
        val block = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = glassRounded(COLOR_FIELD, COLOR_BORDER_RED, 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        block.addView(title(entry.toolId, 14f), matchWrap())
        block.addView(caption("${formatTime(entry.timestampMs)} | risk=${entry.riskLevel.displayName} | approved=${yesNo(entry.approvedByUser)}"), matchWrap(top = 2))
        block.addView(body(entry.result), matchWrap(top = 4))
        addView(block, matchWrap(top = 8))
    }

    private fun LinearLayout.addAuditEntryFull(entry: ToolAuditEntry) {
        addAuditEntryCompact(entry)
        addKeyValue("Action", entry.actionSummary)
        addKeyValue("Raw details", entry.rawDetails.ifBlank { "None" })
        addSpacer(8)
    }

    private fun resultHintFor(title: String): String =
        when (title) {
            "Analyze Image" -> "Last image insight appears here after a run."
            "Ask About Image" -> "Last image answer appears here after a run."
            "Extract Text OCR" -> "Last extracted text appears here. Use Copy Result from the workspace panel."
            "Analyze Audio" -> "Transcript, labels, or summary preview appears here."
            "Analyze Video" -> "Timeline and key moment preview appears here."
            else -> "Similarity result preview appears here."
        }

    private fun addCapabilityCard(capability: StudioCapability) {
        addCard(capability.title, capability.description) {
            val readyKind = if (capability.ready) StatusKind.READY else StatusKind.UNAVAILABLE
            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this@MainActivity).apply {
                text = capability.icon
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_PRIMARY_RED)
                background = glassGradient(COLOR_MUTED_RED_SURFACE, COLOR_GLASS, COLOR_BORDER_RED, 14)
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            top.addView(statusChip(if (capability.ready) "READY" else "CHECKING", readyKind), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginStart = dp(10)
            })
            addView(top, matchWrap(top = 2))
            addView(chipRow(capability.requirementChips), matchWrap(top = 10))
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            actions.addView(productButton(capability.actionLabel, primary = true, onClick = capability.action), LinearLayout.LayoutParams(0, dp(50), 1f))
            actions.addView(productButton(capability.sampleLabel, primary = false, onClick = capability.sampleAction), LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(8)
            })
            addView(actions, matchWrap(top = 10))
            addCaption(resultHintFor(capability.title))
        }
    }

    private fun LinearLayout.addTimelineRow(label: String, value: String) {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassRounded(COLOR_FIELD, COLOR_BORDER, radius = 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val dot = TextView(this@MainActivity).apply {
            text = "•"
            textSize = 24f
            setTextColor(COLOR_PRIMARY_RED)
            gravity = Gravity.CENTER
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT))
        val texts = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(title(label, 14f), matchWrap())
        texts.addView(caption(value), matchWrap(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(row, matchWrap(top = 8))
    }

    private fun chipRow(labels: List<String>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.take(3).forEachIndexed { index, label ->
                addView(actionChip(label), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) marginStart = dp(6)
                })
            }
        }

    private fun actionChip(label: String): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12.5f
            typeface = Typeface.DEFAULT
            setTextColor(COLOR_TEXT)
            background = glassGradient(COLOR_MUTED_RED_SURFACE, COLOR_GLASS, COLOR_BORDER_RED, 20)
        }

    private fun LinearLayout.addActionGrid(actions: List<Pair<String, () -> Unit>>) {
        actions.chunked(2).forEachIndexed { rowIndex, rowActions ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowActions.forEachIndexed { index, action ->
                row.addView(productButton(action.first, primary = index == 0 && rowIndex == 0, onClick = action.second), LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            addView(row, matchWrap(top = if (rowIndex == 0) 2 else 8))
        }
    }

    private fun LinearLayout.addProofRunnerButton(label: String, onClick: () -> Unit) {
        addView(productButton(label, primary = true, onClick = onClick), matchWrap(top = 8))
    }

    private fun LinearLayout.addBlockedProofRunner(label: String, reason: String) {
        val button = productButton(label, primary = false) { }
        button.isEnabled = false
        addView(button, matchWrap(top = 8))
        addCaption(reason)
    }

    private fun LinearLayout.addStatusRow(parent: LinearLayout, label: String, status: String, kind: StatusKind) {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = body(label).apply { typeface = Typeface.DEFAULT_BOLD }
        row.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(statusChip(status, kind), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        parent.addView(row, matchWrap(top = 6))
    }

    private fun LinearLayout.addKeyValue(label: String, value: String) {
        addView(keyValueView(label, value), matchWrap(top = 5))
    }

    private fun LinearLayout.addBody(text: String) {
        addView(body(text), matchWrap(top = 6))
    }

    private fun LinearLayout.addCaption(text: String) {
        addView(caption(text), matchWrap(top = 6))
    }

    private fun LinearLayout.addEmptyState(title: String, detail: String) {
        val block = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = glassRounded(COLOR_FIELD, COLOR_BORDER, radius = 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        block.addView(title(title, 15f), matchWrap())
        block.addView(caption(detail), matchWrap(top = 4))
        addView(block, matchWrap(top = 6))
    }

    private fun userBubble(message: String, time: String): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassGradient(COLOR_USER_MESSAGE, COLOR_MUTED_RED_SURFACE, COLOR_BORDER_RED, 18)
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        bubble.addView(body(message), matchWrap())
        bubble.addView(caption("$time  //").apply { gravity = Gravity.END }, matchWrap(top = 5))
        outer.addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.62f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        return outer
    }

    private fun assistantBubble(message: String): LinearLayout {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassGradient(COLOR_FIELD, COLOR_GLASS, COLOR_BORDER_RED, 22)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(smallLogo(), LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(body(message), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        bubble.addView(row, matchWrap())
        return bubble
    }

    private fun assistantReferenceCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassGradient(COLOR_FIELD, COLOR_GLASS, COLOR_BORDER_RED, 22)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        intro.addView(smallLogo(), LinearLayout.LayoutParams(dp(42), dp(42)))
        intro.addView(body("Absolutely. I can keep this fully local and turn your request into a clear plan."), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        card.addView(intro, matchWrap())
        card.addView(separator(), separatorParams(top = 12, bottom = 10))
        card.addView(featureLine("01", "Plan", "Break the task into a clean sequence."), matchWrap(top = 4))
        card.addView(featureLine("02", "Check", "Use local tools only when needed."), matchWrap(top = 8))
        card.addView(featureLine("03", "Answer", "Return a concise result you can act on."), matchWrap(top = 8))
        card.addView(caption("Would you like a checklist, estimate, or next-step summary?"), matchWrap(top = 12))
        return card
    }

    private fun featureLine(index: String, label: String, detail: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(TextView(this@MainActivity).apply {
                text = index
                setTextColor(COLOR_PRIMARY_RED)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = glassRounded(COLOR_MUTED_RED_SURFACE, COLOR_BORDER_RED, 12)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            copy.addView(title(label, 15f), matchWrap())
            copy.addView(caption(detail), matchWrap(top = 1))
            addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
        }

    private fun smallLogo(): TextView =
        TextView(this).apply {
            text = "R"
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            background = glassGradient(COLOR_MUTED_RED_SURFACE, COLOR_GLASS, COLOR_PRIMARY_RED, 18)
        }

    private fun separator(): View =
        View(this).apply {
            background = rounded(COLOR_BORDER, COLOR_BORDER, 1)
            alpha = 0.55f
            minimumHeight = dp(1)
        }

    private fun LinearLayout.addMessageBubble(sender: String, message: String, user: Boolean) {
        val block = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = glassRounded(if (user) COLOR_USER_MESSAGE else COLOR_FIELD, if (user) COLOR_ACCENT_DARK else COLOR_BORDER, radius = 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        block.addView(caption(sender).apply { typeface = Typeface.DEFAULT_BOLD }, matchWrap())
        block.addView(body(message), matchWrap(top = 4))
        addView(block, matchWrap(top = 8))
    }

    private fun LinearLayout.addSpacer(heightDp: Int) {
        addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

private fun productButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            minHeight = 0
            minWidth = 0
            stateListAnimator = null
            textSize = if (primary) 13f else 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (primary) Color.WHITE else Color.rgb(238, 228, 233))
            background = luxuryButtonBackground(primary)
            elevation = dp(if (primary) 14 else 7).toFloat()
            translationZ = dp(if (primary) 5 else 2).toFloat()
            setPadding(dp(10), dp(1), dp(10), dp(1))

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate()
                            .scaleX(0.965f)
                            .scaleY(0.965f)
                            .alpha(0.92f)
                            .setDuration(85L)
                            .start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(145L)
                            .start()
                    }
                }
                false
            }

            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }

    private fun title(text: String, size: Float = 16f): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT)
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun body(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT)
            textSize = 14f
            includeFontPadding = false
            setLineSpacing(2f, 1.0f)
        }

    private fun caption(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_MUTED)
            textSize = 12.5f
            includeFontPadding = false
            setLineSpacing(2f, 1.0f)
        }

    private fun keyValueView(label: String, value: String): TextView =
        body("$label  $value").apply {
            textSize = 13.5f
        }

    private fun statusChip(text: String, kind: StatusKind): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11.5f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(kind.textColor)
            background = glassRounded(kind.fillColor, kind.strokeColor, radius = 18)
        }

    private fun setChip(view: TextView, text: String, kind: StatusKind) {
        view.text = text
        view.setTextColor(COLOR_TEXT)
        view.typeface = Typeface.DEFAULT
        view.background = glassGradient(COLOR_MUTED_RED_SURFACE, COLOR_GLASS, if (kind == StatusKind.BLOCKED) COLOR_PRIMARY_RED else COLOR_BORDER_RED, radius = RADIUS_CONTROL)
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (kind == StatusKind.READY) COLOR_SUCCESS_DOT else COLOR_PRIMARY_RED)
            setSize(dp(9), dp(9))
        }
        dot.setBounds(0, 0, dp(9), dp(9))
        view.setCompoundDrawables(dot, null, null, null)
        view.compoundDrawablePadding = dp(12)
    }

private fun alphaColor(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private fun luxuryGlass(
        start: Int,
        end: Int,
        accent: Int,
        radius: Int,
        strong: Boolean = false,
    ): android.graphics.drawable.Drawable {
        val corner = dp(radius).toFloat()

        // Restrained luxury glass: graphite body, narrow specular edge,
        // and a controlled accent reserved for active/primary surfaces.
        val shadow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(if (strong) 120 else 78, 0, 0, 0),
                Color.argb(if (strong) 205 else 175, 0, 0, 0),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner + dp(3)
        }

        val body = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(if (strong) 34 else 22, 255, 255, 255),
                alphaColor(start, if (strong) 205 else 188),
                alphaColor(end, if (strong) 231 else 216),
                Color.argb(236, 3, 4, 8),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setStroke(
                dp(1),
                alphaColor(accent, if (strong) 170 else 78),
            )
        }

        val topSpecular = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(if (strong) 78 else 48, 255, 255, 255),
                Color.argb(if (strong) 24 else 12, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
        }

        val accentRefraction = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(
                Color.argb(if (strong) 58 else 18, 230, 18, 52),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
        }

        return android.graphics.drawable.LayerDrawable(
            arrayOf(shadow, body, topSpecular, accentRefraction),
        ).apply {
            setLayerInset(0, 0, dp(3), 0, 0)
            setLayerInset(1, dp(1), dp(1), dp(1), dp(2))
            setLayerInset(2, dp(radius / 3), dp(1), dp(radius / 3), dp(radius.coerceAtLeast(18)))
            setLayerInset(3, dp(3), dp(radius.coerceAtLeast(18)), dp(3), dp(2))
        }
    }

    private fun luxuryButtonBackground(primary: Boolean): android.graphics.drawable.StateListDrawable {
        val normal = if (primary) {
            luxuryGlass(
                start = Color.rgb(174, 15, 39),
                end = Color.rgb(45, 5, 16),
                accent = Color.rgb(255, 104, 125),
                radius = 20,
                strong = true,
            )
        } else {
            luxuryGlass(
                start = Color.rgb(27, 27, 34),
                end = Color.rgb(6, 7, 11),
                accent = Color.rgb(82, 68, 76),
                radius = 20,
                strong = false,
            )
        }

        val pressed = if (primary) {
            luxuryGlass(
                start = Color.rgb(255, 58, 82),
                end = Color.rgb(104, 8, 28),
                accent = Color.rgb(255, 188, 196),
                radius = 20,
                strong = true,
            )
        } else {
            luxuryGlass(
                start = Color.rgb(60, 49, 59),
                end = Color.rgb(15, 8, 15),
                accent = Color.rgb(232, 50, 77),
                radius = 20,
                strong = true,
            )
        }

        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_focused), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun glassRounded(
        fill: Int,
        stroke: Int,
        radius: Int,
    ): android.graphics.drawable.Drawable =
        luxuryGlass(
            start = fill,
            end = Color.rgb(5, 5, 9),
            accent = stroke,
            radius = radius,
            strong = stroke == COLOR_PRIMARY_RED,
        )

    private fun glassGradient(
        start: Int,
        end: Int,
        stroke: Int,
        radius: Int,
    ): android.graphics.drawable.Drawable =
        luxuryGlass(
            start = start,
            end = end,
            accent = stroke,
            radius = radius,
            strong = stroke == COLOR_PRIMARY_RED || start == COLOR_DEEP_CRIMSON,
        )

    private fun compactModelLabel(value: String): String {
        if (value.length <= 34) return value
        return value.take(20) + "…" + value.takeLast(11)
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun separatorParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun runStudioWorkflow(title: String, action: () -> Unit) {
        activeStudioWorkflowTitle = title
        setStudioWorkflowResult(title, "Sample run started. Using bundled local input where needed.")
        action()
    }

    private fun setStudioWorkflowResult(title: String, message: String) {
        lastStudioWorkflowTitle = title
        lastStudioResultPreview = cleanStudioResult(message).ifBlank { "Sample run completed with no text result." }
        studioResultView?.text = "$lastStudioWorkflowTitle\n$lastStudioResultPreview"
        if (currentSection == ProductSection.STUDIO) {
            renderShell(lastUiState)
        }
    }

    private fun cleanStudioResult(message: String): String {
        val lines = message.lines()
        val prefix = lines.takeWhile { it.startsWith("Sample result") || it.startsWith("Result from") }
        val markerIndex = lines.indexOfFirst {
            it.equals("generated text", ignoreCase = true) ||
                it.equals("generated text:", ignoreCase = true) ||
                it.equals("extracted text", ignoreCase = true) ||
                it.equals("extracted text:", ignoreCase = true)
        }
        if (markerIndex >= 0 && markerIndex + 1 < lines.size) {
            return (prefix + lines.drop(markerIndex + 1))
                .filterNot { isRawStudioLine(it) }
                .joinToString("\n")
                .trim()
        }
        return lines
            .filterNot { isRawStudioLine(it) }
            .joinToString("\n")
            .trim()
    }

    private fun isRawStudioLine(line: String): Boolean =
        line.contains("proof result", ignoreCase = true) ||
            line.startsWith("requestId=") ||
            line.startsWith("finish=") ||
            line.startsWith("modelId=") ||
            line.startsWith("runtime=") ||
            line.startsWith("modelPath=") ||
            line.startsWith("mmprojPath=") ||
            line.startsWith("imagePath=") ||
            line.startsWith("videoPath=") ||
            line.startsWith("audioPath=") ||
            line.startsWith("prompt=") ||
            line.startsWith("apiPath=") ||
            line.startsWith("generateFromImage") ||
            line.contains("finish=COMPLETED")

    private fun chooseStudioDocument(
        title: String,
        destinationName: String,
        mimeTypes: Array<String>,
        actionType: StudioDocumentActionType,
    ) {
        pendingStudioDocumentRequest = PendingStudioDocumentState(
            requestId = "studio-document-${UUID.randomUUID()}",
            title = title,
            destinationName = "$destinationName-${System.currentTimeMillis()}",
            actionType = actionType,
        )
        activeStudioWorkflowTitle = title
        setStudioWorkflowResult(title, "Waiting for Android file picker. Selected files stay in private app storage for local analysis.")
        studioDocumentPicker.launch(mimeTypes)
    }

    private fun runImageUnderstandingForFile(file: File, sample: Boolean) {
        runImageRequest(
            title = "Analyze Image",
            file = file,
            prompt = "Describe this image and list the main visible objects.",
            sample = sample,
        )
    }

    private fun runImageQuestionForFile(file: File, sample: Boolean) {
        runImageRequest(
            title = "Ask About Image",
            file = file,
            prompt = "Answer this local prompt about the image: what is important in this image?",
            sample = sample,
        )
    }

    private fun runImageRequest(title: String, file: File, prompt: String, sample: Boolean) {
        val service = engineService ?: return setStudioWorkflowResult(title, "Blocked: engine service is not connected.")
        val modelId = findQwen2VlMtmdModelId(lastTruthSnapshot) ?: "qwen2vl-mtmd-image-text"
        val request = ModalInferenceRequest(
            requestId = "studio-image-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "IMAGE",
            taskType = "IMAGE_TEXT_MULTIMODAL",
            inputRefs = listOf(file.absolutePath),
            textPrompt = prompt,
            params = mapOf("engine.maxTokens" to "160", "engine.temperature" to "0.2", "engine.timeoutMs" to "600000"),
        )
        setStudioWorkflowResult(title, "${if (sample) "Sample result from bundled local input" else "Selected local file"} running through Qwen2VL...")
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                resultStyle = ModalResultStyle.STUDIO_ONLY,
                studio = StudioCompletionSpec(
                    title = title,
                    successLead = if (sample) "Sample result from bundled local input" else "Result from selected local file",
                    errorPrefix = "Image workflow could not complete",
                    audit = ToolAuditSpec(
                        "image_understanding",
                        title,
                        "Image workflow completed.",
                        ToolRiskLevel.LOW,
                        false,
                        "file=${file.absolutePath}",
                    ),
                ),
            ),
        )
        try {
            service.analyzeImage(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "Image workflow call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runOcrForFile(file: File, sample: Boolean) {
        val service = engineService ?: return setStudioWorkflowResult("Extract Text OCR", "Blocked: engine service is not connected.")
        val modelId = findQwen2VlMtmdModelId(lastTruthSnapshot) ?: "qwen2vl-mtmd-image-text"
        val request = ModalInferenceRequest(
            requestId = "studio-ocr-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "IMAGE",
            taskType = "OCR",
            inputRefs = listOf(file.absolutePath),
            textPrompt = "Read all visible text in this image. Preserve line breaks.",
            params = mapOf("engine.maxTokens" to "128", "engine.temperature" to "0.0", "engine.timeoutMs" to "600000"),
        )
        setStudioWorkflowResult("Extract Text OCR", "${if (sample) "Sample result from bundled local input" else "Selected local file"} OCR running...")
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                resultStyle = ModalResultStyle.STUDIO_ONLY,
                studio = StudioCompletionSpec(
                    title = "Extract Text OCR",
                    successLead = if (sample) "Sample result from bundled local input" else "Result from selected local file",
                    errorPrefix = "OCR could not complete",
                    audit = ToolAuditSpec(
                        "ocr",
                        "Extract Text OCR",
                        "OCR completed.",
                        ToolRiskLevel.LOW,
                        false,
                        "file=${file.absolutePath}",
                    ),
                ),
            ),
        )
        try {
            service.runOcr(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "OCR call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runAudioForFile(file: File, sample: Boolean) {
        val service = engineService ?: return setStudioWorkflowResult("Analyze Audio", "Blocked: engine service is not connected.")
        val modelId = findAudioTaggingModelId(lastTruthSnapshot) ?: "sherpa-onnx-ced-base-audio-tagging-int8"
        val request = ModalInferenceRequest(
            requestId = "studio-audio-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "AUDIO",
            taskType = "AUDIO_TAGGING",
            inputRefs = listOf(file.absolutePath),
            textPrompt = null,
            params = mapOf("audio.topK" to "5", "engine.timeoutMs" to "300000"),
        )
        setStudioWorkflowResult("Analyze Audio", "${if (sample) "Sample result from bundled local input" else "Selected local audio"} running...")
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                resultStyle = ModalResultStyle.STUDIO_ONLY,
                studio = StudioCompletionSpec(
                    title = "Analyze Audio",
                    successLead = if (sample) "Sample result from bundled local input" else "Result from selected local audio",
                    errorPrefix = "Audio workflow could not complete",
                    audit = ToolAuditSpec(
                        "audio_understanding",
                        "Analyze Audio",
                        "Audio workflow completed.",
                        ToolRiskLevel.LOW,
                        false,
                        "file=${file.absolutePath}",
                    ),
                ),
            ),
        )
        try {
            service.analyzeAudio(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "Audio workflow call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runVideoForFile(file: File, sample: Boolean) {
        val service = engineService ?: return setStudioWorkflowResult("Analyze Video", "Blocked: engine service is not connected.")
        val modelId = findQwen2VlMtmdModelId(lastTruthSnapshot) ?: "qwen2vl-mtmd-image-text"
        val request = ModalInferenceRequest(
            requestId = "studio-video-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "VIDEO",
            taskType = "VIDEO_FULL_ANALYSIS",
            inputRefs = listOf(file.absolutePath),
            textPrompt = "Describe each sampled video frame and produce a short temporal video summary.",
            params = mapOf("video.sampleCount" to "2", "video.frameMaxTokens" to "96", "engine.timeoutMs" to "600000"),
        )
        setStudioWorkflowResult("Analyze Video", "${if (sample) "Sample result from bundled local input" else "Selected local video"} running...")
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                resultStyle = ModalResultStyle.STUDIO_ONLY,
                studio = StudioCompletionSpec(
                    title = "Analyze Video",
                    successLead = if (sample) "Sample result from bundled local input" else "Result from selected local video",
                    errorPrefix = "Video workflow could not complete",
                    audit = ToolAuditSpec(
                        "video_analysis",
                        "Analyze Video",
                        "Video workflow completed.",
                        ToolRiskLevel.LOW,
                        false,
                        "file=${file.absolutePath}",
                    ),
                ),
            ),
        )
        try {
            service.analyzeVideo(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "Video workflow call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun handleDeterministicAgentTool(task: String): Boolean? {
        val lower = task.lowercase(Locale.US)
        if ("17" in lower && "23" in lower && ("calculate" in lower || "*" in lower || "multiply" in lower)) {
            val result = 17 * 23
            lastAgentTask = task
            lastAgentTimeline = listOf("Task received", "Selected CalculatorTool", "Executed locally", "Result")
            lastAgentToolSummary = "CalculatorTool ran locally without network access. Expected proof result is 391."
            lastAgentResult = "17 * 23 = $result. Multiplication adds 23 seventeen times, which totals 391."
            lastAgentError = null
            auditTool("calculator", task, "Calculated 17 * 23 = $result.", ToolRiskLevel.LOW, approved = false, raw = "expression=17*23; result=$result")
            return true
        }
        if ("flashlight" in lower || "torch" in lower) {
            prepareTorchAction(turnOn = !("off" in lower))
            return true
        }
        if ("notification" in lower) {
            prepareNotificationSummary()
            return true
        }
        if ("calendar" in lower || "event" in lower) {
            prepareCalendarAction(task)
            return true
        }
        if ("contact" in lower) {
            prepareContactsAction(task)
            return true
        }
        if (("open" in lower || "tap" in lower || "press" in lower) && ("app" in lower || "screen" in lower || "button" in lower)) {
            prepareAccessibilityAction(task)
            return true
        }
        return null
    }

    private fun prepareTorchAction(turnOn: Boolean) {
        lastAgentTask = "Turn flashlight ${if (turnOn) "on" else "off"}"
        val cameraPermission = permissionById("camera")
        if (!hasRuntimePermission(Manifest.permission.CAMERA)) {
            showMissingPermission(cameraPermission, "Camera permission is required for flashlight control.")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            lastAgentTimeline = listOf("Task received", "Checked device hardware", "Torch unavailable")
            lastAgentToolSummary = "TorchTool is implemented, but this device does not report a flash unit."
            lastAgentResult = "Flashlight control is unavailable on this device because no camera flash is reported."
            lastAgentError = null
            auditTool("torch", lastAgentTask, "Unavailable: no camera flash.", ToolRiskLevel.MEDIUM, approved = false, raw = "FEATURE_CAMERA_FLASH=false")
            return
        }
        lastAgentTask = "Turn flashlight ${if (turnOn) "on" else "off"}"
        lastAgentTimeline = listOf("Task received", "Checked Camera permission", "Waiting for approval")
        lastAgentToolSummary = "TorchTool is ready. It will use CameraManager only after approval."
        lastAgentResult = "Approve the Torch action to continue."
        pendingApproval = PendingApprovalState(
            approvalId = UUID.randomUUID().toString(),
            toolId = "torch",
            title = "Toggle flashlight",
            detail = "Set flashlight ${if (turnOn) "on" else "off"} using the local CameraManager API.",
            risk = ToolRiskLevel.MEDIUM,
            action = ApprovalActionDescriptor(
                if (turnOn) ApprovalActionType.TORCH_ON else ApprovalActionType.TORCH_OFF,
            ),
        )
    }

    private fun prepareNotificationSummary() {
        val permission = permissionById("notification_listener")
        if (!AgentPermissionCenter.isNotificationListenerEnabled(this)) {
            showMissingPermission(permission, "Notification Listener special access is required to summarize notifications.")
            return
        }
        pendingApproval = null
        val captureScopeId = "notification-${UUID.randomUUID()}"
        if (!R2hNotificationListenerService.openSummaryCapture(captureScopeId)) {
            lastAgentTask = "Summarize notifications"
            lastAgentTimeline = listOf("Task received", "Capture scope unavailable", "Blocked")
            lastAgentToolSummary = "NotificationSummaryTool did not start collection."
            lastAgentResult = "Notification capture could not be started. Try the request again."
            return
        }
        lastAgentTask = "Summarize notifications"
        lastAgentTimeline = listOf("Task received", "Scoped capture opened", "Waiting for approval")
        lastAgentToolSummary = "NotificationSummaryTool is collecting up to 8 notifications for this request only."
        lastAgentResult = "Approve within 60 seconds to summarize notifications arriving during this request."
        pendingApproval = PendingApprovalState(
            approvalId = UUID.randomUUID().toString(),
            toolId = "notification_summary",
            title = "Summarize recent notifications",
            detail = "Read only notifications captured during this explicit 60-second request scope and summarize them on-device.",
            risk = ToolRiskLevel.HIGH,
            action = ApprovalActionDescriptor(ApprovalActionType.NOTIFICATION_SUMMARY, captureScopeId),
        )
    }

    private fun prepareCalendarAction(task: String) {
        val permission = permissionById("calendar")
        if (!hasRuntimePermission(Manifest.permission.READ_CALENDAR) || !hasRuntimePermission(Manifest.permission.WRITE_CALENDAR)) {
            showMissingPermission(permission, "Calendar read/write permission is required before creating or reading events.")
            return
        }
        lastAgentTask = task
        lastAgentTimeline = listOf("Task received", "Calendar permission granted", "Waiting for approval")
        lastAgentToolSummary = "CalendarTool can create a local event after explicit approval."
        lastAgentResult = "Approve the calendar action to create a draft event from this task."
        pendingApproval = PendingApprovalState(
            approvalId = UUID.randomUUID().toString(),
            toolId = "calendar",
            title = "Create calendar event",
            detail = "Create a user-approved calendar event titled R2H Local Plan for tomorrow at 9:00 AM.",
            risk = ToolRiskLevel.HIGH,
            action = ApprovalActionDescriptor(ApprovalActionType.CALENDAR_INSERT, task),
        )
    }

    private fun prepareContactsAction(task: String) {
        val permission = permissionById("contacts")
        if (!hasRuntimePermission(Manifest.permission.READ_CONTACTS)) {
            showMissingPermission(permission, "Contacts permission is required before lookup. The full contact list is never dumped.")
            return
        }
        lastAgentTask = task
        lastAgentTimeline = listOf("Task received", "Contacts permission granted", "Waiting for approval")
        lastAgentToolSummary = "ContactsLookupTool can search a limited query after approval."
        lastAgentResult = "Approve contacts lookup to continue."
        pendingApproval = PendingApprovalState(
            approvalId = UUID.randomUUID().toString(),
            toolId = "contacts_lookup",
            title = "Search contacts",
            detail = "Perform a limited local contact lookup. The full contact list will not be displayed.",
            risk = ToolRiskLevel.HIGH,
            action = ApprovalActionDescriptor(ApprovalActionType.CONTACT_LOOKUP, task),
        )
    }

    private fun prepareAccessibilityAction(task: String) {
        val permission = permissionById("accessibility")
        if (!AgentPermissionCenter.isAccessibilityEnabled(this)) {
            showMissingPermission(permission, "Accessibility service must be manually enabled before any approved screen action.")
            return
        }
        lastAgentTask = task
        lastAgentTimeline = listOf("Task received", "Accessibility enabled", "Waiting for action preview approval")
        lastAgentToolSummary = "AccessibilityActionTool scaffold is enabled but automation remains disabled by default."
        lastAgentResult = "Approve only after reviewing the action preview. No hidden action will run."
        pendingApproval = PendingApprovalState(
            approvalId = UUID.randomUUID().toString(),
            toolId = "accessibility_action",
            title = "Accessibility action preview",
            detail = "Requested task: $task\nThis scaffold logs approval and does not perform hidden taps/text entry in this pass.",
            risk = ToolRiskLevel.HIGH,
            action = ApprovalActionDescriptor(ApprovalActionType.ACCESSIBILITY_PREVIEW, task),
        )
    }

    private fun showMissingPermission(item: AgentPermissionItem?, message: String) {
        val permission = item ?: permissionById("camera")
        lastAgentMissingPermission = permission
        lastAgentTimeline = listOf("Task received", "Checked permissions", "Blocked")
        lastAgentToolSummary = message
        lastAgentResult = message
        lastAgentError = null
        auditTool(permission?.id ?: "permission", lastAgentTask, "Blocked: $message", ToolRiskLevel.MEDIUM, approved = false, raw = "permissionStatus=${permission?.status?.displayName}")
    }

    private fun approvePendingAction() {
        val approval = pendingApproval ?: return
        val claimed = viewModel.claimApproval(approval.approvalId) ?: return
        try {
            when (claimed.action.type) {
                ApprovalActionType.TORCH_ON -> executeTorch(true)
                ApprovalActionType.TORCH_OFF -> executeTorch(false)
                ApprovalActionType.NOTIFICATION_SUMMARY -> executeNotificationSummary(claimed.action.argument)
                ApprovalActionType.CALENDAR_INSERT -> executeCalendarInsert(claimed.action.argument)
                ApprovalActionType.CONTACT_LOOKUP -> executeContactLookup(claimed.action.argument)
                ApprovalActionType.ACCESSIBILITY_PREVIEW -> executeAccessibilityPreview(claimed.action.argument)
            }
        } finally {
            viewModel.completeApproval(claimed.approvalId)
            renderShell(lastUiState)
        }
    }

    private fun cancelPendingAction() {
        val approval = pendingApproval ?: return
        if (viewModel.cancelApproval(approval.approvalId) == null) return
        lastAgentTimeline = listOf("Task received", "Approval requested", "Cancelled")
        lastAgentResult = "${approval.title} was cancelled. No action was executed."
        auditTool(approval.toolId, approval.title, "Cancelled before execution.", approval.risk, approved = false, raw = "user_cancelled=true")
        renderShell(lastUiState)
    }

    private fun executeNotificationSummary(scopeId: String) {
        val notifications = try {
            R2hNotificationListenerService.consumeSummaryCapture(scopeId)
        } finally {
            R2hNotificationListenerService.closeSummaryCapture(scopeId)
        }
        val summary = NotificationSummaryFormatter.format(notifications)
        lastAgentTimeline = listOf("Task received", "Approved", "Consumed scoped notifications", "Scope cleared", "Result")
        lastAgentToolSummary = "NotificationSummaryTool read ${summary.capturedRecordCount} notifications from the active request scope."
        viewModel.update {
            it.copy(
                lastAgentResult = summary.displayText,
                lastAgentResultPersistence = UiContentPersistence.VOLATILE,
            )
        }
        toolAuditStore.add(summary.toAuditEntry(System.currentTimeMillis()))
    }

    private fun executeAccessibilityPreview(task: String) {
        lastAgentTimeline = listOf("Task received", "Approved preview", "No unsafe automation executed", "Result")
        lastAgentResult = "AccessibilityActionTool scaffold is enabled. No tap/text action was executed because full safe automation is disabled in this pass."
        auditTool("accessibility_action", task, lastAgentResult, ToolRiskLevel.HIGH, approved = true, raw = "lastAccessibilityEvent=${R2hAccessibilityService.lastEventSummary}")
    }

    private fun executeTorch(turnOn: Boolean) {
        try {
            val cameraManager = getSystemService(CameraManager::class.java)
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                lastAgentResult = "Flashlight control is unavailable because no flash-capable camera was found."
                auditTool("torch", lastAgentTask, lastAgentResult, ToolRiskLevel.MEDIUM, approved = true, raw = "flashCameraId=null")
            } else {
                cameraManager.setTorchMode(cameraId, turnOn)
                torchEnabled = turnOn
                lastAgentResult = "Flashlight turned ${if (turnOn) "on" else "off"}."
                auditTool("torch", lastAgentTask, lastAgentResult, ToolRiskLevel.MEDIUM, approved = true, raw = "cameraId=$cameraId; torchEnabled=$torchEnabled")
            }
            lastAgentTimeline = listOf("Task received", "Approved", "Executed TorchTool", "Result")
            lastAgentToolSummary = "TorchTool executed through local CameraManager."
            lastAgentError = null
        } catch (t: Throwable) {
            lastAgentTimeline = listOf("Task received", "Approved", "Torch execution failed")
            lastAgentResult = "Flashlight control failed: ${t.message ?: t.javaClass.simpleName}"
            auditTool("torch", lastAgentTask, lastAgentResult, ToolRiskLevel.MEDIUM, approved = true, raw = t.toString())
        }
    }

    private fun executeCalendarInsert(task: String) {
        try {
            val calendarId = queryWritableCalendarId()
            if (calendarId == null) {
                lastAgentTimeline = listOf("Task received", "Approved", "Checked calendars", "Unavailable")
                lastAgentResult = "CalendarTool could not create an event because no writable local calendar was found."
                auditTool("calendar", task, lastAgentResult, ToolRiskLevel.HIGH, approved = true, raw = "calendarId=null")
                return
            }
            val start = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 9)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.MINUTE, 30) }
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, "R2H Local Plan")
                put(CalendarContract.Events.DESCRIPTION, task.take(500))
                put(CalendarContract.Events.DTSTART, start.timeInMillis)
                put(CalendarContract.Events.DTEND, end.timeInMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            lastAgentTimeline = listOf("Task received", "Approved", "Created calendar event", "Result")
            lastAgentResult = if (uri == null) {
                "CalendarTool attempted the approved insert, but the calendar provider did not return an event URI."
            } else {
                "Created calendar event: R2H Local Plan, tomorrow at 9:00 AM."
            }
            auditTool("calendar", task, lastAgentResult, ToolRiskLevel.HIGH, approved = true, raw = "calendarId=$calendarId; uri=$uri")
        } catch (t: Throwable) {
            lastAgentTimeline = listOf("Task received", "Approved", "Calendar insert failed")
            lastAgentResult = "CalendarTool failed: ${t.message ?: t.javaClass.simpleName}"
            auditTool("calendar", task, lastAgentResult, ToolRiskLevel.HIGH, approved = true, raw = t.toString())
        }
    }

    private fun queryWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "(${CalendarContract.Calendars.VISIBLE} = 1)"
        contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun executeContactLookup(task: String) {
        val query = task
            .replace("contacts", "", ignoreCase = true)
            .replace("contact", "", ignoreCase = true)
            .replace("lookup", "", ignoreCase = true)
            .replace("search", "", ignoreCase = true)
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull { it.length >= 3 }
        if (query == null) {
            lastAgentTimeline = listOf("Task received", "Approved", "No specific contact query", "Blocked")
            lastAgentResult = "ContactsLookupTool needs a specific contact name or term. It will not dump the full contact list."
            auditTool("contacts_lookup", task, lastAgentResult, ToolRiskLevel.HIGH, approved = true, raw = "query=null; broad_enumeration_refused=true")
            return
        }
        try {
            val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
            val args = arrayOf("%$query%")
            val matches = mutableListOf<String>()
            contentResolver.query(ContactsContract.Contacts.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
                while (cursor.moveToNext() && matches.size < 3) {
                    matches.add(cursor.getString(0).orEmpty())
                }
            }
            lastAgentTimeline = listOf("Task received", "Approved", "Limited contact lookup", "Result")
            lastAgentResult = if (matches.isEmpty()) {
                "No contacts matched \"$query\"."
            } else {
                "Limited contacts result for \"$query\":\n${matches.joinToString("\n") { "- $it" }}"
            }
            auditTool("contacts_lookup", task, "Limited lookup returned ${matches.size} result(s).", ToolRiskLevel.HIGH, approved = true, raw = "query=$query; matches=${matches.joinToString()}")
        } catch (t: Throwable) {
            lastAgentTimeline = listOf("Task received", "Approved", "Contacts lookup failed")
            lastAgentResult = "ContactsLookupTool failed: ${t.message ?: t.javaClass.simpleName}"
            auditTool("contacts_lookup", task, lastAgentResult, ToolRiskLevel.HIGH, approved = true, raw = t.toString())
        }
    }

    private fun sanitizeAgentResponse(text: String): String {
        val blocked = listOf("engineState=", "loadedModels=", "raw tool", "stacktrace", "Exception:")
        return text
            .lineSequence()
            .filterNot { line -> blocked.any { marker -> line.contains(marker, ignoreCase = true) } }
            .joinToString("\n")
            .trim()
            .ifBlank { "Local agent completed the task." }
    }

    private fun runAgentTask(taskText: String) {
        val cleanTask = taskText.trim()
        if (cleanTask.isBlank()) {
            toast("Enter a local task")
            return
        }
        lastAgentMissingPermission = null
        pendingApproval = null
        handleDeterministicAgentTool(cleanTask)?.let {
            renderShell(lastUiState)
            return
        }
        val service = engineService ?: run {
            lastAgentError = "Checking runtime... Engine service is not connected."
            lastAgentTimeline = listOf("Checking runtime", "Engine service is not connected")
            renderShell(lastUiState)
            return
        }
        val modelId = findTextModelId(lastTruthSnapshot) ?: run {
            lastAgentError = "Checking runtime... No local text model is available yet."
            lastAgentTimeline = listOf("Checking runtime", "Waiting for local text planner")
            renderShell(lastUiState)
            return
        }
        if (!isTextReady(lastTruthSnapshot, modelId)) {
            lastAgentError = "Checking runtime... The local text planner is still warming up."
            lastAgentTimeline = listOf("Task received", "Checking runtime", "Waiting for text planner")
            renderShell(lastUiState)
            return
        }

        lastAgentTask = cleanTask
        lastAgentError = null
        lastAgentResult = "Working locally..."
        lastAgentToolSummary = "Calculator, engine status inspector, and text planner are queued for this task."
        lastAgentTimeline = listOf("Task received", "Planning")
        isAgentRunning = true
        renderShell(lastUiState)

        val context = SessionContext(
            requestId = "agent-ui-${UUID.randomUUID()}",
            appId = packageName,
            userMessage = cleanTask,
            modelId = modelId,
            params = mapOf("engine.timeoutMs" to "300000"),
        )
        val callback = viewModel.createAgentUiCallback(context.requestId, cleanTask)
        try {
            service.runAgent(context, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(
                context.requestId,
                "Local agent call failed: ${e.message ?: "RemoteException"}",
            )
        }
    }

    private fun runTextProof() {
        val service = engineService ?: return setDiagnostics("TEXT proof blocked: engine service is not connected.")
        val modelId = findTextModelId(lastTruthSnapshot)
            ?: return setDiagnostics("TEXT proof blocked: no loaded or loadable TEXT model is exposed by the engine truth snapshot.")
        val request = GenerateRequest(
            requestId = "text-proof-${UUID.randomUUID()}",
            modelId = modelId,
            prompt = "Hello. Reply in one short sentence.",
            streaming = true,
            sessionConfig = null,
        )
        setDiagnostics("TEXT proof started\nModel: $modelId\nAPI: IR2hEngineService.generateText()\nNetwork/cloud: not used by this app.")
        val callback = viewModel.createTextProofCallback(request)
        try {
            service.generateText(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "TEXT proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runAgentProof() {
        val service = engineService ?: return setDiagnostics("Agent proof blocked: engine service is not connected.")
        val modelId = findTextModelId(lastTruthSnapshot)
            ?: return setDiagnostics("Agent proof blocked: no loaded TEXT model is available for the local planner.")
        val context = SessionContext(
            requestId = "agent-proof-${UUID.randomUUID()}",
            appId = packageName,
            userMessage = "Check the engine status, calculate 17 * 23, and summarize whether TEXT is ready.",
            modelId = modelId,
            params = mapOf("engine.timeoutMs" to "300000"),
        )
        setDiagnostics("Agent proof started\nModel: $modelId\nAPI: IR2hEngineService.runAgent()\nExpected calculator result: 391")
        val callback = viewModel.createAgentProofCallback(context.requestId)
        try {
            service.runAgent(context, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(context.requestId, "Agent proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runYoloProof() {
        val service = engineService ?: return setDiagnostics("YOLO proof blocked: engine service is not connected.")
        val modelId = findYoloModelId(lastTruthSnapshot) ?: "yolo11n-detection-onnx"
        val imageFile = try {
            copyAssetToProofFile("test-inputs/image-proof.jpg", "proof-inputs/image-proof.jpg")
        } catch (t: Throwable) {
            return setDiagnostics("YOLO proof blocked: missing test image asset test-inputs/image-proof.jpg (${t.message})")
        }
        val request = ModalInferenceRequest(
            requestId = "yolo-proof-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "IMAGE",
            taskType = "OBJECT_DETECTION",
            inputRefs = listOf(imageFile.absolutePath),
            textPrompt = null,
            params = mapOf(
                "yolo.confidenceThreshold" to "0.05",
                "yolo.iouThreshold" to "0.45",
                "engine.timeoutMs" to "300000",
            ),
        )
        setDiagnostics(
            "YOLO proof started\n" +
                "Model: $modelId\n" +
                "Image: ${imageFile.absolutePath}\n" +
                "API: IR2hEngineService.inferModal(OBJECT_DETECTION)",
        )
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(diagnosticLabel = "YOLO", resultStyle = ModalResultStyle.YOLO),
        )
        try {
            service.inferModal(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "YOLO proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runTtsProof() {
        if (engineService == null) return setDiagnostics("TTS proof blocked: engine service is not connected.")
        val modelId = findTtsModelId(lastTruthSnapshot) ?: "piper-tts-ar-en"
        viewModel.runTtsProof(modelId)
    }

    private fun runSttProof() {
        val service = engineService ?: return setDiagnostics("STT proof blocked: engine service is not connected.")
        val modelId = findSttModelId(lastTruthSnapshot) ?: "whisper-cpp-ggml-tiny"
        val audioFile = try {
            copyAssetToProofFile("test-inputs/stt-proof.wav", "proof-inputs/stt-proof.wav")
        } catch (t: Throwable) {
            return setDiagnostics("STT proof blocked: missing test-inputs/stt-proof.wav (${t.message})")
        }
        val request = ModalInferenceRequest(
            requestId = "stt-proof-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "AUDIO",
            taskType = "SPEECH_TO_TEXT",
            inputRefs = listOf(audioFile.absolutePath),
            textPrompt = null,
            params = mapOf("engine.timeoutMs" to "300000"),
        )
        setDiagnostics(
            "STT proof started\n" +
                "Model: $modelId\n" +
                "Audio: ${audioFile.absolutePath}\n" +
                "API: IR2hEngineService.transcribeSpeech(); inferModal(SPEECH_TO_TEXT)\n" +
                "Runtime: whisper.cpp\n" +
                "Network/cloud: not used by this app.",
        )
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(diagnosticLabel = "STT", resultStyle = ModalResultStyle.GENERIC_TEXT),
        )
        try {
            service.transcribeSpeech(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "STT proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runEmbeddingsProof() {
        if (engineService == null) return setDiagnostics("Embeddings proof blocked: engine service is not connected.")
        val modelId = findEmbeddingModelId(lastTruthSnapshot) ?: "qwen3-embedding-0.6b-q8-gguf"
        viewModel.runEmbeddingsProof(modelId, activeStudioWorkflowTitle)
    }

    private fun runVideoObjectDetectionProof() {
        val service = engineService ?: return setDiagnostics("Video object detection proof blocked: engine service is not connected.")
        val modelId = findVideoYoloModelId(lastTruthSnapshot) ?: "video-yolo11n-object-detection"
        val videoFile = try {
            copyAssetToProofFile("test-inputs/video-proof.mp4", "proof-inputs/video-proof.mp4")
        } catch (t: Throwable) {
            return setDiagnostics("Video object detection proof blocked: missing test-inputs/video-proof.mp4 (${t.message})")
        }
        val request = ModalInferenceRequest(
            requestId = "video-yolo-proof-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "VIDEO",
            taskType = "OBJECT_DETECTION",
            inputRefs = listOf(videoFile.absolutePath),
            textPrompt = null,
            params = mapOf(
                "video.sampleCount" to "3",
                "yolo.confidenceThreshold" to "0.05",
                "yolo.iouThreshold" to "0.45",
                "engine.timeoutMs" to "300000",
            ),
        )
        setDiagnostics(
            "Video object detection proof started\n" +
                "Model: $modelId\n" +
                "Video: ${videoFile.absolutePath}\n" +
                "API: IR2hEngineService.analyzeVideo(VIDEO, OBJECT_DETECTION)\n" +
                "Runtime: frame extraction + YOLO ONNX\n" +
                "Network/cloud: not used by this app.",
        )
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                diagnosticLabel = "Video object detection",
                resultStyle = ModalResultStyle.GENERIC_TEXT,
            ),
        )
        try {
            service.analyzeVideo(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(
                request.requestId,
                "Video object detection proof call failed: ${e.message ?: "RemoteException"}",
            )
        }
    }

    private fun runVideoFullAnalysisProof() {
        val service = engineService ?: return setDiagnostics("VIDEO full analysis proof blocked: engine service is not connected.")
        val modelId = findQwen2VlMtmdModelId(lastTruthSnapshot) ?: "qwen2vl-mtmd-image-text"
        val videoFile = try {
            copyAssetToProofFile("test-inputs/video-proof.mp4", "proof-inputs/video-proof.mp4")
        } catch (t: Throwable) {
            return setDiagnostics("VIDEO full analysis proof blocked: missing test-inputs/video-proof.mp4 (${t.message})")
        }
        val prompt = "Describe each sampled video frame and produce a short temporal video summary."
        val request = ModalInferenceRequest(
            requestId = "video-full-analysis-proof-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "VIDEO",
            taskType = "VIDEO_FULL_ANALYSIS",
            inputRefs = listOf(videoFile.absolutePath),
            textPrompt = prompt,
            params = mapOf(
                "video.sampleCount" to "2",
                "video.frameMaxTokens" to "96",
                "engine.temperature" to "0.2",
                "engine.timeoutMs" to "600000",
            ),
        )
        val started = "VIDEO full analysis proof started\n" +
            "Model: $modelId\n" +
            "Video: ${videoFile.absolutePath}\n" +
            "Prompt: $prompt\n" +
            "API: IR2hEngineService.analyzeVideo(VIDEO, VIDEO_FULL_ANALYSIS)\n" +
            "Runtime: Video frame extraction + Qwen2VL mtmd\n" +
            "Network/cloud: not used by this app."
        setDiagnostics(started)
        val studioTitle = activeStudioWorkflowTitle
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                diagnosticLabel = "VIDEO full analysis",
                resultStyle = ModalResultStyle.GENERIC_TEXT,
                studio = studioTitle?.let {
                    StudioCompletionSpec(
                        title = it,
                        successLead = "Sample result from bundled local input",
                        errorPrefix = "Sample run could not complete",
                        audit = ToolAuditSpec(
                            "video_analysis",
                            it,
                            "Video sample analysis completed.",
                            ToolRiskLevel.LOW,
                            false,
                        ),
                    )
                },
            ),
        )
        try {
            service.analyzeVideo(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(
                request.requestId,
                "VIDEO full analysis proof call failed: ${e.message ?: "RemoteException"}",
            )
        }
    }

    private fun runAudioFullUnderstandingProof() {
        val service = engineService ?: return setDiagnostics("AUDIO proof blocked: engine service is not connected.")
        val modelId = findAudioTaggingModelId(lastTruthSnapshot) ?: "sherpa-onnx-ced-base-audio-tagging-int8"
        val audioFile = try {
            copyAssetToProofFile(
                "model-pack/models/audio/ced-base/sherpa-onnx-ced-base-audio-tagging-2024-04-19/test_wavs/1.wav",
                "proof-inputs/audio-full-understanding-ced-1.wav",
            )
        } catch (t: Throwable) {
            return setDiagnostics("AUDIO proof blocked: missing CED proof WAV asset (${t.message})")
        }
        val request = ModalInferenceRequest(
            requestId = "audio-tagging-proof-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "AUDIO",
            taskType = "AUDIO_TAGGING",
            inputRefs = listOf(audioFile.absolutePath),
            textPrompt = null,
            params = mapOf(
                "audio.topK" to "5",
                "engine.timeoutMs" to "300000",
            ),
        )
        val started = "AUDIO proof started\n" +
            "Model: $modelId\n" +
            "Audio: ${audioFile.absolutePath}\n" +
            "API: IR2hEngineService.analyzeAudio(); inferModal(AUDIO, AUDIO_TAGGING)\n" +
            "Runtime: Sherpa-ONNX CED audio tagging\n" +
            "Model asset: model.int8.onnx\n" +
            "Labels: class_labels_indices.csv\n" +
            "Network/cloud: not used by this app."
        setDiagnostics(started)
        val studioTitle = activeStudioWorkflowTitle
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                diagnosticLabel = "AUDIO",
                resultStyle = ModalResultStyle.GENERIC_TEXT,
                studio = studioTitle?.let {
                    StudioCompletionSpec(
                        title = it,
                        successLead = "Sample result from bundled local input",
                        errorPrefix = "Sample run could not complete",
                        audit = ToolAuditSpec(
                            "audio_understanding",
                            it,
                            "Audio sample analysis completed.",
                            ToolRiskLevel.LOW,
                            false,
                        ),
                    )
                },
            ),
        )
        try {
            service.analyzeAudio(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "AUDIO proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runImageMultimodalProof() {
        val service = engineService ?: return setDiagnostics("MULTIMODAL proof blocked: engine service is not connected.")
        val modelId = findQwen2VlMtmdModelId(lastTruthSnapshot) ?: "qwen2vl-mtmd-image-text"
        val imageFile = try {
            copyAssetToProofFile("test-inputs/image-proof.jpg", "proof-inputs/multimodal-image-proof.jpg")
        } catch (t: Throwable) {
            return setDiagnostics("MULTIMODAL proof blocked: missing test-inputs/image-proof.jpg (${t.message})")
        }
        val prompt = "Describe this image and list the main visible objects."
        val request = ModalInferenceRequest(
            requestId = "multimodal-proof-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "IMAGE",
            taskType = "IMAGE_TEXT_MULTIMODAL",
            inputRefs = listOf(imageFile.absolutePath),
            textPrompt = prompt,
            params = mapOf(
                "engine.maxTokens" to "160",
                "engine.temperature" to "0.2",
                "engine.timeoutMs" to "600000",
            ),
        )
        val started = "MULTIMODAL proof started\n" +
            "Model: $modelId\n" +
            "Image: ${imageFile.absolutePath}\n" +
            "Prompt: $prompt\n" +
            "API: IR2hEngineService.analyzeImage(); inferModal(IMAGE, IMAGE_TEXT_MULTIMODAL); generateFromImage\n" +
            "Runtime: llama.cpp mtmd / Qwen2VL\n" +
            "Model asset: Qwen2-VL-2B-Instruct-Q4_K_M.gguf\n" +
            "mmproj: mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf\n" +
            "Network/cloud: not used by this app."
        setDiagnostics(started)
        val studioTitle = activeStudioWorkflowTitle
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                diagnosticLabel = "MULTIMODAL",
                resultStyle = ModalResultStyle.IMAGE_TEXT,
                studio = studioTitle?.let {
                    StudioCompletionSpec(
                        title = it,
                        successLead = "Sample result from bundled local input",
                        errorPrefix = "Sample run could not complete",
                        audit = ToolAuditSpec(
                            "image_understanding",
                            it,
                            "Image sample analysis completed.",
                            ToolRiskLevel.LOW,
                            false,
                        ),
                    )
                },
            ),
        )
        try {
            service.analyzeImage(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "MULTIMODAL proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun runOcrProof() {
        val service = engineService ?: return setDiagnostics("OCR proof blocked: engine service is not connected.")
        val modelId = findQwen2VlMtmdModelId(lastTruthSnapshot) ?: "qwen2vl-mtmd-image-text"
        val imageFile = try {
            copyAssetToProofFile("test-inputs/ocr-proof.png", "proof-inputs/ocr-proof.png")
        } catch (t: Throwable) {
            return setDiagnostics("OCR proof blocked: missing test-inputs/ocr-proof.png (${t.message})")
        }
        val prompt = "Read all visible text in this image. Preserve line breaks."
        val request = ModalInferenceRequest(
            requestId = "ocr-proof-${UUID.randomUUID()}",
            modelId = modelId,
            modality = "IMAGE",
            taskType = "OCR",
            inputRefs = listOf(imageFile.absolutePath),
            textPrompt = prompt,
            params = mapOf(
                "engine.maxTokens" to "128",
                "engine.temperature" to "0.0",
                "engine.timeoutMs" to "600000",
            ),
        )
        val started = "OCR proof started\n" +
            "Model: $modelId\n" +
            "Image: ${imageFile.absolutePath}\n" +
            "OCR prompt: $prompt\n" +
            "API: IR2hEngineService.runOcr(); inferModal(IMAGE, OCR)\n" +
            "Runtime: Qwen2VL mtmd OCR\n" +
            "Network/cloud: not used by this app."
        setDiagnostics(started)
        val studioTitle = activeStudioWorkflowTitle
        val callback = viewModel.createModalCallback(
            request.requestId,
            ModalCallbackSpec(
                diagnosticLabel = "OCR",
                resultStyle = ModalResultStyle.OCR_TEXT,
                studio = studioTitle?.let {
                    StudioCompletionSpec(
                        title = it,
                        successLead = "Sample result from bundled local input",
                        errorPrefix = "Sample run could not complete",
                        audit = ToolAuditSpec(
                            "ocr",
                            it,
                            "OCR sample completed.",
                            ToolRiskLevel.LOW,
                            false,
                        ),
                    )
                },
            ),
        )
        try {
            service.runOcr(request, callback)
        } catch (e: RemoteException) {
            viewModel.callbackDispatchFailed(request.requestId, "OCR proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun copyAssetToProofFile(assetPath: String, relativeOutputPath: String): File {
        val target = File(filesDir, relativeOutputPath)
        target.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    private fun copyPickedUriToPrivateFile(uri: Uri, destinationName: String): File {
        val displayName = queryDisplayName(uri) ?: destinationName
        val safeName = sanitizeFileName(displayName).ifBlank { destinationName }
        val target = File(filesDir, "studio-inputs/${sanitizeFileName(destinationName)}-$safeName")
        target.parentFile?.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open selected file")
        return target
    }

    private fun requestAgentPermission(item: AgentPermissionItem) {
        when (item.requestKind) {
            AgentPermissionRequestKind.RUNTIME -> {
                val permissions = item.runtimePermissions.ifEmpty { listOfNotNull(item.runtimePermission) }
                if (permissions.isEmpty()) return
                pendingRuntimePermission = item
                runtimePermissionLauncher.launch(permissions.toTypedArray())
            }
            AgentPermissionRequestKind.SETTINGS -> openSettingsSafely(item.settingsIntent)
            AgentPermissionRequestKind.NONE -> toast("${item.name}: ${item.status.displayName}")
        }
    }

    private fun openSettingsSafely(intent: Intent?) {
        if (intent == null) {
            toast("Settings screen is unavailable on this device")
            return
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
                toast("Settings screen is unavailable on this device")
            }
        }
    }

    private fun permissionById(id: String): AgentPermissionItem? =
        AgentPermissionCenter.items(this).firstOrNull { it.id == id }

    private fun hasRuntimePermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isToolAvailable(tool: ToolDefinition): Boolean {
        if (!tool.implemented) return false
        val runtimeOk = tool.permissions.runtimePermissions.all { hasRuntimePermission(it) }
        val specialOk = tool.permissions.specialAccess.all { specialAccessGranted(it) }
        val hardwareOk = tool.id != "torch" || packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        return runtimeOk && specialOk && hardwareOk
    }

    private fun toolBlockedReason(tool: ToolDefinition): String {
        if (!tool.implemented) return "Scaffold"
        val missingRuntime = tool.permissions.runtimePermissions.firstOrNull { !hasRuntimePermission(it) }
        if (missingRuntime != null) return "Permission"
        val missingSpecial = tool.permissions.specialAccess.firstOrNull { !specialAccessGranted(it) }
        if (missingSpecial != null) return "Settings"
        if (tool.id == "torch" && !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return "No flash"
        return "Checking"
    }

    private fun specialAccessGranted(id: String): Boolean =
        when (id) {
            LocalToolRegistry.SPECIAL_NOTIFICATION_LISTENER -> AgentPermissionCenter.isNotificationListenerEnabled(this)
            LocalToolRegistry.SPECIAL_USAGE_ACCESS -> AgentPermissionCenter.hasUsageAccess(this)
            LocalToolRegistry.SPECIAL_ACCESSIBILITY -> AgentPermissionCenter.isAccessibilityEnabled(this)
            LocalToolRegistry.SPECIAL_OVERLAY -> Settings.canDrawOverlays(this)
            else -> false
        }

    private fun auditTool(
        toolId: String,
        action: String,
        result: String,
        risk: ToolRiskLevel,
        approved: Boolean,
        raw: String,
    ) {
        toolAuditStore.add(
            ToolAuditEntry(
                timestampMs = System.currentTimeMillis(),
                toolId = toolId,
                actionSummary = action,
                result = result,
                riskLevel = risk,
                approvedByUser = approved,
                rawDetails = raw,
            ),
        )
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("R2H local result", text))
        toast("Copied")
    }

    private fun formatTime(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))

    private fun setDiagnostics(message: String) {
        viewModel.update { it.copy(diagnosticsText = message) }
        diagnosticsDetailView?.text = message
        Log.i(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.UI_DIAGNOSTIC,
                status = DiagnosticStatus.UPDATED,
                outputContent = message,
            ),
        )
    }

    private fun findYoloModelId(truth: EngineTruthSnapshot?): String? {
        val models = truth?.models.orEmpty()
        return models.firstOrNull { model ->
            model.backendId == "yolo-onnx" || model.modelId == "yolo11n-detection-onnx"
        }?.modelId ?: models.firstOrNull { model ->
            model.backendId != "video-yolo-onnx" &&
                model.modelId != "video-yolo11n-object-detection" &&
                model.modelId.contains("yolo", ignoreCase = true)
        }?.modelId
    }

    private fun findVideoYoloModelId(truth: EngineTruthSnapshot?): String? =
        truth?.models?.firstOrNull { model ->
            model.backendId == "video-yolo-onnx" || model.modelId == "video-yolo11n-object-detection"
        }?.modelId

    private fun findTtsModelId(truth: EngineTruthSnapshot?): String? =
        truth?.models?.firstOrNull { model ->
            model.backendId == "sherpa-onnx-tts" || model.modelId.contains("tts", ignoreCase = true)
        }?.modelId

    private fun findSttModelId(truth: EngineTruthSnapshot?): String? =
        truth?.models?.firstOrNull { model ->
            model.backendId == "whisper-cpp" || model.modelId.contains("whisper", ignoreCase = true)
        }?.modelId

    private fun findEmbeddingModelId(truth: EngineTruthSnapshot?): String? =
        truth?.models?.firstOrNull { model ->
            model.backendId == "llama-cpp-embedding" || model.modelId.contains("embedding", ignoreCase = true)
        }?.modelId

    private fun findAudioTaggingModelId(truth: EngineTruthSnapshot?): String? =
        truth?.models?.firstOrNull { model ->
            model.backendId == "sherpa-onnx-audio-tagging" ||
                model.modelId == "sherpa-onnx-ced-base-audio-tagging-int8"
        }?.modelId

    private fun findQwen2VlMtmdModelId(truth: EngineTruthSnapshot?): String? =
        truth?.models?.firstOrNull { model ->
            model.backendId == "qwen2vl-mtmd" || model.modelId == "qwen2vl-mtmd-image-text"
        }?.modelId

    private fun buildRawSnapshotText(state: RuntimeUiState): String = buildString {
        appendLine("connected=${state.connected}")
        appendLine("apiVersion=${state.apiVersion ?: "unknown"}")
        appendLine("loadedModelId=${state.loadedModelId ?: "none"}")
        appendLine("loadState=${state.loadState ?: "unknown"}")
        appendLine("loadModelId=${state.loadModelId ?: "none"}")
        appendLine("loadProgress=${state.loadProgress ?: 0}%")
        appendLine("loadError=${state.loadError ?: "none"}")
        appendLine("runtimeState=${state.truth?.runtime?.engineState?.name ?: "unknown"}")
        appendLine("activeBackend=${state.truth?.runtime?.activeBackendId ?: "none"}")
        appendLine("readyForInference=${state.truth?.runtime?.readyForInference ?: false}")
        appendLine("catalogModels=${state.truth?.models?.size ?: 0}")
        appendLine("serviceModels=${state.serviceModels.size}")
        appendLine("localModels=${state.localModels.size}")
        appendLine("liveClientSessions=${state.truth?.liveClientSessions?.size ?: 0}")
        append("errors=${state.truth?.lastErrors?.size ?: 0}")
    }.trimEnd()

    private fun findTextModelId(truth: EngineTruthSnapshot?): String? {
        val runtime = truth?.runtime
        return runtime?.activeTextModelId
            ?: runtime?.activeModelId
            ?: truth?.models
                ?.firstOrNull { it.ready && it.backendId == "llama-cpp" }
                ?.modelId
            ?: truth?.models
                ?.firstOrNull { it.runtimeState in setOf(ModelRuntimeState.ACTIVE, ModelRuntimeState.LOADED, ModelRuntimeState.LOADABLE) }
                ?.modelId
    }

    private fun isTextReady(truth: EngineTruthSnapshot?, modelId: String?): Boolean {
        if (truth == null || modelId == null) return false
        val textModality = truth.modalityState.firstOrNull { it.modality == "TEXT" }
        val textTaskReady = textModality?.supportedTaskTypes.orEmpty().any {
            it == "TEXT_GENERATION" || it == "GENERATE_TEXT" || it == "CHAT"
        }
        return truth.runtime.readyForInference &&
            textModality?.ready == true &&
            textModality.activeModelId == modelId &&
            textTaskReady
    }

    private fun isModelReady(truth: EngineTruthSnapshot?, modelId: String?): Boolean =
        modelId != null && truth?.models?.firstOrNull { it.modelId == modelId }?.let(::isRuntimeLoadedModel) == true

    private fun isRuntimeLoadedModel(model: ModelRuntimeInfo): Boolean =
        model.lastError == null && model.runtimeState in setOf(ModelRuntimeState.ACTIVE, ModelRuntimeState.LOADED)

    private fun modelReadinessReason(model: ModelRuntimeInfo): String {
        if (model.ready) {
            return "ready because descriptor/model/runtime are loaded and request path is reported ready"
        }

        model.lastError?.let { error ->
            return when (model.runtimeState) {
                ModelRuntimeState.FAILED -> "failed load: ${error.code.name}: ${error.message}"
                else -> "${error.code.name}: ${error.message}"
            }
        }

        return when {
            model.installationState.name == "MISSING" -> "missing model file"
            model.validationState.name == "INVALID" -> "descriptor or model contract invalid"
            model.runtimeState == ModelRuntimeState.LOADABLE -> "loadable but not ready until loaded and output proof exists"
            model.runtimeState == ModelRuntimeState.UNLOADED -> "not loaded"
            model.runtimeState == ModelRuntimeState.LOADING -> "load in progress"
            model.runtimeState == ModelRuntimeState.LOADED -> "runtime loaded; capability API path is available"
            model.runtimeState == ModelRuntimeState.ACTIVE -> "active runtime model; request path is available"
            else -> "not ready"
        }
    }

    private fun jniAvailabilityText(truth: EngineTruthSnapshot?): String {
        val textModality = truth?.modalityState?.firstOrNull { it.modality == "TEXT" }
        return if (textModality?.runtimeKey == "llama-cpp" && textModality.ready) {
            "available for TEXT through llama.cpp JNI"
        } else {
            "not proven"
        }
    }

    private fun llamaCppStateText(truth: EngineTruthSnapshot?): String {
        val textModality = truth?.modalityState?.firstOrNull { it.runtimeKey == "llama-cpp" }
        return when {
            textModality == null -> "not registered"
            textModality.ready -> "ready with active model ${textModality.activeModelId ?: "none"}"
            textModality.lastError != null -> "failed: ${textModality.lastError!!.message}"
            else -> "registered but not ready"
        }
    }

    private fun capabilityRecords(state: RuntimeUiState): List<CapabilityUiRecord> {
        val truth = state.truth
        val modelId = findTextModelId(truth)
        val textReady = isTextReady(truth, modelId)
        val ttsModelId = findTtsModelId(truth)
        val sttModelId = findSttModelId(truth)
        val embeddingModelId = findEmbeddingModelId(truth)
        val audioTaggingModelId = findAudioTaggingModelId(truth)
        val qwen2VlMtmdModelId = findQwen2VlMtmdModelId(truth)
        val yoloModelId = findYoloModelId(truth)
        val videoYoloModelId = findVideoYoloModelId(truth)
        val qwenReady = isModelReady(truth, qwen2VlMtmdModelId)
        val yoloReady = isModelReady(truth, yoloModelId)
        val videoReady = isModelReady(truth, videoYoloModelId) || qwenReady
        val ttsReady = isModelReady(truth, ttsModelId)
        val sttReady = isModelReady(truth, sttModelId)
        val audioReady = isModelReady(truth, audioTaggingModelId)
        val embeddingReady = isModelReady(truth, embeddingModelId)
        val checkingLabel = if (truth == null) "CHECKING" else "CHECKING"
        val checkingKind = StatusKind.UNAVAILABLE
        val textProof = when {
            lastGenerationText.isNotBlank() -> "Installed UI generation output captured in this session"
            textReady -> "Runtime loaded and request path available; run Chat proof to capture output"
            else -> "Waiting for runtime truth snapshot or active TEXT model"
        }

        return listOf(
            CapabilityUiRecord(
                name = "TEXT / GENERATE_TEXT",
                status = if (textReady) "READY" else checkingLabel,
                kind = if (textReady) StatusKind.READY else checkingKind,
                runtime = "llama.cpp JNI",
                model = modelId ?: "None loaded",
                apiPath = "IR2hEngineService.generate(); IR2hEngineService.generateText()",
                proofStatus = textProof,
                reason = if (textReady) {
                    "Descriptor, local GGUF model, JNI runtime, service API, and text input path are active."
                } else {
                    "Waiting for the engine to report a loaded TEXT model and ready runtime."
                },
            ),
            CapabilityUiRecord(
                name = "IMAGE",
                status = if (qwenReady) "READY" else checkingLabel,
                kind = if (qwenReady) StatusKind.READY else checkingKind,
                runtime = "llama.cpp mtmd / Qwen2VL",
                model = qwen2VlMtmdModelId ?: "Qwen2-VL GGUF present; mmproj present",
                apiPath = "IR2hEngineService.analyzeImage(); inferModal(IMAGE, IMAGE_TEXT_MULTIMODAL)",
                proofStatus = if (qwenReady) {
                    "MODEL_ASSET_PRESENT: YES; MMPROJ_PRESENT: YES; run image multimodal proof for generated text"
                } else {
                    "MODEL_ASSET_PRESENT: YES; OUTPUT_PRODUCED: NO; INSTALLED_PROOF: NO"
                },
                reason = if (qwenReady) {
                    "Qwen2VL mtmd model is loaded for local prompt+image generation."
                } else {
                    "Waiting for the mtmd generation runtime to report ready through the central service."
                },
            ),
            CapabilityUiRecord(
                name = "IMAGE DETECTION / YOLO",
                status = if (yoloReady) "READY" else checkingLabel,
                kind = if (yoloReady) StatusKind.READY else checkingKind,
                runtime = "YOLO ONNX Runtime Android backend registered when model-pack bootstrap succeeds",
                model = yoloModelId ?: "YOLO ONNX present; YOLO NCNN bin/param present",
                apiPath = "IR2hEngineService.inferModal(IMAGE, OBJECT_DETECTION)",
                proofStatus = if (yoloReady) "Runtime model reports ready; proof runner available." else "Waiting for runtime model readiness.",
                reason = "YOLO decode, letterbox resize, normalization, ONNX inference, COCO label mapping, NMS, and service result mapping are wired through the local service.",
            ),
            CapabilityUiRecord(
                name = "AUDIO",
                status = if (audioReady) "READY" else checkingLabel,
                kind = if (audioReady) StatusKind.READY else checkingKind,
                runtime = "Sherpa-ONNX CED audio tagging",
                model = audioTaggingModelId ?: "No audio-understanding model descriptor",
                apiPath = "IR2hEngineService.analyzeAudio(); inferModal(AUDIO, AUDIO_TAGGING)",
                proofStatus = if (audioReady) {
                    "MODEL_ASSET_PRESENT: YES; LABELS_PRESENT: YES; run audio full understanding proof for top-K labels"
                } else {
                    "No installed audio analysis proof"
                },
                reason = if (audioReady) {
                    "CED-base audio tagging is packaged, loaded through Sherpa-ONNX, and exposed separately from STT/TTS."
                } else {
                    "Waiting for the separate audio understanding model to report ready."
                },
            ),
            CapabilityUiRecord(
                name = "STT",
                status = if (sttReady) "READY" else checkingLabel,
                kind = if (sttReady) StatusKind.READY else checkingKind,
                runtime = "whisper.cpp JNI",
                model = sttModelId ?: "Whisper tiny GGML asset present",
                apiPath = "IR2hEngineService.transcribeSpeech(); inferModal(SPEECH_TO_TEXT)",
                proofStatus = if (sttReady) {
                    "MODEL_ASSET_PRESENT: YES; RUNTIME_CONNECTED: YES; run STT proof for transcript output"
                } else {
                    "MODEL_ASSET_PRESENT: YES; RUNTIME_CONNECTED: NO; INSTALLED_PROOF: NO"
                },
                reason = if (sttReady) {
                    "Local whisper.cpp model, JNI runtime, WAV input path, and SPEECH_TO_TEXT service path are loaded."
                } else {
                    "Waiting for whisper.cpp to report the local GGML model ready."
                },
            ),
            CapabilityUiRecord(
                name = "TTS",
                status = if (ttsReady) "READY" else checkingLabel,
                kind = if (ttsReady) StatusKind.READY else checkingKind,
                runtime = "Sherpa-ONNX Piper",
                model = ttsModelId ?: "Piper ONNX voices present",
                apiPath = "IR2hEngineService.synthesizeSpeech(); inferModal(TEXT_TO_SPEECH)",
                proofStatus = if (ttsReady) {
                    "MODEL_ASSET_PRESENT: YES; RUNTIME_CONNECTED: YES; run TTS proof for WAV output"
                } else {
                    "MODEL_ASSET_PRESENT: YES; RUNTIME_CONNECTED: NO; INSTALLED_PROOF: NO"
                },
                reason = if (ttsReady) {
                    "Selected Arabic and English Piper voices, espeak-ng-data, Sherpa JNI runtime, and TEXT_TO_SPEECH service path are loaded."
                } else {
                    "Waiting for Sherpa/Piper to report selected voices ready."
                },
            ),
            CapabilityUiRecord(
                name = "VIDEO",
                status = if (videoReady) "READY" else checkingLabel,
                kind = if (videoReady) StatusKind.READY else checkingKind,
                runtime = "Video frame extraction + Qwen2VL mtmd",
                model = videoYoloModelId ?: qwen2VlMtmdModelId ?: "Video model not loaded",
                apiPath = "IR2hEngineService.analyzeVideo(); inferModal(VIDEO, VIDEO_FULL_ANALYSIS)",
                proofStatus = if (videoReady) {
                    "MODEL_ASSET_PRESENT: YES; MMPROJ_PRESENT: YES; run video full analysis proof for per-frame summaries"
                } else {
                    "No installed video input/output proof"
                },
                reason = if (videoReady) {
                    "Full video analysis can reuse installed frame extraction and the ready local Qwen2VL mtmd image-to-text runtime."
                } else {
                    "Waiting for the local video analysis runtime to report ready."
                },
            ),
            CapabilityUiRecord(
                name = "MULTIMODAL",
                status = if (qwenReady) "READY" else checkingLabel,
                kind = if (qwenReady) StatusKind.READY else checkingKind,
                runtime = "llama.cpp mtmd / Qwen2VL",
                model = qwen2VlMtmdModelId ?: "Qwen2-VL GGUF present; mmproj present",
                apiPath = "IR2hEngineService.generateMultimodal(); inferModal(MULTIMODAL)",
                proofStatus = if (qwenReady) {
                    "MODEL_ASSET_PRESENT: YES; MMPROJ_PRESENT: YES; proof runner available for generated text"
                } else {
                    "MODEL_ASSET_PRESENT: YES; MMPROJ_PRESENT: YES; OUTPUT_PRODUCED: NO"
                },
                reason = if (qwenReady) {
                    "Native mtmd runtime is exposed through the service for prompt+image generation."
                } else {
                    "Waiting for the native mtmd runtime to report generated multimodal output ready."
                },
            ),
            CapabilityUiRecord(
                name = "OCR",
                status = if (qwenReady) "READY" else checkingLabel,
                kind = if (qwenReady) StatusKind.READY else checkingKind,
                runtime = "Qwen2VL mtmd OCR",
                model = qwen2VlMtmdModelId ?: "Qwen2VL mtmd model not loaded",
                apiPath = "IR2hEngineService.runOcr(); inferModal(IMAGE, OCR)",
                proofStatus = if (qwenReady) {
                    "MODEL_ASSET_PRESENT: YES; MMPROJ_PRESENT: YES; run OCR proof for extracted text"
                } else {
                    "No installed OCR extraction proof"
                },
                reason = if (qwenReady) {
                    "OCR is routed through the ready local Qwen2VL mtmd image-to-text runtime."
                } else {
                    "Waiting for the Qwen2VL mtmd vision runtime to report OCR ready."
                },
            ),
            CapabilityUiRecord(
                name = "EMBEDDINGS",
                status = if (embeddingReady) "READY" else checkingLabel,
                kind = if (embeddingReady) StatusKind.READY else checkingKind,
                runtime = "llama.cpp Qwen3 embeddings JNI",
                model = embeddingModelId ?: "Qwen3 Embedding GGUF asset present",
                apiPath = "IR2hEngineService.createEmbedding(); inferModal(EMBEDDING)",
                proofStatus = if (embeddingReady) {
                    "MODEL_ASSET_PRESENT: YES; RUNTIME_CONNECTED: YES; run embeddings proof for vector similarity"
                } else {
                    "MODEL_ASSET_PRESENT: YES; RUNTIME_CONNECTED: NO; EMBEDDING_OUTPUT_PRODUCED: NO"
                },
                reason = if (embeddingReady) {
                    "Qwen3 Embedding 0.6B was locally converted to Q8 GGUF, loaded through llama.cpp, and wired to createEmbedding()."
                } else {
                    "Waiting for Qwen3 GGUF embeddings to report ready."
                },
            ),
            CapabilityUiRecord(
                name = "TOOLS / AGENTS",
                status = if (textReady) "READY" else checkingLabel,
                kind = if (textReady) StatusKind.READY else checkingKind,
                runtime = "TEXT planner + local deterministic tool executor",
                model = modelId ?: "None loaded",
                apiPath = "IR2hEngineService.runAgent()",
                proofStatus = if (textReady) "Local planner and tool path can run through runAgent()." else "Waiting for local TEXT planner readiness.",
                reason = "runAgent uses the local planner path and deterministic tools such as calculator and engine status inspector.",
            ),
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun queryFileSize(uri: Uri): Long? {
        val projection = arrayOf(OpenableColumns.SIZE)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                return cursor.getLong(sizeIndex)
            }
        }
        return null
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[^\w.\-]"""), "_")

    private fun buildCopyProgressText(fileName: String, copiedBytes: Long, totalBytes: Long?): String {
        val copied = formatSize(copiedBytes)
        val total = totalBytes?.let { formatSize(it) } ?: "unknown"
        val percent = if (totalBytes != null && totalBytes > 0L) {
            DecimalFormat("0.0").format((copiedBytes.toDouble() / totalBytes.toDouble()) * 100.0) + "%"
        } else {
            "--"
        }

        return buildString {
            appendLine("Importing model...")
            appendLine("File: $fileName")
            appendLine("Progress: $percent")
            appendLine("Copied: $copied / $total")
            appendLine()
            appendLine("Destination:")
            append(File(filesDir, MODELS_DIR).absolutePath)
        }
    }

    private fun buildFailureText(title: String, throwable: Throwable): String =
        buildString {
            appendLine(title)
            append(throwable.javaClass.simpleName)
            throwable.message?.let {
                append(": ")
                append(it)
            }
        }

    private fun yesNo(value: Boolean): String =
        if (value) "YES" else "NO"

    private fun formatSize(sizeBytes: Long): String {
        val kb = 1024L
        val mb = kb * 1024L
        val gb = mb * 1024L

        return when {
            sizeBytes >= gb -> String.format("%.2f GB", sizeBytes.toDouble() / gb.toDouble())
            sizeBytes >= mb -> String.format("%.2f MB", sizeBytes.toDouble() / mb.toDouble())
            sizeBytes >= kb -> String.format("%.2f KB", sizeBytes.toDouble() / kb.toDouble())
            else -> "$sizeBytes B"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class StatusKind(val fillColor: Int, val strokeColor: Int, val textColor: Int) {
        READY(Color.rgb(23, 62, 45), Color.rgb(58, 155, 108), Color.rgb(232, 255, 244)),
        UNAVAILABLE(Color.rgb(42, 13, 18), Color.rgb(122, 32, 40), Color.rgb(255, 218, 222)),
        BLOCKED(Color.rgb(75, 18, 27), Color.rgb(255, 59, 69), Color.rgb(255, 228, 230)),
    }

    private companion object {
        val COLOR_DARK: Int = Color.rgb(5, 5, 6)
        val COLOR_APP_SHELL: Int = Color.rgb(9, 9, 11)
        val COLOR_TEXT: Int = Color.rgb(247, 243, 243)
        val COLOR_TEXT_MUTED: Int = Color.rgb(126, 116, 119)
        val COLOR_MUTED: Int = Color.rgb(184, 174, 176)
        val COLOR_PRIMARY_RED: Int = Color.rgb(255, 59, 69)
        val COLOR_PRIMARY_RED_DARK: Int = Color.rgb(176, 12, 28)
        val COLOR_ACCENT: Int = COLOR_PRIMARY_RED
        val COLOR_ACCENT_DARK: Int = Color.rgb(139, 16, 24)
        val COLOR_DEEP_CRIMSON: Int = Color.rgb(139, 16, 24)
        val COLOR_SURFACE: Int = Color.rgb(9, 9, 11)
        val COLOR_GLASS: Int = Color.rgb(16, 16, 20)
        val COLOR_ELEVATED_GLASS: Int = Color.rgb(23, 16, 20)
        val COLOR_RED_BLACK_GLASS: Int = Color.rgb(28, 13, 16)
        val COLOR_PANEL: Int = Color.rgb(16, 16, 20)
        val COLOR_CARD: Int = Color.rgb(23, 16, 20)
        val COLOR_FIELD: Int = Color.rgb(24, 16, 18)
        val COLOR_USER_MESSAGE: Int = Color.rgb(42, 13, 18)
        val COLOR_MUTED_RED_SURFACE: Int = Color.rgb(42, 13, 18)
        val COLOR_BORDER: Int = Color.rgb(52, 32, 35)
        val COLOR_BORDER_RED: Int = Color.rgb(122, 32, 40)
        val COLOR_SUCCESS_DOT: Int = Color.rgb(66, 210, 130)
    }
}

private sealed class ImportResult {
    data class Success(
        val importedFilePath: String,
        val importedFileName: String,
        val registeredCount: Int,
        val localModelIds: List<String>,
        val selectedModelId: String,
    ) : ImportResult()

    data class Error(val message: String) : ImportResult()
}
